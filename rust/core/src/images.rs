//! A poster and backdrop cache for the home network: devices ask core for TMDB images ("/img/t/p/w500/abc.jpg") and
//! core fetches each from image.tmdb.org once, then serves it from disk. Kept under a size cap, oldest first.

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

pub struct Images { dir: PathBuf, cap_bytes: u64, writes: AtomicUsize, http: ureq::Agent }

impl Images {
    pub fn new(dir: PathBuf, cap_bytes: u64) -> Self {
        let _ = std::fs::create_dir_all(&dir);
        Images { dir, cap_bytes, writes: AtomicUsize::new(0),
            http: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(30)).build() }
    }

    /// The image at a TMDB path ("t/p/<size>/<file>"): from disk, else fetched and kept. None: not a TMDB image path,
    /// or TMDB doesn't have it.
    pub fn get(&self, path: &str) -> Option<(PathBuf, &'static str)> {
        let ok = path.starts_with("t/p/") && path.len() < 200 && !path.contains("..")
            && path.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'-' | b'.'));
        if !ok { return None; }
        let kind = if path.ends_with(".png") { "image/png" } else if path.ends_with(".svg") { "image/svg+xml" } else { "image/jpeg" };
        let file = self.dir.join(path.replace('/', "_"));
        if file.is_file() {
            // Touched on use, so pruning drops what nobody looks at.
            let _ = std::fs::File::options().append(true).open(&file).and_then(|f| f.set_modified(std::time::SystemTime::now()));
            return Some((file, kind));
        }
        let resp = self.http.get(&format!("https://image.tmdb.org/{path}")).call().ok()?;
        let tmp = file.with_extension("part");
        let mut out = std::fs::File::create(&tmp).ok()?;
        std::io::copy(&mut std::io::Read::take(resp.into_reader(), 20 << 20), &mut out).ok()?;
        drop(out);
        std::fs::rename(&tmp, &file).ok()?;
        if self.writes.fetch_add(1, Ordering::Relaxed) % 500 == 0 { prune(&self.dir, self.cap_bytes); }
        Some((file, kind))
    }
}

/// Deletes the least recently used files in [dir] (recursively) until it's under [cap] bytes.
pub fn prune(dir: &std::path::Path, cap: u64) {
    let mut files = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
            let Ok(m) = e.metadata() else { continue };
            if m.is_dir() { stack.push(e.path()); } else { files.push((m.modified().unwrap_or(std::time::UNIX_EPOCH), m.len(), e.path())); }
        }
    }
    let mut total: u64 = files.iter().map(|f| f.1).sum();
    if total <= cap { return; }
    files.sort_by_key(|f| f.0);
    for (_, len, path) in files {
        if total <= cap { break; }
        if std::fs::remove_file(&path).is_ok() { total -= len; }
    }
}
