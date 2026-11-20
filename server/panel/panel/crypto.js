// jaskTV account crypto in the browser (WebCrypto), byte-for-byte the same as the app's AccountCrypto and core's
// engine: the password gives a login key (sent) and a wrap key (never sent) that opens the account key; records are
// sealed with the account key, so the account server only ever holds what it can't read.

const te = new TextEncoder();
const td = new TextDecoder();
const subtle = globalThis.crypto.subtle;

export const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
export const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
const b64url = (bytes) => b64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function hmac(keyBytes, data) {
  const key = await subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await subtle.sign("HMAC", key, typeof data === "string" ? te.encode(data) : data));
}

/** { loginKey: base64 text, wrapKey: bytes } from email and password (300,000 rounds: about a second). */
export async function passwordKeys(email, password) {
  const base = await subtle.importKey("raw", te.encode(password), "PBKDF2", false, ["deriveBits"]);
  const salt = te.encode("jasktv-account:" + email.trim().toLowerCase());
  const master = new Uint8Array(await subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 300000 }, base, 256));
  return { loginKey: b64(await hmac(master, "jasktv-login")), wrapKey: await hmac(master, "jasktv-wrap") };
}

async function aesOpen(keyBytes, bytes, aad) {
  if (bytes.length <= 28) throw new Error("truncated");
  const key = await subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  return new Uint8Array(await subtle.decrypt({ name: "AES-GCM", iv: bytes.slice(0, 12), additionalData: te.encode(aad) }, key, bytes.slice(12)));
}

async function aesSeal(keyBytes, plain, aad) {
  const key = await subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv, additionalData: te.encode(aad) }, key, plain));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv); out.set(ct, 12);
  return out;
}

/** The account key from the vault ("<jk1 password copy>|<recovery copy>"), or null when the password doesn't open it. */
export async function unwrapVault(vault, wrapKey) {
  const part = (vault || "").split("|")[0];
  if (!part.startsWith("jk1:")) return null;
  try { const k = await aesOpen(wrapKey, unb64(part.slice(4)), "jasktv-vault-pw"); return k.length === 32 ? k : null; }
  catch { return null; }
}

/** The vault's password copy for [accountKey] (what a new account stores; used by the local test). */
export async function wrapAccountKey(accountKey, wrapKey) { return "jk1:" + b64(await aesSeal(wrapKey, accountKey, "jasktv-vault-pw")); }

export async function seal(key, kind, id, plain) { return "js1:" + b64(await aesSeal(key, te.encode(plain), `${kind}/${id}`)); }
export async function open(key, kind, id, sealed) {
  if (!sealed.startsWith("js1:")) throw new Error("unsupported item");
  return td.decode(await aesOpen(key, unb64(sealed.slice(4)), `${kind}/${id}`));
}

/** A record's id: base64url(HMAC(account key, coll \0 key)[:18]). */
export async function recordId(key, coll, k) { return b64url((await hmac(key, `${coll}\u0000${k}`)).slice(0, 18)); }

/** Opens a wire record {id, stamp, data}: {coll, key, stamp, value} (value null: removed), or null when it isn't genuine. */
export async function openRecord(key, w) {
  try {
    const s = JSON.parse(await open(key, "r2", w.id, w.data));
    if (s.s !== w.stamp || (await recordId(key, s.c, s.k)) !== w.id) return null;
    return { coll: s.c, key: s.k, stamp: s.s, value: s.v ?? null, seq: w.seq };
  } catch { return null; }
}

export async function sealRecord(key, coll, k, stamp, value) {
  const id = await recordId(key, coll, k);
  return { id, stamp, data: await seal(key, "r2", id, JSON.stringify({ c: coll, k, s: stamp, v: value ?? null })) };
}

/** Stamps like the app's: "<13-digit ms>-<4 hex counter>-<8 char tag>", never going backwards, on the server's clock. */
export function stampClock(tag) {
  let offset = 0, last = 0, n = 0;
  return {
    observe(serverNow) { offset = serverNow - Date.now(); },
    now() { return Date.now() + offset; },
    next(after) {
      let now = Date.now() + offset;
      if (after) now = Math.max(now, Number(after.slice(0, 13)));
      if (now > last) { last = now; n = 0; } else n += 1;
      return `${String(last).padStart(13, "0")}-${n.toString(16).padStart(4, "0")}-${tag}`;
    },
  };
}
