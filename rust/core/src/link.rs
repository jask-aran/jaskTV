//! Core on your jaskTV account. It signs in the way a TV does: it shows a code and QR code (console and setup
//! page), your phone approves, and the phone seals the account key to core. Then core keeps a record in your account
//! (kind "core"): its addresses, port, version, what it can do, and the access secret clients use with it. Every
//! signed-in jaskTV device reads that record through sync, so nobody types an address.
//!
//! What core keeps (its sign-in, the account key, its access secret) is in a state file beside the binary, readable
//! only by the account running core.

use jasktv_engine::account::{AccountClient, PushItem};
use jasktv_engine::crypto::{self, AccountKey, PairKey};
use jasktv_engine::records::{self, StampClock};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Serialize, Deserialize, Default, Clone)]
struct Saved {
    /// Clients prove they may use core with this (sent as a path prefix or a Bearer header).
    access_secret: String,
    server: Option<String>,
    token: Option<String>,
    device_id: Option<String>,
    email: Option<String>,
    account_key: Option<String>,
    /// The old (sync v1) record's revision, kept only to remove it.
    record_rev: i64,
    /// This core's stamp tag for sync records (8 characters), made once.
    #[serde(default)]
    tag: String,
}

/// What core holds of the account's synced data (read-only copies of the app's collections).
#[derive(Default)]
pub struct Synced {
    /// "pl": playlists, by uid (the app's `SyncedSource`, credentials included).
    pub playlists: std::collections::BTreeMap<String, serde_json::Value>,
    /// "st": settings, by name ("tmdbKey", "stremioAddons", …).
    pub settings: std::collections::BTreeMap<String, serde_json::Value>,
    seq: i64,
    stamps: std::collections::HashMap<(String, String), String>,
    /// Core's own record as last written or read back (to write only on change).
    core_record: Option<serde_json::Value>,
    /// Commands for this core (collection "cmd", key "<core id>/<command id>") not yet done: (key, value).
    pub commands: Vec<(String, serde_json::Value)>,
    /// The account-wide records core acts on: "lease" (who builds the match table), "snap" (its snapshots in the
    /// account's files) and other cores' "core" records (to copy from the builder directly).
    pub shared: std::collections::HashMap<(String, String), serde_json::Value>,
}

/// What the setup page and console show.
#[derive(Clone)]
pub enum Status {
    /// No account server configured: core works by its address and secret alone.
    Standalone,
    Pairing { code: String, link: String, fingerprint: String },
    Waiting(String),
    SignedIn { email: String },
}

pub struct Link {
    path: PathBuf,
    saved: Mutex<Saved>,
    status: Mutex<Status>,
    client: Option<AccountClient>,
    port: u16,
    synced: Mutex<Synced>,
    clock: Mutex<Option<StampClock>>,
    /// "full" or "helper" (see Config::role), and the addresses devices should use (empty: this machine's LAN address).
    role: String,
    advertise: Vec<String>,
    public_url: Mutex<String>,
}

impl Link {
    pub fn load(path: PathBuf, account_server: &str, port: u16, role: &str, advertise: Vec<String>) -> Arc<Link> {
        let mut saved: Saved = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        let fresh = saved.access_secret.is_empty();
        if fresh { saved.access_secret = crypto::random_secret(24); }
        let client = (!account_server.is_empty()).then(|| AccountClient::new(account_server));
        // A different server than the one core signed in to: start over there.
        if let (Some(c), Some(s)) = (&client, &saved.server) {
            if c.base() != s { saved = Saved { access_secret: saved.access_secret, ..Default::default() }; }
        }
        let status = match (&client, &saved.email) {
            (None, _) => Status::Standalone,
            (Some(_), Some(email)) if saved.token.is_some() => Status::SignedIn { email: email.clone() },
            _ => Status::Waiting("Starting sign-in…".into()),
        };
        let fresh = fresh || saved.tag.is_empty();
        if saved.tag.is_empty() { saved.tag = crypto::random_secret(6); } // 6 bytes → 8 base64url characters
        let clock = StampClock::new(&saved.tag);
        let link = Arc::new(Link { path, saved: Mutex::new(saved), status: Mutex::new(status), client, port,
            synced: Mutex::new(Synced::default()), clock: Mutex::new(Some(clock)), role: role.to_string(), advertise, public_url: Mutex::new(String::new()) });
        if fresh { link.save(); }
        link
    }

    pub fn access_secret(&self) -> String { self.saved.lock().unwrap().access_secret.clone() }

    /// A new access secret: every device picks it up from core's record; old links stop working.
    pub fn new_secret(&self) {
        self.saved.lock().unwrap().access_secret = crypto::random_secret(24);
        self.save();
    }

    /// The account's playlists and settings as core last read them.
    pub fn synced<R>(&self, f: impl FnOnce(&Synced) -> R) -> R { f(&self.synced.lock().unwrap()) }
    pub fn status(&self) -> Status { self.status.lock().unwrap().clone() }
    fn set_status(&self, s: Status) { *self.status.lock().unwrap() = s; }

    fn save(&self) {
        let text = serde_json::to_string_pretty(&*self.saved.lock().unwrap()).expect("state serialises");
        let tmp = self.path.with_extension("tmp");
        if std::fs::write(&tmp, text).is_ok() {
            restrict(&tmp);
            let _ = std::fs::rename(&tmp, &self.path);
        } else {
            eprintln!("jasktv-core: can't write {}", self.path.display());
        }
    }

    /// Signs in with email and password (no phone needed): core gets its own session as a "core" device and the
    /// account key from the vault's password copy. The password itself is never stored.
    pub fn login(&self, email: &str, password: &str) -> Result<String, String> {
        let client = self.client.as_ref().ok_or("account_server isn't set in core's config")?;
        let keys = crypto::password_keys(email, password);
        let signed = client.login(email, &keys.login_key, &device_name(), "core").map_err(|e| e.to_string())?;
        let me = client.me(&signed.token).map_err(|e| e.to_string())?;
        let unlocked = me["vault"].as_str().and_then(crypto::vault_password_part)
            .and_then(|part| crypto::unwrap_with_key(part, &keys.wrap_key));
        let Some(account_key) = unlocked else {
            let _ = client.sign_out(&signed.token);
            return Err("signed in, but the password doesn't unlock the account's synced data (the vault has no password copy); \
                sign core in from your phone instead".into());
        };
        {
            let mut s = self.saved.lock().unwrap();
            s.server = Some(client.base().to_string());
            s.token = Some(signed.token);
            s.device_id = Some(signed.device_id);
            s.email = Some(signed.user.email.clone());
            s.account_key = Some(crypto::key_to_text(&account_key));
            s.record_rev = 0;
        }
        self.save();
        self.publish(client).map_err(|e| format!("signed in, but couldn't write core's record: {e}"))?;
        Ok(signed.user.email)
    }

    /// Removes core's record and ends its session (it leaves the device list), and forgets the account key.
    pub fn logout(&self) -> Result<(), String> {
        let (token, id, rev) = {
            let s = self.saved.lock().unwrap();
            (s.token.clone().ok_or("core isn't signed in")?, s.device_id.clone(), s.record_rev)
        };
        let client = self.client.as_ref().ok_or("account_server isn't set in core's config")?;
        if let Some(id) = id {
            let key = self.saved.lock().unwrap().account_key.as_deref().and_then(crypto::key_from_text);
            if let (Some(key), Some(clock)) = (key, self.clock.lock().unwrap().as_ref()) {
                let _ = client.records_pull(&token, i64::MAX - 1, 1).map(|p| clock.observe_server(p.now));
                if let Err(e) = client.records_put(&token, &[records::seal(&key, "core", &id, &clock.next(), None)]) {
                    eprintln!("jasktv-core: couldn't remove its record: {e}");
                }
            }
            if rev > 0 {
                let gone = PushItem { kind: "core".into(), id, base_rev: rev, updated_at: now_ms(), deleted: true, data: None };
                let _ = client.push(&token, vec![gone]);
            }
        }
        let result = client.sign_out(&token).map(|_| ()).map_err(|e| e.to_string());
        { let mut s = self.saved.lock().unwrap(); *s = Saved { access_secret: crypto::random_secret(24), tag: s.tag.clone(), ..Default::default() }; }
        self.save();
        result
    }

    pub fn set_public_url(&self, url: &str) { *self.public_url.lock().unwrap() = url.to_string(); }

    /// Core's device id and the account key, once signed in.
    pub fn identity(&self) -> Option<(String, AccountKey)> {
        let s = self.saved.lock().unwrap();
        Some((s.device_id.clone()?, s.account_key.as_deref().and_then(crypto::key_from_text)?))
    }

    /// Reads the account's records now (for commands that run without the server loop).
    pub fn catch_up_now(&self) {
        let Some(client) = &self.client else { return };
        let (token, key) = { let s = self.saved.lock().unwrap(); (s.token.clone(), s.account_key.as_deref().and_then(crypto::key_from_text)) };
        if let (Some(token), Some(key)) = (token, key) {
            let clock = self.clock.lock().unwrap();
            if let Err(e) = self.catch_up(client, &token, &key, clock.as_ref().expect("clock")) { eprintln!("jasktv-core: can't read the account: {e}"); }
        }
    }

    /// Copies TMDB matches of [source] from a device on the home network into [take] (rows: how many were new).
    /// (whether the device had them, rows added).
    pub fn copy_from(&self, device: Option<&str>, addr: std::net::SocketAddr, source: &str, mut take: impl FnMut(&serde_json::Value) -> usize)
        -> Result<(bool, usize), String> {
        let (me, key) = {
            let s = self.saved.lock().unwrap();
            (s.device_id.clone().ok_or("core isn't signed in")?, s.account_key.as_deref().and_then(crypto::key_from_text).ok_or("core has no account key")?)
        };
        let mut added = 0;
        let found = jasktv_engine::lan::fetch(&key, &me, device, addr, source, "tmdb", |chunk| added += take(chunk))?;
        Ok((found, added))
    }

    /// Runs for the life of core: signs in when it isn't, then keeps its record current.
    pub fn run(self: Arc<Self>) {
        let Some(client) = &self.client else { return };
        loop {
            let signed_in = self.saved.lock().unwrap().token.is_some();
            if !signed_in {
                self.pair(client);
                continue;
            }
            match self.publish(client) {
                Ok(()) => {}
                Err(e) if e.status == 401 => {
                    // Signed out from a phone (removed from the device list): sign in again.
                    eprintln!("jasktv-core: signed out of the account; showing a new sign-in code");
                    let mut s = self.saved.lock().unwrap();
                    *s = Saved { access_secret: crypto::random_secret(24), tag: s.tag.clone(), ..Default::default() };
                    drop(s);
                    self.save();
                    continue;
                }
                Err(e) => eprintln!("jasktv-core: couldn't update its record in your account: {e}"),
            }
            std::thread::sleep(Duration::from_secs(300));
        }
    }

    /// One sign-in code, shown until the phone approves it or it expires.
    fn pair(&self, client: &AccountClient) {
        let key = PairKey::new();
        let public = key.public_text();
        let start = match client.pair_start(&public, &device_name(), "core") {
            Ok(s) => s,
            Err(e) => {
                self.set_status(Status::Waiting(format!("Can't start sign-in: {e}. Trying again shortly.")));
                std::thread::sleep(Duration::from_secs(30));
                return;
            }
        };
        let link = client.pair_link(&start.code, &public);
        let fingerprint = crypto::pair_fingerprint(&public);
        eprintln!("\njasktv-core isn't signed in. On your phone: jaskTV → Settings → Account → Sign in a TV,");
        eprintln!("then scan this code (or enter {} {}), and check it shows {fingerprint}.\n", &start.code[..4], &start.code[4..]);
        if let Ok(qr) = qrcode::QrCode::new(link.as_bytes()) {
            eprintln!("{}", qr.render::<qrcode::render::unicode::Dense1x2>().dark_color(qrcode::render::unicode::Dense1x2::Light)
                .light_color(qrcode::render::unicode::Dense1x2::Dark).quiet_zone(true).build());
        }
        self.set_status(Status::Pairing { code: start.code.clone(), link, fingerprint });
        loop {
            std::thread::sleep(Duration::from_secs(3));
            if now_ms() > start.expires_at { return; }
            let take = match client.pair_take(&start.code, &start.secret) { Ok(t) => t, Err(_) => continue };
            match take.state.as_str() {
                "expired" => return,
                "approved" => {
                    let Some(r) = take.result else { return };
                    let account_key = match key.open_from_phone(&r.phone_key, &start.code, &r.sealed_key) {
                        Ok(k) => k,
                        Err(e) => { eprintln!("jasktv-core: the phone's approval couldn't be opened ({e}); try again"); return; }
                    };
                    {
                        let mut s = self.saved.lock().unwrap();
                        s.server = Some(client.base().to_string());
                        s.token = Some(r.token);
                        s.device_id = Some(r.device_id);
                        s.email = Some(r.user.email.clone());
                        s.account_key = Some(crypto::key_to_text(&account_key));
                        s.record_rev = 0;
                    }
                    self.save();
                    eprintln!("jasktv-core: signed in as {}", r.user.email);
                    self.set_status(Status::SignedIn { email: r.user.email });
                    return;
                }
                _ => {}
            }
        }
    }

    /// Catches up with the account's records (keeping playlists and settings), then writes core's own record:
    /// collection "core", key = core's device id, sealed with the account key like every record.
    /// An account-wide record (see [Synced::shared]), as last read.
    pub fn shared(&self, coll: &str, key: &str) -> Option<serde_json::Value> {
        self.synced.lock().unwrap().shared.get(&(coll.to_string(), key.to_string())).cloned()
    }

    /// This core's device id and the account key, with its token: for the account's files.
    pub fn file_access(&self) -> Option<(AccountClient, String, String, AccountKey)> {
        let s = self.saved.lock().unwrap();
        let key = s.account_key.as_deref().and_then(crypto::key_from_text)?;
        Some((AccountClient::new(self.client.as_ref()?.base()), s.token.clone()?, s.device_id.clone()?, key))
    }

    pub fn role(&self) -> &str { &self.role }

    /// Commands for this core taken from the account since the last call (key, value).
    pub fn take_commands(&self) -> Vec<(String, serde_json::Value)> { std::mem::take(&mut self.synced.lock().unwrap().commands) }

    /// The account server's address and this core's token, for the live connection.
    pub fn live_target(&self) -> Option<(String, String)> {
        let token = self.saved.lock().unwrap().token.clone()?;
        Some((self.client.as_ref()?.base().to_string(), token))
    }

    /// Writes one record [coll]/[key] (a command's result). Err: not signed in or refused.
    pub fn put_record(&self, coll: &str, key: &str, value: serde_json::Value) -> Result<(), String> {
        let client = self.client.as_ref().ok_or("no account server")?;
        let (token, k) = {
            let s = self.saved.lock().unwrap();
            let k: AccountKey = s.account_key.as_deref().and_then(crypto::key_from_text).ok_or("not signed in")?;
            (s.token.clone().ok_or("not signed in")?, k)
        };
        let clock_guard = self.clock.lock().unwrap();
        let clock = clock_guard.as_ref().ok_or("not signed in")?;
        let held = self.synced.lock().unwrap().stamps.get(&(coll.to_string(), key.to_string())).cloned();
        let mut stamp = clock.next();
        while held.as_deref().is_some_and(|h| h >= stamp.as_str()) { stamp = clock.next(); }
        let sealed = records::seal(&k, coll, key, &stamp, Some(value.clone()));
        let result = client.records_put(&token, &[sealed.clone()]).map_err(|e| e.message)?;
        clock.observe_server(result.now);
        if let Some(r) = result.rejected.first() { return Err(r.reason.clone()); }
        let mut synced = self.synced.lock().unwrap();
        if result.accepted.iter().any(|a| a.id == sealed.id) {
            // Taken: this is now the account's copy (catch-up skips what core wrote itself, by its stamp).
            synced.stamps.insert((coll.to_string(), key.to_string()), stamp);
            if matches!(coll, "lease" | "snap" | "core") { synced.shared.insert((coll.to_string(), key.to_string()), value); }
        }
        // A newer copy at the server (another core wrote it at the same moment): that one is the account's.
        drop(synced);
        for w in &result.stale {
            if let Some(r) = records::open(&k, w) {
                let mut synced = self.synced.lock().unwrap();
                synced.stamps.insert((r.coll.clone(), r.key.clone()), r.stamp.clone());
                if let (true, Some(v)) = (matches!(r.coll.as_str(), "lease" | "snap" | "core"), r.value) { synced.shared.insert((r.coll, r.key), v); }
            }
        }
        Ok(())
    }

    /// The server's clock, as core last saw it (ms), for judging how old a command is.
    pub fn server_now(&self) -> i64 {
        self.clock.lock().unwrap().as_ref().map(|c| c.next()[..13].parse().unwrap_or_else(|_| now_ms())).unwrap_or_else(now_ms)
    }

    /// Writes one record of [coll] under core's own id (its status, a command's result). Err: not signed in or refused.
    pub fn put_own(&self, coll: &str, value: serde_json::Value) -> Result<(), String> {
        let client = self.client.as_ref().ok_or("no account server")?;
        let (token, id, key) = {
            let s = self.saved.lock().unwrap();
            let key: AccountKey = s.account_key.as_deref().and_then(crypto::key_from_text).ok_or("not signed in")?;
            (s.token.clone().ok_or("not signed in")?, s.device_id.clone().ok_or("not signed in")?, key)
        };
        let clock_guard = self.clock.lock().unwrap();
        let clock = clock_guard.as_ref().ok_or("not signed in")?;
        let stamp = clock.next();
        let result = client.records_put(&token, &[records::seal(&key, coll, &id, &stamp, Some(value))]).map_err(|e| e.message)?;
        clock.observe_server(result.now);
        match result.rejected.first() { Some(r) => Err(r.reason.clone()), None => Ok(()) }
    }

    fn publish(&self, client: &AccountClient) -> Result<(), jasktv_engine::account::AccountError> {
        let (token, id, key, secret, legacy_rev) = {
            let s = self.saved.lock().unwrap();
            let key: AccountKey = s.account_key.as_deref().and_then(crypto::key_from_text).ok_or_else(|| missing("account key"))?;
            (s.token.clone().ok_or_else(|| missing("token"))?, s.device_id.clone().ok_or_else(|| missing("device id"))?, key,
             s.access_secret.clone(), s.record_rev)
        };
        let clock_guard = self.clock.lock().unwrap();
        let clock = clock_guard.as_ref().expect("clock");
        self.catch_up(client, &token, &key, clock)?;
        let urls: Vec<String> = Some(self.public_url.lock().unwrap().clone()).filter(|u| !u.is_empty()).into_iter().collect();
        let record = json!({
            "name": device_name(),
            "version": env!("CARGO_PKG_VERSION"),
            "addresses": if self.advertise.is_empty() { lan_addresses() } else { self.advertise.clone() },
            "role": self.role,
            // Reaches core from anywhere (tried after the home-network addresses).
            "urls": urls,
            "port": self.port,
            "secret": secret,
            "capabilities": capabilities(&self.role),
        });
        let held = self.synced.lock().unwrap().stamps.get(&("core".to_string(), id.clone())).cloned();
        // Every write reaches every connected device: write only when something in it changed (or it's missing).
        let same = self.synced.lock().unwrap().core_record.as_ref() == Some(&record);
        if same && held.is_some() {
            let email = self.saved.lock().unwrap().email.clone().unwrap_or_default();
            self.set_status(Status::SignedIn { email });
            return Ok(());
        }
        let mut stamp = clock.next();
        if held.as_deref().is_some_and(|h| h >= stamp.as_str()) { stamp = clock.next(); }
        let result = client.records_put(&token, &[records::seal(&key, "core", &id, &stamp, Some(record.clone()))])?;
        clock.observe_server(result.now);
        if result.accepted.iter().any(|a| a.id == records::id_of(&key, "core", &id)) {
            let mut synced = self.synced.lock().unwrap();
            synced.core_record = Some(record);
            synced.stamps.insert(("core".into(), id.clone()), stamp);
        }
        if let Some(r) = result.rejected.first() { eprintln!("jasktv-core: the account server refused core's record: {}", r.reason); }
        // The first version wrote a sync v1 item, which no device reads: remove it once.
        if legacy_rev > 0 {
            let gone = PushItem { kind: "core".into(), id: id.clone(), base_rev: legacy_rev, updated_at: now_ms(), deleted: true, data: None };
            if client.push(&token, vec![gone]).is_ok() { self.saved.lock().unwrap().record_rev = 0; self.save(); }
        }
        let email = self.saved.lock().unwrap().email.clone().unwrap_or_default();
        self.set_status(Status::SignedIn { email });
        Ok(())
    }

    fn catch_up(&self, client: &AccountClient, token: &str, key: &AccountKey, clock: &StampClock) -> Result<(), jasktv_engine::account::AccountError> {
        let since = self.synced.lock().unwrap().seq;
        let (got, seq) = client.records_all(token, key, clock, since)?;
        let mut synced = self.synced.lock().unwrap();
        for r in got {
            let at = (r.coll.clone(), r.key.clone());
            if synced.stamps.get(&at).is_some_and(|held| held >= &r.stamp) { continue; }
            synced.stamps.insert(at, r.stamp.clone());
            if r.coll == "core" && Some(&r.key) == self.saved.lock().unwrap().device_id.as_ref() { synced.core_record = r.value.clone(); continue; }
            if matches!(r.coll.as_str(), "lease" | "snap" | "core") {
                match r.value { Some(v) => { synced.shared.insert((r.coll, r.key), v); } None => { synced.shared.remove(&(r.coll, r.key)); } }
                continue;
            }
            if r.coll == "cmd" {
                let mine = self.saved.lock().unwrap().device_id.as_ref().is_some_and(|me| r.key.starts_with(&format!("{me}/")));
                if let (true, Some(v)) = (mine, r.value) { if v.get("done").is_none_or(|d| d.is_null()) { synced.commands.push((r.key, v)); } }
                continue;
            }
            let map = match r.coll.as_str() { "pl" => &mut synced.playlists, "st" => &mut synced.settings, _ => continue };
            match r.value { Some(v) => { map.insert(r.key, v); } None => { map.remove(&r.key); } }
        }
        synced.seq = seq;
        Ok(())
    }
}

fn missing(what: &str) -> jasktv_engine::account::AccountError {
    jasktv_engine::account::AccountError { status: 0, code: "state".into(), message: format!("core's saved sign-in has no {what}") }
}

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

/// "jasktv-core on <machine>".
fn device_name() -> String {
    let host = std::env::var("COMPUTERNAME").ok()
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok().map(|h| h.trim().to_string()))
        .or_else(|| std::env::var("HOSTNAME").ok())
        .filter(|h| !h.is_empty()).unwrap_or_else(|| "this machine".into());
    format!("jasktv-core on {host}")
}

/// The machine's address on its main network: the one used to reach the outside. A UDP "connect" sends nothing.
/// What a core of [role] offers devices. A helper doesn't stream (no torrents, no Stremio add-on).
pub fn capabilities(role: &str) -> Vec<&'static str> {
    let mut c = vec!["xtream-cache", "guide", "guide-extra", "tmdb", "images", "deltas", "meta"];
    if role != "helper" { c.extend(["live-cache", "releases", "play", "stremio"]); }
    c
}

pub fn lan_addresses() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(sock) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if sock.connect("192.0.2.1:9").is_ok() {
            if let Ok(addr) = sock.local_addr() { out.push(addr.ip().to_string()); }
        }
    }
    out
}

/// Only the account running core may read its state file (Unix). On Windows the file sits in a folder the user owns.
fn restrict(path: &std::path::Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    let _ = path;
}
