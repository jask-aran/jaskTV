import { env } from "cloudflare:test";
import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const BASE = "https://account.test";
const call = (path: string, init: RequestInit & { token?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.token) headers.set("Authorization", `Bearer ${init.token}`);
  if (init.body) headers.set("content-type", "application/json");
  return (exports as any).default.fetch(new Request(BASE + path, { ...init, headers }));
};
async function invite(email: string) {
  await env.DB.prepare("INSERT OR IGNORE INTO invites (email, created_at) VALUES (?, ?)").bind(email, Date.now()).run();
}
async function signIn(sub: string, email: string, name = "Phone") {
  const res = await call("/v1/auth/google", { method: "POST", body: JSON.stringify({ idToken: `dev:${sub}:${email}`, device: { name, kind: "phone" } }) });
  return { status: res.status, body: (await res.json()) as any };
}

describe("account server", () => {
  it("refuses Google accounts that weren't invited", async () => {
    const { status, body } = await signIn("s-uninvited", "nobody@example.com");
    expect(status).toBe(403);
    expect(body.error).toBe("not_invited");
  });

  it("signs in an invited account, and the same Google account again on a second device", async () => {
    await invite("a@example.com");
    const first = await signIn("s-a", "A@example.com");
    expect(first.status).toBe(200);
    expect(first.body.token).toMatch(/^jt1\./);
    const second = await signIn("s-a", "a@example.com", "TV");
    expect(second.body.user.id).toBe(first.body.user.id);
    const me = await (await call("/v1/me", { token: second.body.token })).json() as any;
    expect(me.devices.map((d: any) => d.name).sort()).toEqual(["Phone", "TV"]);
    expect(me.vault).toBeNull();
  });

  it("rejects missing, malformed and forged tokens", async () => {
    expect((await call("/v1/me")).status).toBe(401);
    expect((await call("/v1/me", { token: "nonsense" })).status).toBe(401);
    await invite("b@example.com");
    const { body } = await signIn("s-b", "b@example.com");
    const forged = body.token.replace(/\.[^.]+$/, ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    expect((await call("/v1/me", { token: forged })).status).toBe(401);
  });

  it("keeps accounts apart: one account's token never reads another's items", async () => {
    await invite("c@example.com"); await invite("d@example.com");
    const c = (await signIn("s-c", "c@example.com")).body;
    const d = (await signIn("s-d", "d@example.com")).body;
    await call("/v1/sync", { method: "POST", token: c.token, body: JSON.stringify({ items: [{ kind: "source", id: "x1", baseRev: 0, updatedAt: 1, data: "sealed-c" }] }) });
    const pulled = await (await call("/v1/sync?since=0", { token: d.token })).json() as any;
    expect(pulled.items).toEqual([]);
    // Another account's user id with this account's secret: refused.
    const mixed = `jt1.${c.user.id}.${d.token.split(".")[2]}`;
    expect((await call("/v1/sync?since=0", { token: mixed })).status).toBe(401);
  });

  it("syncs items between devices, with deletions and conflicts", async () => {
    await invite("e@example.com");
    const phone = (await signIn("s-e", "e@example.com", "Phone")).body;
    const tv = (await signIn("s-e", "e@example.com", "TV")).body;
    const push = async (token: string, items: unknown[]) =>
      (await call("/v1/sync", { method: "POST", token, body: JSON.stringify({ items }) })).json() as Promise<any>;

    const r1 = await push(phone.token, [{ kind: "source", id: "s1", baseRev: 0, updatedAt: 100, data: "v1" }, { kind: "prefs", id: "app", baseRev: 0, updatedAt: 100, data: "p1" }]);
    expect(r1.accepted).toHaveLength(2);
    const onTv = await (await call("/v1/sync?since=0", { token: tv.token })).json() as any;
    expect(onTv.items.map((i: any) => i.data).sort()).toEqual(["p1", "v1"]);

    // The TV edits from the revision it saw; the phone's stale, older edit then conflicts and gets the TV's copy.
    const s1 = onTv.items.find((i: any) => i.id === "s1");
    expect((await push(tv.token, [{ kind: "source", id: "s1", baseRev: s1.rev, updatedAt: 200, data: "v2-tv" }])).accepted).toHaveLength(1);
    const stale = await push(phone.token, [{ kind: "source", id: "s1", baseRev: s1.rev, updatedAt: 150, data: "v2-phone" }]);
    expect(stale.accepted).toHaveLength(0);
    expect(stale.conflicts[0].data).toBe("v2-tv");

    // Deleting leaves a tombstone so other devices remove it too.
    const del = await push(phone.token, [{ kind: "source", id: "s1", baseRev: stale.conflicts[0].rev, updatedAt: 300, deleted: true }]);
    const since = await (await call(`/v1/sync?since=${r1.rev}`, { token: tv.token })).json() as any;
    expect(since.items.find((i: any) => i.id === "s1")).toMatchObject({ deleted: true, data: null, rev: del.rev });
  });

  it("keeps the first vault unless replaced", async () => {
    await invite("f@example.com");
    const f = (await signIn("s-f", "f@example.com")).body;
    const put = async (wrapped: string, replace = false) =>
      (await call("/v1/vault", { method: "PUT", token: f.token, body: JSON.stringify({ wrapped, replace }) })).json() as Promise<any>;
    expect((await put("first")).stored).toBe(true);
    expect(await put("second")).toEqual({ stored: false, vault: "first" });
    expect((await put("third", true)).vault).toBe("third");
  });

  it("signs a device out, removes devices, and deletes the account", async () => {
    await invite("g@example.com");
    const phone = (await signIn("s-g", "g@example.com", "Phone")).body;
    const tv = (await signIn("s-g", "g@example.com", "TV")).body;
    expect((await call(`/v1/devices/${tv.deviceId}`, { method: "DELETE", token: phone.token })).status).toBe(200);
    expect((await call("/v1/me", { token: tv.token })).status).toBe(401);
    expect((await call("/v1/auth/signout", { method: "POST", token: phone.token })).status).toBe(200);
    expect((await call("/v1/me", { token: phone.token })).status).toBe(401);
    const again = (await signIn("s-g", "g@example.com")).body;
    expect((await call("/v1/account", { method: "DELETE", token: again.token })).status).toBe(200);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE google_sub = 's-g'").first("n")).toBe(0);
    // The invite stays, so the person can sign up again.
    expect((await signIn("s-g", "g@example.com")).status).toBe(200);
  });

  it("tells other connected devices when something changed", async () => {
    await invite("h@example.com");
    const phone = (await signIn("s-h", "h@example.com", "Phone")).body;
    const tv = (await signIn("s-h", "h@example.com", "TV")).body;
    const res = await call("/v1/live", { token: tv.token, headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(101);
    const ws = res.webSocket!;
    ws.accept();
    const messages: any[] = [];
    ws.addEventListener("message", (e: MessageEvent) => messages.push(JSON.parse(e.data as string)));
    await call("/v1/sync", { method: "POST", token: phone.token, body: JSON.stringify({ items: [{ kind: "prefs", id: "app", baseRev: 0, updatedAt: 1, data: "x" }] }) });
    await new Promise((r) => setTimeout(r, 100));
    expect(messages.map((m) => m.t)).toEqual(["hello", "changed"]);
    ws.close();
  });

  it("refuses oversized and malformed pushes", async () => {
    await invite("i@example.com");
    const t = (await signIn("s-i", "i@example.com")).body.token;
    const big = "x".repeat(1025 * 1024);
    expect((await call("/v1/sync", { method: "POST", token: t, body: JSON.stringify({ items: [{ kind: "source", id: "a", baseRev: 0, updatedAt: 1, data: big }] }) })).status).toBe(400);
    expect((await call("/v1/sync", { method: "POST", token: t, body: JSON.stringify({ items: [{ kind: "../x", id: "a", baseRev: 0, updatedAt: 1, data: "d" }] }) })).status).toBe(400);
  });
});

describe("email and password", () => {
  const key = (n: string) => n.padEnd(44, "A"); // A login key, as a device derives it (base64, 32 bytes).
  const post = (path: string, body: unknown) => call(path, { method: "POST", body: JSON.stringify(body) });

  it("registers invited emails only, once", async () => {
    expect((await post("/v1/auth/register", { email: "p1@example.com", loginKey: key("k1") })).status).toBe(403);
    await invite("p1@example.com");
    const ok = await post("/v1/auth/register", { email: "P1@Example.com ", loginKey: key("k1"), device: { name: "Phone", kind: "phone" } });
    expect(ok.status).toBe(200);
    const body = await ok.json() as any;
    expect(body.user.email).toBe("p1@example.com");
    expect((await call("/v1/me", { token: body.token })).status).toBe(200);
    expect((await post("/v1/auth/register", { email: "p1@example.com", loginKey: key("k2") })).status).toBe(409);
  });

  it("signs in with the right key, not a wrong one, and answers unknown emails the same way", async () => {
    await invite("p2@example.com");
    await post("/v1/auth/register", { email: "p2@example.com", loginKey: key("right") });
    const good = await post("/v1/auth/login", { email: "p2@example.com", loginKey: key("right"), device: { name: "TV", kind: "tv" } });
    expect(good.status).toBe(200);
    const bad = await post("/v1/auth/login", { email: "p2@example.com", loginKey: key("wrong") });
    const unknown = await post("/v1/auth/login", { email: "nobody2@example.com", loginKey: key("wrong") });
    expect([bad.status, unknown.status]).toEqual([401, 401]);
    expect(((await bad.json()) as any).error).toBe(((await unknown.json()) as any).error);
  });

  it("locks an account for a while after 10 wrong attempts", async () => {
    await invite("p3@example.com");
    await post("/v1/auth/register", { email: "p3@example.com", loginKey: key("right") });
    for (let i = 0; i < 10; i++) expect((await post("/v1/auth/login", { email: "p3@example.com", loginKey: key("wrong" + i) })).status).toBe(401);
    expect((await post("/v1/auth/login", { email: "p3@example.com", loginKey: key("right") })).status).toBe(429);
  });

  it("stores only a hash of the login key", async () => {
    await invite("p4@example.com");
    await post("/v1/auth/register", { email: "p4@example.com", loginKey: key("secretkey") });
    const row = await env.DB.prepare("SELECT password_hash, password_salt FROM users WHERE email = 'p4@example.com'").first<any>();
    expect(row.password_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row.password_hash).not.toContain("secretkey");
  });
});
