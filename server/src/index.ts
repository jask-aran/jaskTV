import { Account, randomToken, itemProblem, LIMITS, RECORD_LIMITS, sha256, type Env, type IncomingItem, type SyncRecord } from "./account";
import { AuthError, verifyGoogleIdToken, type GoogleIdentity } from "./google";
import { hashLoginKey, sameHex } from "./password";

export { Account };

/**
 * jaskTV account API (v1). Sign-in is optional in the app; once signed in, a device syncs its playlists and
 * preferences (encrypted on the device) through its account's Durable Object.
 *
 *   POST   /v1/auth/register { email, loginKey, device } → { token, deviceId, user }   (invited emails only)
 *   POST   /v1/auth/login    { email, loginKey, device } → { token, deviceId, user }
 *   POST   /v1/auth/google   { idToken, device: { name, kind } } → { token, deviceId, user }
 *   POST   /v1/auth/signout
 *   GET    /v1/me            → { user, devices, vault, rev }
 *   PUT    /v1/vault         { wrapped, replace? }
 *   POST   /v1/auth/password { loginKey, vault } (signed-in device: a new password; the vault re-wrapped for it)
 *   GET    /v1/sync?since=N  → { rev, items }
 *   POST   /v1/sync          { items: [{ kind, id, baseRev, updatedAt, deleted, data }] }
 *   DELETE /v1/devices/:id
 *   DELETE /v1/account
 *   GET    /v1/live          WebSocket: { t: "changed", rev } (v1 items); v2: { t: "records" } pushes, { t: "put" } writes, presence
 *   GET    /v2/records?since=N&limit=M → { seq, records, more, now }
 *   POST   /v2/records       { records: [{ id, stamp, data }] } → { seq, accepted, stale, rejected, now }
 *
 * Tokens are "jt1.<userId>.<secret>": the user id routes to the account's object, which checks the secret.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.code, message: e.message }, e.status);
      if (e instanceof AuthError) return json({ error: "bad_token", message: e.message }, 401);
      console.error("Unhandled", e);
      return json({ error: "server_error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  if (path === "/v1/health") return json({ ok: true, environment: env.ENVIRONMENT });
  if (method === "POST" && path === "/v1/auth/google") return signIn(request, env);
  if (method === "POST" && path === "/v1/auth/register") return register(request, env);
  if (method === "POST" && path === "/v1/auth/login") return login(request, env);
  if (method === "GET" && path === "/.well-known/assetlinks.json") return assetLinks(env);
  if (method === "GET" && path === "/pair") return pairPage();
  if (method === "POST" && path === "/v1/pair/start") return pairStart(request, env);
  const take = /^\/v1\/pair\/([0-9A-Z]{8})\/take$/.exec(path);
  if (method === "POST" && take) return pairTake(request, env, take[1]);

  const { userId, deviceId, account } = await authenticate(request, env);
  if (method === "GET" && path === "/v1/live") {
    const forwarded = new Request(request, { headers: new Headers(request.headers) });
    forwarded.headers.set("X-Jask-Device", deviceId);
    const ip = request.headers.get("CF-Connecting-IP");
    forwarded.headers.set("X-Jask-Net", ip ? (await sha256(`${userId}|${ip}`)).slice(0, 16) : "");
    return account.fetch(forwarded);
  }
  if (method === "GET" && path === "/v1/me") return json(Object.assign({ deviceId }, await account.me()));
  if (method === "POST" && path === "/v1/auth/signout") { await account.signOut(deviceId); return json({ ok: true }); }
  if (method === "POST" && path === "/v1/auth/password") {
    // A signed-in device sets a new password (the old one can be forgotten: the device token is the proof). It sends
    // the new password's login key and the account key re-wrapped for it; the recovery code's copy is unchanged.
    const body = await readJson<{ loginKey?: unknown; vault?: unknown }>(request, 64 * 1024);
    if (typeof body.loginKey !== "string" || !LOGIN_KEY.test(body.loginKey)) throw new HttpError(400, "bad_request", "loginKey is required");
    if (typeof body.vault !== "string" || !body.vault.includes("|")) throw new HttpError(400, "bad_request", "vault is required");
    await account.putVault(body.vault, true);
    const { hash, salt } = await hashLoginKey(body.loginKey);
    await env.DB.prepare("UPDATE users SET password_hash = ?, password_salt = ?, failed_logins = 0, locked_until = 0 WHERE id = ?")
      .bind(hash, salt, userId).run();
    return json({ ok: true });
  }
  if (method === "PUT" && path === "/v1/vault") {
    const body = await readJson<{ wrapped?: unknown; replace?: unknown }>(request, 8 * 1024);
    if (typeof body.wrapped !== "string") throw new HttpError(400, "bad_request", "wrapped is required");
    return json(await account.putVault(body.wrapped, body.replace === true));
  }
  if (method === "GET" && path === "/v1/sync") {
    const since = Number(url.searchParams.get("since") ?? 0);
    if (!Number.isFinite(since) || since < 0) throw new HttpError(400, "bad_request", "since must be a revision");
    return json(await account.pull(since));
  }
  if (method === "POST" && path === "/v1/sync") {
    const body = await readJson<{ items?: unknown }>(request, 4 * 1024 * 1024);
    if (!Array.isArray(body.items)) throw new HttpError(400, "bad_request", "items is required");
    if (body.items.length > LIMITS.itemsPerPush) throw new HttpError(400, "rejected", "Too many items in one push");
    const items = body.items.map(parseItem);
    for (const it of items) { const problem = itemProblem(it); if (problem) throw new HttpError(400, "rejected", problem); }
    try {
      return json(await account.push(deviceId, items));
    } catch (e) {
      throw new HttpError(400, "rejected", e instanceof Error ? e.message : "Rejected");
    }
  }
  if (method === "GET" && path === "/v2/records") {
    const since = Number(url.searchParams.get("since") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? RECORD_LIMITS.perPull);
    if (!Number.isFinite(since) || since < 0 || !Number.isFinite(limit) || limit < 1) throw new HttpError(400, "bad_request", "since and limit must be numbers");
    return json(await account.pullRecords(since, limit));
  }
  if (method === "POST" && path === "/v2/records") {
    const body = await readJson<{ records?: unknown }>(request, 32 * 1024 * 1024);
    if (!Array.isArray(body.records)) throw new HttpError(400, "bad_request", "records is required");
    if (body.records.length > RECORD_LIMITS.perPut) throw new HttpError(400, "rejected", "Too many records in one put");
    const records = body.records.map((r: any) => ({ id: String(r?.id ?? ""), stamp: String(r?.stamp ?? ""), data: typeof r?.data === "string" ? r.data : "" } as SyncRecord));
    return json(await account.putRecords(deviceId, records));
  }
  const pair = /^\/v1\/pair\/([0-9A-Z]{8})$/.exec(path);
  if (method === "GET" && pair) {
    const info = await env.ACCOUNT.getByName("pair:" + pair[1]).pairInfo();
    if (!info) throw new HttpError(404, "no_pairing", "That code has expired or doesn't exist. Show a new one on the TV.");
    return json(info);
  }
  const approve = /^\/v1\/pair\/([0-9A-Z]{8})\/approve$/.exec(path);
  if (method === "POST" && approve) {
    const body = await readJson<{ phoneKey?: unknown; sealedKey?: unknown }>(request, 16 * 1024);
    if (typeof body.phoneKey !== "string" || typeof body.sealedKey !== "string") throw new HttpError(400, "bad_request", "phoneKey and sealedKey are required");
    const pairing = env.ACCOUNT.getByName("pair:" + approve[1]);
    const info = await pairing.pairInfo();
    if (!info) throw new HttpError(404, "no_pairing", "That code has expired. Show a new one on the TV.");
    const me = await account.me() as { user: { id: string; email: string; name?: string } };
    let session;
    try { session = await account.createSession(me.user, info.device); }
    catch (e) { throw new HttpError(409, "device_limit", e instanceof Error ? e.message : "Too many devices"); }
    const ok = await pairing.pairApprove({ ...session, user: me.user, phoneKey: body.phoneKey, sealedKey: body.sealedKey });
    if (!ok) { await account.removeDevice(session.deviceId); throw new HttpError(409, "no_pairing", "That code was just used or expired."); }
    return json({ ok: true, deviceId: session.deviceId });
  }
  const device = /^\/v1\/devices\/([0-9a-f-]{36})$/.exec(path);
  if (method === "DELETE" && device) {
    return (await account.removeDevice(device[1])) ? json({ ok: true }) : json({ error: "not_found" }, 404);
  }
  if (method === "DELETE" && path === "/v1/account") {
    await account.wipe();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM users WHERE id = ?").bind(userId),
      env.DB.prepare("UPDATE invites SET used_by = NULL WHERE used_by = ?").bind(userId),
    ]);
    return json({ ok: true });
  }
  throw new HttpError(404, "not_found", "No such endpoint");
}

async function signIn(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ idToken?: unknown; device?: { name?: unknown; kind?: unknown } }>(request, 16 * 1024);
  if (typeof body.idToken !== "string") throw new HttpError(400, "bad_request", "idToken is required");
  const identity = await identify(body.idToken, env);
  const email = identity.email.trim().toLowerCase();
  const db = env.DB;
  let user = await db.prepare("SELECT id, email, name FROM users WHERE google_sub = ?").bind(identity.sub)
    .first<{ id: string; email: string; name: string | null }>();
  if (!user) {
    // Invite only: the email must be invited before its first sign-in.
    const invite = await db.prepare("SELECT email, used_by FROM invites WHERE email = ?").bind(email).first<{ used_by: string | null }>();
    if (!invite) throw new HttpError(403, "not_invited", "This Google account hasn't been invited to jaskTV yet");
    const id = crypto.randomUUID();
    await db.batch([
      db.prepare("INSERT INTO users (id, google_sub, email, name, created_at) VALUES (?, ?, ?, ?, ?)")
        .bind(id, identity.sub, email, identity.name ?? null, Date.now()),
      db.prepare("UPDATE invites SET used_by = ? WHERE email = ?").bind(id, email),
    ]);
    user = { id, email, name: identity.name ?? null };
  }
  const name = typeof body.device?.name === "string" && body.device.name.trim() ? body.device.name.trim() : "Device";
  const kind = typeof body.device?.kind === "string" ? body.device.kind : "phone";
  const profile = { id: user.id, email: user.email, name: user.name ?? undefined };
  let session;
  try {
    session = await env.ACCOUNT.getByName(user.id).createSession(profile, { name, kind });
  } catch (e) {
    throw new HttpError(409, "device_limit", e instanceof Error ? e.message : "Too many devices");
  }
  return json({ ...session, user: profile });
}

// ---- Email + password ----
// The device sends a login key derived from the password, never the password itself; the same derivation also
// gives the device the key that unlocks its encrypted data, which the server never receives.

interface PasswordBody { email?: unknown; loginKey?: unknown; device?: { name?: unknown; kind?: unknown } }
const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;
const LOGIN_KEY = /^[A-Za-z0-9+/=_-]{40,100}$/;
const MAX_FAILURES = 10;
const LOCK_MS = 15 * 60_000;

async function readPasswordBody(request: Request) {
  const body = await readJson<PasswordBody>(request, 8 * 1024);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  if (!EMAIL.test(email)) throw new HttpError(400, "bad_email", "That doesn't look like an email address");
  if (typeof body.loginKey !== "string" || !LOGIN_KEY.test(body.loginKey)) throw new HttpError(400, "bad_request", "loginKey is required");
  return { email, loginKey: body.loginKey, device: body.device };
}

async function register(request: Request, env: Env): Promise<Response> {
  const { email, loginKey, device } = await readPasswordBody(request);
  const db = env.DB;
  const existing = await db.prepare("SELECT id FROM users WHERE email = ?").bind(email).first();
  if (existing) throw new HttpError(409, "exists", "There's already an account for this email: sign in instead");
  const invite = await db.prepare("SELECT used_by FROM invites WHERE email = ?").bind(email).first();
  if (!invite) throw new HttpError(403, "not_invited", "This email hasn't been invited to jaskTV yet");
  const { hash, salt } = await hashLoginKey(loginKey);
  const id = crypto.randomUUID();
  await db.batch([
    db.prepare("INSERT INTO users (id, email, created_at, password_hash, password_salt) VALUES (?, ?, ?, ?, ?)").bind(id, email, Date.now(), hash, salt),
    db.prepare("UPDATE invites SET used_by = ? WHERE email = ?").bind(id, email),
  ]);
  return session(env, { id, email }, device);
}

async function login(request: Request, env: Env): Promise<Response> {
  const { email, loginKey, device } = await readPasswordBody(request);
  const db = env.DB;
  const user = await db.prepare("SELECT id, email, name, password_hash, password_salt, failed_logins, locked_until FROM users WHERE email = ?")
    .bind(email).first<{ id: string; email: string; name: string | null; password_hash: string | null; password_salt: string | null; failed_logins: number; locked_until: number }>();
  // The same answer for an unknown email and a wrong password.
  const wrong = new HttpError(401, "wrong_password", "Wrong email or password");
  if (!user || !user.password_hash || !user.password_salt) {
    await hashLoginKey(loginKey); // Same time as a real check.
    throw wrong;
  }
  if (user.locked_until > Date.now()) throw new HttpError(429, "locked", "Too many wrong attempts. Try again in 15 minutes.");
  const { hash } = await hashLoginKey(loginKey, user.password_salt);
  if (!sameHex(hash, user.password_hash)) {
    const failures = user.failed_logins + 1;
    await db.prepare("UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?")
      .bind(failures >= MAX_FAILURES ? 0 : failures, failures >= MAX_FAILURES ? Date.now() + LOCK_MS : 0, user.id).run();
    throw wrong;
  }
  if (user.failed_logins > 0) await db.prepare("UPDATE users SET failed_logins = 0 WHERE id = ?").bind(user.id).run();
  return session(env, { id: user.id, email: user.email, name: user.name ?? undefined }, device);
}

/** Codes: 8 characters without look-alikes (no 0/O, 1/I/L). */
const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
function pairCode(): string {
  const b = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(b, (x) => CODE_ALPHABET[x % CODE_ALPHABET.length]).join("");
}

async function pairStart(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ tvKey?: unknown; device?: { name?: unknown; kind?: unknown } }>(request, 8 * 1024);
  if (typeof body.tvKey !== "string" || body.tvKey.length < 40 || body.tvKey.length > 400) throw new HttpError(400, "bad_request", "tvKey is required");
  const name = typeof body.device?.name === "string" && body.device.name.trim() ? body.device.name.trim().slice(0, 60) : "TV";
  const kind = typeof body.device?.kind === "string" ? body.device.kind.slice(0, 16) : "tv";
  const code = pairCode();
  const secret = randomToken(24);
  const { expiresAt } = await env.ACCOUNT.getByName("pair:" + code).pairStart({ tvKey: body.tvKey, device: { name, kind }, secretHash: await sha256(secret) });
  return json({ code, secret, expiresAt });
}

async function pairTake(request: Request, env: Env, code: string): Promise<Response> {
  const body = await readJson<{ secret?: unknown }>(request, 1024);
  if (typeof body.secret !== "string") throw new HttpError(400, "bad_request", "secret is required");
  return json(await env.ACCOUNT.getByName("pair:" + code).pairTake(await sha256(body.secret)));
}

/** Lets Android open /pair links straight in the app (the debug build, signed with the local debug key). */
function assetLinks(env: Env): Response {
  const fingerprints = (env.ANDROID_CERTS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return json(["app.jasktv", "app.jasktv.debug"].map((pkg) => ({
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: fingerprints },
  })));
}

/** A phone without the app (or a link opened in a browser): how to finish with the code. */
function pairPage(): Response {
  const html = `<!doctype html><meta name=viewport content="width=device-width"><title>jaskTV</title>
<body style="font:18px system-ui;background:#111;color:#eee;padding:24px">
<h2>Sign in your TV</h2><p>Open jaskTV on your phone, go to Settings → Account → <b>Sign in a TV</b>, and enter:</p>
<p id=c style="font:700 32px monospace;letter-spacing:4px"></p>
<script>document.getElementById("c").textContent=(new URLSearchParams(location.hash.slice(1)).get("c")||"the code on the TV")</script>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}

async function session(env: Env, profile: { id: string; email: string; name?: string }, device?: { name?: unknown; kind?: unknown }) {
  const name = typeof device?.name === "string" && device.name.trim() ? device.name.trim() : "Device";
  const kind = typeof device?.kind === "string" ? device.kind : "phone";
  try {
    return json(Object.assign({ user: profile }, await env.ACCOUNT.getByName(profile.id).createSession(profile, { name, kind })));
  } catch (e) {
    throw new HttpError(409, "device_limit", e instanceof Error ? e.message : "Too many devices");
  }
}

async function identify(idToken: string, env: Env): Promise<GoogleIdentity> {
  // Local development and tests only: "dev:<sub>:<email>". DEV_AUTH is never set on a deployed Worker.
  if (env.DEV_AUTH === "1" && env.ENVIRONMENT === "local" && idToken.startsWith("dev:")) {
    const [, sub, email] = idToken.split(":");
    if (!sub || !email) throw new AuthError("Bad dev token");
    return { sub, email, name: "Dev " + sub };
  }
  const audiences = env.GOOGLE_CLIENT_IDS.split(",").map((s) => s.trim()).filter(Boolean);
  if (audiences.length === 0) throw new HttpError(503, "not_configured", "Google sign-in isn't set up on this server yet");
  return verifyGoogleIdToken(idToken, audiences);
}

async function authenticate(request: Request, env: Env) {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer jt1\.([0-9a-f-]{36})\.([A-Za-z0-9_-]{20,100})$/.exec(header);
  if (!match) throw new HttpError(401, "signed_out", "Sign in required");
  const [, userId, secret] = match;
  const account = env.ACCOUNT.getByName(userId);
  const deviceId = await account.authenticate(secret);
  if (!deviceId) throw new HttpError(401, "signed_out", "This device was signed out");
  return { userId, deviceId, account };
}

function parseItem(raw: unknown): IncomingItem {
  const it = raw as Record<string, unknown>;
  if (typeof it?.kind !== "string" || typeof it.id !== "string") throw new HttpError(400, "bad_request", "Item needs kind and id");
  return {
    kind: it.kind, id: it.id, baseRev: Number(it.baseRev ?? 0), updatedAt: Number(it.updatedAt ?? 0),
    deleted: it.deleted === true, data: typeof it.data === "string" ? it.data : null,
  };
}

async function readJson<T>(request: Request, maxBytes: number): Promise<T> {
  const text = await request.text();
  if (text.length > maxBytes) throw new HttpError(413, "too_large", "Request too large");
  try { return JSON.parse(text) as T; } catch { throw new HttpError(400, "bad_request", "Invalid JSON"); }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}
