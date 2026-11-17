import { env } from "cloudflare:test";
import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const call = (path: string, init: RequestInit & { token?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.token) headers.set("Authorization", `Bearer ${init.token}`);
  if (init.body) headers.set("content-type", "application/json");
  return (exports as any).default.fetch(new Request("https://account.test" + path, { ...init, headers }));
};
let n = 0;
async function device(email: string, name: string) {
  await env.DB.prepare("INSERT OR IGNORE INTO invites (email, created_at) VALUES (?, ?)").bind(email, 0).run();
  const res = await call("/v1/auth/google", { method: "POST", body: JSON.stringify({ idToken: `dev:${email}:${email}`, device: { name, kind: "phone" } }) });
  return (await res.json()) as any;
}
const stamp = (ms: number, counter = 0, dev = "devA0000") => `${String(ms).padStart(13, "0")}-${counter.toString(16).padStart(4, "0")}-${dev}`;
const put = async (token: string, records: unknown[]) => (await call("/v2/records", { method: "POST", token, body: JSON.stringify({ records }) })).json() as Promise<any>;
const pull = async (token: string, since = 0, limit = 1000) => (await call(`/v2/records?since=${since}&limit=${limit}`, { token })).json() as Promise<any>;
const id = () => "rec" + String(++n).padStart(16, "0");

describe("records", () => {
  it("keeps the newest stamp, whatever order writes arrive in", async () => {
    const a = await device("r1@example.com", "A");
    const r = id(), now = Date.now();
    expect((await put(a.token, [{ id: r, stamp: stamp(now, 0, "devB0000"), data: "newer" }])).accepted).toHaveLength(1);
    const late = await put(a.token, [{ id: r, stamp: stamp(now - 1000), data: "older" }]);
    expect(late.accepted).toHaveLength(0);
    expect(late.stale[0].data).toBe("newer");
    // Same time: the device tag breaks the tie, the same way everywhere.
    expect((await put(a.token, [{ id: r, stamp: stamp(now, 0, "devC0000"), data: "tie-c" }])).accepted).toHaveLength(1);
    expect((await pull(a.token)).records.find((x: any) => x.id === r).data).toBe("tie-c");
    // A retry of what's held is acknowledged, not duplicated.
    const retry = await put(a.token, [{ id: r, stamp: stamp(now, 0, "devC0000"), data: "tie-c" }]);
    expect(retry.accepted).toHaveLength(1);
    expect(retry.seq).toBe((await pull(a.token)).seq);
  });

  it("refuses stamps from a clock far in the future, and bad ids", async () => {
    const a = await device("r2@example.com", "A");
    const res = await put(a.token, [{ id: id(), stamp: stamp(Date.now() + 3_600_000), data: "x" }, { id: "x", stamp: stamp(Date.now()), data: "x" }]);
    expect(res.rejected.map((r: any) => r.reason)).toEqual(["Stamp is in the future (check the device clock)", "Bad record id"]);
    expect(Math.abs(res.now - Date.now())).toBeLessThan(5000);
  });

  it("pages a catch-up in order", async () => {
    const a = await device("r3@example.com", "A");
    const now = Date.now();
    await put(a.token, Array.from({ length: 25 }, (_, i) => ({ id: id(), stamp: stamp(now, i), data: "d" + i })));
    const first = await pull(a.token, 0, 10);
    expect(first.records).toHaveLength(10);
    expect(first.more).toBe(true);
    let since = first.records.at(-1).seq, got = 10;
    for (;;) { const page = await pull(a.token, since, 10); got += page.records.length; if (!page.more) break; since = page.records.at(-1).seq; }
    expect(got).toBe(25);
  });

  it("pushes records to other devices at once, writes over the socket, relays presence without storing it", async () => {
    const phone = await device("r4@example.com", "Phone");
    const tv = await device("r4@example.com", "TV");
    const open = async (token: string) => {
      const res = await call("/v1/live", { token, headers: { Upgrade: "websocket" } });
      const ws = res.webSocket!; ws.accept();
      const got: any[] = []; ws.addEventListener("message", (e: MessageEvent) => got.push(JSON.parse(e.data as string)));
      return { ws, got };
    };
    const p = await open(phone.token), t = await open(tv.token);
    const r = id();
    p.ws.send(JSON.stringify({ t: "put", id: 7, records: [{ id: r, stamp: stamp(Date.now()), data: "sealed" }] }));
    p.ws.send(JSON.stringify({ t: "presence", playing: { title: "Fox Cricket" } }));
    await new Promise((res) => setTimeout(res, 150));
    expect(p.got.find((m) => m.t === "ack")).toMatchObject({ id: 7, accepted: [{ id: r }] });
    expect(t.got.find((m) => m.t === "records").records[0]).toMatchObject({ id: r, data: "sealed" });
    expect(t.got.find((m) => m.t === "presence")).toMatchObject({ from: phone.deviceId, playing: { title: "Fox Cricket" } });
    expect(p.got.some((m) => m.t === "records" || m.t === "presence")).toBe(false); // not echoed to the writer
    expect((await pull(phone.token)).records).toHaveLength(1);
    p.ws.close(); t.ws.close();
  });

  it("drops writes from a device removed while connected", async () => {
    const phone = await device("r5@example.com", "Phone");
    const tv = await device("r5@example.com", "TV");
    const res = await call("/v1/live", { token: tv.token, headers: { Upgrade: "websocket" } });
    const ws = res.webSocket!; ws.accept();
    await call(`/v1/devices/${tv.deviceId}`, { method: "DELETE", token: phone.token });
    try { ws.send(JSON.stringify({ t: "put", id: 1, records: [{ id: id(), stamp: stamp(Date.now()), data: "x" }] })); } catch { /* closed */ }
    await new Promise((r) => setTimeout(r, 100));
    expect((await pull(phone.token)).records).toHaveLength(0);
  });
});
