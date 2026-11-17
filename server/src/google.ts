/**
 * Verifies a Google ID token (from Android's Credential Manager "Sign in with Google") without any library:
 * RS256 against Google's published keys, issuer, audience, expiry and a verified email.
 */
export interface GoogleIdentity {
  sub: string;
  email: string;
  name?: string;
}

const CERTS = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);

interface Jwk { kid: string; n: string; e: string; kty: string; alg?: string }
let keys: { at: number; maxAge: number; byKid: Map<string, CryptoKey> } | null = null;

async function googleKey(kid: string): Promise<CryptoKey | undefined> {
  const now = Date.now();
  if (!keys || now - keys.at > keys.maxAge || !keys.byKid.has(kid)) {
    const res = await fetch(CERTS);
    if (!res.ok) throw new Error(`Google keys unavailable (${res.status})`);
    const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get("cache-control") ?? "")?.[1] ?? 3600) * 1000;
    const body = (await res.json()) as { keys: Jwk[] };
    const byKid = new Map<string, CryptoKey>();
    for (const k of body.keys) {
      byKid.set(k.kid, await crypto.subtle.importKey("jwk", { kty: k.kty, n: k.n, e: k.e, alg: "RS256", ext: true },
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]));
    }
    keys = { at: now, maxAge, byKid };
  }
  return keys.byKid.get(kid);
}

function b64urlBytes(s: string): Uint8Array {
  const pad = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(pad), (c) => c.charCodeAt(0));
}

export async function verifyGoogleIdToken(token: string, audiences: string[], now = Date.now()): Promise<GoogleIdentity> {
  const parts = token.split(".");
  if (parts.length !== 3 || token.length > 8192) throw new AuthError("Malformed token");
  const header = JSON.parse(new TextDecoder().decode(b64urlBytes(parts[0]))) as { alg: string; kid: string };
  if (header.alg !== "RS256" || !header.kid) throw new AuthError("Unexpected token algorithm");
  const key = await googleKey(header.kid);
  if (!key) throw new AuthError("Unknown signing key");
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!ok) throw new AuthError("Bad signature");
  const claims = JSON.parse(new TextDecoder().decode(b64urlBytes(parts[1]))) as Record<string, unknown>;
  if (!ISSUERS.has(String(claims.iss))) throw new AuthError("Wrong issuer");
  if (!audiences.includes(String(claims.aud))) throw new AuthError("Wrong audience");
  const exp = Number(claims.exp) * 1000;
  if (!(exp > now - 60_000)) throw new AuthError("Token expired");
  if (claims.email_verified !== true && claims.email_verified !== "true") throw new AuthError("Email not verified");
  if (typeof claims.sub !== "string" || typeof claims.email !== "string") throw new AuthError("Missing identity");
  return { sub: claims.sub, email: claims.email, name: typeof claims.name === "string" ? claims.name : undefined };
}

export class AuthError extends Error {}
