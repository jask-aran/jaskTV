//! TMDB matching on core, the app's way (see `CanonicalMovieWorker`, `CanonicalMovieResolver`, `CanonicalShowWorker`):
//! each film in a playlist's cached list is matched once, copies of one film share one lookup, and a match is kept
//! with the evidence (name, year, provider TMDB id) it was made from, so a device takes it only while its own
//! evidence for that title is the same. Shows: each provider TMDB id is checked against the provider's names.
//!
//! Results live in core's SQLite file with the app's column names and are served as the same bulk chunks nearby
//! devices send each other (`LanCatalogue` part "tmdb"), so the app imports them with its existing code.
//!
//! TMDB requests go through one gate: at most [PER_SECOND] a second and [WORKERS] at once, waiting out a 429.

use crate::link::Link;
use crate::provider::{Provider, ACTIONS};
use jasktv_engine::matching::{self, Candidate, Decision};
use jasktv_engine::xtream;
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const PER_SECOND: u32 = 20;
const WORKERS: usize = 8;
const WRITE_EVERY: usize = 300;

pub struct Matcher {
    /// Set by the account-wide builder claim (see `shared`): only the core holding it asks TMDB; the others copy.
    pub may_match: std::sync::atomic::AtomicBool,
    from_devices: bool, db: Mutex<Connection>, link: Arc<Link>, provider: Arc<Provider>, gate: Mutex<(Instant, Instant)>, state: Mutex<Value> }

struct Title { tmdb_id: i64, title: String, original_title: String, year: Option<i32>, language: Option<String>, poster: Option<String>,
    backdrop: Option<String>, synopsis: Option<String>, genres: Option<String>, rating: Option<f64>, runtime: Option<i64> }

struct Link_ { stream_id: String, evidence: String, tmdb_id: Option<i64>, confidence: f64, provenance: &'static str, candidates: String }

impl Matcher {
    /// [from_devices]: copy matches from devices on the home network first (a full core at home; not a helper).
    pub fn new(path: PathBuf, link: Arc<Link>, provider: Arc<Provider>, from_devices: bool) -> Result<Arc<Self>, String> {
        let db = Connection::open(path).map_err(|e| e.to_string())?;
        db.execute_batch("
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS movie_identity_matches (source TEXT NOT NULL, streamId TEXT NOT NULL, evidence TEXT NOT NULL,
                tmdbId INTEGER, confidence REAL NOT NULL, provenance TEXT NOT NULL, candidateIds TEXT NOT NULL, changed INTEGER NOT NULL,
                PRIMARY KEY (source, streamId));
            CREATE INDEX IF NOT EXISTS matches_changed ON movie_identity_matches (source, changed);
            CREATE INDEX IF NOT EXISTS matches_evidence ON movie_identity_matches (evidence);
            CREATE TABLE IF NOT EXISTS canonical_movies (tmdbId INTEGER PRIMARY KEY, title TEXT NOT NULL, originalTitle TEXT NOT NULL,
                year INTEGER, originalLanguage TEXT, posterUrl TEXT, backdropUrl TEXT, synopsis TEXT, genres TEXT, rating REAL,
                runtimeMinutes INTEGER, updatedMillis INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS canonical_shows (tmdbId INTEGER PRIMARY KEY, validated INTEGER NOT NULL, title TEXT,
                originalTitle TEXT, year INTEGER, originalLanguage TEXT, posterUrl TEXT, backdropUrl TEXT, synopsis TEXT, genres TEXT,
                rating REAL, seasonCount INTEGER, checkedMillis INTEGER NOT NULL, network TEXT, status TEXT, networkLogo TEXT);
        ").map_err(|e| e.to_string())?;
        let now = Instant::now();
        Ok(Arc::new(Matcher { may_match: std::sync::atomic::AtomicBool::new(false), from_devices, db: Mutex::new(db), link, provider, gate: Mutex::new((now, now)), state: Mutex::new(json!({})) }))
    }

    pub fn status(&self) -> Value {
        let mut v = self.state.lock().unwrap().clone();
        let db = self.db.lock().unwrap();
        let count = |sql: &str| db.query_row(sql, [], |r| r.get::<_, i64>(0)).unwrap_or(0);
        v["films_matched"] = json!(count("SELECT COUNT(*) FROM movie_identity_matches WHERE tmdbId IS NOT NULL"));
        v["films_checked"] = json!(count("SELECT COUNT(*) FROM movie_identity_matches"));
        v["shows_checked"] = json!(count("SELECT COUNT(*) FROM canonical_shows"));
        v
    }

    fn tmdb_key(&self) -> Option<String> {
        self.link.synced(|s| s.settings.get("tmdbKey").and_then(Value::as_str).map(|k| k.trim().to_string()).filter(|k| !k.is_empty()))
    }

    /// Runs for the life of core: after each provider refresh (and every half hour), matches what's new.
    pub fn run(self: Arc<Self>) {
        std::thread::sleep(Duration::from_secs(60));
        let mut copied_at: Option<Instant> = None;
        loop {
            // First what devices on the home network matched already (a TV or phone that's been matching for days),
            // so only what nobody has goes to TMDB. Every six hours: each copy has the device export everything.
            if self.from_devices && copied_at.is_none_or(|t| t.elapsed() > Duration::from_secs(6 * 3600)) {
                copied_at = Some(Instant::now());
                self.copy_from_devices();
            }
            // Only the account's builder asks TMDB (one place at a time does the work; everyone else copies its table).
            let building = self.may_match.load(std::sync::atomic::Ordering::SeqCst);
            if let (true, Some(key)) = (building, self.tmdb_key()) {
                for source in self.provider.sources() {
                    let started = Instant::now();
                    match self.match_films(&key, &source) {
                        Ok((done, failed)) if done + failed > 0 =>
                            eprintln!("matcher: {done} films checked ({failed} left for later) in {:.0} s", started.elapsed().as_secs_f64()),
                        Ok(_) => {}
                        Err(e) => eprintln!("matcher: films: {e}"),
                    }
                    match self.match_shows(&key, &source) {
                        Ok((done, failed)) if done + failed > 0 => eprintln!("matcher: {done} shows checked ({failed} left for later)"),
                        Ok(_) => {}
                        Err(e) => eprintln!("matcher: shows: {e}"),
                    }
                }
            }
            // Every 30 minutes; sooner once this core becomes the builder.
            for _ in 0..180 {
                std::thread::sleep(Duration::from_secs(10));
                if !building && self.may_match.load(std::sync::atomic::Ordering::SeqCst) { break; }
            }
        }
    }

    fn copy_from_devices(&self) {
        let peers = match crate::peers::browse(Duration::from_secs(5)) { Ok(p) => p, Err(e) => { eprintln!("matcher: can't look for devices: {e}"); return; } };
        let me = self.link.identity().map(|(id, _)| id).unwrap_or_default();
        for peer in peers.into_iter().filter(|p| p.device != me) {
            for source in self.provider.sources() {
                match self.link.copy_from(Some(&peer.device), peer.addr, &source, |chunk| self.import_chunk(&source, chunk, false)) {
                    Ok((true, added)) => eprintln!("matcher: copied {added} new rows from device {:.8}", peer.device),
                    Ok((false, _)) => {}
                    // An old app version, or one not signed in to this account: fine, match without it.
                    Err(e) => eprintln!("matcher: device {:.8} at {}: {e}", peer.device, peer.addr),
                }
            }
        }
    }

    // ---- TMDB ----

    /// One GET, spaced and limited. Ok(None): 404. A 429 waits for Retry-After and tries again (three times).
    fn tmdb(&self, key: &str, path: &str, extra: &str) -> Result<Option<Value>, String> {
        static AGENT: std::sync::OnceLock<ureq::Agent> = std::sync::OnceLock::new();
        let agent = AGENT.get_or_init(|| ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(30)).build());
        for _ in 0..4 {
            let wait = {
                let mut g = self.gate.lock().unwrap();
                let now = Instant::now();
                let at = now.max(g.0).max(g.1);
                g.0 = at + Duration::from_millis(1000 / PER_SECOND as u64);
                at - now
            };
            std::thread::sleep(wait);
            let url = format!("https://api.themoviedb.org/3/{path}?api_key={}{extra}", crate::search::enc(key));
            match agent.get(&url).call() {
                Ok(r) => return r.into_json().map(Some).map_err(|_| "TMDB sent something unreadable".into()),
                Err(ureq::Error::Status(404, _)) => return Ok(None),
                Err(ureq::Error::Status(429, r)) => {
                    let secs = r.header("Retry-After").and_then(|s| s.parse().ok()).unwrap_or(2u64).clamp(1, 60);
                    self.gate.lock().unwrap().1 = Instant::now() + Duration::from_secs(secs);
                }
                // The address holds the key: never in an error.
                Err(ureq::Error::Status(code, _)) => return Err(format!("TMDB answered HTTP {code}")),
                Err(ureq::Error::Transport(t)) => return Err(format!("can't reach TMDB ({})", t.kind())),
            }
        }
        Err("TMDB kept asking to slow down".into())
    }

    // ---- Films ----

    /// The app's `CanonicalMovieResolver.resolve` plus the worker's "already have that title" shortcut.
    fn resolve_film(&self, key: &str, name: &str, year: Option<i32>, hint: Option<&str>) -> Result<(Option<Title>, Decision), String> {
        let expected = matching::release_year(name, year);
        if let Some(id) = hint.and_then(|h| matching::kt_int(h)).map(i64::from).filter(|&i| i > 0) {
            if let Some(t) = self.title(id) {
                let c = Candidate { id: t.tmdb_id, title: t.title.clone(), original_title: t.original_title.clone(), year: t.year };
                return Ok((Some(t), matching::explicit(&c, expected, true)));
            }
            let Some(detail) = self.tmdb(key, &format!("movie/{id}"), "")? else { return Ok((None, Decision::Unmatched)) };
            let Some(c) = candidate(&detail) else { return Ok((None, Decision::Unmatched)) };
            if c.id != id { return Ok((None, Decision::Unmatched)); }
            return Ok((title_of(&detail), matching::explicit(&c, expected, false)));
        }
        let q = matching::query(name);
        if q.trim().is_empty() { return Ok((None, Decision::Unmatched)); }
        let mut extra = format!("&query={}&include_adult=false", crate::search::enc(&q));
        if let Some(y) = expected { extra.push_str(&format!("&year={y}")); }
        let raw: Vec<Value> = self.tmdb(key, "search/movie", &extra)?.and_then(|v| v["results"].as_array().cloned()).unwrap_or_default();
        let candidates: Vec<Candidate> = raw.iter().filter_map(candidate).collect();
        let decision = matching::decide(name, expected, &candidates);
        if let Decision::Accepted { id, .. } = decision {
            return Ok(match raw.iter().find(|o| candidate(o).map(|c| c.id) == Some(id)).and_then(title_of) {
                Some(t) => (Some(t), decision),
                None => (None, Decision::Unmatched),
            });
        }
        Ok((None, decision))
    }

    fn title(&self, id: i64) -> Option<Title> {
        self.db.lock().unwrap().query_row("SELECT tmdbId, title, originalTitle, year, originalLanguage, posterUrl, backdropUrl, synopsis,
            genres, rating, runtimeMinutes FROM canonical_movies WHERE tmdbId = ?", [id], |r| Ok(Title { tmdb_id: r.get(0)?, title: r.get(1)?,
            original_title: r.get(2)?, year: r.get(3)?, language: r.get(4)?, poster: r.get(5)?, backdrop: r.get(6)?, synopsis: r.get(7)?,
            genres: r.get(8)?, rating: r.get(9)?, runtime: r.get(10)? })).optional().ok().flatten()
    }

    /// Matches every film in [source]'s list whose evidence changed or that has no match. (checked, failed).
    fn match_films(&self, key: &str, source: &str) -> Result<(usize, usize), String> {
        let Some(path) = self.provider.file(source, ACTIONS[3]) else { return Ok((0, 0)) };
        let rows = xtream::vod_rows(std::fs::File::open(path).map_err(|e| e.to_string())?)?;
        let held: HashMap<String, String> = {
            let db = self.db.lock().unwrap();
            let mut st = db.prepare("SELECT streamId, evidence FROM movie_identity_matches WHERE source = ?").map_err(|e| e.to_string())?;
            let it = st.query_map([source], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))).map_err(|e| e.to_string())?;
            it.filter_map(Result::ok).collect()
        };
        let mut pending: Vec<&xtream::VodRow> = rows.iter()
            .filter(|r| held.get(&r.stream_id).map(String::as_str) != Some(matching::evidence(&r.name, r.year, r.tmdb_id.as_deref()).as_str()))
            .collect();
        if pending.is_empty() { return Ok((0, 0)); }
        // As the app orders it: titles with a provider TMDB id first (one exact request), then the newest.
        pending.sort_by_key(|r| (r.tmdb_id.as_deref().map(str::trim).unwrap_or("").is_empty(), -r.added_millis));
        let mut groups: Vec<(String, Vec<&xtream::VodRow>)> = Vec::new();
        let mut index: HashMap<String, usize> = HashMap::new();
        for r in pending {
            let k = matching::group_key(&r.name, r.year, r.tmdb_id.as_deref());
            match index.get(&k) { Some(&i) => groups[i].1.push(r), None => { index.insert(k.clone(), groups.len()); groups.push((k, vec![r])); } }
        }
        self.set_state("films", json!({ "pending": groups.iter().map(|g| g.1.len()).sum::<usize>(), "lookups": groups.len() }));
        let next = Mutex::new(groups.into_iter());
        let out: Mutex<(Vec<(Option<Title>, Link_)>, usize, usize)> = Mutex::new((Vec::new(), 0, 0));
        std::thread::scope(|s| {
            for _ in 0..WORKERS {
                s.spawn(|| loop {
                    let Some((_, group)) = next.lock().unwrap().next() else { break };
                    let first = group[0];
                    let ev = matching::evidence(&first.name, first.year, first.tmdb_id.as_deref());
                    let links: Vec<(Option<Title>, Link_)> = if let Some(c) = self.by_evidence(&ev) {
                        group.iter().map(|m| (None, Link_ { stream_id: m.stream_id.clone(),
                            evidence: matching::evidence(&m.name, m.year, m.tmdb_id.as_deref()), ..c.clone() })).collect()
                    } else {
                        match self.resolve_film(key, &first.name, first.year, first.tmdb_id.as_deref()) {
                            Ok((title, decision)) => {
                                let mut title = title;
                                group.iter().map(|m| (title.take(), link_of(m, &decision))).collect()
                            }
                            Err(e) => {
                                let failures = { let mut o = out.lock().unwrap(); o.2 += group.len(); o.2 };
                                if failures == group.len() { eprintln!("matcher: {e}"); }
                                if failures > 200 { break; }
                                continue;
                            }
                        }
                    };
                    let (batch, failures) = {
                        let mut o = out.lock().unwrap();
                        o.1 += links.len();
                        o.0.extend(links);
                        (if o.0.len() >= WRITE_EVERY { std::mem::take(&mut o.0) } else { Vec::new() }, o.2)
                    };
                    self.write_films(source, batch);
                    // A run of failures (TMDB down, a bad key): stop until the next round.
                    if failures > 200 { break; }
                });
            }
        });
        let (rest, done, failed) = out.into_inner().unwrap();
        self.write_films(source, rest);
        self.set_state("films", json!({ "pending": failed }));
        Ok((done, failed))
    }

    fn by_evidence(&self, ev: &str) -> Option<Link_> {
        self.db.lock().unwrap().query_row("SELECT streamId, evidence, tmdbId, confidence, provenance, candidateIds FROM movie_identity_matches
            WHERE evidence = ? LIMIT 1", [ev], |r| Ok(Link_ { stream_id: r.get(0)?, evidence: r.get(1)?, tmdb_id: r.get(2)?, confidence: r.get(3)?,
            provenance: provenance(&r.get::<_, String>(4)?), candidates: r.get(5)? })).optional().ok().flatten()
    }

    fn write_films(&self, source: &str, batch: Vec<(Option<Title>, Link_)>) {
        if batch.is_empty() { return; }
        let mut db = self.db.lock().unwrap();
        let Ok(tx) = db.transaction() else { return };
        for (title, l) in &batch {
            if let Some(t) = title {
                let _ = tx.execute("INSERT OR REPLACE INTO canonical_movies VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", params![t.tmdb_id, t.title,
                    t.original_title, t.year, t.language, t.poster, t.backdrop, t.synopsis, t.genres, t.rating, t.runtime, now_ms()]);
            }
            let _ = tx.execute("INSERT OR REPLACE INTO movie_identity_matches VALUES (?,?,?,?,?,?,?,?)",
                params![source, l.stream_id, l.evidence, l.tmdb_id, l.confidence, l.provenance, l.candidates, now_ms()]);
        }
        let _ = tx.commit();
    }

    // ---- Shows ----

    /// Checks each provider TMDB id for shows not checked yet (the app's `CanonicalShowWorker`). (checked, failed).
    fn match_shows(&self, key: &str, source: &str) -> Result<(usize, usize), String> {
        let Some(path) = self.provider.file(source, ACTIONS[1]) else { return Ok((0, 0)) };
        let rows = xtream::series_rows(std::fs::File::open(path).map_err(|e| e.to_string())?)?;
        // Hint → (distinct names, earliest year), as the app's `CanonicalShowDao.pending` groups them.
        let mut hints: HashMap<i64, (Vec<String>, Option<i32>, i64)> = HashMap::new();
        for r in &rows {
            let Some(t) = r.tmdb_id.as_deref().map(str::trim).filter(|t| !t.is_empty() && t.bytes().all(|b| b.is_ascii_digit())) else { continue };
            let Some(id) = t.parse::<i64>().ok().filter(|&i| (1..=2_147_483_647).contains(&i)) else { continue };
            let e = hints.entry(id).or_insert((Vec::new(), None, 0));
            if !e.0.contains(&r.name) { e.0.push(r.name.clone()); }
            e.1 = match (e.1, r.year) { (Some(a), Some(b)) => Some(a.min(b)), (a, b) => a.or(b) };
            e.2 = e.2.max(r.added_millis);
        }
        let checked: std::collections::HashSet<i64> = {
            let db = self.db.lock().unwrap();
            let mut st = db.prepare("SELECT tmdbId FROM canonical_shows").map_err(|e| e.to_string())?;
            let ids = st.query_map([], |r| r.get(0)).map_err(|e| e.to_string())?.filter_map(Result::ok).collect();
            ids
        };
        let mut pending: Vec<(i64, (Vec<String>, Option<i32>, i64))> = hints.into_iter().filter(|(id, _)| !checked.contains(id)).collect();
        if pending.is_empty() { return Ok((0, 0)); }
        pending.sort_by_key(|(_, h)| -h.2);
        self.set_state("shows", json!({ "pending": pending.len() }));
        let next = Mutex::new(pending.into_iter());
        let out: Mutex<(Vec<Value>, usize, usize)> = Mutex::new((Vec::new(), 0, 0));
        std::thread::scope(|s| {
            for _ in 0..WORKERS {
                s.spawn(|| loop {
                    let Some((id, (names, year, _))) = next.lock().unwrap().next() else { break };
                    // The app joins names with "," and splits them again: names holding a comma split the same way here.
                    let joined = names.join(",");
                    let names: Vec<&str> = joined.split(',').map(str::trim).filter(|n| !n.is_empty()).collect();
                    match self.tmdb(key, &format!("tv/{id}"), "") {
                        Ok(found) => {
                            let row = show_row(id, found.as_ref(), &names, year);
                            let batch = {
                                let mut o = out.lock().unwrap();
                                o.1 += 1;
                                o.0.push(row);
                                if o.0.len() >= WRITE_EVERY { std::mem::take(&mut o.0) } else { Vec::new() }
                            };
                            self.write_shows(batch);
                        }
                        Err(e) => {
                            let failures = { let mut o = out.lock().unwrap(); o.2 += 1; o.2 };
                            if failures == 1 { eprintln!("matcher: {e}"); }
                            if failures > 200 { break; }
                        }
                    }
                });
            }
        });
        let (rest, done, failed) = out.into_inner().unwrap();
        self.write_shows(rest);
        self.set_state("shows", json!({ "pending": failed }));
        Ok((done, failed))
    }

    fn write_shows(&self, batch: Vec<Value>) {
        if batch.is_empty() { return; }
        let mut db = self.db.lock().unwrap();
        let Ok(tx) = db.transaction() else { return };
        for v in &batch {
            let _ = tx.execute("INSERT OR REPLACE INTO canonical_shows VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", params![
                v["tmdbId"].as_i64(), v["validated"].as_i64(), v["title"].as_str(), v["originalTitle"].as_str(), v["year"].as_i64(),
                v["originalLanguage"].as_str(), v["posterUrl"].as_str(), v["backdropUrl"].as_str(), v["synopsis"].as_str(),
                v["genres"].as_str(), v["rating"].as_f64(), v["seasonCount"].as_i64(), v["checkedMillis"].as_i64(), v["network"].as_str(),
                v["status"].as_str(), v["networkLogo"].as_str()]);
        }
        let _ = tx.commit();
    }

    /// Takes rows another device matched (a `LanCatalogue` chunk) for [source]. Rows core already has stay as they are;
    /// a device's own manual picks aren't taken. Returns how many rows were new.
    /// Takes one bulk chunk. [replace]: it comes from the account's builder (its table is the account's), so rows
    /// replace what's here; else (another device's copy) only missing rows are added.
    pub fn import_chunk(&self, source: &str, chunk: &Value, replace: bool) -> usize {
        let insert = if replace { "INSERT OR REPLACE" } else { "INSERT OR IGNORE" };
        let table = chunk["table"].as_str().unwrap_or("");
        let columns: Vec<&str> = chunk["columns"].as_array().map(|c| c.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
        let Some(rows) = chunk["rows"].as_array() else { return 0 };
        let at = |name: &str| columns.iter().position(|c| *c == name);
        let keep: &[&str] = match table {
            "canonical_movies" => &["tmdbId", "title", "originalTitle", "year", "originalLanguage", "posterUrl", "backdropUrl", "synopsis", "genres", "rating", "runtimeMinutes", "updatedMillis"],
            "canonical_shows" => &["tmdbId", "validated", "title", "originalTitle", "year", "originalLanguage", "posterUrl", "backdropUrl", "synopsis", "genres", "rating", "seasonCount", "checkedMillis", "network", "status", "networkLogo"],
            "movie_identity_matches" => &["streamId", "evidence", "tmdbId", "confidence", "provenance", "candidateIds"],
            _ => return 0,
        };
        let present: Vec<(&str, usize)> = keep.iter().filter_map(|c| at(c).map(|i| (*c, i))).collect();
        let to_sql = |v: &Value| -> rusqlite::types::Value { match v {
            Value::Number(n) => n.as_i64().map(rusqlite::types::Value::Integer).unwrap_or_else(|| rusqlite::types::Value::Real(n.as_f64().unwrap_or(0.0))),
            Value::String(s) => rusqlite::types::Value::Text(s.clone()),
            Value::Bool(b) => rusqlite::types::Value::Integer(*b as i64),
            _ => rusqlite::types::Value::Null,
        } };
        let (sql, extra): (String, Vec<rusqlite::types::Value>) = if table == "movie_identity_matches" {
            if present.len() < keep.len() { return 0; }
            (format!("{insert} INTO movie_identity_matches (source, {}, changed) VALUES (?, {}, ?)",
                present.iter().map(|p| p.0).collect::<Vec<_>>().join(", "), vec!["?"; present.len()].join(", ")),
             vec![rusqlite::types::Value::Text(source.into())])
        } else {
            if at("tmdbId").is_none() { return 0; }
            (format!("{insert} INTO {table} ({}) VALUES ({})", present.iter().map(|p| p.0).collect::<Vec<_>>().join(", "),
                vec!["?"; present.len()].join(", ")), vec![])
        };
        let manual = at("manual");
        let mut db = self.db.lock().unwrap();
        let Ok(tx) = db.transaction() else { return 0 };
        let mut added = 0;
        {
            let Ok(mut st) = tx.prepare(&sql) else { return 0 };
            for row in rows {
                let Some(r) = row.as_array() else { continue };
                if manual.and_then(|i| r.get(i)).and_then(Value::as_i64) == Some(1) { continue; }
                let mut values: Vec<rusqlite::types::Value> = extra.clone();
                values.extend(present.iter().map(|(_, i)| r.get(*i).map(to_sql).unwrap_or(rusqlite::types::Value::Null)));
                if table == "movie_identity_matches" { values.push(rusqlite::types::Value::Integer(now_ms())); }
                if let Ok(n) = st.execute(rusqlite::params_from_iter(values)) { added += n; }
            }
        }
        let _ = tx.commit();
        added
    }

    /// One film through core's matcher (for `compare-matches`): the TMDB id and provenance it decides.
    pub fn check(&self, key: &str, name: &str, year: Option<i32>, hint: Option<&str>) -> Result<(Option<i64>, &'static str), String> {
        let (_, d) = self.resolve_film(key, name, year, hint)?;
        Ok(match d {
            Decision::Accepted { id, provenance, .. } => (Some(id), provenance),
            Decision::Ambiguous(_) => (None, "ambiguous"),
            Decision::Unmatched => (None, "unmatched"),
        })
    }

    fn set_state(&self, part: &str, v: Value) { self.state.lock().unwrap()[part] = v; }

    // ---- Serving ----

    /// [source]'s matches and the TMDB titles, as the app's bulk chunks (one JSON object a line), for `LanCatalogue.Import`.
    /// Only what changed after [since] (core's clock, from the last export's closing line); the closing line carries
    /// the time to ask from next.
    pub fn export(&self, source: &str, since: i64, mut emit: impl FnMut(&Value) -> bool) {
        let started = now_ms();
        let db = self.db.lock().unwrap();
        let tables: [(&str, &str, &[&str]); 3] = [
            ("movie_identity_matches", "SELECT 0, streamId, evidence, tmdbId, confidence, provenance, 0, candidateIds FROM movie_identity_matches WHERE source = ?1 AND changed > ?2",
             &["sourceId", "streamId", "evidence", "tmdbId", "confidence", "provenance", "manual", "candidateIds"]),
            ("canonical_movies", "SELECT * FROM canonical_movies WHERE ?1 IS NOT NULL AND updatedMillis > ?2",
             &["tmdbId", "title", "originalTitle", "year", "originalLanguage", "posterUrl", "backdropUrl", "synopsis", "genres", "rating", "runtimeMinutes", "updatedMillis"]),
            ("canonical_shows", "SELECT * FROM canonical_shows WHERE ?1 IS NOT NULL AND checkedMillis > ?2",
             &["tmdbId", "validated", "title", "originalTitle", "year", "originalLanguage", "posterUrl", "backdropUrl", "synopsis", "genres", "rating", "seasonCount", "checkedMillis", "network", "status", "networkLogo"]),
        ];
        for (table, sql, columns) in tables {
            let Ok(mut st) = db.prepare(sql) else { continue };
            let Ok(mut rows) = st.query(params![source, since]) else { continue };
            let mut chunk: Vec<Value> = Vec::with_capacity(1500);
            while let Ok(Some(r)) = rows.next() {
                chunk.push(Value::Array((0..columns.len()).map(|i| match r.get_ref(i) {
                    Ok(rusqlite::types::ValueRef::Integer(n)) => json!(n),
                    Ok(rusqlite::types::ValueRef::Real(f)) => json!(f),
                    Ok(rusqlite::types::ValueRef::Text(t)) => json!(String::from_utf8_lossy(t)),
                    _ => Value::Null,
                }).collect()));
                if chunk.len() >= 1500 && !emit(&json!({ "table": table, "columns": columns, "rows": std::mem::take(&mut chunk) })) { return; }
            }
            if !chunk.is_empty() && !emit(&json!({ "table": table, "columns": columns, "rows": chunk })) { return; }
        }
        emit(&json!({ "done": true, "now": started }));
    }
}

impl Clone for Link_ {
    fn clone(&self) -> Self { Link_ { stream_id: self.stream_id.clone(), evidence: self.evidence.clone(), tmdb_id: self.tmdb_id,
        confidence: self.confidence, provenance: self.provenance, candidates: self.candidates.clone() } }
}

fn provenance(s: &str) -> &'static str {
    match s {
        "validated-provider-tmdb" => "validated-provider-tmdb",
        "validated-provider-tmdb-cache" => "validated-provider-tmdb-cache",
        "exact-title-year" => "exact-title-year",
        "ambiguous" => "ambiguous",
        _ => "unmatched",
    }
}

fn link_of(m: &xtream::VodRow, d: &Decision) -> Link_ {
    let evidence = matching::evidence(&m.name, m.year, m.tmdb_id.as_deref());
    let stream_id = m.stream_id.clone();
    match d {
        Decision::Accepted { id, confidence, provenance } =>
            Link_ { stream_id, evidence, tmdb_id: Some(*id), confidence: *confidence, provenance, candidates: String::new() },
        Decision::Ambiguous(ids) => Link_ { stream_id, evidence, tmdb_id: None, confidence: 0.0, provenance: "ambiguous",
            candidates: ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",") },
        Decision::Unmatched => Link_ { stream_id, evidence, tmdb_id: None, confidence: 0.0, provenance: "unmatched", candidates: String::new() },
    }
}

fn text(o: &Value, k: &str) -> Option<String> {
    match &o[k] { Value::String(s) => Some(s.clone()), Value::Number(n) => Some(n.to_string()), Value::Bool(b) => Some(b.to_string()), _ => None }
}

/// The app's `candidate`: id, a non-blank title, original title, year from release_date.
fn candidate(o: &Value) -> Option<Candidate> {
    let id = text(o, "id")?.parse::<i64>().ok().filter(|&i| i > 0)?;
    let title = text(o, "title").filter(|t| !t.trim().is_empty())?;
    Some(Candidate { id, title, original_title: text(o, "original_title").unwrap_or_default(),
        year: text(o, "release_date").and_then(|d| d.chars().take(4).collect::<String>().parse().ok()) })
}

/// The app's `metadata`.
fn title_of(o: &Value) -> Option<Title> {
    let c = candidate(o)?;
    Some(Title { tmdb_id: c.id, title: c.title, original_title: c.original_title, year: c.year,
        language: text(o, "original_language").filter(|l| !l.trim().is_empty()),
        poster: text(o, "poster_path").map(|p| format!("https://image.tmdb.org/t/p/w500{p}")),
        backdrop: text(o, "backdrop_path").map(|p| format!("https://image.tmdb.org/t/p/w1280{p}")),
        synopsis: text(o, "overview"),
        genres: o["genres"].as_array().map(|g| g.iter().filter_map(|x| text(x, "name")).collect::<Vec<_>>().join(", ")),
        rating: o["vote_average"].as_f64().filter(|&r| r > 0.0), runtime: o["runtime"].as_i64() })
}

/// The app's `CanonicalShowResolver.resolve`: a checked row, validated or not.
fn show_row(id: i64, o: Option<&Value>, names: &[&str], year: Option<i32>) -> Value {
    let rejected = || json!({ "tmdbId": id, "validated": 0, "checkedMillis": now_ms() });
    let Some(o) = o else { return rejected() };
    let t = |k: &str| text(o, k).filter(|s| !s.trim().is_empty());
    let Some(title) = t("name") else { return rejected() };
    let original = t("original_name").unwrap_or_default();
    let first_year: Option<i32> = t("first_air_date").and_then(|d| d.chars().take(4).collect::<String>().parse().ok());
    if !matching::show_accepts(names, year, &title, &original, first_year) { return rejected(); }
    let network = o["networks"].as_array().and_then(|n| n.first());
    json!({
        "tmdbId": id, "validated": 1, "title": title, "originalTitle": if original.trim().is_empty() { None } else { Some(original) },
        "year": first_year, "originalLanguage": t("original_language"),
        "posterUrl": t("poster_path").map(|p| format!("https://image.tmdb.org/t/p/w500{p}")),
        "backdropUrl": t("backdrop_path").map(|p| format!("https://image.tmdb.org/t/p/w1280{p}")),
        "synopsis": t("overview"),
        "genres": o["genres"].as_array().map(|g| g.iter().filter_map(|x| text(x, "name")).collect::<Vec<_>>().join(", ")).filter(|g| !g.trim().is_empty()),
        "rating": o["vote_average"].as_f64().filter(|&r| r > 0.0), "seasonCount": o["number_of_seasons"].as_i64(),
        "checkedMillis": now_ms(), "network": network.and_then(|n| text(n, "name")), "status": t("status"),
        "networkLogo": network.and_then(|n| text(n, "logo_path")).map(|p| format!("https://image.tmdb.org/t/p/w154{p}")).unwrap_or_default(),
    })
}

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }
