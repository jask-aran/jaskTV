import { env } from "cloudflare:test";
import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const call = (path: string, init: RequestInit & { token?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.token) headers.set("Authorization", `Bearer ${init.token}`);
  if (init.body) headers.set("content-type", "application/json");
  return (exports as any).default.fetch(new Request("https://account.test" + path, { ...init, headers }));
};
async function phone(email: string) {
  await env.DB.prepare("INSERT OR IGNORE INTO invites (email, created_at) VALUES (?, ?)").bind(email, 0).run();
  return (await (await call("/v1/auth/google", { method: "POST", body: JSON.stringify({ idToken: `dev:${email}:${email}`, device: { name: "Phone", kind: "phone" } }) })).json()) as any;
}
const tvKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "A".repeat(52);

describe("TV sign-in by QR", () => {
  it("pairs a TV: the phone approves, the TV collects once", async () => {
    const p = await phone("pair1@example.com");
    const start = await (await call("/v1/pair/start", { method: "POST", body: JSON.stringify({ tvKey, device: { name: "Living Room TV", kind: "tv" } }) })).json() as any;
    expect(start.code).toMatch(/^[2-9A-HJKMNP-Z]{8}$/);
    // Before approval the TV waits; with a wrong secret it learns nothing.
    expect(await (await call(`/v1/pair/${start.code}/take`, { method: "POST", body: JSON.stringify({ secret: start.secret }) })).json()).toEqual({ state: "pending" });
    expect(await (await call(`/v1/pair/${start.code}/take`, { method: "POST", body: JSON.stringify({ secret: "wrong" }) })).json()).toEqual({ state: "expired" });
    // The phone sees which TV it is, then approves.
    const info = await (await call(`/v1/pair/${start.code}`, { token: p.token })).json() as any;
    expect(info).toMatchObject({ tvKey, device: { name: "Living Room TV", kind: "tv" } });
    const ok = await (await call(`/v1/pair/${start.code}/approve`, { method: "POST", token: p.token, body: JSON.stringify({ phoneKey: "pk", sealedKey: "sealed" }) })).json() as any;
    expect(ok.ok).toBe(true);
    const got = await (await call(`/v1/pair/${start.code}/take`, { method: "POST", body: JSON.stringify({ secret: start.secret }) })).json() as any;
    expect(got.state).toBe("approved");
    expect(got.result).toMatchObject({ phoneKey: "pk", sealedKey: "sealed", deviceId: ok.deviceId, user: { email: "pair1@example.com" } });
    // The TV's token works, and the TV is in the account's devices.
    const me = await (await call("/v1/me", { token: got.result.token })).json() as any;
    expect(me.devices.map((d: any) => d.name)).toEqual(["Phone", "Living Room TV"]);
    // Collected once: gone after.
    expect(await (await call(`/v1/pair/${start.code}/take`, { method: "POST", body: JSON.stringify({ secret: start.secret }) })).json()).toEqual({ state: "expired" });
    expect((await call(`/v1/pair/${start.code}`, { token: p.token })).status).toBe(404);
  });

  it("can't be approved twice or without signing in", async () => {
    const p = await phone("pair2@example.com");
    const start = await (await call("/v1/pair/start", { method: "POST", body: JSON.stringify({ tvKey, device: { name: "TV", kind: "tv" } }) })).json() as any;
    expect((await call(`/v1/pair/${start.code}/approve`, { method: "POST", body: JSON.stringify({ phoneKey: "pk", sealedKey: "s" }) })).status).toBe(401);
    expect((await call(`/v1/pair/${start.code}/approve`, { method: "POST", token: p.token, body: JSON.stringify({ phoneKey: "pk", sealedKey: "s" }) })).status).toBe(200);
    expect((await call(`/v1/pair/${start.code}/approve`, { method: "POST", token: p.token, body: JSON.stringify({ phoneKey: "pk", sealedKey: "s" }) })).status).toBe(404);
    expect((await (await call("/v1/me", { token: p.token })).json() as any).devices).toHaveLength(2); // not three
  });

  it("serves the app-link file and the fallback page", async () => {
    expect((await call("/.well-known/assetlinks.json")).status).toBe(200);
    expect(await (await call("/pair")).text()).toContain("Sign in a TV");
  });
});

describe("changing a forgotten password from a signed-in device", () => {
  it("the new password signs in, the old one doesn't, and the vault holds the new wrap", async () => {
    const email = "pwchange@example.com";
    await env.DB.prepare("INSERT OR IGNORE INTO invites (email, created_at) VALUES (?, ?)").bind(email, 0).run();
    const oldKey = btoa("o".repeat(32)), newKey = btoa("n".repeat(32));
    const reg = await (await call("/v1/auth/register", { method: "POST", body: JSON.stringify({ email, loginKey: oldKey, device: { name: "Phone", kind: "phone" } }) })).json() as any;
    await call("/v1/vault", { method: "PUT", token: reg.token, body: JSON.stringify({ wrapped: "jk1:old|jv1:recovery" }) });
    // Without signing in: refused.
    expect((await call("/v1/auth/password", { method: "POST", body: JSON.stringify({ loginKey: newKey, vault: "jk1:new|jv1:recovery" }) })).status).toBe(401);
    expect((await call("/v1/auth/password", { method: "POST", token: reg.token, body: JSON.stringify({ loginKey: newKey, vault: "jk1:new|jv1:recovery" }) })).status).toBe(200);
    const login = (k: string) => call("/v1/auth/login", { method: "POST", body: JSON.stringify({ email, loginKey: k, device: { name: "TV", kind: "tv" } }) });
    expect((await login(oldKey)).status).toBe(401);
    const ok = await (await login(newKey)).json() as any;
    expect((await (await call("/v1/me", { token: ok.token })).json() as any).vault).toBe("jk1:new|jv1:recovery");
    // The phone that changed it is still signed in.
    expect((await call("/v1/me", { token: reg.token })).status).toBe(200);
  });
});
