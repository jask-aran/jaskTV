//! Services core can run for you, so a home server is one program to set up: TorrServer (streams torrents) and
//! Prowlarr (searches your indexers). `jasktv-core setup` downloads the right build for this machine (Linux x86-64
//! or ARM64, Windows x86-64) from each project's GitHub releases into `services/` beside core; while core runs it
//! starts them, restarts one that exits, and reads Prowlarr's API key from Prowlarr's own config, so nothing is copied
//! by hand. A service you already run elsewhere: leave it out of `services` and set its address in the config instead.
//!
//! Prowlarr needs nothing from you either: core turns off its sign-in page and keeps it on this machine only (core is
//! the only thing that talks to it), and when it has no indexers adds a starter set of public trackers, keeping the
//! ones Prowlarr's own test says work from here. Indexers you add or remove later are left alone.

use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;

pub const TORRSERVER_PORT: u16 = 8090;
pub const PROWLARR_PORT: u16 = 9696;

/// Public trackers added to an empty Prowlarr (its definition names). Knaben searches many others itself; the rest
/// fill gaps. One that's blocked from this network (Cloudflare checks, ISP blocks) fails Prowlarr's test and is skipped.
const STARTER_INDEXERS: &[&str] = &["knaben", "yts", "thepiratebay", "eztv", "1337x", "limetorrents", "nyaasi", "torrentscsv",
    "torrentdownloads", "uindex", "kickasstorrents-ws"];

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Kind { TorrServer, Prowlarr }

impl Kind {
    pub fn name(self) -> &'static str { match self { Kind::TorrServer => "torrserver", Kind::Prowlarr => "prowlarr" } }
    pub fn from_name(s: &str) -> Option<Kind> { match s { "torrserver" => Some(Kind::TorrServer), "prowlarr" => Some(Kind::Prowlarr), _ => None } }
    /// Where the newest build for this machine downloads from: TorrServer's "latest" release link, Prowlarr's own
    /// update service (both redirect to the file on GitHub; neither uses GitHub's rate-limited API).
    fn download_url(self) -> Option<String> {
        let (os, arch) = (std::env::consts::OS, std::env::consts::ARCH);
        Some(match self {
            // The MatriX line: its API (/echo, /stream?link=…&play) is what core and players use. The newer "Silo"
            // line is a rewrite without it, so "latest" isn't taken blindly.
            Kind::TorrServer => format!("https://github.com/YouROK/TorrServer/releases/download/{}/{}", newest_matrix(), match (os, arch) {
                ("windows", "x86_64") => "TorrServer-windows-amd64.exe",
                ("linux", "x86_64") => "TorrServer-linux-amd64",
                ("linux", "aarch64") => "TorrServer-linux-arm64",
                _ => return None,
            }),
            Kind::Prowlarr => format!("https://prowlarr.servarr.com/v1/update/master/updatefile?runtime=netcore&{}", match (os, arch) {
                ("windows", "x86_64") => "os=windows&arch=x64",
                ("linux", "x86_64") => "os=linux&arch=x64",
                ("linux", "aarch64") => "os=linux&arch=arm64",
                _ => return None,
            }),
        })
    }

    fn program(self, dir: &Path) -> PathBuf {
        let exe = if cfg!(windows) { ".exe" } else { "" };
        match self {
            Kind::TorrServer => dir.join(format!("TorrServer{exe}")),
            // On Windows its console build: Prowlarr.exe puts an icon in the tray, and core runs it out of sight.
            Kind::Prowlarr => dir.join("Prowlarr").join(if cfg!(windows) { "Prowlarr.Console.exe".to_string() } else { "Prowlarr".to_string() }),
        }
    }
}

pub struct Services { dir: PathBuf, kinds: Vec<Kind>, children: Mutex<Vec<(Kind, Child)>>, torrent_cache: u64, configured: Mutex<bool>, prowlarr_ready: Mutex<bool> }

impl Services {
    /// [torrent_cache]: bytes of torrent data TorrServer may keep on disk (see [configure_torrserver]).
    pub fn new(dir: PathBuf, names: &[String], torrent_cache: u64) -> Self {
        let kinds = names.iter().filter_map(|n| Kind::from_name(n)).collect();
        Services { dir, kinds, children: Mutex::new(Vec::new()), torrent_cache, configured: Mutex::new(false), prowlarr_ready: Mutex::new(false) }
    }

    /// TorrServer keeps what it downloads on disk (not 64 MB in memory, forgotten on drop), so a film started on one
    /// device starts at once on another. Core keeps that folder under the cap, oldest first.
    fn configure_torrserver(&self) {
        let base = format!("http://127.0.0.1:{TORRSERVER_PORT}/settings");
        let Ok(r) = ureq::post(&base).send_json(serde_json::json!({ "action": "get" })) else { return };
        let Ok(mut sets) = r.into_json::<Value>() else { return };
        let cache = self.home(Kind::TorrServer).join("cache");
        let _ = std::fs::create_dir_all(&cache);
        sets["UseDisk"] = Value::Bool(true);
        sets["TorrentsSavePath"] = Value::String(cache.display().to_string());
        sets["RemoveCacheOnDrop"] = Value::Bool(false);
        // Per torrent: enough for a whole film at good quality.
        sets["CacheSize"] = serde_json::json!(8u64 << 30);
        if ureq::post(&base).send_json(serde_json::json!({ "action": "set", "sets": sets })).is_ok() {
            *self.configured.lock().unwrap() = true;
            eprintln!("services: TorrServer keeps torrents on disk (up to {:.0} GB)", self.torrent_cache as f64 / 1e9);
        }
    }

    /// Prowlarr's config: no sign-in page (it asks for an account on first open otherwise), listening on this machine
    /// only, no browser at start. True when it changed (Prowlarr must restart to take it).
    fn configure_prowlarr_file(&self) -> bool {
        let path = self.home(Kind::Prowlarr).join("data").join("config.xml");
        let Ok(text) = std::fs::read_to_string(&path) else { return false };
        let mut next = text.clone();
        for (tag, value) in [("AuthenticationMethod", "External"), ("BindAddress", "127.0.0.1"), ("LaunchBrowser", "False")] {
            let open = format!("<{tag}>");
            let close = format!("</{tag}>");
            match (next.find(&open), next.find(&close)) {
                (Some(a), Some(b)) if b > a => next.replace_range(a + open.len()..b, value),
                _ => if let Some(end) = next.rfind("</Config>") { next.insert_str(end, &format!("  {open}{value}{close}\n")); },
            }
        }
        if next == text { return false; }
        std::fs::write(&path, next).is_ok()
    }

    /// Stops [k] (core starts it again within 15 s), even one left running by an earlier core.
    pub fn restart(&self, k: Kind) -> Result<String, String> {
        if !self.manages(k) { return Err(format!("{} isn't run by this core", k.name())); }
        let mut children = self.children.lock().unwrap();
        for (_, c) in children.iter_mut().filter(|(ck, _)| *ck == k) { let _ = c.kill(); let _ = c.wait(); }
        children.retain(|(ck, _)| *ck != k);
        drop(children);
        if k == Kind::Prowlarr { stop_listening(PROWLARR_PORT); *self.prowlarr_ready.lock().unwrap() = false; }
        if k == Kind::TorrServer { *self.configured.lock().unwrap() = false; }
        Ok(format!("{} restarting", k.name()))
    }

    /// The starter trackers Prowlarr doesn't have yet (keeping those that pass its test). Ok: how many were added.
    pub fn add_starter_trackers(&self) -> Result<String, String> {
        if !self.manages(Kind::Prowlarr) { return Err("Prowlarr isn't run by this core".into()); }
        self.seed(true).map(|n| format!("{n} trackers added")).ok_or_else(|| "Prowlarr didn't answer".into())
    }

    /// An empty Prowlarr gets the starter trackers. Returns how many it has afterwards (None: Prowlarr didn't answer).
    fn seed_prowlarr(&self) -> Option<usize> { self.seed(false) }

    /// [missing_only]: add the starter trackers it doesn't have (else: only when it has none). How many it added
    /// (or, when it already had some and [missing_only] is false, how many it has).
    fn seed(&self, missing_only: bool) -> Option<usize> {
        let key = self.prowlarr_key()?;
        let base = format!("http://127.0.0.1:{PROWLARR_PORT}/api/v1");
        let get = |path: &str| ureq::get(&format!("{base}/{path}")).set("X-Api-Key", &key).timeout(Duration::from_secs(60)).call().ok()?.into_json::<Value>().ok();
        let existing = get("indexer")?;
        let have: Vec<String> = existing.as_array()?.iter().filter_map(|i| i["definitionName"].as_str().map(|n| n.to_lowercase())).collect();
        if !have.is_empty() && !missing_only { return Some(have.len()); }
        let schema = get("indexer/schema")?;
        let profile = get("appprofile").and_then(|p| p.as_array()?.first()?.get("id")?.as_i64()).unwrap_or(1);
        let mut added = Vec::new();
        for name in STARTER_INDEXERS.iter().filter(|n| !have.iter().any(|h| h == *n)) {
            let Some(mut def) = schema.as_array()?.iter().find(|d| d["definitionName"].as_str().is_some_and(|n| n.eq_ignore_ascii_case(name))).cloned() else { continue };
            def["enable"] = Value::Bool(true);
            def["appProfileId"] = serde_json::json!(profile);
            def["priority"] = serde_json::json!(25);
            // Prowlarr tests an indexer before saving it: one it can't reach from here is refused.
            if ureq::post(&format!("{base}/indexer")).set("X-Api-Key", &key).timeout(Duration::from_secs(60)).send_json(def.clone()).is_ok() {
                added.push(def["name"].as_str().unwrap_or(name).to_string());
            }
        }
        eprintln!("services: added {} trackers that work from here: {}", added.len(), added.join(", "));
        Some(added.len())
    }

    pub fn manages(&self, k: Kind) -> bool { self.kinds.contains(&k) }

    fn home(&self, k: Kind) -> PathBuf { self.dir.join(k.name()) }

    /// Downloads every managed service that isn't here yet (or all of them again with [update]).
    pub fn install(&self, update: bool) -> Result<(), String> {
        for &k in &self.kinds {
            let home = self.home(k);
            if !update && k.program(&home).is_file() { eprintln!("{}: already installed", k.name()); continue; }
            std::fs::create_dir_all(&home).map_err(|e| e.to_string())?;
            let url = k.download_url().ok_or_else(|| format!("{}: no build for {} {}", k.name(), std::env::consts::OS, std::env::consts::ARCH))?;
            // Follow redirects by hand until the GitHub release address, which names the version and file
            // (".../releases/download/<version>/<file>"); GitHub then hands off to its file storage.
            let hop = ureq::AgentBuilder::new().redirects(0).timeout_connect(Duration::from_secs(15)).build();
            let mut at = url.clone();
            for _ in 0..4 {
                if at.contains("/releases/download/") { break; }
                match hop.get(&at).set("User-Agent", "jasktv-core").call() {
                    Ok(r) if (300..400).contains(&r.status()) => at = r.header("Location").unwrap_or_default().to_string(),
                    Ok(_) => break,
                    Err(e) => return Err(format!("{}: can't find the download ({e})", k.name())),
                }
            }
            let mut parts = at.rsplit('/');
            let name = parts.next().unwrap_or("download").split('?').next().unwrap_or("download").to_string();
            let tag = parts.next().unwrap_or("?").to_string();
            let resp = ureq::get(&at).set("User-Agent", "jasktv-core").call().map_err(|e| format!("{}: download failed ({e})", k.name()))?;
            let size = resp.header("Content-Length").and_then(|n| n.parse::<f64>().ok()).unwrap_or(0.0);
            eprintln!("{}: downloading {name} ({tag}, {:.0} MB)…", k.name(), size / 1e6);
            let download = home.join(name);
            let mut file = std::fs::File::create(&download).map_err(|e| e.to_string())?;
            std::io::copy(&mut resp.into_reader(), &mut file).map_err(|e| format!("{}: download cut off ({})", k.name(), e.kind()))?;
            drop(file);
            match k {
                Kind::TorrServer => {
                    std::fs::rename(&download, k.program(&home)).map_err(|e| e.to_string())?;
                    make_executable(&k.program(&home));
                }
                Kind::Prowlarr => {
                    // tar unpacks both the Linux .tar.gz and (Windows' bsdtar) the .zip.
                    let _ = std::fs::remove_dir_all(home.join("Prowlarr"));
                    let ok = Command::new("tar").arg("-xf").arg(&download).arg("-C").arg(&home).status().map(|s| s.success()).unwrap_or(false);
                    let _ = std::fs::remove_file(&download);
                    if !ok { return Err("prowlarr: couldn't unpack it (is `tar` installed?)".into()); }
                    make_executable(&k.program(&home));
                }
            }
            std::fs::write(home.join("version.txt"), &tag).ok();
            eprintln!("{}: installed {tag}", k.name());
        }
        Ok(())
    }

    /// Runs for the life of core: starts each installed service, and starts it again if it exits.
    pub fn run(&self) {
        let mut tick = 0u64;
        loop {
            tick += 1;
            if self.manages(Kind::TorrServer) {
                let up = std::net::TcpStream::connect_timeout(&([127, 0, 0, 1], TORRSERVER_PORT).into(), Duration::from_millis(300)).is_ok();
                if up && !*self.configured.lock().unwrap() { self.configure_torrserver(); }
                if !up { *self.configured.lock().unwrap() = false; }
                // Every ten minutes or so: the disk cache under its cap.
                if tick % 40 == 1 { crate::images::prune(&self.home(Kind::TorrServer).join("cache"), self.torrent_cache); }
            }
            if self.manages(Kind::Prowlarr) && !*self.prowlarr_ready.lock().unwrap() {
                let up = std::net::TcpStream::connect_timeout(&([127, 0, 0, 1], PROWLARR_PORT).into(), Duration::from_millis(300)).is_ok();
                if up && self.configure_prowlarr_file() {
                    // Restart it to take the new config: stop ours (or one left running) and start again below.
                    eprintln!("services: Prowlarr set to need no sign-in, on this machine only; restarting it");
                    let mut children = self.children.lock().unwrap();
                    for (_, c) in children.iter_mut().filter(|(k, _)| *k == Kind::Prowlarr) { let _ = c.kill(); let _ = c.wait(); }
                    children.retain(|(k, _)| *k != Kind::Prowlarr);
                    drop(children);
                    stop_listening(PROWLARR_PORT);
                    std::thread::sleep(Duration::from_secs(3));
                } else if up && self.seed_prowlarr().is_some() {
                    *self.prowlarr_ready.lock().unwrap() = true;
                }
            }
            for &k in &self.kinds {
                let mut children = self.children.lock().unwrap();
                let running = children.iter_mut().any(|(ck, c)| *ck == k && matches!(c.try_wait(), Ok(None)));
                if running { continue; }
                children.retain(|(ck, _)| *ck != k);
                let home = self.home(k);
                let program = k.program(&home);
                if !program.is_file() { continue; }
                // Already listening (left running by a core that stopped, or started by hand): use that one.
                let port = match k { Kind::TorrServer => TORRSERVER_PORT, Kind::Prowlarr => PROWLARR_PORT };
                if std::net::TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(300)).is_ok() { continue; }
                let mut cmd = Command::new(&program);
                match k {
                    Kind::TorrServer => { cmd.arg("-p").arg(TORRSERVER_PORT.to_string()).arg("-d").arg(home.join("data")); }
                    Kind::Prowlarr => { cmd.arg("-nobrowser").arg(format!("-data={}", home.join("data").display())); }
                }
                let _ = std::fs::create_dir_all(home.join("data"));
                let log = std::fs::OpenOptions::new().create(true).append(true).open(home.join("output.log")).ok();
                cmd.current_dir(&home).stdin(Stdio::null())
                    .stdout(log.as_ref().and_then(|f| f.try_clone().ok()).map(Stdio::from).unwrap_or(Stdio::null()))
                    .stderr(log.map(Stdio::from).unwrap_or(Stdio::null()));
                hide_window(&mut cmd);
                match cmd.spawn() {
                    Ok(child) => { eprintln!("services: started {}", k.name()); children.push((k, child)); }
                    Err(e) => eprintln!("services: can't start {}: {e}", k.name()),
                }
            }
            std::thread::sleep(Duration::from_secs(15));
        }
    }

    /// Prowlarr's API key, from the config.xml Prowlarr writes on its first start.
    pub fn prowlarr_key(&self) -> Option<String> {
        if !self.manages(Kind::Prowlarr) { return None; }
        let text = std::fs::read_to_string(self.home(Kind::Prowlarr).join("data").join("config.xml")).ok()?;
        let start = text.find("<ApiKey>")? + 8;
        let end = text[start..].find("</ApiKey>")? + start;
        Some(text[start..end].trim().to_string()).filter(|k| !k.is_empty())
    }

    /// Prowlarr's indexers: [{name, enabled}] (empty when it isn't managed here or doesn't answer).
    pub fn prowlarr_indexers(&self) -> Vec<Value> {
        let Some(key) = self.prowlarr_key() else { return vec![] };
        ureq::get(&format!("http://127.0.0.1:{PROWLARR_PORT}/api/v1/indexer")).set("X-Api-Key", &key).timeout(Duration::from_secs(10)).call().ok()
            .and_then(|r| r.into_json::<Value>().ok())
            .and_then(|v| v.as_array().map(|a| a.iter().map(|i| serde_json::json!({ "name": i["name"], "enabled": i["enable"] })).collect()))
            .unwrap_or_default()
    }

    pub fn status(&self) -> Value {
        let mut children = self.children.lock().unwrap();
        Value::Object(self.kinds.iter().map(|&k| {
            let port = match k { Kind::TorrServer => TORRSERVER_PORT, Kind::Prowlarr => PROWLARR_PORT };
            let running = children.iter_mut().any(|(ck, c)| *ck == k && matches!(c.try_wait(), Ok(None)))
                || std::net::TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(300)).is_ok();
            let version = std::fs::read_to_string(self.home(k).join("version.txt")).unwrap_or_default();
            (k.name().to_string(), serde_json::json!({ "running": running, "version": version.trim() }))
        }).collect())
    }
}

/// Stops whatever Prowlarr is listening on [port] when it isn't core's own child (left by a core that stopped).
fn stop_listening(port: u16) {
    if std::net::TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(300)).is_err() { return; }
    #[cfg(windows)] { let mut c = Command::new("taskkill"); c.args(["/F", "/IM", "Prowlarr.exe"]); hide_window(&mut c); let _ = c.output(); }
    #[cfg(not(windows))] { let _ = Command::new("pkill").args(["-x", "Prowlarr"]).output(); }
}

/// The newest "MatriX.N" TorrServer tag, from the project's tags page; MatriX.150 when that can't be read.
fn newest_matrix() -> String {
    let page = ureq::get("https://github.com/YouROK/TorrServer/tags").set("User-Agent", "jasktv-core").call().ok()
        .and_then(|r| r.into_string().ok()).unwrap_or_default();
    let mut best: Option<(Vec<u32>, String)> = None;
    for part in page.split("releases/tag/MatriX.").skip(1) {
        let v: String = part.chars().take_while(|c| c.is_ascii_digit() || *c == '.').collect();
        let v = v.trim_end_matches('.').to_string();
        let nums: Vec<u32> = v.split('.').filter_map(|n| n.parse().ok()).collect();
        if !nums.is_empty() && best.as_ref().is_none_or(|(b, _)| nums > *b) { best = Some((nums, v)); }
    }
    best.map(|(_, v)| format!("MatriX.{v}")).unwrap_or_else(|| "MatriX.150".into())
}

fn make_executable(p: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o755));
    }
    #[cfg(not(unix))]
    let _ = p;
}

/// No console window per service on Windows.
fn hide_window(cmd: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    #[cfg(not(windows))]
    let _ = cmd;
}
