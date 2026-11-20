//! jasktv-core: jaskTV's home server.
//!
//! It joins your jaskTV account the way a TV does (your phone approves it) and keeps a record there, so every
//! signed-in device finds it without typing an address. Today it finds copies of films and episodes through
//! Prowlarr and streams them through TorrServer; matching and caching for every device come next.
//!
//! Everything except the setup page (`/`) and `/health` needs core's access secret: as a path prefix
//! (`/s/<secret>/…`, which Stremio and players can use) or an `Authorization: Bearer <secret>` header.
//! A small threaded server, no async runtime. Builds for Linux and Windows (build.sh).

mod deltas;
mod images;
mod lanpeer;
mod link;
mod matcher;
mod peers;
mod provider;
mod search;
mod services;
mod shared;
mod guides;
mod meta;

use link::{Link, Status};
use search::Search;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tiny_http::{Header, Request, Response, Server};

/// Read from jasktv-core.json beside the binary (or the path given as the first argument), then JASKTV_* variables.
#[derive(Deserialize, Clone, Default)]
#[serde(default)]
pub struct Config {
    /// Where core listens, e.g. "0.0.0.0:7000".
    pub listen: String,
    /// The jaskTV account server, to sign core in to your account. Blank: core runs on its own.
    pub account_server: String,
    /// Prowlarr's address and API key (Prowlarr: Settings → General → API Key).
    pub prowlarr_url: String,
    pub prowlarr_key: String,
    /// How core reaches TorrServer, usually on the same machine.
    pub torrserver_url: String,
    /// How players reach TorrServer. Blank: the host the device used to reach core, on TorrServer's port.
    pub torrserver_public_url: String,
    /// The most releases returned for one title.
    pub max_results: usize,
    /// Services core installs and runs itself: "torrserver", "prowlarr" (see `jasktv-core setup`).
    pub services: Vec<String>,
    /// Disk for torrent data TorrServer keeps (GB, default 10), and for posters and backdrops (GB, default 2).
    pub torrent_cache_gb: f64,
    pub image_cache_gb: f64,
    /// "full" (default): everything, on an always-on machine at home. "helper": matching, lists, the guide and posters
    /// for when the home core is off (a VPS, say): no torrents, no streaming, no home-network discovery, no services.
    pub role: String,
    /// Addresses devices should reach core on (e.g. its Tailscale address). Empty: the address it listens on, when
    /// that's a specific one, else this machine's LAN address.
    pub advertise: Vec<String>,
    /// An https:// address that reaches core from anywhere (e.g. a Cloudflare Tunnel to a helper on a VPS).
    pub public_url: String,
}

fn base_dir() -> std::path::PathBuf {
    std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf())).unwrap_or_else(|| ".".into())
}

fn load_config(arg: Option<String>) -> Config {
    let path = arg.map(std::path::PathBuf::from).unwrap_or_else(|| base_dir().join("jasktv-core.json"));
    let mut c: Config = match std::fs::read_to_string(&path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_else(|e| fail(&format!("config {}: {e}", path.display()))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Config::default(),
        Err(e) => fail(&format!("config {}: {e}", path.display())),
    };
    let env = |name: &str, into: &mut String| {
        if let Ok(v) = std::env::var(name) { if !v.trim().is_empty() { *into = v.trim().to_string(); } }
    };
    env("JASKTV_LISTEN", &mut c.listen);
    env("JASKTV_ACCOUNT_SERVER", &mut c.account_server);
    env("JASKTV_PROWLARR_URL", &mut c.prowlarr_url);
    env("JASKTV_PROWLARR_KEY", &mut c.prowlarr_key);
    env("JASKTV_TORRSERVER_URL", &mut c.torrserver_url);
    env("JASKTV_TORRSERVER_PUBLIC_URL", &mut c.torrserver_public_url);
    if c.listen.is_empty() { c.listen = "0.0.0.0:7000".into(); }
    if c.role != "helper" { c.role = "full".into(); } else { c.services.clear(); }
    if c.advertise.is_empty() {
        if let Some((host, _)) = c.listen.rsplit_once(':') {
            let host = host.trim_matches(|ch| ch == '[' || ch == ']');
            if host != "0.0.0.0" && host != "::" && !host.is_empty() { c.advertise.push(host.to_string()); }
        }
    }
    if c.torrserver_url.is_empty() { c.torrserver_url = "http://127.0.0.1:8090".into(); }
    if c.max_results == 0 { c.max_results = 30; }
    if c.torrent_cache_gb <= 0.0 { c.torrent_cache_gb = 10.0; }
    if c.image_cache_gb <= 0.0 { c.image_cache_gb = 2.0; }
    for url in [&mut c.account_server, &mut c.prowlarr_url, &mut c.torrserver_url, &mut c.torrserver_public_url] {
        while url.ends_with('/') { url.pop(); }
    }
    c
}

fn fail(message: &str) -> ! {
    eprintln!("jasktv-core: {message}");
    std::process::exit(1)
}

struct Core { helper: bool, shared: Arc<shared::Shared>, guides: Arc<guides::Guides>, status_now: std::sync::atomic::AtomicBool, search: Search, link: Arc<Link>, provider: Arc<provider::Provider>, matcher: Arc<matcher::Matcher>, services: Arc<services::Services>, lan: Arc<lanpeer::LanPeer>, images: Arc<images::Images>, meta: meta::Meta }

/// Windows hands a program's open handles to the programs it starts. Core's output is usually core.log, and services
/// started with it kept the file open after core stopped, so the next core couldn't write it and failed to start.
#[cfg(windows)]
fn keep_output_to_core() {
    extern "system" {
        fn GetStdHandle(which: u32) -> *mut core::ffi::c_void;
        fn SetHandleInformation(handle: *mut core::ffi::c_void, mask: u32, flags: u32) -> i32;
    }
    const HANDLE_FLAG_INHERIT: u32 = 1;
    for which in [-11i32 as u32, -12i32 as u32] { // standard output, standard error
        unsafe { SetHandleInformation(GetStdHandle(which), HANDLE_FLAG_INHERIT, 0); }
    }
}

fn main() {
    #[cfg(windows)]
    keep_output_to_core();
    let mut args = std::env::args().skip(1).peekable();
    let command = match args.peek().map(String::as_str) { Some("login" | "logout" | "new-secret" | "secret" | "compare-matches" | "copy-from" | "peers" | "setup" | "update-services" | "install-service") => args.next(), _ => None };
    let cfg = load_config(if matches!(command.as_deref(), Some("compare-matches" | "copy-from" | "peers")) { None } else { args.next() });
    let port: u16 = cfg.listen.rsplit_once(':').and_then(|(_, p)| p.parse().ok()).unwrap_or(7000);
    if matches!(command.as_deref(), Some("setup" | "update-services")) {
        let update = command.as_deref() == Some("update-services");
        let names = if cfg.services.is_empty() { vec!["torrserver".to_string(), "prowlarr".to_string()] } else { cfg.services.clone() };
        if !update { write_config_services(&names); }
        let services = services::Services::new(base_dir().join("services"), &names, 0);
        if let Err(e) = services.install(update) { fail(&e); }
        eprintln!("\nDone. Start core (or restart it) and it runs {}.", names.join(" and "));
        eprintln!("Then add your indexers in Prowlarr: http://<this machine>:{}/ (Indexers → Add).", services::PROWLARR_PORT);
        eprintln!("To start core with this machine: jasktv-core install-service");
        return;
    }
    if command.as_deref() == Some("install-service") { install_service(&cfg.role); return; }
    if let Some(command) = command {
        let link = Link::load(base_dir().join("jasktv-core-state.json"), &cfg.account_server, port, &cfg.role, cfg.advertise.clone());
        if command == "peers" {
            match peers::browse(std::time::Duration::from_secs(6)) {
                Ok(list) => for p in list { println!("{} {}", p.device, p.addr) },
                Err(e) => fail(&e),
            }
            return;
        }
        if command == "copy-from" {
            let addr = args.next().and_then(|a| a.parse().ok()).unwrap_or_else(|| fail("usage: copy-from <ip:port>"));
            let link_ = link.clone();
            let provider = provider::Provider::new(base_dir().join("cache").join("provider"), link.clone(), true);
            let m = matcher::Matcher::new(base_dir().join("cache").join("core.db"), link.clone(), provider, false).unwrap_or_else(|e| fail(&e));
            link_.catch_up_now();
            for source in m_sources(&link_) {
                match link_.copy_from(None, addr, &source, |chunk| m.import_chunk(&source, chunk, false)) {
                    Ok((found, added)) => eprintln!("jasktv-core: {source:.8}…: {} {added} new rows", if found { "copied," } else { "that device doesn't have it;" }),
                    Err(e) => eprintln!("jasktv-core: {e}"),
                }
            }
            return;
        }
        if command == "compare-matches" { compare_matches(link, args.collect()); return; }
        if command == "secret" { println!("{}", link.access_secret()); return; }
        if command == "new-secret" { link.new_secret(); eprintln!("jasktv-core: new access secret saved; restart core so devices get it"); return; }
        let result = if command == "login" {
            // Email, then password, one per line on standard input (typed, or piped from a file).
            eprint!("Email and password for your jaskTV account, one per line:\n");
            let mut lines = std::io::stdin().lines().map_while(Result::ok);
            let email = lines.next().unwrap_or_default();
            let password = lines.next().unwrap_or_default();
            if email.trim().is_empty() || password.is_empty() { fail("no email or password given"); }
            link.login(email.trim(), &password).map(|email| format!("signed in as {email}; core's record is in your account"))
        } else {
            link.logout().map(|_| "signed out; core has left your account".to_string())
        };
        match result { Ok(m) => { eprintln!("jasktv-core: {m}"); return; } Err(e) => fail(&e) }
    }
    let server = Arc::new(Server::http(&cfg.listen).unwrap_or_else(|e| fail(&format!("listen on {}: {e}", cfg.listen))));
    let link = Link::load(base_dir().join("jasktv-core-state.json"), &cfg.account_server, port, &cfg.role, cfg.advertise.clone());
    link.set_public_url(cfg.public_url.trim_end_matches('/'));
    eprintln!("jasktv-core {} listening on {}", env!("CARGO_PKG_VERSION"), cfg.listen);
    eprintln!("  setup page: http://<this machine>:{port}/");
    eprintln!("  access secret: `jasktv-core secret` prints it (for Stremio: http://<this machine>:{port}/s/<secret>/manifest.json)");
    if cfg.role == "full" && (cfg.prowlarr_url.is_empty() || cfg.prowlarr_key.is_empty()) && !cfg.services.iter().any(|s| s == "prowlarr") {
        eprintln!("  Prowlarr isn't set (run `jasktv-core setup`, or set prowlarr_url and prowlarr_key): searches will find nothing");
    }
    if cfg.account_server.is_empty() { eprintln!("  account_server isn't set: core won't join your jaskTV account"); }
    {
        let link = link.clone();
        std::thread::spawn(move || link.run());
    }
    let provider = provider::Provider::new(base_dir().join("cache").join("provider"), link.clone(), cfg.role == "full");
    if !cfg.account_server.is_empty() {
        let p = provider.clone();
        std::thread::spawn(move || p.run());
        let p = provider.clone();
        std::thread::spawn(move || p.run_slots());
    }
    let matcher = matcher::Matcher::new(base_dir().join("cache").join("core.db"), link.clone(), provider.clone(), cfg.role == "full")
        .unwrap_or_else(|e| fail(&format!("can't open its database: {e}")));
    let shared = shared::Shared::new(link.clone(), matcher.clone(), provider.clone(), base_dir().join("cache"));
    let guides = guides::Guides::new(link.clone(), matcher.clone(), provider.clone(), base_dir().join("cache").join("guides"));
    if !cfg.account_server.is_empty() { let g = guides.clone(); std::thread::spawn(move || g.run()); }
    if !cfg.account_server.is_empty() {
        let matcher = matcher.clone();
        std::thread::spawn(move || matcher.run());
        // Who builds the account's match table, its snapshots, and copying it when another core builds.
        let shared = shared.clone();
        std::thread::spawn(move || shared.run());
    }
    let lan = lanpeer::LanPeer::new(link.clone(), matcher.clone());
    // A helper isn't on the home network: no announcing, no device links.
    if !cfg.account_server.is_empty() && cfg.role == "full" {
        let lan = lan.clone();
        std::thread::spawn(move || lan.run());
    }
    let services = Arc::new(services::Services::new(base_dir().join("services"), &cfg.services, (cfg.torrent_cache_gb * 1e9) as u64));
    let images = Arc::new(images::Images::new(base_dir().join("cache").join("images"), (cfg.image_cache_gb * 1e9) as u64));
    {
        let services = services.clone();
        std::thread::spawn(move || services.run());
    }
    let helper = cfg.role == "helper";
    if helper { eprintln!("  role: helper (matching, lists, guide, posters; no torrents or streaming)"); }
    let core = Arc::new(Core { helper, shared, guides, status_now: std::sync::atomic::AtomicBool::new(false), search: Search::new(cfg, services.clone()), link, provider, matcher, services, lan, images, meta: meta::Meta::new(base_dir().join("cache").join("meta")) });
    // Core's status as an account record ("cst", under core's id), for the control panel: it reads it even when core
    // is off or out of reach (showing when it was last seen). Written when something changed (at most every 2 minutes)
    // and at least every 15 minutes.
    {
        let core = core.clone();
        std::thread::spawn(move || {
            let (mut last, mut written) = (Value::Null, std::time::Instant::now() - Duration::from_secs(3600));
            loop {
                let mut status = core.health(true);
                if let Some(o) = status.as_object_mut() { o.remove("account"); o.remove("core"); }
                status["indexers"] = Value::Array(core.services.prowlarr_indexers());
                let since = written.elapsed();
                let forced = core.status_now.swap(false, std::sync::atomic::Ordering::SeqCst);
                if (status != last && (forced || since >= Duration::from_secs(120))) || since >= Duration::from_secs(900) {
                    let mut out = status.clone();
                    out["at"] = json!(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0));
                    match core.link.put_own("cst", out) {
                        Ok(()) => { last = status; written = std::time::Instant::now(); }
                        Err(e) if e != "not signed in" => eprintln!("jasktv-core: couldn't publish status: {e}"),
                        Err(_) => {}
                    }
                }
                // A minute between looks, or sooner when a command asks for fresh status.
                for _ in 0..30 { if core.status_now.load(std::sync::atomic::Ordering::SeqCst) { break; } std::thread::sleep(Duration::from_secs(2)); }
            }
        });
    }
    // Commands from the control panel arrive as records; the live connection wakes core when any record changes.
    { let core = core.clone(); std::thread::spawn(move || live(core)); }
    // A handful of threads: searches wait on Prowlarr and play waits on TorrServer, so a slow one mustn't block others.
    let workers: Vec<_> = (0..8).map(|_| {
        let (server, core) = (server.clone(), core.clone());
        std::thread::spawn(move || loop {
            match server.recv() {
                Ok(request) => core.handle(request),
                Err(e) => eprintln!("jasktv-core: {e}"),
            }
        })
    }).collect();
    for w in workers { let _ = w.join(); }
}

type Reply = Response<std::io::Cursor<Vec<u8>>>;

impl Core {
    fn handle(&self, request: Request) {
        let (raw_path, query) = match request.url().split_once('?') {
            Some((p, q)) => (decode(p), parse_query(q)),
            None => (decode(request.url()), HashMap::new()),
        };
        let host = request.headers().iter().find(|h| h.field.equiv("Host")).map(|h| h.value.as_str().to_string())
            .unwrap_or_else(|| "localhost:7000".into());
        let secret = self.link.access_secret();
        // The secret as a path prefix (for players and Stremio) or a Bearer header (for jaskTV).
        let prefix = format!("/s/{secret}");
        let (path, authorised) = match raw_path.strip_prefix(&prefix) {
            Some(rest) if rest.is_empty() || rest.starts_with('/') => (rest.to_string(), true),
            _ => {
                let bearer = request.headers().iter().find(|h| h.field.equiv("Authorization"))
                    .map(|h| h.value.as_str() == format!("Bearer {secret}")).unwrap_or(false);
                (raw_path.clone(), bearer)
            }
        };
        let base = format!("http://{host}{prefix}");
        let parts: Vec<&str> = path.trim_start_matches('/').split('/').collect();
        let response = match (parts.as_slice(), authorised) {
            ([""], _) if !authorised => html(self.setup_page()),
            (["health"], _) => json_response(self.health(authorised)),
            (_, false) => Response::from_string("jasktv-core: this needs core's access secret (see the setup page)").with_status_code(401),
            // A playlist's cached list (films, series, their categories), byte for byte as the provider sent it.
            // What was removed from a list since ?since= (ids), for a device applying changes.
            (["xtream", source, action, "removed"], true) => {
                let since: i64 = query.get("since").and_then(|s| s.parse().ok()).unwrap_or(0);
                match self.provider.deltas.as_ref().and_then(|d| d.since(source, action, since)).filter(|_| self.provider.file(source, action).is_some()) {
                    Some((_, removed, _)) => json_response(serde_json::from_str(&removed).unwrap_or(Value::Null)),
                    None => Response::from_string("no changes kept").with_status_code(404),
                }
            }
            // Only the entries that changed since ?since=, when core has tracked the list since then.
            (["xtream", source, action], true) if query.get("since").is_some_and(|s| s != "0") && self.provider.file(source, action).is_some()
                && self.provider.deltas.as_ref().and_then(|d| d.since(source, action, query["since"].parse().unwrap_or(0))).is_some() => {
                let (changed, _, now) = self.provider.deltas.as_ref().unwrap().since(source, action, query["since"].parse().unwrap_or(0)).unwrap();
                eprintln!("served {action} changes to {}", request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default());
                let _ = request.respond(Response::from_string(changed).with_header(header("Content-Type", "application/json; charset=utf-8"))
                    .with_header(header("X-Core-Delta", "1")).with_header(header("X-Core-Now", &now.to_string()))
                    .with_header(header("Access-Control-Allow-Origin", "*")));
                return;
            }
            (["xtream", source, action], true) => match self.provider.file(source, action).and_then(|p| std::fs::File::open(p).ok()) {
                Some(file) => {
                    eprintln!("served {action} to {}", request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default());
                    let kind = if *action == provider::GUIDE { "application/xml" } else { "application/json; charset=utf-8" };
                    let mut reply = Response::from_file(file).with_header(header("Content-Type", kind));
                    // The whole list: the time to ask for changes from next.
                    if let Some(t) = self.provider.deltas.as_ref().and_then(|d| d.indexed(source, action)) { reply = reply.with_header(header("X-Core-Now", &t.to_string())); }
                    let _ = request.respond(reply);
                    return;
                }
                None => Response::from_string("not cached").with_status_code(404),
            },
            // The network's guide for a playlist (see guides.rs): gzip XMLTV.
            (["guide", source], true) => match self.guides.file(source).and_then(|p| std::fs::File::open(p).ok()) {
                Some(f) => { let _ = request.respond(Response::from_file(f).with_header(header("Content-Type", "application/gzip"))); return; }
                None => json_response(json!({ "error": "no guide for this playlist yet" })).with_status_code(404),
            },
            // TMDB matches for a playlist and every TMDB title, as the app's bulk chunks (one JSON object a line).
            (["catalogue", source, "tmdb"], true) if source.len() == 64 => {
                let since: i64 = query.get("since").and_then(|s| s.parse().ok()).unwrap_or(0);
                let path = base_dir().join("cache").join(format!("tmdb-{source}-{}.ndjson", std::process::id() as u64 * 1000 + rand_suffix()));
                let mut out = std::io::BufWriter::new(match std::fs::File::create(&path) { Ok(f) => f, Err(e) => { eprintln!("export: {e}"); return; } });
                self.matcher.export(source, since, |chunk| serde_json::to_writer(&mut out, chunk).is_ok() && std::io::Write::write_all(&mut out, b"\n").is_ok());
                drop(out);
                eprintln!("served TMDB matches to {}", request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default());
                let file = std::fs::File::open(&path);
                // The open handle keeps the data readable; the name can go now (Windows: after the reply).
                #[cfg(unix)] let _ = std::fs::remove_file(&path);
                match file {
                    Ok(f) => { let _ = request.respond(Response::from_file(f).with_header(header("Content-Type", "application/x-ndjson"))); }
                    Err(_) => { let _ = request.respond(Response::from_string("export failed").with_status_code(500)); }
                }
                #[cfg(not(unix))] let _ = std::fs::remove_file(&path);
                return;
            }
            // Title details (TMDB) and review scores (OMDb), fetched once with the account's keys and kept for every device.
            (["meta", "tmdb", ..], true) => {
                let raw = request.url().split_once('?').map(|(_, q)| q.to_string()).unwrap_or_default();
                let key = self.link.synced(|s| s.settings.get("tmdbKey").and_then(Value::as_str).unwrap_or("").trim().to_string());
                match self.meta.tmdb(path.trim_start_matches('/').trim_start_matches("meta/tmdb/"), &raw, &key) {
                    Some(body) => Response::from_data(body).with_header(header("Content-Type", "application/json")),
                    None => Response::from_string("not available").with_status_code(404),
                }
            }
            (["meta", "omdb"], true) => {
                let key = self.link.synced(|s| s.settings.get("omdbKey").and_then(Value::as_str).unwrap_or("").trim().to_string());
                match query.get("i").and_then(|i| self.meta.omdb(i, &key)) {
                    Some(body) => Response::from_data(body).with_header(header("Content-Type", "application/json")),
                    None => Response::from_string("not available").with_status_code(404),
                }
            }
            // A TMDB poster or backdrop, from core's cache.
            (["img", ..], true) => match self.images.get(path.trim_start_matches('/').trim_start_matches("img/")) {
                Some((file, kind)) => match std::fs::File::open(file) {
                    Ok(f) => {
                        let _ = request.respond(Response::from_file(f).with_header(header("Content-Type", kind))
                            .with_header(header("Cache-Control", "max-age=2592000")));
                        return;
                    }
                    Err(_) => Response::from_string("not found").with_status_code(404),
                },
                None => Response::from_string("not found").with_status_code(404),
            },
            // A helper doesn't stream: no torrent search, play or Stremio add-on.
            // The tray's actions on this machine (its menu): the same as the control panel's commands.
            (["action", "refresh-lists"], true) => { self.provider.refresh_now(); self.status_now.store(true, std::sync::atomic::Ordering::SeqCst); json_response(json!({ "ok": true, "message": "refreshing every list now" })) }
            (["action", "restart", name], true) => match services::Kind::from_name(name).ok_or_else(|| "unknown service".to_string()).and_then(|k| self.services.restart(k)) {
                Ok(m) => json_response(json!({ "ok": true, "message": m })),
                Err(e) => json_response(json!({ "ok": false, "message": e })),
            },
            (["manifest.json"] | ["stream", ..] | ["play", ..], true) if self.helper => Response::from_string("this core is a helper: it doesn't stream").with_status_code(404),
            (["manifest.json"], true) => json_response(self.search.manifest()),
            (["stream", kind, id], true) => json_response(self.search.streams(kind, id, &base)),
            (["play", _], true) => match self.search.play(&query, &host) {
                Ok(target) => Response::from_string("").with_status_code(302).with_header(header("Location", &target)),
                Err((code, message)) => { eprintln!("play: {message}"); Response::from_string(message).with_status_code(code) }
            },
            _ => Response::from_string("not found").with_status_code(404),
        };
        let _ = request.respond(response.with_header(header("Access-Control-Allow-Origin", "*")));
    }

    /// Whether core is up, signed in, and can reach Prowlarr and TorrServer. Details only with the secret.
    fn health(&self, authorised: bool) -> Value {
        let account = match self.link.status() {
            Status::SignedIn { .. } => "signed_in",
            Status::Pairing { .. } | Status::Waiting(_) => "signing_in",
            Status::Standalone => "standalone",
        };
        if !authorised { return json!({ "core": true, "version": env!("CARGO_PKG_VERSION"), "account": account }); }
        let (prowlarr, torrserver) = self.search.health();
        let (playlists, tmdb_key) = self.link.synced(|s| (s.playlists.len(),
            s.settings.get("tmdbKey").and_then(Value::as_str).is_some_and(|k| !k.is_empty())));
        json!({ "core": true, "version": env!("CARGO_PKG_VERSION"), "account": account,
            "synced": { "playlists": playlists, "tmdbKey": tmdb_key }, "provider": self.provider.status(),
            "prowlarr": prowlarr, "torrserver": torrserver, "matching": self.matcher.status(), "shared": self.shared.status(), "services": self.services.status(), "lan_linked": self.lan.linked(), "capabilities": link::capabilities(if self.helper { "helper" } else { "full" }), "role": if self.helper { "helper" } else { "full" } })
    }

    /// The page at core's address: sign-in while it isn't signed in, then where it stands. Never shows the secret.
    fn setup_page(&self) -> String {
        let body = match self.link.status() {
            Status::Standalone => "<p>Core isn't connected to a jaskTV account (no <code>account_server</code> in its config). \
                Devices can still use it by the address printed in its console.</p>".to_string(),
            Status::Waiting(message) => format!("<p>{}</p>", escape(&message)),
            Status::Pairing { code, link, fingerprint } => {
                let qr = qrcode::QrCode::new(link.as_bytes()).map(|q| q.render::<qrcode::render::svg::Color>()
                    .min_dimensions(240, 240).dark_color(qrcode::render::svg::Color("#111"))
                    .light_color(qrcode::render::svg::Color("#fff")).build()).unwrap_or_default();
                format!("<h2>Sign core in to your account</h2>\
                    <p>On your phone: jaskTV → Settings → Account → <b>Sign in a TV</b>, then scan this code.</p>\
                    <div class=qr>{qr}</div>\
                    <p>Or enter <b class=code>{} {}</b>. Your phone should show <b>{}</b>.</p>\
                    <p class=dim>The code changes every few minutes; this page shows the current one when reloaded.</p>",
                    &code[..4], &code[4..], escape(&fingerprint))
            }
            Status::SignedIn { email } => format!("<h2>Signed in</h2><p>Core is on the jaskTV account <b>{}</b>. \
                Your signed-in devices find it on their own.</p>", escape(&email)),
        };
        format!("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>\
            <title>jasktv-core</title><style>body{{font:16px/1.5 system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 16px;\
            background:#141413;color:#ecebe8}}h1{{font-size:22px}}.qr svg{{background:#fff;padding:12px;border-radius:12px}}\
            .code{{font-family:ui-monospace,monospace;letter-spacing:.1em}}.dim{{color:#a19d97;font-size:14px}}code{{color:#a19d97}}</style>\
            <h1>jasktv-core {}</h1>{body}", env!("CARGO_PKG_VERSION"))
    }
}

/// A few bits to keep two exports at once from sharing a file name.
fn rand_suffix() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    N.fetch_add(1, Ordering::Relaxed) % 1000
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("header")
}

fn json_response(v: Value) -> Reply {
    Response::from_string(v.to_string()).with_header(header("Content-Type", "application/json; charset=utf-8"))
}

fn html(text: String) -> Reply {
    Response::from_string(text).with_header(header("Content-Type", "text/html; charset=utf-8"))
}

fn escape(s: &str) -> String { s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;") }

fn decode(s: &str) -> String {
    let hex = |b: u8| (b as char).to_digit(16).map(|d| d as u8);
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(h), Some(l)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) { out.push(h * 16 + l); i += 3; continue; }
        }
        out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn parse_query(q: &str) -> HashMap<String, String> {
    q.split('&').filter(|p| !p.is_empty()).map(|p| match p.split_once('=') {
        Some((k, v)) => (decode(k), decode(v)),
        None => (decode(p), String::new()),
    }).collect()
}

/// `jasktv-core compare-matches <app database> [per kind]`: runs core's matcher over a sample of the app's own film
/// matches (each kind: matched by id, by title and year, ambiguous, unmatched) and lists where they disagree.
/// Needs the TMDB key in JASKTV_TMDB_KEY. Makes real TMDB requests (about 4 × per kind).
fn compare_matches(link: Arc<Link>, args: Vec<String>) {
    let db_path = args.first().unwrap_or_else(|| fail("usage: compare-matches <app database> [per kind]"));
    let per: usize = args.get(1).and_then(|n| n.parse().ok()).unwrap_or(25);
    let key = std::env::var("JASKTV_TMDB_KEY").unwrap_or_else(|_| fail("set JASKTV_TMDB_KEY"));
    let tmp = std::env::temp_dir().join(format!("jasktv-compare-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&tmp);
    let provider = provider::Provider::new(tmp.join("provider"), link.clone(), true);
    let m = matcher::Matcher::new(tmp.join("core.db"), link, provider, false).unwrap_or_else(|e| fail(&e));
    let app = rusqlite::Connection::open_with_flags(db_path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap_or_else(|e| fail(&e.to_string()));
    let (mut same, mut total) = (0, 0);
    for kind in ["validated-provider-tmdb", "exact-title-year", "ambiguous", "unmatched"] {
        let mut st = app.prepare("SELECT m.name, m.year, m.tmdbId, l.tmdbId FROM movie_identity_matches l JOIN movies m
            ON m.sourceId = l.sourceId AND m.streamId = l.streamId WHERE l.provenance = ? AND l.manual = 0 ORDER BY random() LIMIT ?").unwrap();
        let rows: Vec<(String, Option<i32>, Option<String>, Option<i64>)> = st.query_map(rusqlite::params![kind, per as i64],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))).unwrap().filter_map(Result::ok).collect();
        let mut agree = 0;
        for (name, year, hint, want) in &rows {
            match m.check(&key, name, *year, hint.as_deref()) {
                Ok((got, how)) if got == *want && how == kind => agree += 1,
                Ok((got, how)) => println!("  {kind}: {name:?} → app {want:?}, core {got:?} {how}"),
                Err(e) => println!("  {kind}: {name:?} → error {e}"),
            }
        }
        println!("{kind}: {agree} of {} agree", rows.len());
        same += agree; total += rows.len();
    }
    println!("overall: {same} of {total}");
    let _ = std::fs::remove_dir_all(&tmp);
}

/// The Xtream playlists on the account, by source key.
fn m_sources(link: &Link) -> Vec<String> {
    link.synced(|s| s.playlists.values().filter(|p| p["kind"].as_str() == Some("XTREAM"))
        .map(|p| provider::source_key("XTREAM", p["url"].as_str().unwrap_or(""), p["username"].as_str().unwrap_or(""))).collect())
}

/// Adds the services to jasktv-core.json (keeping everything else in it).
fn write_config_services(names: &[String]) {
    let path = base_dir().join("jasktv-core.json");
    let mut v: Value = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_else(|| json!({}));
    v["services"] = json!(names);
    // Core reaches the services it runs on this machine.
    if names.iter().any(|n| n == "prowlarr") && v["prowlarr_url"].as_str().unwrap_or("").is_empty() {
        v["prowlarr_url"] = json!(format!("http://127.0.0.1:{}", services::PROWLARR_PORT));
    }
    if let Err(e) = std::fs::write(&path, serde_json::to_string_pretty(&v).unwrap_or_default()) { fail(&format!("can't write {}: {e}", path.display())); }
}

/// Starts core with the machine: a systemd user service on Linux (survives logout with lingering), a hidden launcher
/// in the Startup folder on Windows.
fn install_service(role: &str) {
    let exe = std::env::current_exe().unwrap_or_else(|e| fail(&e.to_string()));
    #[cfg(target_os = "linux")]
    {
        // Low priority for CPU and disk, so whatever else the machine runs comes first; a helper also gets a CPU and
        // memory cap (it shares a box, e.g. a VPS with a game server).
        let mut limits = String::from("Nice=10\nIOSchedulingClass=idle\n");
        if role == "helper" { limits.push_str("CPUQuota=100%\nMemoryMax=1G\n"); }
        let root = std::env::var("USER").map(|u| u == "root").unwrap_or(false) || std::env::var("HOME").map(|h| h == "/root").unwrap_or(false);
        let unit = format!("[Unit]\nDescription=jasktv-core\nAfter=network-online.target\nWants=network-online.target\n\n\
            [Service]\nExecStart={}\nWorkingDirectory={}\nRestart=on-failure\nRestartSec=10\n{limits}\n[Install]\nWantedBy={}\n",
            exe.display(), base_dir().display(), if root { "multi-user.target" } else { "default.target" });
        let (dir, scope): (std::path::PathBuf, &[&str]) = if root { ("/etc/systemd/system".into(), &[]) } else {
            let home = std::env::var("HOME").unwrap_or_else(|_| fail("HOME isn't set"));
            (std::path::Path::new(&home).join(".config/systemd/user"), &["--user"])
        };
        let _ = std::fs::create_dir_all(&dir);
        std::fs::write(dir.join("jasktv-core.service"), unit).unwrap_or_else(|e| fail(&e.to_string()));
        let systemctl = |args: &[&str]| std::process::Command::new("systemctl").args(scope).args(args).status().is_ok_and(|s| s.success());
        if systemctl(&["daemon-reload"]) && systemctl(&["enable", "--now", "jasktv-core"]) {
            eprintln!("jasktv-core: installed and started (systemctl {}status jasktv-core).", if root { "" } else { "--user " });
        } else {
            eprintln!("jasktv-core: wrote {}; start it with: systemctl {}enable --now jasktv-core", dir.join("jasktv-core.service").display(), if root { "" } else { "--user " });
        }
        if !root { eprintln!("To keep it running when you're logged out: sudo loginctl enable-linger $USER"); }
    }
    #[cfg(windows)]
    {
        // Needs an administrator terminal: a boot task (runs with nobody signed in) and firewall rules can't be made
        // otherwise. `net session` only succeeds as administrator.
        let admin = std::process::Command::new("net").arg("session").stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null())
            .status().is_ok_and(|s| s.success());
        if !admin { fail("run this from an administrator terminal (right-click Terminal → Run as administrator), once"); }
        let dir = base_dir();
        // The launcher keeps core's output in core.log (a boot task has no console).
        let launcher = dir.join("run-core.cmd");
        std::fs::write(&launcher, "@echo off\r\ncd /d \"%~dp0\"\r\njasktv-core.exe 1>> core.log 2>&1\r\n").unwrap_or_else(|e| fail(&e.to_string()));
        let run = |args: &[&str]| std::process::Command::new(args[0]).args(&args[1..]).stdout(std::process::Stdio::null())
            .status().is_ok_and(|s| s.success());
        let task = format!("cmd.exe /c \"\"{}\"\"", launcher.display());
        if !run(&["schtasks", "/Create", "/F", "/TN", "jasktv-core", "/SC", "ONSTART", "/RU", "SYSTEM", "/RL", "HIGHEST", "/TR", &task]) {
            fail("couldn't create the startup task");
        }
        // Firewall: allow core and the services core runs, on private networks only. Windows' own "Block" rules from a
        // dismissed prompt are named after the program; those go.
        let programs = [("jasktv-core", exe.clone()),
            ("jaskTV TorrServer", dir.join(r"services\torrserver\TorrServer.exe")),
            ("jaskTV Prowlarr", dir.join(r"services\prowlarr\Prowlarr\Prowlarr.exe"))];
        for (name, program) in &programs {
            let file = program.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            run(&["netsh", "advfirewall", "firewall", "delete", "rule", &format!("name={file}")]);
            run(&["netsh", "advfirewall", "firewall", "delete", "rule", &format!("name={name}")]);
            let ok = run(&["netsh", "advfirewall", "firewall", "add", "rule", &format!("name={name}"), "dir=in", "action=allow",
                &format!("program={}", program.display()), "profile=private", "enable=yes"]);
            eprintln!("firewall: {} {}", if ok { "allowed" } else { "couldn't allow" }, program.display());
        }
        // The old login launcher, if there is one: the boot task replaces it.
        if let Ok(appdata) = std::env::var("APPDATA") {
            let _ = std::fs::remove_file(std::path::Path::new(&appdata).join(r"Microsoft\Windows\Start Menu\Programs\Startup\jasktv-core.vbs"));
        }
        eprintln!("jasktv-core: starts with Windows now (task \"jasktv-core\"), signed in or not.");
        eprintln!("Start it now without restarting: schtasks /Run /TN jasktv-core   (stop any copy already running first)");
        eprintln!("To undo: schtasks /Delete /TN jasktv-core /F, and remove the three \"jasktv-core\"/\"jaskTV …\" firewall rules.");
    }
    let _ = exe;
}


/// Core's live connection to the account server (the same one the apps hold): whenever records change, core reads
/// them and runs any command for it (collection "cmd", key "<core id>/<id>", value {action, at, …}), then writes the
/// command back with {done, ok, message}. A command older than five minutes is answered "expired", never run, so a
/// core coming back after a while (or reading the account from the start) doesn't replay old ones.
fn live(core: Arc<Core>) {
    use tungstenite::client::IntoClientRequest;
    let mut backoff = 5;
    loop {
        let Some((base, token)) = core.link.live_target() else { std::thread::sleep(Duration::from_secs(10)); continue };
        let url = format!("{}/v1/live", base.replacen("https://", "wss://", 1).replacen("http://", "ws://", 1));
        let mut request = match url.as_str().into_client_request() { Ok(r) => r, Err(e) => { eprintln!("live: {e}"); return; } };
        if let Ok(v) = format!("Bearer {token}").parse() { request.headers_mut().insert("Authorization", v); }
        match tungstenite::connect(request) {
            Ok((mut ws, _)) => {
                backoff = 5;
                let timeout = Some(Duration::from_secs(25));
                match ws.get_mut() {
                    tungstenite::stream::MaybeTlsStream::Plain(s) => { let _ = s.set_read_timeout(timeout); }
                    tungstenite::stream::MaybeTlsStream::Rustls(s) => { let _ = s.get_mut().set_read_timeout(timeout); }
                    _ => {}
                }
                let mut quiet = 0;
                loop {
                    match ws.read() {
                        Ok(tungstenite::Message::Text(text)) => {
                            quiet = 0;
                            if text.contains("\"t\":\"records\"") || text.contains("\"t\":\"hello\"") { run_commands(&core); }
                        }
                        Ok(_) => quiet = 0,
                        Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                            // Quiet: keep the connection alive (the server answers "ping" with "pong"); give up after a minute of silence.
                            quiet += 1;
                            if quiet > 2 || ws.send(tungstenite::Message::Text("ping".into())).is_err() { break; }
                        }
                        Err(_) => break,
                    }
                }
            }
            Err(tungstenite::Error::Http(r)) if r.status() == 401 => { std::thread::sleep(Duration::from_secs(30)); }
            Err(e) => { eprintln!("live: can't reach the account server: {e}"); }
        }
        std::thread::sleep(Duration::from_secs(backoff));
        backoff = (backoff * 2).min(120);
    }
}

fn run_commands(core: &Core) {
    core.link.catch_up_now();
    for (key, mut cmd) in core.link.take_commands() {
        let age = core.link.server_now() - cmd["at"].as_i64().unwrap_or(0);
        let action = cmd["action"].as_str().unwrap_or("").to_string();
        let result: Result<String, String> = if !(0..5 * 60_000).contains(&age) { Err("expired before this core saw it".into()) } else {
            match action.as_str() {
                "refresh-lists" => { core.provider.refresh_now(); Ok("refreshing every list now".into()) }
                "restart" => match cmd["service"].as_str().and_then(services::Kind::from_name) {
                    Some(k) => core.services.restart(k),
                    None => Err("unknown service".into()),
                },
                "add-trackers" => core.services.add_starter_trackers(),
                "status" => Ok("status sent".into()),
                _ => Err(format!("this core doesn't know \"{action}\"")),
            }
        };
        eprintln!("command {action}: {}", match &result { Ok(m) => m.clone(), Err(e) => format!("failed: {e}") });
        cmd["done"] = json!(core.link.server_now());
        cmd["ok"] = json!(result.is_ok());
        cmd["message"] = json!(match result { Ok(m) | Err(m) => m });
        if let Err(e) = core.link.put_record("cmd", &key, cmd) { eprintln!("command {action}: couldn't write the result: {e}"); }
        core.status_now.store(true, std::sync::atomic::Ordering::SeqCst);
    }
}
