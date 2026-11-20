// The jaskTV Control Panel. It signs in like a device, opens the account key with the password (kept in this tab's
// memory only), and works on the account's sealed records:
//   - reads cores ("core" records and their "cst" status), playlists ("pl"), shared settings ("st") and the device list;
//   - writes playlists and settings the way the apps do, so every device and core picks them up;
//   - sends commands to a core as "cmd" records ("<core id>/<id>": {action, at}); the core answers in the same record
//     with {done, ok, message}, and ignores commands more than five minutes old.
// Changes arrive over the account's live connection (/v1/live), the same one the apps hold.
import * as c from "./crypto.js";

const API = location.origin;
const SESSION = "jasktv-panel-session"; // { email, token, tag }: useless without the password, which is never stored
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));

let session = null;          // { email, token, key, tag }
let clock = null;
const records = new Map();   // "coll\0key" -> { coll, key, stamp, value }
let seq = 0, me = null, socket = null, retry = 0, tab = "overview", rendering = false, again = false;

// ---- Account server ----

async function call(method, path, body, token = session?.token) {
  const r = await fetch(API + path, {
    method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) { const e = new Error(data.message || data.error || `HTTP ${r.status}`); e.status = r.status; throw e; }
  if (typeof data.now === "number") clock?.observe(data.now);
  return data;
}

function saved() { try { return JSON.parse(localStorage.getItem(SESSION) || "null"); } catch { return null; } }
function save(v) { try { v ? localStorage.setItem(SESSION, JSON.stringify(v)) : localStorage.removeItem(SESSION); } catch { /* private mode */ } }
const randomHex = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");

async function signIn(email, password) {
  const address = email.trim().toLowerCase();
  const keys = await c.passwordKeys(address, password);
  const prev = saved();
  let token = null;
  // This browser's earlier session for the same account: reuse it rather than adding another device.
  if (prev && prev.email === address) {
    try { me = await call("GET", "/v1/me", null, prev.token); token = prev.token; } catch (e) { if (e.status !== 401) throw e; }
  }
  if (!token) {
    const r = await call("POST", "/v1/auth/login", { email: address, loginKey: keys.loginKey, device: { name: "Control Panel", kind: "web" } }, null);
    token = r.token;
    me = await call("GET", "/v1/me", null, token);
  }
  const key = await c.unwrapVault(me.vault, keys.wrapKey);
  if (!key) throw new Error("This password doesn't open the account's data. If it was changed recently, sign in on a device first.");
  const tag = prev?.tag && /^[a-z0-9]{8}$/.test(prev.tag) ? prev.tag : randomHex(4);
  save({ email: address, token, tag });
  session = { email: address, token, key, tag };
  clock = c.stampClock(tag);
}

async function take(wire) {
  for (const w of wire) {
    seq = Math.max(seq, w.seq || 0);
    const r = await c.openRecord(session.key, w);
    if (!r) continue;
    const id = `${r.coll}\u0000${r.key}`;
    const held = records.get(id);
    if (!held || held.stamp < r.stamp) records.set(id, r);
  }
}

async function pull() {
  for (;;) {
    const page = await call("GET", `/v2/records?since=${seq}&limit=1000`);
    await take(page.records);
    if (!page.more) break;
  }
}

/** Writes records [coll, key, value] (value null removes); each stamped after the copy held, so it wins. */
async function put(...items) {
  const wire = [];
  for (const [coll, key, value] of items) {
    const held = records.get(`${coll}\u0000${key}`);
    wire.push(await c.sealRecord(session.key, coll, key, clock.next(held?.stamp), value));
  }
  const r = await call("POST", "/v2/records", { records: wire });
  if (r.rejected?.length) throw new Error(r.rejected[0].reason);
  if (r.stale?.length) await take(r.stale);
  // What was accepted is ours now: take it without waiting for the next pull.
  for (let i = 0; i < items.length; i++) {
    const [coll, key, value] = items[i];
    if (r.accepted.some((a) => a.id === wire[i].id)) records.set(`${coll}\u0000${key}`, { coll, key, stamp: wire[i].stamp, value });
  }
  schedule();
}

// ---- Live connection ----

function connect() {
  if (!session) return;
  const url = API.replace(/^http/, "ws") + "/v1/live";
  let ws;
  try { ws = new WebSocket(url, ["jasktv", session.token]); } catch { return later(); }
  socket = ws;
  let ping = null;
  ws.onopen = () => {
    retry = 0; $("live").textContent = "● live";
    ping = setInterval(() => { try { ws.send("ping"); } catch { /* closing */ } }, 30_000);
  };
  ws.onmessage = async (ev) => {
    if (ev.data === "pong") return;
    let msg; try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.now) clock.observe(msg.now);
    if (msg.t === "records") { await take(msg.records || []); schedule(); }
    else if (msg.t === "hello" || msg.t === "changed") { await pull().catch(() => {}); schedule(); }
  };
  ws.onclose = (ev) => {
    clearInterval(ping);
    if (socket !== ws) return;
    socket = null;
    if (ev.code === 4001) return signedOut("This panel was signed out from another device.");
    $("live").textContent = "reconnecting…";
    later();
  };
}

function later() {
  if (!session) return;
  retry = Math.min(retry + 1, 6);
  setTimeout(async () => { if (!session || socket) return; try { await refreshMe(); await pull(); schedule(); } catch { /* offline */ } connect(); }, 1000 * 2 ** retry);
}

async function refreshMe() { me = Object.assign(me || {}, await call("GET", "/v1/me")); }

// ---- Reading the account ----

const values = (coll) => [...records.values()].filter((r) => r.coll === coll && r.value != null);
const setting = (k) => records.get(`st\u0000${k}`)?.value;

function ago(ms) {
  if (!ms) return "never";
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}
const num = (n) => (n ?? 0).toLocaleString();
const mb = (b) => (b / 1e6).toFixed(b > 1e8 ? 0 : 1) + " MB";

async function sha256hex(text) {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...h].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const sourceKey = (v) => sha256hex(`${v.kind}|${String(v.url || "").replace(/\/+$/, "")}|${v.username || ""}`);

const LIST_NAMES = { get_vod_streams: "Films", get_series: "Series", get_live_streams: "Live channels", xmltv: "Guide" };

// ---- Views ----

function schedule() {
  if (rendering) { again = true; return; }
  rendering = true;
  requestAnimationFrame(async () => {
    try { await render(); } finally { rendering = false; if (again) { again = false; schedule(); } }
  });
}

const notices = {}; // result lines under forms ("Saved for every device."), kept across redraws
function notice(id, text) { notices[id] = text; const el = $(id); if (el) el.textContent = text; }

async function render() {
  if (!session) return;
  // Never redraw over something being typed: a change from another device waits until the form is saved or left.
  if (tab !== "overview" && [...document.querySelectorAll("#view input")].some((i) => i.dataset.dirty)) return;
  const keep = document.activeElement?.id;
  if (tab === "overview") await renderOverview();
  else if (tab === "playlists") renderPlaylists();
  else renderSettings();
  for (const [id, text] of Object.entries(notices)) { const el = $(id); if (el) el.textContent = text; }
  if (keep && $(keep)) $(keep).focus();
}

document.addEventListener("input", (ev) => { if (ev.target.closest("#view")) ev.target.dataset.dirty = "1"; });

function commandState(coreId) {
  const cmds = values("cmd").filter((r) => r.key.startsWith(coreId + "/")).sort((a, b) => (b.value.at || 0) - (a.value.at || 0));
  const last = cmds[0]?.value;
  if (!last) return "";
  const label = ACTIONS.find((a) => a.action === last.action && (a.service ?? null) === (last.service ?? null))?.label || last.action;
  if (!last.done) return clock.now() - last.at > 5 * 60_000 ? `${label}: no answer (core offline?)` : `${label}: sent…`;
  return `${label}: ${last.ok ? "" : "failed — "}${last.message || "done"} · ${ago(last.done)}`;
}

const ACTIONS = [
  { action: "refresh-lists", label: "Refresh lists now", full: false },
  { action: "restart", service: "prowlarr", label: "Restart Prowlarr", full: true },
  { action: "restart", service: "torrserver", label: "Restart TorrServer", full: true },
  { action: "add-trackers", label: "Add missing trackers", full: true },
];

async function renderOverview() {
  const playlists = values("pl");
  const byKey = {};
  for (const p of playlists) byKey[await sourceKey(p.value)] = p.value.name;
  const status = Object.fromEntries(values("cst").map((r) => [r.key, r.value]));
  const devices = me?.devices || [];
  const seen = Object.fromEntries(devices.map((d) => [d.id, d.lastSeen]));
  const cores = values("core").map((r) => ({ id: r.key, ...r.value, status: status[r.key] }))
    .sort((a, b) => (a.role === "helper") - (b.role === "helper"));

  const coreCards = cores.map((k) => {
    const st = k.status || {};
    const at = Math.max(st.at || 0, seen[k.id] || 0);
    const fresh = at && Date.now() - at < 20 * 60_000;
    const helper = k.role === "helper";
    const lists = Object.entries(st.provider || {}).filter(([n]) => LIST_NAMES[n.split("/")[1]])
      .map(([n, v]) => { const [src, action] = n.split("/");
        return `<tr><td>${esc(byKey[src] || "Playlist")} · ${esc(LIST_NAMES[action])}</td><td class="n">${action === "xmltv" ? mb(v.bytes) : num(v.items)}</td>
          <td class="n muted">${ago(v.fetched_at)}</td></tr>${v.last_error ? `<tr><td colspan=3 class="err">${esc(v.last_error)}</td></tr>` : ""}`; }).join("");
    const m = st.matching || {};
    const pct = m.films_checked ? Math.round((100 * (m.films_matched || 0)) / m.films_checked) : 0;
    const svc = st.services || {};
    const trackers = (st.indexers || []).filter((i) => i.enabled).map((i) => i.name);
    const service = (name, s, detail) => s ? `<div class="row"><span><span class="dot ${s.running ? "ok" : "bad"}"></span>&nbsp; ${name} <span class="muted">${esc(s.version)}</span></span><span class="v">${detail}</span></div>` : "";
    const buttons = ACTIONS.filter((a) => !a.full || !helper).map((a) =>
      `<button class="ghost" data-core="${esc(k.id)}" data-action="${a.action}" ${a.service ? `data-service="${a.service}"` : ""}>${a.label}</button>`).join("");
    return `<div class="card">
      <h3><span class="dot ${fresh ? "ok" : "bad"}"></span>${esc(k.name)}</h3>
      <div class="sub">${helper ? "Helper: lists, guide, matching, posters" : "Full core: also torrents and streaming"} · v${esc(k.version)} · ${fresh ? "seen" : "last seen"} ${ago(at)}</div>
      <div class="chips">${(k.addresses || []).concat(k.urls || []).map((a) => `<span class="chip">${esc(a)}</span>`).join("")}</div>
      ${lists ? `<table><tr><th>Lists from your provider</th><th class="n">Items</th><th class="n">Fetched</th></tr>${lists}</table>` : `<div class="note">${st.at ? "No lists fetched yet: add a playlist, or wait for this core's first fetch." : "No status yet: this core is on an older version, or hasn't reported."}</div>`}
      ${m.films_checked ? `<div class="row" style="margin-top:6px"><span>TMDB matching</span><span class="v">${num(m.films_matched)} of ${num(m.films_checked)} films · ${num(m.shows_checked)} shows</span></div><div class="bar"><i style="width:${pct}%"></i></div>` : ""}
      ${service("Prowlarr", svc.prowlarr, trackers.length ? esc(trackers.join(", ")) : "no trackers")}
      ${service("TorrServer", svc.torrserver, svc.torrserver?.running ? "streaming torrents" : "stopped")}
      <div class="actions">${buttons}</div>
      <div class="result" aria-live="polite">${esc(commandState(k.id))}</div>
    </div>`;
  }).join("") || `<div class="card"><div class="sub">No jasktv-core has joined this account yet. Install one on an always-on computer and sign it in.</div></div>`;

  const deviceRows = devices.filter((d) => d.kind !== "core").map((d) => {
    const self = d.id === me.deviceId;
    return `<div class="row"><span>${esc(d.name)} <span class="muted">· ${esc(d.kind)}</span></span>
      <span class="v">${ago(d.lastSeen)} ${self ? '<span class="chip">this panel</span>' : `<button class="danger" data-device="${esc(d.id)}" data-name="${esc(d.name)}">Sign out</button>`}</span></div>`;
  }).join("");

  // Who builds the account's TMDB match table, and how fresh its copy in the account's files is.
  const lease = records.get("lease\u0000tmdb")?.value;
  const snaps = values("snap").map((r) => r.value);
  const newest = Math.max(0, ...snaps.map((v) => Math.max(v.base_at || 0, v.delta_now || 0)));
  const leaseLive = lease && lease.until > clock.now();
  const builder = leaseLive ? `<b>${esc(lease.name)}</b> builds the TMDB match table; every other core and device copies it.`
    : cores.length ? "No core is building the TMDB match table right now: the next one to run takes over." : "";
  const shared = builder ? `<div class="card" style="margin-bottom:14px"><div class="row"><span>${builder}</span>
    <span class="v">${newest ? `Account copy updated ${ago(newest)}` : "No account copy yet"}</span></div></div>` : "";

  $("view").innerHTML = `<h2>Cores</h2>${shared}<div class="grid">${coreCards}</div>
    <h2>Devices</h2><div class="card">${deviceRows || '<div class="sub">None</div>'}</div>`;
}

const KINDS = { XTREAM: "Xtream codes", M3U: "M3U playlist", STALKER: "Stalker portal" };

function renderPlaylists() {
  const rows = values("pl").sort((a, b) => a.value.name.localeCompare(b.value.name)).map((p) => {
    const v = p.value; let host = ""; try { host = new URL(v.url).host; } catch { host = v.url ? "file" : ""; }
    return `<div class="row"><span>${esc(v.name)} <span class="muted">· ${esc(KINDS[v.kind] || v.kind)} · ${esc(host)}</span></span>
      <span class="v"><button class="ghost" data-toggle="${esc(p.key)}">${v.enabled === false ? "Turn on" : "Turn off"}</button>
      <button class="ghost" data-edit="${esc(p.key)}">Edit</button> <button class="danger" data-remove="${esc(p.key)}">Remove</button></span></div>`;
  }).join("");
  $("view").innerHTML = `<h2>Playlists</h2><div class="card">${rows || '<div class="sub">No playlists yet.</div>'}
    <div class="actions"><button id="addPlaylist">Add playlist</button></div></div>
    <div class="note">Every device and core gets these. Passwords are sealed with your account key.</div>`;
}

function renderSettings() {
  const addons = setting("stremioAddons") || [];
  const field = (id, label, value, type = "text", hint = "") =>
    `<label for="${id}">${label}</label><input id="${id}" type="${type}" value="${esc(value ?? "")}" autocomplete="off" spellcheck="false" placeholder="${esc(hint)}">`;
  const full = values("core").find((r) => r.value.role !== "helper");
  $("view").innerHTML = `<div class="grid">
    <div><h2>Metadata</h2><div class="card">
      ${field("tmdbKey", "TMDB API key", setting("tmdbKey"), "password")}
      ${field("omdbKey", "OMDb API key", setting("omdbKey"), "password", "optional")}
      <div class="actions"><button id="saveKeys">Save</button></div><div class="result" id="keysResult"></div>
    </div></div>
    <div><h2>Stremio add-ons</h2><div class="card">
      ${addons.map((a, i) => `<div class="row"><span>${esc(a.name)}</span><span class="v"><button class="danger" data-addon="${i}">Remove</button></span></div>`).join("") || '<div class="sub">None.</div>'}
      ${field("addonUrl", "Add an add-on", "", "url", "https://…/manifest.json")}
      <div class="actions"><button id="addAddon">Add</button></div><div class="result" id="addonResult"></div>
    </div></div>
    <div><h2>Your own torrent services</h2><div class="card">
      <div class="sub">${full ? `Not needed: ${esc(full.value.name)} runs Prowlarr and TorrServer for every device.` : "Only if you run them yourself, without a core."}</div>
      ${field("prowlarrUrl", "Prowlarr address", setting("prowlarrUrl"), "url", "http://192.168.1.20:9696")}
      ${field("prowlarrKey", "Prowlarr API key", setting("prowlarrKey"), "password")}
      ${field("torrServerUrl", "TorrServer address", setting("torrServerUrl"), "url", "http://192.168.1.20:8090")}
      <div class="actions"><button id="saveTorrent">Save</button></div><div class="result" id="torrentResult"></div>
    </div></div></div>`;
}

// ---- Playlist dialog ----

function playlistDialog(key) {
  const v = key ? records.get(`pl\u0000${key}`)?.value : { kind: "XTREAM", enabled: true };
  const f = $("dlgForm");
  const kind = v.kind || "XTREAM";
  f.innerHTML = `<h3 style="margin:0 0 4px">${key ? "Edit playlist" : "Add playlist"}</h3>
    <label for="pKind">Type</label><select id="pKind" ${key ? "disabled" : ""}>${Object.entries(KINDS).map(([k, l]) => `<option value="${k}" ${k === kind ? "selected" : ""}>${l}</option>`).join("")}</select>
    <label for="pName">Name</label><input id="pName" required value="${esc(v.name || "")}">
    <label for="pUrl" id="pUrlLabel">Server address</label><input id="pUrl" type="url" required value="${esc(v.url || "")}" placeholder="http://provider.example:8080">
    <div class="two" id="pCreds"><div><label for="pUser">Username</label><input id="pUser" value="${esc(v.username || "")}" autocomplete="off"></div>
      <div><label for="pPass">Password</label><input id="pPass" type="password" value="${esc(v.password || "")}" autocomplete="off"></div></div>
    <div id="pMacBox"><label for="pMac">MAC address</label><input id="pMac" value="${esc(v.macAddress || "")}" placeholder="00:1A:79:…"></div>
    <label for="pEpg">Guide address (optional)</label><input id="pEpg" type="url" value="${esc(v.epgUrl || "")}">
    <div class="result err" id="pErr"></div>
    <div class="actions"><button value="save" id="pSave">${key ? "Save" : "Add"}</button><button class="ghost" value="cancel" formnovalidate>Cancel</button></div>`;
  const shape = () => {
    const k = $("pKind").value;
    $("pCreds").hidden = k !== "XTREAM"; $("pMacBox").hidden = k !== "STALKER";
    $("pUrlLabel").textContent = k === "M3U" ? "Playlist address (.m3u)" : k === "STALKER" ? "Portal address" : "Server address";
  };
  $("pKind").onchange = shape; shape();
  $("pSave").onclick = async (ev) => {
    ev.preventDefault();
    const k = $("pKind").value;
    const next = { ...v, kind: k, name: $("pName").value.trim(), url: $("pUrl").value.trim(), enabled: v.enabled !== false,
      username: k === "XTREAM" ? $("pUser").value.trim() || null : null, password: k === "XTREAM" ? $("pPass").value || null : null,
      macAddress: k === "STALKER" ? $("pMac").value.trim() || null : null, epgUrl: $("pEpg").value.trim() || null,
      userAgent: v.userAgent || "OpenTV/0.1 (Android)", liveFormat: v.liveFormat || "HLS" };
    if (!next.name || !/^https?:\/\//i.test(next.url)) { $("pErr").textContent = "A name and an http(s) address are needed."; return; }
    if (k === "XTREAM" && (!next.username || !next.password)) { $("pErr").textContent = "Xtream codes need a username and password."; return; }
    try { await put(["pl", key || randomHex(12), next]); $("dlg").close(); }
    catch (e) { $("pErr").textContent = e.message; }
  };
  $("dlg").showModal();
}

// ---- Actions ----

document.addEventListener("click", async (ev) => {
  const b = ev.target.closest("button");
  if (!b || !session) return;
  try {
    if (b.dataset.tab) {
      tab = b.dataset.tab;
      for (const k of Object.keys(notices)) delete notices[k];
      document.querySelectorAll("nav button").forEach((n) => n.toggleAttribute("aria-current", n === b) || n.removeAttribute("aria-current"));
      b.setAttribute("aria-current", "page");
      schedule();
    } else if (b.dataset.core) {
      const cmd = { action: b.dataset.action, at: clock.now(), from: "Control Panel" };
      if (b.dataset.service) cmd.service = b.dataset.service;
      b.disabled = true;
      await put(["cmd", `${b.dataset.core}/${randomHex(6)}`, cmd]);
    } else if (b.dataset.device) {
      if (!confirm(`Sign "${b.dataset.name}" out of your account? It will need to sign in again.`)) return;
      await call("DELETE", `/v1/devices/${b.dataset.device}`); await refreshMe(); schedule();
    } else if (b.dataset.toggle) {
      const r = records.get(`pl\u0000${b.dataset.toggle}`);
      await put(["pl", r.key, { ...r.value, enabled: r.value.enabled === false }]);
    } else if (b.dataset.edit) playlistDialog(b.dataset.edit);
    else if (b.dataset.remove) {
      const r = records.get(`pl\u0000${b.dataset.remove}`);
      if (confirm(`Remove "${r.value.name}" from every device?`)) await put(["pl", r.key, null]);
    } else if (b.id === "addPlaylist") playlistDialog(null);
    else if (b.id === "saveKeys") {
      await put(["st", "tmdbKey", $("tmdbKey").value.trim()], ["st", "omdbKey", $("omdbKey").value.trim()]);
      document.querySelectorAll("#view input").forEach((i) => delete i.dataset.dirty);
      notice("keysResult", "Saved for every device.");
    } else if (b.id === "saveTorrent") {
      await put(["st", "prowlarrUrl", $("prowlarrUrl").value.trim().replace(/\/+$/, "")], ["st", "prowlarrKey", $("prowlarrKey").value.trim()],
        ["st", "torrServerUrl", $("torrServerUrl").value.trim().replace(/\/+$/, "")]);
      document.querySelectorAll("#view input").forEach((i) => delete i.dataset.dirty);
      notice("torrentResult", "Saved for every device.");
    } else if (b.id === "addAddon") {
      const url = $("addonUrl").value.trim();
      if (!/^https?:\/\/.+manifest\.json$/i.test(url)) { notice("addonResult", "Paste the add-on's manifest.json address."); return; }
      let name = new URL(url).host;
      try { const m = await (await fetch(url)).json(); if (m?.name) name = String(m.name); } catch { /* keep the host */ }
      const list = (setting("stremioAddons") || []).filter((a) => a.manifestUrl !== url);
      await put(["st", "stremioAddons", [...list, { manifestUrl: url, name }]]);
      document.querySelectorAll("#view input").forEach((i) => delete i.dataset.dirty);
      notice("addonResult", `Added ${name}.`);
    } else if (b.dataset.addon) {
      const list = (setting("stremioAddons") || []).filter((_, i) => String(i) !== b.dataset.addon);
      await put(["st", "stremioAddons", list]);
    }
  } catch (e) {
    if (e.status === 401) return signedOut("This panel was signed out.");
    alert(e.message);
  } finally { if (b.dataset.core) b.disabled = false; }
});

// ---- Sign in and out ----

function signedOut(message) {
  const ws = socket; socket = null; try { ws?.close(); } catch { /* closed */ }
  session = null; records.clear(); seq = 0;
  const prev = saved(); if (prev) save({ email: prev.email, tag: prev.tag }); // keep the email to prefill, not the token
  $("view").hidden = true; $("who").hidden = true; $("nav").hidden = true; $("login").hidden = false; $("msg").textContent = message || "";
}

$("form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  $("go").disabled = true; $("msg").textContent = "";
  try {
    await signIn($("em").value, $("pw").value);
    $("pw").value = "";
    $("login").hidden = true; $("view").hidden = false; $("who").hidden = false; $("nav").hidden = false; $("email").textContent = session.email;
    $("view").innerHTML = '<div class="sub" style="margin:24px 4px">Opening your account…</div>';
    await pull(); schedule(); connect();
    setInterval(() => { if (session) { refreshMe().then(schedule).catch(() => {}); } }, 60_000); // last-seen times
  } catch (e) {
    $("msg").textContent = e.status === 401 ? "Wrong email or password." : e.status === 429 ? e.message : e.message || "Couldn't sign in.";
  } finally { $("go").disabled = false; }
});

$("signout").addEventListener("click", async () => {
  try { await call("POST", "/v1/auth/signout", {}); } catch { /* already gone */ }
  signedOut();
});

$("dlgForm").addEventListener("click", (ev) => { if (ev.target.value === "cancel") { ev.preventDefault(); $("dlg").close(); } });

const prev = saved();
if (prev?.email) $("em").value = prev.email;
