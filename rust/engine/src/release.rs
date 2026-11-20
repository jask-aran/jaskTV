//! Release names: resolution, HDR, episode marks, ranking. Pure functions.

use regex_lite::Regex;
use std::collections::HashSet;
use std::sync::OnceLock;

/// One torrent found for a title.
#[derive(Clone, Debug)]
pub struct Release {
    pub name: String,
    pub indexer: String,
    pub size: u64,
    pub seeders: u64,
    /// Lowercase hex info hash, or empty.
    pub hash: String,
    /// Magnet link (or a .torrent URL) for TorrServer.
    pub link: String,
}

macro_rules! re {
    ($name:ident, $pattern:expr) => {
        fn $name() -> &'static Regex {
            static R: OnceLock<Regex> = OnceLock::new();
            R.get_or_init(|| Regex::new($pattern).expect("pattern"))
        }
    };
}

re!(res_4k, r"(?i)\b(2160p|4k|uhd)\b");
re!(res_1080, r"(?i)\b1080[pi]\b");
re!(res_720, r"(?i)\b720p\b");
re!(dolby, r"(?i)\b(dv|dovi|dolby[ .]?vision)\b");
re!(hdr, r"(?i)\bhdr(10\+?)?\b");
re!(btih, r"(?i)btih:([a-f0-9]{40})");
// Releases nobody wants to stream: camera copies and the like.
re!(junk, r"(?i)\b(cam|camrip|hdcam|ts|telesync|hdts|telecine|tc|scr|screener)\b");
re!(episode_mark, r"(?i)\bs(\d{1,2})[ ._-]?e(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b");
re!(season_mark, r"(?i)\bs(\d{1,2})\b|\bseason[ ._-]?(\d{1,2})\b");
re!(complete, r"(?i)\bcomplete\b");
re!(video_file, r"(?i)\.(mkv|mp4|avi|m4v|mov|ts|webm)$");
re!(sample_file, r"(?i)\bsample\b");

impl Release {
    /// 4 (2160p) … 1 (SD or unknown).
    pub fn resolution(&self) -> u8 {
        if res_4k().is_match(&self.name) { 4 }
        else if res_1080().is_match(&self.name) { 3 }
        else if res_720().is_match(&self.name) { 2 }
        else { 1 }
    }

    pub fn label(&self) -> String {
        let res = ["", "SD", "720p", "1080p", "4K"][self.resolution() as usize];
        let hdr = if dolby().is_match(&self.name) { " Dolby Vision" } else if hdr().is_match(&self.name) { " HDR" } else { "" };
        format!("{res}{hdr}")
    }

    /// Torrentio's layout ("👤 220 💾 12.3 GB ⚙️ indexer"), which jaskTV already reads.
    pub fn details(&self) -> String {
        let mut parts = Vec::new();
        if self.seeders > 0 { parts.push(format!("👤 {}", self.seeders)); }
        if self.size > 0 { parts.push(format!("💾 {:.1} GB", self.size as f64 / 1e9)); }
        if !self.indexer.is_empty() { parts.push(format!("⚙️ {}", self.indexer)); }
        parts.join(" ")
    }
}

pub fn hash_of(link: &str) -> String {
    btih().captures(link).map(|c| c[1].to_ascii_lowercase()).unwrap_or_default()
}

fn fold(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push(' ');
    let mut space = true;
    for c in s.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
            space = false;
        } else if !space {
            out.push(' ');
            space = true;
        }
    }
    if !space { out.push(' '); }
    out
}

const STOP_WORDS: [&str; 5] = ["the", "a", "an", "of", "and"];

/// Every significant word of the title is in the release name (for searches by text).
pub fn names_title(release: &str, title: &str) -> bool {
    let folded = fold(release);
    fold(title).split_whitespace().filter(|w| !STOP_WORDS.contains(w))
        .all(|w| folded.contains(&format!(" {w} ")))
}

fn num(s: Option<regex_lite::Match>) -> u32 {
    s.and_then(|m| m.as_str().parse().ok()).unwrap_or(0)
}

/// The release is that episode, or a pack holding that season.
pub fn covers_episode(name: &str, season: u32, episode: u32) -> bool {
    let mut marked = false;
    for c in episode_mark().captures_iter(name) {
        marked = true;
        let s = num(c.get(1)).max(num(c.get(3)));
        let e = num(c.get(2)).max(num(c.get(4)));
        if s == season && e == episode { return true; }
    }
    if marked { return false; }
    if season_mark().captures_iter(name).any(|c| num(c.get(1)).max(num(c.get(2))) == season) { return true; }
    complete().is_match(name)
}

/// A file inside a pack is that episode.
pub fn is_episode_file(path: &str, season: u32, episode: u32) -> bool {
    episode_mark().captures_iter(path)
        .any(|c| num(c.get(1)).max(num(c.get(3))) == season && num(c.get(2)).max(num(c.get(4))) == episode)
}

pub fn is_video(path: &str) -> bool {
    video_file().is_match(path) && !sample_file().is_match(path)
}

/// One entry per torrent, junk dropped, then resolution, seeders and size, best first.
pub fn rank(found: Vec<Release>, max: usize) -> Vec<Release> {
    let mut seen = HashSet::new();
    let mut out: Vec<Release> = found.into_iter().filter(|r| {
        let key = if r.hash.is_empty() { r.link.clone() } else { r.hash.clone() };
        !key.is_empty() && !junk().is_match(&r.name) && seen.insert(key)
    }).collect();
    out.sort_by(|a, b| b.resolution().cmp(&a.resolution())
        .then(b.seeders.cmp(&a.seeders)).then(b.size.cmp(&a.size)));
    out.truncate(max);
    out
}
