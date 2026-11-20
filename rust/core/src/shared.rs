//! The account-wide TMDB match table: one table for the whole account, built in one place at a time and copied
//! everywhere else.
//!
//! - **The builder.** One core holds the account's "builder" claim (record "lease"/"tmdb": {holder, rank, name,
//!   until}). Only it asks TMDB (`Matcher::may_match`). The claim lasts five minutes and is renewed every minute or
//!   so; a core that goes quiet loses it, and the most suitable core still running takes it (a full core before a
//!   helper, ties to the lower device id). A more suitable core takes it over from a less suitable one.
//! - **The account's copy.** The builder keeps an encrypted snapshot of each playlist's table in the account's files:
//!   a base (everything, rewritten daily) and a delta (what changed since the base, rewritten as it changes). Record
//!   "snap"/<playlist key> says which and up to when. Devices and cores read it when they can't reach the builder.
//! - **Everyone else copies.** A core that isn't the builder copies from it directly when it can reach it (its
//!   address and secret are in its "core" record), else from the snapshot. Copies from the builder replace rows:
//!   its table is the account's.
//!
//! Snapshot files are the bulk chunk lines a core serves (`/catalogue/<key>/tmdb`), gzipped, then sealed with the
//! account key (kind "blob", id = file name), so the account server stores what it can't read.

use crate::link::Link;
use crate::matcher::Matcher;
use crate::provider::Provider;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use jasktv_engine::crypto;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, Read, Write};
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const LEASE_MS: i64 = 5 * 60_000;
const BASE_EVERY_MS: i64 = 24 * 3600_000;

pub struct Shared { link: Arc<Link>, matcher: Arc<Matcher>, provider: Arc<Provider>, file: PathBuf, state: Mutex<Value> }

fn rank(role: &str) -> i64 { if role == "helper" { 1 } else { 2 } }

impl Shared {
    pub fn new(link: Arc<Link>, matcher: Arc<Matcher>, provider: Arc<Provider>, dir: PathBuf) -> Arc<Self> {
        let file = dir.join("shared.json");
        let state = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_else(|| json!({}));
        Arc::new(Shared { link, matcher, provider, file, state: Mutex::new(state) })
    }

    fn save(&self) { let _ = std::fs::write(&self.file, self.state.lock().unwrap().to_string()); }
    fn cursor(&self, k: &str) -> i64 { self.state.lock().unwrap()["cursors"][k].as_i64().unwrap_or(0) }
    fn set_cursor(&self, k: &str, v: i64) { self.state.lock().unwrap()["cursors"][k] = json!(v); self.save(); }

    /// Who builds, as last read: (holder id, holder name).
    pub fn builder(&self) -> Option<(String, String)> {
        let l = self.link.shared("lease", "tmdb")?;
        Some((l["holder"].as_str()?.to_string(), l["name"].as_str().unwrap_or("").to_string()))
    }

    pub fn run(self: Arc<Self>) {
        std::thread::sleep(Duration::from_secs(15));
        let mut followed = Instant::now() - Duration::from_secs(3600);
        let mut published = Instant::now() - Duration::from_secs(3600);
        loop {
            let building = self.claim();
            if building != self.matcher.may_match.swap(building, Ordering::SeqCst) {
                eprintln!("shared: {}", if building { "this core builds the account's match table" } else { "another core builds the match table; copying it" });
            }
            if building && published.elapsed() >= Duration::from_secs(10 * 60) {
                published = Instant::now();
                for source in self.provider.sources() { if let Err(e) = self.publish(&source) { eprintln!("shared: snapshot: {e}"); } }
            }
            if !building && followed.elapsed() >= Duration::from_secs(10 * 60) {
                followed = Instant::now();
                for source in self.provider.sources() { if let Err(e) = self.follow(&source) { eprintln!("shared: copying: {e}"); } }
            }
            std::thread::sleep(Duration::from_secs(60));
        }
    }

    /// Takes or renews the builder claim when this core should hold it. True: this core is the builder.
    fn claim(&self) -> bool {
        self.link.catch_up_now();
        let Some((_, _, me, _)) = self.link.file_access() else { return false };
        let now = self.link.server_now();
        let mine = rank(self.link.role());
        let lease = self.link.shared("lease", "tmdb");
        let holder = lease.as_ref().and_then(|l| l["holder"].as_str().map(str::to_string));
        let until = lease.as_ref().and_then(|l| l["until"].as_i64()).unwrap_or(0);
        let their = lease.as_ref().and_then(|l| l["rank"].as_i64()).unwrap_or(0);
        let held_by_me = holder.as_deref() == Some(me.as_str());
        let take = holder.is_none() || until < now || (!held_by_me && (mine > their || (mine == their && me < *holder.as_ref().unwrap() && until < now + LEASE_MS / 2)));
        let renew = held_by_me && until - now < LEASE_MS - 90_000;
        if !(take || renew) { return held_by_me && until >= now; }
        let name = format!("{}", self.link.shared("core", &me).and_then(|c| c["name"].as_str().map(str::to_string)).unwrap_or_else(|| "jasktv-core".into()));
        let value = json!({ "holder": me, "rank": mine, "name": name, "until": now + LEASE_MS, "role": self.link.role() });
        if let Err(e) = self.link.put_record("lease", "tmdb", value) { eprintln!("shared: claim: {e}"); return false; }
        // Two cores can claim at once: the newer write wins at the server. Read back who holds it.
        self.link.catch_up_now();
        self.link.shared("lease", "tmdb").and_then(|l| l["holder"].as_str().map(|h| h == me)).unwrap_or(false)
    }

    /// The builder's side: the snapshot of [source]'s table in the account's files, when the table changed.
    fn publish(&self, source: &str) -> Result<(), String> {
        let (client, token, me, key) = self.link.file_access().ok_or("not signed in")?;
        let snap = self.link.shared("snap", source);
        let base_now = snap.as_ref().filter(|s| s["by"].as_str() == Some(me.as_str())).and_then(|s| s["base_now"].as_i64()).unwrap_or(0);
        let delta_now = snap.as_ref().and_then(|s| s["delta_now"].as_i64()).unwrap_or(base_now);
        let base_at = snap.as_ref().and_then(|s| s["base_at"].as_i64()).unwrap_or(0);
        let fresh_base = base_now == 0 || self.link.server_now() - base_at > BASE_EVERY_MS;
        let since = if fresh_base { 0 } else { base_now };
        // Anything new since the last file? (A delta with nothing in it isn't worth writing.)
        if !fresh_base {
            let mut any = false;
            self.matcher.export(source, delta_now, |c| { if c.get("rows").and_then(Value::as_array).is_some_and(|r| !r.is_empty()) { any = true; } !any });
            if !any { return Ok(()); }
        }
        let started = Instant::now();
        let mut gz = GzEncoder::new(Vec::new(), Compression::new(6));
        let mut now = 0i64;
        let mut rows = 0usize;
        self.matcher.export(source, since, |c| {
            if let Some(n) = c.get("now").and_then(Value::as_i64) { now = n; }
            rows += c.get("rows").and_then(Value::as_array).map(|r| r.len()).unwrap_or(0);
            gz.write_all(c.to_string().as_bytes()).and_then(|_| gz.write_all(b"\n")).is_ok()
        });
        let plain = gz.finish().map_err(|e| e.to_string())?;
        let name = format!("tmdb-{}-{}", &source[..16.min(source.len())], if fresh_base { "base" } else { "delta" });
        let sealed = crypto::seal_bytes(&key, "blob", &name, &plain);
        client.put_blob(&token, &name, &sealed).map_err(|e| e.message)?;
        let record = if fresh_base {
            json!({ "by": me, "base": name, "base_now": now, "base_at": self.link.server_now(), "delta": null, "delta_now": now })
        } else {
            let mut r = snap.unwrap_or_else(|| json!({}));
            r["delta"] = json!(name); r["delta_now"] = json!(now);
            r
        };
        self.link.put_record("snap", source, record)?;
        eprintln!("shared: wrote the {} ({} rows, {:.1} MB) in {:.0} s", if fresh_base { "base snapshot" } else { "changes" }, rows,
            sealed.len() as f64 / 1e6, started.elapsed().as_secs_f64());
        Ok(())
    }

    /// A core that isn't the builder: copies the builder's table, directly when it answers, else from the snapshot.
    fn follow(&self, source: &str) -> Result<(), String> {
        let Some((holder, name)) = self.builder() else { return Ok(()) };
        if let Some(core) = self.link.shared("core", &holder) {
            let secret = core["secret"].as_str().unwrap_or("");
            let port = core["port"].as_i64().unwrap_or(7000);
            let mut bases: Vec<String> = core["addresses"].as_array().into_iter().flatten().filter_map(Value::as_str)
                .map(|a| if a.contains(':') { format!("http://[{a}]:{port}") } else { format!("http://{a}:{port}") }).collect();
            bases.extend(core["urls"].as_array().into_iter().flatten().filter_map(Value::as_str).map(|u| u.trim_end_matches('/').to_string()));
            let k = format!("peer:{holder}:{source}");
            for base in bases {
                let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(3)).timeout(Duration::from_secs(900)).build();
                let Ok(resp) = agent.get(&format!("{base}/catalogue/{source}/tmdb?since={}", self.cursor(&k)))
                    .set("Authorization", &format!("Bearer {secret}")).call() else { continue };
                let (rows, now) = self.import(resp.into_reader(), source)?;
                if let Some(n) = now { self.set_cursor(&k, n); }
                if rows > 0 { eprintln!("shared: copied {rows} rows from {name}"); }
                return Ok(());
            }
        }
        // The builder is out of reach: its snapshot in the account's files.
        let Some(snap) = self.link.shared("snap", source) else { return Ok(()) };
        let (client, token, _, key) = self.link.file_access().ok_or("not signed in")?;
        let by = snap["by"].as_str().unwrap_or("");
        let k = format!("snap:{by}:{source}");
        let mut at = self.cursor(&k);
        for (file, upto) in [(snap["base"].as_str(), snap["base_now"].as_i64().unwrap_or(0)), (snap["delta"].as_str(), snap["delta_now"].as_i64().unwrap_or(0))] {
            let Some(file) = file else { continue };
            // The base: only when this core has nothing from it yet. The changes: when there's something newer.
            if upto <= at || (file.ends_with("-base") && at > 0) { continue; }
            let sealed = client.get_blob(&token, file).map_err(|e| e.message)?.ok_or("the snapshot is missing")?;
            let plain = crypto::open_bytes(&key, "blob", file, &sealed).map_err(|e| e.0.to_string())?;
            let (rows, _) = self.import(GzDecoder::new(&plain[..]), source)?;
            at = upto;
            self.set_cursor(&k, at);
            eprintln!("shared: copied {rows} rows from the account's snapshot ({file})");
        }
        Ok(())
    }

    /// Reads bulk chunk lines into the matcher (replacing rows: they're the builder's). (rows, the copy's "now").
    fn import(&self, reader: impl Read, source: &str) -> Result<(usize, Option<i64>), String> {
        let mut rows = 0;
        for line in std::io::BufReader::with_capacity(1 << 16, reader).lines() {
            let line = line.map_err(|e| e.to_string())?;
            if line.trim().is_empty() { continue; }
            let chunk: Value = serde_json::from_str(&line).map_err(|e| e.to_string())?;
            if chunk.get("done").is_some_and(|d| d.as_bool() == Some(true)) { return Ok((rows, chunk["now"].as_i64())); }
            rows += self.matcher.import_chunk(source, &chunk, true);
        }
        Ok((rows, None))
    }

    /// For status: who builds and how fresh the account's copy is.
    pub fn status(&self) -> Value {
        let snaps: HashMap<String, Value> = self.provider.sources().into_iter()
            .filter_map(|s| self.link.shared("snap", &s).map(|v| (s, json!({ "base_at": v["base_at"], "delta_now": v["delta_now"] })))).collect();
        json!({ "builder": self.builder().map(|(id, name)| json!({ "id": id, "name": name })), "building": self.matcher.may_match.load(Ordering::SeqCst), "snapshots": snaps })
    }
}
