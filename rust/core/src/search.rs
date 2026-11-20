//! Finding and playing copies: Prowlarr searches, ranking, and TorrServer playback. Answers in the Stremio add-on
//! shape, which jaskTV's add-on support already reads (and Stremio can use too).

use crate::Config;
use jasktv_engine::release::{covers_episode, hash_of, is_episode_file, is_video, names_title, rank, Release};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Searches, titles and chosen files, each kept for a while; swept as it's written so it can't grow without bound.
#[derive(Default)]
struct Cache { entries: Mutex<HashMap<String, (Instant, Cached)>> }

#[derive(Clone)]
enum Cached { Releases(Vec<Release>), Meta(Option<(String, u32)>), File(u64) }

impl Cache {
    fn get(&self, key: &str) -> Option<Cached> {
        let map = self.entries.lock().unwrap();
        map.get(key).filter(|(until, _)| Instant::now() < *until).map(|(_, v)| v.clone())
    }
    fn set(&self, key: String, value: Cached, ttl: Duration) {
        let mut map = self.entries.lock().unwrap();
        if map.len() > 2000 { let now = Instant::now(); map.retain(|_, (until, _)| now < *until); }
        map.insert(key, (Instant::now() + ttl, value));
    }
}

pub struct Search { cfg: Config, http: ureq::Agent, cache: Cache, services: std::sync::Arc<crate::services::Services> }

impl Search {
    pub fn new(cfg: Config, services: std::sync::Arc<crate::services::Services>) -> Self {
        // Redirects are for the player to follow, never core.
        let http = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(60)).redirects(0).build();
        Search { cfg, http, cache: Cache::default(), services }
    }

    /// Prowlarr's key: from the config, else from the Prowlarr core runs.
    fn prowlarr_key(&self) -> String {
        if !self.cfg.prowlarr_key.is_empty() { return self.cfg.prowlarr_key.clone(); }
        self.services.prowlarr_key().unwrap_or_default()
    }

    fn prowlarr_url(&self) -> String {
        if !self.cfg.prowlarr_url.is_empty() { return self.cfg.prowlarr_url.clone(); }
        if self.services.manages(crate::services::Kind::Prowlarr) { format!("http://127.0.0.1:{}", crate::services::PROWLARR_PORT) } else { String::new() }
    }

    pub fn manifest(&self) -> Value {
        json!({
            "id": "app.jasktv.core", "version": env!("CARGO_PKG_VERSION"), "name": "jasktv-core",
            "description": "Your Prowlarr indexers, streamed through your TorrServer.",
            "resources": ["stream"], "types": ["movie", "series"], "idPrefixes": ["tt"], "catalogs": []
        })
    }

    pub fn health(&self) -> (bool, bool) {
        let ok = |url: String| !url.is_empty() && match self.http.get(&url).call() {
            Ok(_) => true,
            Err(ureq::Error::Status(code, _)) => code < 500,
            Err(_) => false,
        };
        let key = self.prowlarr_key();
        (!key.is_empty() && ok(format!("{}/api/v1/health?apikey={}", self.prowlarr_url(), enc(&key))),
         ok(format!("{}/echo", self.cfg.torrserver_url)))
    }

    /// Stremio's /stream/{type}/{id}.json: the ranked releases, as links to /play under [base].
    pub fn streams(&self, kind: &str, id: &str, base: &str) -> Value {
        let empty = json!({ "streams": [] });
        let Some(id) = id.strip_suffix(".json") else { return empty };
        let mut it = id.split(':');
        let imdb = it.next().unwrap_or_default();
        let season: u32 = it.next().and_then(|s| s.parse().ok()).unwrap_or(0);
        let episode: u32 = it.next().and_then(|s| s.parse().ok()).unwrap_or(0);
        let valid = imdb.len() > 2 && imdb.starts_with("tt") && imdb[2..].chars().all(|c| c.is_ascii_digit());
        if !valid || !(kind == "movie" || (kind == "series" && season > 0 && episode > 0)) { return empty; }

        let key = format!("releases|{imdb}|{season}|{episode}");
        let releases = match self.cache.get(&key) {
            Some(Cached::Releases(r)) => r,
            _ => {
                let ranked = rank(self.find(kind, imdb, season, episode), self.cfg.max_results);
                // A miss is kept briefly (indexers can be down); a hit for longer.
                let ttl = if ranked.is_empty() { 120 } else { 900 };
                self.cache.set(key, Cached::Releases(ranked.clone()), Duration::from_secs(ttl));
                ranked
            }
        };
        let streams: Vec<Value> = releases.iter().map(|r| {
            let mut q = format!("link={}&name={}", enc(&r.link), enc(&r.name));
            if season > 0 { q.push_str(&format!("&s={season}&e={episode}")); }
            let label = r.label();
            json!({
                "name": format!("jasktv-core\n{label}"),
                "title": format!("{}\n{}", r.name, r.details()),
                "url": format!("{base}/play/{}?{q}", if r.hash.is_empty() { "link" } else { &r.hash }),
                "behaviorHints": { "bingeGroup": format!("jasktv-core|{label}"), "filename": r.name, "notWebReady": true }
            })
        }).collect();
        json!({ "streams": streams, "cacheMaxAge": 600 })
    }

    /// Prowlarr by IMDb id first (exact); by title too when that finds little.
    fn find(&self, kind: &str, imdb: &str, season: u32, episode: u32) -> Vec<Release> {
        if self.prowlarr_url().is_empty() || self.prowlarr_key().is_empty() { return Vec::new(); }
        let series = kind == "series";
        let (categories, search_type) = if series { ("5000", "tvsearch") } else { ("2000", "movie") };
        let mut query = format!("{{ImdbId:{imdb}}}");
        if series { query.push_str(&format!("{{Season:{season}}}{{Episode:{episode}}}")); }
        let mut found = self.prowlarr(&query, search_type, categories);
        if found.len() < 5 {
            // Many indexers can't search by IMDb id: the title too, from Stremio's public metadata.
            if let Some((name, year)) = self.meta(kind, imdb) {
                let mut queries = vec![if series { format!("{name} S{season:02}E{episode:02}") }
                    else if year > 0 { format!("{name} {year}") } else { name.clone() }];
                if series { queries.push(format!("{name} S{season:02}")); } // season packs
                let results: Vec<Vec<Release>> = std::thread::scope(|s| {
                    let handles: Vec<_> = queries.iter().map(|q| s.spawn(|| self.prowlarr(q, "search", categories))).collect();
                    handles.into_iter().map(|h| h.join().unwrap_or_default()).collect()
                });
                let years: Vec<String> = if year > 0 { vec![year.to_string(), (year - 1).to_string(), (year + 1).to_string()] } else { vec![] };
                found.extend(results.into_iter().flatten().filter(|r| names_title(&r.name, &name)
                    && (series || years.is_empty() || years.iter().any(|y| r.name.contains(y.as_str())))));
            }
        }
        if series { found.retain(|r| covers_episode(&r.name, season, episode)); }
        found
    }

    /// One search across every indexer Prowlarr has, torrents only.
    fn prowlarr(&self, query: &str, search_type: &str, categories: &str) -> Vec<Release> {
        #[derive(Deserialize, Default)]
        #[serde(default, rename_all = "camelCase")]
        struct Item { title: String, indexer: String, size: f64, seeders: Option<f64>, info_hash: Option<String>,
            magnet_url: Option<String>, download_url: Option<String>, protocol: Option<String> }
        let url = format!("{}/api/v1/search?query={}&type={search_type}&categories={categories}&limit=100", self.prowlarr_url(), enc(query));
        let items: Vec<Item> = match self.http.get(&url).set("X-Api-Key", &self.prowlarr_key()).call() {
            Ok(response) => match response.into_json() { Ok(items) => items, Err(e) => { eprintln!("prowlarr {query:?}: {e}"); return Vec::new(); } },
            Err(e) => { eprintln!("prowlarr {query:?}: {e}"); return Vec::new(); }
        };
        items.into_iter().filter_map(|it| {
            if it.protocol.as_deref().is_some_and(|p| !p.eq_ignore_ascii_case("torrent")) { return None; }
            let magnet = it.magnet_url.filter(|m| m.starts_with("magnet:"));
            let mut hash = it.info_hash.unwrap_or_default().to_ascii_lowercase();
            if hash.is_empty() { hash = magnet.as_deref().map(hash_of).unwrap_or_default(); }
            let link = magnet
                .or_else(|| (!hash.is_empty()).then(|| format!("magnet:?xt=urn:btih:{hash}&dn={}", enc(&it.title))))
                // A .torrent through Prowlarr: TorrServer fetches it.
                .or(it.download_url.filter(|d| !d.is_empty()))?;
            Some(Release { name: it.title, indexer: it.indexer, size: it.size.max(0.0) as u64,
                seeders: it.seeders.unwrap_or(0.0).max(0.0) as u64, hash, link })
        }).collect()
    }

    /// A title's name and year from Cinemeta (Stremio's public metadata add-on), kept for a day.
    fn meta(&self, kind: &str, imdb: &str) -> Option<(String, u32)> {
        let key = format!("meta|{imdb}");
        if let Some(Cached::Meta(m)) = self.cache.get(&key) { return m; }
        let found = self.http.get(&format!("https://v3-cinemeta.strem.io/meta/{kind}/{imdb}.json")).call().ok()
            .and_then(|r| r.into_json::<Value>().ok())
            .and_then(|v| {
                let meta = v.get("meta")?;
                let name = meta.get("name")?.as_str()?.to_string();
                let year_text = meta.get("year").or(meta.get("releaseInfo")).and_then(Value::as_str).unwrap_or("");
                let year = year_text.chars().take_while(char::is_ascii_digit).collect::<String>().parse().unwrap_or(0);
                Some((name, year))
            });
        self.cache.set(key, Cached::Meta(found.clone()), Duration::from_secs(86_400));
        found
    }

    /// Asks TorrServer which file to play (the episode in a pack, else the largest video) and returns TorrServer's
    /// stream address for it, for a redirect. On play rather than on search: finding a torrent's files takes seconds.
    pub fn play(&self, q: &HashMap<String, String>, host: &str) -> Result<String, (u16, String)> {
        let link = q.get("link").filter(|l| !l.is_empty()).ok_or((400, "link missing".to_string()))?;
        let season: u32 = q.get("s").and_then(|s| s.parse().ok()).unwrap_or(0);
        let episode: u32 = q.get("e").and_then(|s| s.parse().ok()).unwrap_or(0);
        let name: String = q.get("name").map(|n| n.chars().take(80).collect()).unwrap_or_else(|| "video".into());
        let key = format!("file|{link}|{season}|{episode}");
        let index = match self.cache.get(&key) {
            Some(Cached::File(i)) => i,
            _ => {
                let i = self.pick_file(link, season, episode).map_err(|e| (502, format!("TorrServer couldn't open that torrent: {e}")))?;
                self.cache.set(key, Cached::File(i), Duration::from_secs(6 * 3600));
                i
            }
        };
        let public = if !self.cfg.torrserver_public_url.is_empty() { self.cfg.torrserver_public_url.clone() } else {
            // The machine the device reached core on, on TorrServer's port.
            let port = self.cfg.torrserver_url.rsplit_once(':').map(|(_, p)| p.trim_end_matches('/'))
                .filter(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())).unwrap_or("8090");
            format!("http://{}:{port}", host_only(host))
        };
        Ok(format!("{public}/stream/{}?link={}&index={index}&play", enc(&name), enc(link)))
    }

    fn pick_file(&self, link: &str, season: u32, episode: u32) -> Result<u64, String> {
        #[derive(Deserialize)]
        struct Stat { #[serde(default)] file_stats: Vec<File> }
        #[derive(Deserialize)]
        struct File { id: u64, #[serde(default)] path: String, #[serde(default)] length: u64 }
        let url = format!("{}/stream/stat?link={}&stat", self.cfg.torrserver_url, enc(link));
        let stat: Stat = self.http.get(&url).call().map_err(|e| e.to_string())?.into_json().map_err(|e| e.to_string())?;
        let best = stat.file_stats.iter()
            .filter(|f| is_video(&f.path) && (season == 0 || is_episode_file(&f.path, season, episode)))
            .max_by_key(|f| f.length);
        match best {
            Some(f) => Ok(f.id),
            None if season > 0 => Err(format!("no file for S{season:02}E{episode:02} in that torrent")),
            None => stat.file_stats.first().map(|f| f.id).ok_or_else(|| "no files yet (no peers?)".into()),
        }
    }
}

/// "192.168.1.20:7000" → "192.168.1.20"; "[fe80::1]:7000" → "[fe80::1]".
pub fn host_only(host: &str) -> &str {
    if host.starts_with('[') { return host.split_inclusive(']').next().unwrap_or(host); }
    match host.rsplit_once(':') {
        Some((h, port)) if port.chars().all(|c| c.is_ascii_digit()) => h,
        _ => host,
    }
}

/// Percent-encodes everything but unreserved characters.
pub fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') { out.push(b as char); }
        else { out.push_str(&format!("%{b:02X}")); }
    }
    out
}
