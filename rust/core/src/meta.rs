//! Title details for the home network: devices ask core for TMDB details (cast, logos, age ratings, collections,
//! recommendations, seasons, people) and OMDb review scores, and core fetches each once with the account's keys,
//! keeps it on disk and serves it to every device. Weak devices then make one quick request per page, not ten
//! TMDB calls, and the OMDb daily limit is spent once per title for the whole network.
//!
//!  - GET /meta/tmdb/<path>?<query>: a TMDB v3 GET (movie/…, tv/…, collection/…, person/…; nothing else).
//!  - GET /meta/omdb?i=tt…: OMDb's answer for an IMDb id.
//! Kept 3 days (people and collections 7, OMDb 7); a stale copy is served when TMDB or OMDb can't be reached.

use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::time::{Duration, SystemTime};

pub struct Meta { dir: PathBuf, http: ureq::Agent }

const DAY: u64 = 24 * 60 * 60;

impl Meta {
    pub fn new(dir: PathBuf) -> Self {
        let _ = std::fs::create_dir_all(&dir);
        Meta { dir, http: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(8)).timeout(Duration::from_secs(20)).build() }
    }

    /// TMDB's answer for [path] (e.g. "movie/550") with [query] (its own parameters; any api_key in it is dropped).
    pub fn tmdb(&self, path: &str, query: &str, key: &str) -> Option<Vec<u8>> {
        let ok = path.len() < 120 && !path.contains("..")
            && ["movie/", "tv/", "collection/", "person/"].iter().any(|p| path.starts_with(p))
            && path.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'-'));
        if !ok || key.is_empty() { return None; }
        let mut params: Vec<&str> = query.split('&').filter(|p| !p.is_empty() && !p.starts_with("api_key=")).collect();
        params.sort_unstable();
        let own = params.join("&");
        if own.len() > 400 { return None; }
        let keep = if path.starts_with("person/") || path.starts_with("collection/") { 7 * DAY } else { 3 * DAY };
        let url = format!("https://api.themoviedb.org/3/{path}?api_key={key}{}{own}", if own.is_empty() { "" } else { "&" });
        self.cached(&format!("tmdb {path}?{own}"), keep, &url)
    }

    /// OMDb's answer for an IMDb id ("tt0903747").
    pub fn omdb(&self, imdb: &str, key: &str) -> Option<Vec<u8>> {
        let ok = imdb.starts_with("tt") && imdb.len() < 16 && imdb[2..].bytes().all(|b| b.is_ascii_digit());
        if !ok || key.is_empty() { return None; }
        self.cached(&format!("omdb {imdb}"), 7 * DAY, &format!("https://www.omdbapi.com/?apikey={key}&i={imdb}"))
    }

    fn cached(&self, name: &str, keep: u64, url: &str) -> Option<Vec<u8>> {
        let file = self.dir.join(format!("{:x}.json", Sha256::digest(name.as_bytes())));
        let age = std::fs::metadata(&file).and_then(|m| m.modified()).ok()
            .and_then(|t| SystemTime::now().duration_since(t).ok()).map(|d| d.as_secs());
        if let Some(a) = age { if a < keep { if let Ok(b) = std::fs::read(&file) { return Some(b); } } }
        let fresh = self.http.get(url).call().ok().and_then(|r| {
            let mut body = Vec::new();
            std::io::Read::read_to_end(&mut std::io::Read::take(r.into_reader(), 4 << 20), &mut body).ok()?;
            Some(body)
        });
        match fresh {
            Some(body) => {
                let tmp = file.with_extension("part");
                if std::fs::write(&tmp, &body).is_ok() { let _ = std::fs::rename(&tmp, &file); }
                Some(body)
            }
            None => std::fs::read(&file).ok(),
        }
    }
}
