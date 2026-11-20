//! Core keeps a copy of each Xtream playlist's big lists (films, series and their categories) and serves the same
//! bytes to devices, so the provider sees one download instead of one per device, and a new device loads its
//! library from the home network. Devices parse the lists with their own code, so nothing here can drift from the
//! app's rules.
//!
//! Also the live channel lists (refreshed every ten minutes, served only while under half an hour old: event channels
//! are renamed per event) and the guide (XMLTV, every six hours). Sign-in checks and per-title info stay direct.
//!
//! The provider allows one connection at a time. A refresh runs only when the account shows no active stream, has
//! hard deadlines, and never retries in a loop. Provider addresses carry the login, so errors never include them.

use crate::link::Link;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The cached actions, in refresh order (series first: they're smaller and come up first in the app too).
pub const ACTIONS: [&str; 4] = ["get_series_categories", "get_series", "get_vod_categories", "get_vod_streams"];
pub const LIVE: [&str; 2] = ["get_live_categories", "get_live_streams"];
pub const GUIDE: &str = "xmltv";

/// How often each list is fetched, and how old a copy may be and still be served (older: devices go to the provider).
fn policy(action: &str) -> (Duration, Duration) {
    match action {
        // The whole live list is 22 MB: every 6 hours, for channels added or removed. Renamed event slots come from
        // the slot pass in between (see [Provider::run_slots]), so a copy stays good to serve for 8 hours.
        a if LIVE.contains(&a) => (Duration::from_secs(6 * 3600), Duration::from_secs(8 * 3600)),
        GUIDE => (Duration::from_secs(6 * 3600), Duration::from_secs(36 * 3600)),
        _ => (Duration::from_secs(6 * 3600), Duration::from_secs(14 * 86400)),
    }
}

fn all_actions() -> impl Iterator<Item = &'static str> { ACTIONS.into_iter().chain(LIVE).chain([GUIDE]) }
const BUSY_RETRY: Duration = Duration::from_secs(20 * 60);

#[derive(Clone, Default, serde::Serialize)]
pub struct ListState { pub fetched_at: i64, pub bytes: u64, pub items: usize, pub last_error: Option<String> }

#[derive(Clone)]
struct Playlist { key: String, name: String, url: String, username: String, password: String, user_agent: String, epg_url: String }

pub struct Provider { pub deltas: Option<crate::deltas::Deltas>, force: std::sync::atomic::AtomicBool, live: bool, dir: PathBuf, link: Arc<Link>, state: Mutex<std::collections::BTreeMap<String, ListState>> }

impl Provider {
    /// [live]: also keep the live lists (every ten minutes). A helper doesn't: fewer provider requests from afar.
    pub fn new(dir: PathBuf, link: Arc<Link>, live: bool) -> Arc<Self> {
        let _ = std::fs::create_dir_all(&dir);
        let mut state = std::collections::BTreeMap::new();
        // What's on disk from before a restart.
        if let Ok(entries) = std::fs::read_dir(&dir) {
            for source in entries.flatten() {
                for action in all_actions() {
                    let p = source.path().join(file_name(action));
                    if let Ok(meta) = std::fs::metadata(&p) {
                        let fetched_at = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as i64).unwrap_or(0);
                        let items = std::fs::read_to_string(p.with_extension("count")).ok().and_then(|t| t.trim().parse().ok()).unwrap_or(0);
                        state.insert(format!("{}/{action}", source.file_name().to_string_lossy()), ListState { fetched_at, bytes: meta.len(), items, last_error: None });
                    }
                }
            }
        }
        let deltas = crate::deltas::Deltas::open(&dir.join("deltas.db")).map_err(|e| eprintln!("provider: change tracking off: {e}")).ok();
        // Live tracking started again: fetch the live list now, so the slot pass has something to work from.
        if deltas.as_ref().is_some_and(|d| d.live_reset) { state.retain(|k: &String, _| !k.ends_with("/get_live_streams")); }
        Arc::new(Provider { deltas, force: std::sync::atomic::AtomicBool::new(false), live, dir, link, state: Mutex::new(state) })
    }

    pub fn status(&self) -> Value { serde_json::to_value(&*self.state.lock().unwrap()).unwrap_or_default() }

    /// A cached list's file, for serving: only while it's fresh enough for its kind (see [policy]).
    pub fn file(&self, source_key: &str, action: &str) -> Option<PathBuf> {
        let valid = source_key.len() == 64 && source_key.bytes().all(|b| b.is_ascii_hexdigit()) && all_actions().any(|a| a == action);
        let p = self.dir.join(source_key).join(file_name(action));
        let age = std::fs::metadata(&p).ok()?.modified().ok()?.elapsed().unwrap_or_default();
        (valid && age < policy(action).1).then_some(p)
    }

    /// Keys of the Xtream playlists core keeps lists for.
    pub fn sources(&self) -> Vec<String> { self.playlists().into_iter().map(|p| p.key).collect() }

    fn playlists(&self) -> Vec<Playlist> {
        self.link.synced(|s| s.playlists.values().filter_map(|p| {
            let text = |k: &str| p.get(k).and_then(Value::as_str).unwrap_or("").to_string();
            if text("kind") != "XTREAM" || p.get("enabled").and_then(Value::as_bool) == Some(false) { return None; }
            let url = text("url");
            let username = text("username");
            Some(Playlist { key: source_key("XTREAM", &url, &username), name: text("name"), url: url.trim_end_matches('/').to_string(),
                username, password: text("password"), user_agent: text("userAgent"), epg_url: text("epgUrl") })
        }).collect())
    }

    /// Runs for the life of core: fetches each playlist's lists when they're due (see [policy]).
    pub fn run(self: Arc<Self>) {
        std::thread::sleep(Duration::from_secs(20)); // let the account catch up first
        loop {
            let mut wait = Duration::from_secs(10 * 60);
            // "Refresh lists now" (a command from the control panel): every list is due.
            let forced = self.force.swap(false, std::sync::atomic::Ordering::SeqCst);
            for p in self.playlists() {
                let due: Vec<&str> = all_actions().filter(|a| self.live || !LIVE.contains(a)).filter(|a| {
                    if forced { return true; }
                    let at = self.state.lock().unwrap().get(&format!("{}/{a}", p.key)).map(|s| s.fetched_at).unwrap_or(0);
                    let age = Duration::from_millis((now_ms() - at).max(0) as u64);
                    let every = policy(a).0;
                    if age < every { wait = wait.min(every - age); false } else { true }
                }).collect();
                if due.is_empty() { continue; }
                match self.busy(&p) {
                    Ok(false) => self.refresh(&p, &due),
                    Ok(true) => { eprintln!("provider ({}): a stream is playing; refreshing later", p.name); wait = wait.min(BUSY_RETRY); }
                    Err(e) => { eprintln!("provider ({}): can't check the account: {e}", p.name); wait = wait.min(BUSY_RETRY); }
                }
            }
            // Sleeps until the next list is due, waking early for "refresh now".
            let until = std::time::Instant::now() + wait.max(Duration::from_secs(60));
            while std::time::Instant::now() < until && !self.force.load(std::sync::atomic::Ordering::SeqCst) {
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    }

    /// The slot pass, for the life of core (full cores only): event slots ("US| SOCCER PPV 3") are renamed as one event
    /// ends and the next is scheduled, far faster than the full live list (6-hourly). Each minute it re-reads up to
    /// twelve categories (see [crate::deltas::Deltas::busy_categories]): every 3 minutes for ones that changed in
    /// the last 2 hours, every 15 for the rest of the day's. Renames are recorded as changes, so devices pick them up
    /// with their next changes-only copy. On a timer only: nothing it finds makes it run again sooner.
    pub fn run_slots(self: Arc<Self>) {
        if !self.live { return; }
        let mut checked: std::collections::HashMap<(String, String), i64> = std::collections::HashMap::new();
        std::thread::sleep(Duration::from_secs(90));
        loop {
            std::thread::sleep(Duration::from_secs(60));
            let Some(deltas) = &self.deltas else { return };
            let now = now_ms();
            for p in self.playlists() {
                // Busy categories (changed lately), then every event-slot category by its name (PPV, EVENT), so one
                // that comes to life between the 6-hourly full passes is found within half an hour.
                let busy: std::collections::HashMap<String, i64> = deltas.busy_categories(&p.key, "get_live_streams").into_iter().collect();
                let mut all: Vec<(String, i64)> = busy.iter().map(|(c, t)| (c.clone(), *t)).collect();
                for c in self.slot_categories(&p.key) { if !busy.contains_key(&c) { all.push((c, 0)); } }
                let mut due: Vec<(String, i64)> = all.into_iter().filter(|(c, last)| {
                    let every = if now - last < 2 * 3_600_000 { 3 * 60_000 } else if now - last < 86_400_000 { 15 * 60_000 } else { 30 * 60_000 };
                    now - checked.get(&(p.key.clone(), c.clone())).copied().unwrap_or(0) >= every
                }).collect();
                due.sort_by_key(|(c, _)| checked.get(&(p.key.clone(), c.clone())).copied().unwrap_or(0));
                let (mut renamed, mut cats) = (0usize, 0usize);
                for (c, _) in due.into_iter().take(12) {
                    checked.insert((p.key.clone(), c.clone()), now);
                    let (agent, _) = self.agent(&p, Duration::from_secs(30));
                    let url = format!("{}&category_id={}", Self::api_url(&p, Some("get_live_streams")), crate::search::enc(&c));
                    let mut body = Vec::new();
                    let ok = agent.get(&url).call().ok().map(|r| r.into_reader().take(8 << 20).read_to_end(&mut body).is_ok()).unwrap_or(false);
                    if !ok { continue; }
                    if let Ok(n) = deltas.apply_partial(&p.key, "get_live_streams", &body) { if n > 0 { renamed += n; cats += 1; } }
                }
                if renamed > 0 { eprintln!("provider ({}): slots: {renamed} renamed in {cats} categories", p.name); }
            }
        }
    }

    /// Live categories named as event slots ("US| SOCCER PPV", "US| PPV EVENT"), from core's copy of the categories.
    fn slot_categories(&self, key: &str) -> Vec<String> {
        let Ok(text) = std::fs::read_to_string(self.dir.join(key).join(file_name("get_live_categories"))) else { return Vec::new() };
        let Ok(Value::Array(cats)) = serde_json::from_str::<Value>(&text) else { return Vec::new() };
        cats.iter().filter_map(|c| {
            let name = c["category_name"].as_str()?.to_uppercase();
            let words: Vec<&str> = name.split(|ch: char| !ch.is_ascii_alphanumeric()).collect();
            let slot = words.iter().any(|w| matches!(*w, "PPV" | "EVENT" | "EVENTS")) && !words.contains(&"REPLAY");
            let id = c["category_id"].as_str().map(str::to_string).or_else(|| c["category_id"].as_i64().map(|i| i.to_string()))?;
            slot.then_some(id)
        }).collect()
    }

    /// Fetches every list of every playlist now (the provider's busy check still applies).
    pub fn refresh_now(&self) { self.force.store(true, std::sync::atomic::Ordering::SeqCst); }

    fn agent(&self, p: &Playlist, total: Duration) -> (ureq::Agent, String) {
        let ua = if p.user_agent.is_empty() { "jasktv-core".to_string() } else { p.user_agent.clone() };
        (ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(15)).timeout(total).user_agent(&ua).build(), ua)
    }

    fn api_url(p: &Playlist, action: Option<&str>) -> String {
        let mut u = format!("{}/player_api.php?username={}&password={}", p.url, crate::search::enc(&p.username), crate::search::enc(&p.password));
        if let Some(a) = action { u.push_str("&action="); u.push_str(a); }
        u
    }

    /// Whether the account has a stream open (its one connection in use).
    fn busy(&self, p: &Playlist) -> Result<bool, String> {
        let (agent, _) = self.agent(p, Duration::from_secs(30));
        let v: Value = agent.get(&Self::api_url(p, None)).call().map_err(redact)?.into_json().map_err(|_| "not JSON".to_string())?;
        let num = |k: &str| v["user_info"][k].as_str().and_then(|s| s.parse::<i64>().ok()).or_else(|| v["user_info"][k].as_i64()).unwrap_or(0);
        Ok(num("active_cons") > 0)
    }

    fn refresh(&self, p: &Playlist, due: &[&str]) {
        let dir = self.dir.join(&p.key);
        let _ = std::fs::create_dir_all(&dir);
        for &action in due {
            let id = format!("{}/{action}", p.key);
            let before = self.state.lock().unwrap().get(&id).cloned().unwrap_or_default();
            let started = std::time::Instant::now();
            match self.fetch(p, action, &dir, before.items, before.bytes) {
                Ok((bytes, items)) => {
                    // What changed since the last copy, for devices asking with ?since=.
                    if let (Some(d), true) = (&self.deltas, crate::deltas::id_field(action).is_some()) {
                        let t = std::time::Instant::now();
                        match d.index(&p.key, action, &dir.join(file_name(action))) {
                            Ok((c, r)) if !LIVE.contains(&action) || c + r > 0 =>
                                eprintln!("provider ({}): {action}: {c} changed, {r} gone ({:.1} s)", p.name, t.elapsed().as_secs_f64()),
                            Ok(_) => {}
                            Err(e) => eprintln!("provider ({}): {action}: couldn't compare: {e}", p.name),
                        }
                    }
                    // Live lists every ten minutes would fill the log: only the big ones.
                    if !LIVE.contains(&action) { eprintln!("provider ({}): {action}: {items} items, {:.1} MB in {:.0} s", p.name, bytes as f64 / 1e6, started.elapsed().as_secs_f64()); }
                    self.state.lock().unwrap().insert(id, ListState { fetched_at: now_ms(), bytes, items, last_error: None });
                }
                Err(e) => {
                    eprintln!("provider ({}): {action}: {e}; keeping the previous copy", p.name);
                    // Kept as fetched now so a failing list waits for the next round instead of retrying at once.
                    self.state.lock().unwrap().insert(id, ListState { fetched_at: now_ms(), last_error: Some(e), ..before });
                }
            }
        }
    }

    /// Downloads one list to a temporary file and keeps it only if it's a JSON array that hasn't collapsed.
    fn fetch(&self, p: &Playlist, action: &str, dir: &std::path::Path, previous: usize, previous_bytes: u64) -> Result<(u64, usize), String> {
        let (agent, _) = self.agent(p, Duration::from_secs(15 * 60));
        let url = if action == GUIDE {
            // The playlist's own guide address when it has one (as the app does), else the provider's xmltv.php.
            if !p.epg_url.trim().is_empty() { p.epg_url.trim().to_string() }
            else { format!("{}/xmltv.php?username={}&password={}", p.url, crate::search::enc(&p.username), crate::search::enc(&p.password)) }
        } else { Self::api_url(p, Some(action)) };
        let resp = agent.get(&url).call().map_err(redact)?;
        let tmp = dir.join(format!("{action}.part"));
        let mut out = std::io::BufWriter::new(std::fs::File::create(&tmp).map_err(|e| e.to_string())?);
        let bytes = std::io::copy(&mut resp.into_reader().take(1 << 30), &mut out).map_err(|e| format!("download cut off: {}", e.kind()))?;
        out.flush().map_err(|e| e.to_string())?;
        drop(out);
        if action == GUIDE {
            // XML (or gzip, which the app unpacks itself), and not collapsed to a fraction of the last copy.
            let mut head = [0u8; 64];
            let n = std::fs::File::open(&tmp).and_then(|mut f| f.read(&mut head)).unwrap_or(0);
            let first = head[..n].iter().position(|b| !b.is_ascii_whitespace()).map(|i| &head[i..n]).unwrap_or(&[]);
            let looks_right = first.starts_with(b"<") || first.starts_with(&[0x1f, 0x8b]) || first.starts_with(&[0xef, 0xbb, 0xbf]);
            if !looks_right || (previous_bytes > 1_000_000 && bytes < previous_bytes / 3) {
                let _ = std::fs::remove_file(&tmp);
                return Err(if looks_right { format!("only {:.1} MB (was {:.1}): looks like a bad reply", bytes as f64 / 1e6, previous_bytes as f64 / 1e6) }
                    else { "the provider didn't send a guide".into() });
            }
            std::fs::rename(&tmp, dir.join(file_name(action))).map_err(|e| e.to_string())?;
            return Ok((bytes, 0));
        }
        let items = count_array(&tmp)?;
        if previous > 100 && items < previous / 2 {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("only {items} items (was {previous}): looks like a bad reply"));
        }
        std::fs::rename(&tmp, dir.join(file_name(action))).map_err(|e| e.to_string())?;
        let _ = std::fs::write(dir.join(format!("{action}.count")), items.to_string());
        Ok((bytes, items))
    }
}

/// How many elements a JSON array file holds, read as a stream (lists run to tens of MB). Not an array: an error.
fn count_array(path: &std::path::Path) -> Result<usize, String> {
    struct Counter(usize);
    impl<'de> serde::de::Visitor<'de> for Counter {
        type Value = usize;
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { f.write_str("a JSON array") }
        fn visit_seq<A: serde::de::SeqAccess<'de>>(mut self, mut seq: A) -> Result<usize, A::Error> {
            while seq.next_element::<serde::de::IgnoredAny>()?.is_some() { self.0 += 1; }
            Ok(self.0)
        }
    }
    let mut reader = BufReader::with_capacity(1 << 16, std::fs::File::open(path).map_err(|e| e.to_string())?);
    let first = loop {
        let buf = reader.fill_buf().map_err(|e| e.to_string())?;
        match buf.iter().position(|b| !b.is_ascii_whitespace()) {
            Some(i) => break Some(buf[i]),
            None if buf.is_empty() => break None,
            None => { let n = buf.len(); reader.consume(n); }
        }
    };
    if first != Some(b'[') { return Err("the provider didn't send a list (empty, or an error page)".into()); }
    let mut de = serde_json::Deserializer::from_reader(reader);
    serde::Deserializer::deserialize_seq(&mut de, Counter(0)).map_err(|e| format!("not valid JSON ({})", e.classify() as u8))
}

fn file_name(action: &str) -> String { if action == GUIDE { "xmltv.xml".into() } else { format!("{action}.json") } }

/// The app's `LanCatalogue.sourceKey`: SHA-256 of "<kind>|<url without trailing />|<username>", hex.
pub fn source_key(kind: &str, url: &str, username: &str) -> String {
    let h = Sha256::digest(format!("{kind}|{}|{username}", url.trim_end_matches('/')).as_bytes());
    h.iter().map(|b| format!("{b:02x}")).collect()
}

/// A fetch error without the address (it holds the provider login).
fn redact(e: ureq::Error) -> String {
    match e {
        ureq::Error::Status(code, _) => format!("the provider answered HTTP {code}"),
        ureq::Error::Transport(t) => format!("can't reach the provider ({})", t.kind()),
    }
}

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }
