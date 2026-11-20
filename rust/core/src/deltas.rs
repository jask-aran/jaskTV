//! What changed in each big provider list since a device last copied it, so a device parses and writes a few hundred
//! entries per refresh instead of 187,000. After each download core compares every entry (by provider id) with its
//! previous copy by a hash of its exact JSON, and keeps entries that changed, with when, and ids that disappeared.
//! A device asks with `?since=<core's time from its last copy>` and gets the changed entries in the provider's own
//! JSON shape (so the app reads them with its usual code) plus the removed ids.

use rusqlite::{params, Connection, OptionalExtension};
use serde::de::{Deserializer, SeqAccess, Visitor};
use serde_json::value::RawValue;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// The lists core tracks changes for, and each one's id field.
pub fn id_field(action: &str) -> Option<&'static str> {
    match action { "get_vod_streams" | "get_live_streams" => Some("stream_id"), "get_series" => Some("series_id"), _ => None }
}

pub struct Deltas { db: Mutex<Connection>, pub live_reset: bool }

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

impl Deltas {
    pub fn open(path: &Path) -> Result<Self, String> {
        let db = Connection::open(path).map_err(|e| e.to_string())?;
        db.execute_batch("
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS entries (source TEXT NOT NULL, action TEXT NOT NULL, id TEXT NOT NULL, hash INTEGER NOT NULL,
                changed INTEGER NOT NULL, removed INTEGER NOT NULL, json TEXT, PRIMARY KEY (source, action, id));
            CREATE INDEX IF NOT EXISTS entries_changed ON entries (source, action, changed);
            -- since: when core started tracking a list (older copies can't be brought up to date by changes);
            -- indexed: the newest pass (what a device's next 'since' is).
            CREATE TABLE IF NOT EXISTS lists (source TEXT NOT NULL, action TEXT NOT NULL, since INTEGER NOT NULL, indexed INTEGER NOT NULL,
                PRIMARY KEY (source, action));
        ").map_err(|e| e.to_string())?;
        // Version 2 compares live entries without "num" (see content_hash): the live list's old hashes no longer
        // compare, so its tracking starts again (devices copy it whole once; the next full pass starts tracking).
        let version: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap_or(0);
        let reset = version < 2;
        if reset {
            db.execute_batch("DELETE FROM entries WHERE action = 'get_live_streams'; DELETE FROM lists WHERE action = 'get_live_streams'; PRAGMA user_version = 2;")
                .map_err(|e| e.to_string())?;
        }
        Ok(Deltas { db: Mutex::new(db), live_reset: reset })
    }

    /// Compares a freshly downloaded list with the previous one. (changed, removed).
    pub fn index(&self, source: &str, action: &str, file: &Path) -> Result<(usize, usize), String> {
        let Some(field) = id_field(action) else { return Ok((0, 0)) };
        let now = now_ms();
        let mut db = self.db.lock().unwrap();
        let held: HashMap<String, i64> = {
            let mut st = db.prepare("SELECT id, hash FROM entries WHERE source = ? AND action = ? AND removed = 0").map_err(|e| e.to_string())?;
            let rows = st.query_map(params![source, action], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))).map_err(|e| e.to_string())?;
            rows.filter_map(Result::ok).collect()
        };
        let first = !db.query_row("SELECT 1 FROM lists WHERE source = ? AND action = ?", params![source, action], |_| Ok(())).optional()
            .map_err(|e| e.to_string())?.is_some();
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let mut seen: HashSet<String> = HashSet::with_capacity(held.len().max(1024));
        let mut changed = 0usize;
        {
            let mut put = tx.prepare("INSERT OR REPLACE INTO entries (source, action, id, hash, changed, removed, json) VALUES (?, ?, ?, ?, ?, 0, ?)")
                .map_err(|e| e.to_string())?;
            let reader = std::io::BufReader::with_capacity(1 << 16, std::fs::File::open(file).map_err(|e| e.to_string())?);
            each_raw(reader, |raw| {
                let text = raw.get();
                // The id, as the app reads it: a string or a number, as written.
                let id = serde_json::from_str::<HashMap<String, &RawValue>>(text).ok()
                    .and_then(|m| m.get(field).map(|v| v.get().trim_matches('"').to_string()))
                    .filter(|id| !id.is_empty() && id != "null");
                let Some(id) = id else { return };
                let hash = content_hash(action, text);
                if held.get(&id) != Some(&hash) {
                    let _ = put.execute(params![source, action, id, hash, now, text]);
                    changed += 1;
                }
                seen.insert(id);
            })?;
        }
        let gone: Vec<&String> = held.keys().filter(|id| !seen.contains(*id)).collect();
        {
            let mut drop = tx.prepare("UPDATE entries SET removed = 1, changed = ?, json = NULL WHERE source = ? AND action = ? AND id = ?")
                .map_err(|e| e.to_string())?;
            for id in &gone { let _ = drop.execute(params![now, source, action, id]); }
        }
        tx.execute("INSERT INTO lists (source, action, since, indexed) VALUES (?1, ?2, ?3, ?3)
            ON CONFLICT(source, action) DO UPDATE SET indexed = ?3", params![source, action, now]).map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;
        Ok((if first { 0 } else { changed }, gone.len()))
    }

    /// Entries re-read outside a full pass (one category of the live list): each one that differs from what core
    /// holds is recorded as changed now. Nothing is removed (a category is only part of the list). Returns how many
    /// changed. Only for a list core already tracks.
    pub fn apply_partial(&self, source: &str, action: &str, body: &[u8]) -> Result<usize, String> {
        let Some(field) = id_field(action) else { return Ok(0) };
        let now = now_ms();
        let mut db = self.db.lock().unwrap();
        if db.query_row("SELECT 1 FROM lists WHERE source = ? AND action = ?", params![source, action], |_| Ok(())).optional()
            .map_err(|e| e.to_string())?.is_none() { return Ok(0); }
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let mut changed = 0usize;
        {
            let mut get = tx.prepare("SELECT hash FROM entries WHERE source = ? AND action = ? AND id = ? AND removed = 0").map_err(|e| e.to_string())?;
            let mut put = tx.prepare("INSERT OR REPLACE INTO entries (source, action, id, hash, changed, removed, json) VALUES (?, ?, ?, ?, ?, 0, ?)")
                .map_err(|e| e.to_string())?;
            each_raw(body, |raw| {
                let text = raw.get();
                let id = serde_json::from_str::<HashMap<String, &RawValue>>(text).ok()
                    .and_then(|m| m.get(field).map(|v| v.get().trim_matches('"').to_string()))
                    .filter(|id| !id.is_empty() && id != "null");
                let Some(id) = id else { return };
                let hash = content_hash(action, text);
                let held: Option<i64> = get.query_row(params![source, action, id], |r| r.get(0)).optional().ok().flatten();
                if held != Some(hash) && put.execute(params![source, action, id, hash, now, text]).is_ok() { changed += 1; }
            })?;
        }
        tx.commit().map_err(|e| e.to_string())?;
        Ok(changed)
    }

    /// Live categories whose channels changed in the last day (after the first pass), with when they last changed:
    /// where events are being scheduled, so where slots get renamed.
    pub fn busy_categories(&self, source: &str, action: &str) -> Vec<(String, i64)> {
        let db = self.db.lock().unwrap();
        let day = now_ms() - 86_400_000;
        let Ok(mut st) = db.prepare("SELECT json_extract(e.json, '$.category_id') AS c, MAX(e.changed) FROM entries e
            JOIN lists l ON l.source = e.source AND l.action = e.action
            WHERE e.source = ? AND e.action = ? AND e.removed = 0 AND e.changed > l.since + 1000 AND e.changed > ? AND c IS NOT NULL
            GROUP BY c") else { return Vec::new() };
        st.query_map(params![source, action, day], |r| {
            let c: rusqlite::types::Value = r.get(0)?;
            let c = match c { rusqlite::types::Value::Text(t) => t, rusqlite::types::Value::Integer(i) => i.to_string(), _ => String::new() };
            Ok((c, r.get::<_, i64>(1)?))
        }).map(|rows| rows.filter_map(Result::ok).filter(|(c, _)| !c.is_empty()).collect()).unwrap_or_default()
    }

    /// The changes since [since] as (JSON array of changed entries, JSON array of removed ids, core's time to ask from
    /// next). None: core can't answer from changes (it started tracking this list after [since]): send the whole list.
    pub fn since(&self, source: &str, action: &str, since: i64) -> Option<(String, String, i64)> {
        let db = self.db.lock().unwrap();
        let (tracked, indexed): (i64, i64) = db.query_row("SELECT since, indexed FROM lists WHERE source = ? AND action = ?",
            params![source, action], |r| Ok((r.get(0)?, r.get(1)?))).ok()?;
        if since <= 0 || since < tracked { return None; }
        let mut changed = String::from("[");
        let mut removed = String::from("[");
        let mut st = db.prepare("SELECT id, removed, json FROM entries WHERE source = ? AND action = ? AND changed > ?").ok()?;
        let mut rows = st.query(params![source, action, since]).ok()?;
        while let Ok(Some(r)) = rows.next() {
            let id: String = r.get(0).ok()?;
            if r.get::<_, i64>(1).ok()? == 1 {
                if removed.len() > 1 { removed.push(','); }
                removed.push_str(&serde_json::to_string(&id).ok()?);
            } else if let Ok(json) = r.get::<_, String>(2) {
                if changed.len() > 1 { changed.push(','); }
                changed.push_str(&json);
            }
        }
        changed.push(']');
        removed.push(']');
        drop(rows); drop(st);
        // Slot passes record changes between full passes: the next 'since' is the newest change, if later.
        let newest: i64 = db.query_row("SELECT COALESCE(MAX(changed), 0) FROM entries WHERE source = ? AND action = ?",
            params![source, action], |r| r.get(0)).unwrap_or(0);
        Some((changed, removed, indexed.max(newest)))
    }

    /// When core last compared this list (a device's next 'since' after copying the whole file).
    pub fn indexed(&self, source: &str, action: &str) -> Option<i64> {
        self.db.lock().unwrap().query_row("SELECT indexed FROM lists WHERE source = ? AND action = ?", params![source, action], |r| r.get(0)).ok()
    }
}

/// An entry's hash, without "num": the provider numbers entries by their position in the reply, so the same channel
/// has another "num" in a one-category reply than in the whole list. Everything else counts (a rename, a new logo).
fn content_hash(action: &str, text: &str) -> i64 {
    if action != "get_live_streams" { let d = Sha256::digest(text.as_bytes()); return i64::from_le_bytes(d[..8].try_into().unwrap()); }
    let mut h = Sha256::new();
    match serde_json::from_str::<std::collections::BTreeMap<String, &RawValue>>(text) {
        Ok(map) => for (k, v) in map.iter().filter(|(k, _)| k.as_str() != "num") { h.update(k.as_bytes()); h.update(b"\0"); h.update(v.get().as_bytes()); h.update(b"\0"); },
        Err(_) => h.update(text.as_bytes()),
    }
    let d = h.finalize();
    i64::from_le_bytes(d[..8].try_into().unwrap())
}

/// Calls [each] with every element of a JSON array, as its exact text.
fn each_raw<R: std::io::Read>(reader: R, mut each: impl FnMut(&RawValue)) -> Result<(), String> {
    struct V<'a, F>(&'a mut F);
    impl<'de, F: FnMut(&RawValue)> Visitor<'de> for V<'_, F> {
        type Value = ();
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { f.write_str("a JSON array") }
        fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
            while let Some(raw) = seq.next_element::<Box<RawValue>>()? { (self.0)(&raw); }
            Ok(())
        }
    }
    let mut de = serde_json::Deserializer::from_reader(reader);
    de.deserialize_seq(V(&mut each)).map_err(|e| format!("not a readable list: {e}"))
}
