import { DurableObject } from "cloudflare:workers";

/**
 * One account: its signed-in devices and sessions, its encrypted vault (the account key, wrapped by the
 * recovery code) and its synced items. Item data is encrypted on the devices; this object stores and
 * orders it, never reads it. Every signed-in device can hold a WebSocket here to hear about changes.
 */
export interface Env {
  DB: D1Database;
  ACCOUNT: DurableObjectNamespace<Account>;
  GOOGLE_CLIENT_IDS: string;
  ENVIRONMENT: string;
  DEV_AUTH?: string;
  /** SHA-256 fingerprints of the app's signing certificates, comma-separated (for Android app links). */
  ANDROID_CERTS?: string;
}

export interface DeviceInfo { name: string; kind: string }
export interface Device extends DeviceInfo { id: string; createdAt: number; lastSeen: number }
export interface Item { kind: string; id: string; rev: number; updatedAt: number; deleted: boolean; data: string | null }
export interface IncomingItem { kind: string; id: string; baseRev: number; updatedAt: number; deleted: boolean; data: string | null }

/** A record as stored and sent: an opaque id, its stamp (when and by which device it was written) and sealed data. */
export interface SyncRecord { id: string; stamp: string; data: string; seq?: number }

/** "<13-digit ms>-<4 hex counter>-<8-char device tag>": compares as text in time order, ties broken by device. */
export const STAMP = /^\d{13}-[0-9a-f]{4}-[A-Za-z0-9_-]{8}$/;
const RECORD_ID = /^[A-Za-z0-9_-]{16,64}$/;
/** How far ahead of the server's clock a stamp may be: further, and a wrong device clock would win forever. */
const FUTURE_MS = 5 * 60_000;
export const RECORD_LIMITS = { dataBytes: 64 * 1024, perPut: 1000, perPull: 1000, total: 500_000 };

export function recordProblem(r: SyncRecord, now: number): string | null {
  if (!RECORD_ID.test(r.id)) return "Bad record id";
  if (!STAMP.test(r.stamp)) return "Bad stamp";
  if (Number(r.stamp.slice(0, 13)) > now + FUTURE_MS) return "Stamp is in the future (check the device clock)";
  if (typeof r.data !== "string" || r.data.length === 0 || r.data.length > RECORD_LIMITS.dataBytes) return "Bad record data";
  return null;
}

const PAIR_MS = 10 * 60_000;

export const LIMITS = { itemBytes: 1024 * 1024, itemsPerPush: 500, itemsTotal: 5000, devices: 20, vaultBytes: 4096 };
const KIND = /^[a-z][a-z0-9_-]{0,31}$/;
const ID = /^[A-Za-z0-9_-]{1,64}$/;

/** Why an item can't be stored, or null. Checked by the Worker before the call and again here. */
export function itemProblem(it: IncomingItem): string | null {
  if (!KIND.test(it.kind) || !ID.test(it.id)) return "Bad item key";
  if (!it.deleted && (typeof it.data !== "string" || it.data.length > LIMITS.itemBytes)) return "Bad item data";
  return null;
}

export class Account extends DurableObject<Env> {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => {
      this.sql.exec(`
        CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
          created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL, created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS items (kind TEXT NOT NULL, id TEXT NOT NULL, rev INTEGER NOT NULL, updated_at INTEGER NOT NULL,
          deleted INTEGER NOT NULL, data TEXT, PRIMARY KEY (kind, id));
        CREATE INDEX IF NOT EXISTS items_rev ON items(rev);
        CREATE TABLE IF NOT EXISTS records (id TEXT PRIMARY KEY, stamp TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS records_seq ON records(seq);
      `);
    });
    // Keep-alives are answered without waking the object, so idle connected TVs cost nothing.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  private meta(key: string): string | null {
    return this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
  }
  private setMeta(key: string, value: string) {
    this.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
  }

  // ---- Sessions and devices ----

  async createSession(user: { id: string; email: string; name?: string }, device: DeviceInfo): Promise<{ token: string; deviceId: string }> {
    this.setMeta("user", JSON.stringify(user));
    const count = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices").one().n;
    if (count >= LIMITS.devices) throw new Error("Too many devices on this account; remove one first");
    const deviceId = crypto.randomUUID();
    const now = Date.now();
    this.sql.exec("INSERT INTO devices (id, name, kind, created_at, last_seen) VALUES (?, ?, ?, ?, ?)",
      deviceId, device.name.slice(0, 60), device.kind.slice(0, 20), now, now);
    const secret = randomToken();
    this.sql.exec("INSERT INTO sessions (token_hash, device_id, created_at) VALUES (?, ?, ?)", await sha256(secret), deviceId, now);
    return { token: `jt1.${user.id}.${secret}`, deviceId };
  }

  /** The device a session secret belongs to, or null. Also notes when the device was last seen (at most once a minute). */
  async authenticate(secret: string): Promise<string | null> {
    const row = this.sql.exec<{ device_id: string }>("SELECT device_id FROM sessions WHERE token_hash = ?", await sha256(secret)).toArray()[0];
    if (!row) return null;
    this.sql.exec("UPDATE devices SET last_seen = ? WHERE id = ? AND last_seen < ?", Date.now(), row.device_id, Date.now() - 60_000);
    return row.device_id;
  }

  async signOut(deviceId: string): Promise<void> {
    this.removeDeviceRows(deviceId);
  }

  async removeDevice(deviceId: string): Promise<boolean> {
    const found = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE id = ?", deviceId).one().n > 0;
    this.removeDeviceRows(deviceId);
    return found;
  }

  private removeDeviceRows(deviceId: string) {
    this.sql.exec("DELETE FROM sessions WHERE device_id = ?", deviceId);
    this.sql.exec("DELETE FROM devices WHERE id = ?", deviceId);
    for (const ws of this.ctx.getWebSockets(deviceId)) ws.close(4001, "Signed out");
  }

  async me(): Promise<{ user: unknown; devices: Device[]; vault: string | null; rev: number }> {
    const devices = this.sql.exec<{ id: string; name: string; kind: string; created_at: number; last_seen: number }>(
      "SELECT * FROM devices ORDER BY created_at").toArray()
      .map((d) => ({ id: d.id, name: d.name, kind: d.kind, createdAt: d.created_at, lastSeen: d.last_seen }));
    return { user: JSON.parse(this.meta("user") ?? "null"), devices, vault: this.meta("vault"), rev: Number(this.meta("rev") ?? 0) };
  }

  // ---- Vault: the account key, wrapped on a device by the recovery code ----

  /** Stores the wrapped account key. Only the first one is kept unless [replace] (a new recovery code). */
  async putVault(wrapped: string, replace: boolean): Promise<{ stored: boolean; vault: string }> {
    if (wrapped.length > LIMITS.vaultBytes) throw new Error("Vault too large");
    const existing = this.meta("vault");
    if (existing && !replace) return { stored: false, vault: existing };
    this.setMeta("vault", wrapped);
    return { stored: true, vault: wrapped };
  }

  // ---- Items ----

  async pull(since: number): Promise<{ rev: number; items: Item[] }> {
    const items = this.sql.exec<{ kind: string; id: string; rev: number; updated_at: number; deleted: number; data: string | null }>(
      "SELECT * FROM items WHERE rev > ? ORDER BY rev LIMIT 2000", since).toArray()
      .map((r) => ({ kind: r.kind, id: r.id, rev: r.rev, updatedAt: r.updated_at, deleted: r.deleted === 1, data: r.data }));
    return { rev: Number(this.meta("rev") ?? 0), items };
  }

  /**
   * Applies a device's changes. A change is taken when the device saw the current copy (its [IncomingItem.baseRev]
   * matches), or when it is newer by its own clock; otherwise the stored copy is returned for the device to adopt.
   * Other connected devices are told the account changed.
   */
  async push(deviceId: string, incoming: IncomingItem[]): Promise<{ rev: number; accepted: { kind: string; id: string; rev: number }[]; conflicts: Item[] }> {
    if (incoming.length > LIMITS.itemsPerPush) throw new Error("Too many items in one push");
    for (const it of incoming) { const problem = itemProblem(it); if (problem) throw new Error(problem); }
    let rev = Number(this.meta("rev") ?? 0);
    const accepted: { kind: string; id: string; rev: number }[] = [];
    const conflicts: Item[] = [];
    // All synchronous SQL: applied atomically with no interleaving.
    for (const it of incoming) {
      const cur = this.sql.exec<{ rev: number; updated_at: number; deleted: number; data: string | null }>(
        "SELECT rev, updated_at, deleted, data FROM items WHERE kind = ? AND id = ?", it.kind, it.id).toArray()[0];
      if (!cur) {
        const total = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM items").one().n;
        if (total >= LIMITS.itemsTotal) throw new Error("Account item limit reached");
      }
      if (cur && cur.rev !== it.baseRev && it.updatedAt <= cur.updated_at) {
        conflicts.push({ kind: it.kind, id: it.id, rev: cur.rev, updatedAt: cur.updated_at, deleted: cur.deleted === 1, data: cur.data });
        continue;
      }
      rev += 1;
      this.sql.exec(`INSERT INTO items (kind, id, rev, updated_at, deleted, data) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(kind, id) DO UPDATE SET rev = excluded.rev, updated_at = excluded.updated_at, deleted = excluded.deleted, data = excluded.data`,
        it.kind, it.id, rev, it.updatedAt, it.deleted ? 1 : 0, it.deleted ? null : it.data);
      accepted.push({ kind: it.kind, id: it.id, rev });
    }
    this.setMeta("rev", String(rev));
    if (accepted.length > 0) this.broadcast({ t: "changed", rev }, deviceId);
    return { rev, accepted, conflicts };
  }

  // ---- Records (sync v2): one encrypted record per fact; the newest stamp wins ----

  /** Records changed after [since], oldest first, at most [limit]. [more]: another page follows. */
  async pullRecords(since: number, limit = RECORD_LIMITS.perPull): Promise<{ seq: number; records: SyncRecord[]; more: boolean; now: number }> {
    const rows = this.sql.exec<{ id: string; stamp: string; seq: number; data: string }>(
      "SELECT id, stamp, seq, data FROM records WHERE seq > ? ORDER BY seq LIMIT ?", since, Math.min(limit, RECORD_LIMITS.perPull) + 1).toArray();
    const more = rows.length > limit;
    return { seq: Number(this.meta("seq") ?? 0), records: rows.slice(0, limit), more, now: Date.now() };
  }

  /**
   * Stores each record whose stamp is newer than the one held (compare-and-keep-newest, in one synchronous
   * transaction, so concurrent writers can't race). Older ones come back as [stale] with the newer copy.
   * Other connected devices get the accepted records at once.
   */
  async putRecords(deviceId: string, records: SyncRecord[]): Promise<{ seq: number; accepted: { id: string; seq: number }[]; stale: SyncRecord[]; rejected: { id: string; reason: string }[]; now: number }> {
    if (records.length > RECORD_LIMITS.perPut) throw new Error("Too many records in one put");
    const now = Date.now();
    let seq = Number(this.meta("seq") ?? 0);
    const accepted: { id: string; seq: number }[] = [];
    const stale: SyncRecord[] = [];
    const rejected: { id: string; reason: string }[] = [];
    const pushed: SyncRecord[] = [];
    let total = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM records").one().n;
    for (const r of records) {
      const problem = recordProblem(r, now);
      if (problem) { rejected.push({ id: String(r.id), reason: problem }); continue; }
      const cur = this.sql.exec<{ stamp: string; seq: number; data: string }>("SELECT stamp, seq, data FROM records WHERE id = ?", r.id).toArray()[0];
      if (cur && cur.stamp >= r.stamp) {
        if (cur.stamp !== r.stamp) stale.push({ id: r.id, stamp: cur.stamp, data: cur.data, seq: cur.seq });
        else accepted.push({ id: r.id, seq: cur.seq }); // already held (a retry)
        continue;
      }
      if (!cur && total >= RECORD_LIMITS.total) { rejected.push({ id: r.id, reason: "Account record limit reached" }); continue; }
      if (!cur) total += 1;
      seq += 1;
      this.sql.exec(`INSERT INTO records (id, stamp, seq, data) VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET stamp = excluded.stamp, seq = excluded.seq, data = excluded.data`, r.id, r.stamp, seq, r.data);
      accepted.push({ id: r.id, seq });
      pushed.push({ id: r.id, stamp: r.stamp, data: r.data, seq });
    }
    this.setMeta("seq", String(seq));
    if (pushed.length > 0) this.broadcast({ t: "records", seq, records: pushed }, deviceId);
    return { seq, accepted, stale, rejected, now };
  }

  // ---- TV sign-in by QR (an object named "pair:<code>" holds one pairing; it lives 10 minutes) ----

  /** The TV starts a pairing: its one-time public key, its name, and a hash of the secret it'll collect with. */
  async pairStart(p: { tvKey: string; device: DeviceInfo; secretHash: string }): Promise<{ expiresAt: number }> {
    this.sql.exec("CREATE TABLE IF NOT EXISTS pairing (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
    const expiresAt = Date.now() + PAIR_MS;
    this.sql.exec("INSERT OR REPLACE INTO pairing (k, v) VALUES ('p', ?)", JSON.stringify({ ...p, expiresAt, state: "pending" }));
    await this.ctx.storage.setAlarm(expiresAt + 60_000);
    return { expiresAt };
  }

  private pairing(): any | null {
    this.sql.exec("CREATE TABLE IF NOT EXISTS pairing (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
    const row = this.sql.exec<{ v: string }>("SELECT v FROM pairing WHERE k = 'p'").toArray()[0];
    const p = row ? JSON.parse(row.v) : null;
    return p && p.expiresAt > Date.now() ? p : null;
  }

  /** What the phone shows before approving: the TV's name and key (for the fingerprint check). */
  async pairInfo(): Promise<{ tvKey: string; device: DeviceInfo; expiresAt: number } | null> {
    const p = this.pairing();
    return p && p.state === "pending" ? { tvKey: p.tvKey, device: p.device, expiresAt: p.expiresAt } : null;
  }

  /** The phone approved: keep the TV's new session and the account key sealed to the TV's key, for the TV to collect. */
  async pairApprove(result: unknown): Promise<boolean> {
    const p = this.pairing();
    if (!p || p.state !== "pending") return false;
    this.sql.exec("UPDATE pairing SET v = ? WHERE k = 'p'", JSON.stringify({ ...p, state: "approved", result }));
    return true;
  }

  /** The TV collects (once; the pairing is gone after). Null: not approved yet. */
  async pairTake(secretHash: string): Promise<{ state: string; result?: unknown }> {
    const p = this.pairing();
    if (!p || p.secretHash !== secretHash) return { state: "expired" };
    if (p.state !== "approved") return { state: "pending" };
    await this.ctx.storage.deleteAll();
    return { state: "approved", result: p.result };
  }

  async alarm(): Promise<void> {
    // Only pairing objects set alarms: an expired pairing leaves nothing behind.
    if (this.meta("user") === null) await this.ctx.storage.deleteAll();
  }

  // ---- Live connection ----

  async fetch(request: Request): Promise<Response> {
    const deviceId = request.headers.get("X-Jask-Device");
    if (!deviceId || request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [deviceId]);
    // [net]: which network the device connects from (a hash of its public address), so devices on the same
    // network can try a direct link. Never stored.
    pair[1].serializeAttachment({ deviceId, net: request.headers.get("X-Jask-Net") ?? "" });
    pair[1].send(JSON.stringify({ t: "hello", rev: Number(this.meta("rev") ?? 0), seq: Number(this.meta("seq") ?? 0), now: Date.now(),
      net: request.headers.get("X-Jask-Net") ?? "" }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  /**
   * Messages from a device:
   *  { t: "put", id, records }  → { t: "ack", id, ...putRecords } to it; the records go to the others.
   *  { t: "presence", ... }     → relayed to the other devices with who sent it and their network; never stored.
   */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    const att = ws.deserializeAttachment() as { deviceId: string; net: string } | null;
    if (!att) return;
    // A device removed from the account: its socket closes; nothing it sends is taken.
    if (this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE id = ?", att.deviceId).one().n === 0) { ws.close(4001, "Signed out"); return; }
    let msg: any;
    try { msg = JSON.parse(message); } catch { return; }
    if (msg?.t === "put" && Array.isArray(msg.records)) {
      try {
        const result = await this.putRecords(att.deviceId, msg.records);
        ws.send(JSON.stringify({ t: "ack", id: msg.id, ...result }));
      } catch (e) {
        ws.send(JSON.stringify({ t: "error", id: msg.id, message: e instanceof Error ? e.message : "Rejected" }));
      }
    } else if (msg?.t === "presence") {
      const text = JSON.stringify({ ...msg, from: att.deviceId, net: att.net });
      if (text.length > 16 * 1024) return;
      for (const other of this.ctx.getWebSockets()) {
        const o = other.deserializeAttachment() as { deviceId?: string } | null;
        if (o?.deviceId !== att.deviceId) try { other.send(text); } catch { /* closing */ }
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try { ws.close(code === 1005 ? 1000 : code, "Closed"); } catch { /* already closed */ }
  }

  private broadcast(message: unknown, exceptDevice?: string) {
    const text = JSON.stringify(message);
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment() as { deviceId?: string } | null;
      if (att?.deviceId === exceptDevice) continue;
      try { ws.send(text); } catch { /* closing */ }
    }
  }

  /** Deletes everything this account holds (account deletion). */
  async wipe(): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) ws.close(4002, "Account deleted");
    await this.ctx.storage.deleteAll();
  }
}

export function randomToken(bytes = 32): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
