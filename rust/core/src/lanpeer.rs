//! Core as a member of the devices' own home-network mesh (the app's `LanLink`): it announces itself like a device
//! (`_jasktv._tcp`, "jaskTV-<id>", TXT d = core's device id), answers the same sealed handshake, keeps links alive
//! with heartbeats, and serves bulk copies. So a phone or TV, even a build that knows nothing about core, copies
//! core's TMDB matches the way it copies another device's, over the link it already has.
//!
//! Frames are JSON lines sealed with the account key; see `jasktv_engine::lan` for the handshake. Before a connection
//! proves it holds the key it gets 5 s and 64 KB; at most 16 such connections at once.

use crate::link::Link;
use crate::matcher::Matcher;
use jasktv_engine::crypto::{self, AccountKey};
use jasktv_engine::random_hex;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const MAX_HELLO: u64 = 64 * 1024;
const MAX_FRAME: u64 = 8 * 1024 * 1024;

pub struct LanPeer { link: Arc<Link>, matcher: Arc<Matcher>, unproven: AtomicUsize, linked: Mutex<Vec<String>> }

fn now() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

impl LanPeer {
    pub fn new(link: Arc<Link>, matcher: Arc<Matcher>) -> Arc<Self> {
        Arc::new(LanPeer { link, matcher, unproven: AtomicUsize::new(0), linked: Mutex::new(Vec::new()) })
    }

    /// Devices linked to core right now.
    pub fn linked(&self) -> Vec<String> { self.linked.lock().unwrap().clone() }

    /// Listens and announces for the life of core (once it's signed in: the account key is the proof both ways).
    pub fn run(self: Arc<Self>) {
        let (me, key) = loop {
            if let Some(k) = self.link.identity() { break k; }
            std::thread::sleep(Duration::from_secs(10));
        };
        let listener = match TcpListener::bind("0.0.0.0:0") { Ok(l) => l, Err(e) => { eprintln!("lan: can't listen: {e}"); return; } };
        let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
        // Announced like a device, so the app's discovery finds it.
        let mdns = mdns_sd::ServiceDaemon::new();
        if let Ok(daemon) = &mdns {
            let host = format!("jasktv-core-{}.local.", &me[..8]);
            let ips: Vec<std::net::IpAddr> = crate::link::lan_addresses().iter().filter_map(|a| a.parse().ok()).collect();
            let props = [("d", me.as_str())];
            match mdns_sd::ServiceInfo::new("_jasktv._tcp.local.", &format!("jaskTV-{}", &me[..8]), &host, &ips[..], port, &props[..]) {
                Ok(info) => { if let Err(e) = daemon.register(info) { eprintln!("lan: can't announce: {e}"); } }
                Err(e) => eprintln!("lan: can't announce: {e}"),
            }
        }
        eprintln!("lan: listening for devices on port {port}");
        for stream in listener.incoming().flatten() {
            if self.unproven.fetch_add(1, Ordering::SeqCst) >= 16 { self.unproven.fetch_sub(1, Ordering::SeqCst); continue; }
            let (this, me, key) = (self.clone(), me.clone(), key);
            std::thread::spawn(move || {
                let mut proven = false;
                let device = this.serve(stream, &me, &key, &mut proven);
                if !proven { this.unproven.fetch_sub(1, Ordering::SeqCst); }
                if let Some(d) = device { this.linked.lock().unwrap().retain(|x| *x != d); }
            });
        }
        drop(mdns);
    }

    /// One connection, dialled by a device. Returns the device it linked (to unlist when it closes).
    fn serve(&self, stream: TcpStream, me: &str, key: &AccountKey, proven: &mut bool) -> Option<String> {
        stream.set_nodelay(true).ok();
        stream.set_read_timeout(Some(Duration::from_secs(5))).ok();
        let out = Arc::new(Mutex::new(stream.try_clone().ok()?));
        let mut reader = BufReader::with_capacity(64 * 1024, stream.try_clone().ok()?);
        let mine = random_hex(16);
        let send = |out: &Mutex<TcpStream>, theirs: &str, mut f: Value| -> bool {
            f["from"] = json!(me);
            f["at"] = json!(now());
            f["sid"] = json!(theirs);
            let line = crypto::seal(key, "lan", "frame", &f.to_string());
            let mut o = out.lock().unwrap();
            o.write_all(line.as_bytes()).and_then(|_| o.write_all(b"\n")).is_ok()
        };
        let read = |reader: &mut BufReader<TcpStream>, max: u64| -> Option<Value> {
            let mut line = String::new();
            let n = Read::take(&mut *reader, max).read_line(&mut line).ok()?;
            if n == 0 || !line.ends_with('\n') { return None; }
            serde_json::from_str(&crypto::open(key, "lan", "frame", line.trim_end()).ok()?).ok()
        };
        // Step 1: their number (and maybe a bulk request). A stranger's frame doesn't open: hang up.
        let hello = read(&mut reader, MAX_HELLO)?;
        let device = hello["from"].as_str()?.to_string();
        let theirs = hello["nonce"].as_str()?.to_string();
        if hello.get("sid").is_some_and(|s| !s.is_null()) || device == me { return None; }
        // Step 2: ours.
        if !send(&out, &theirs, json!({ "nonce": mine })) { return None; }
        // Step 3: they confirm with our number.
        let confirm = read(&mut reader, MAX_HELLO)?;
        if confirm["sid"].as_str() != Some(mine.as_str()) || confirm["from"].as_str() != Some(device.as_str()) { return None; }
        *proven = true;
        self.unproven.fetch_sub(1, Ordering::SeqCst);

        if let Some(bulk) = hello.get("bulk").filter(|b| !b.is_null()) {
            let (source, part) = (bulk["source"].as_str().unwrap_or(""), bulk["part"].as_str().unwrap_or(""));
            stream.set_write_timeout(Some(Duration::from_secs(60))).ok();
            if part == "tmdb" && source.len() == 64 {
                eprintln!("lan: sending TMDB matches to device {:.8}", device);
                let mut ok = true;
                self.matcher.export(source, 0, |chunk| {
                    if chunk.get("done").is_some() { return true; } // the closing line is ours to write, below
                    ok = send(&out, &theirs, json!({ "chunk": chunk }));
                    ok
                });
                if ok { send(&out, &theirs, json!({ "chunk": { "done": true, "found": true } })); }
            } else {
                // Channels and the film and series rows are the app's own tables: devices copy those from each other.
                send(&out, &theirs, json!({ "chunk": { "done": true, "found": false } }));
            }
            return None;
        }

        // A link: heartbeats both ways until it goes quiet.
        self.linked.lock().unwrap().push(device.clone());
        stream.set_read_timeout(Some(Duration::from_secs(20))).ok();
        let alive = Arc::new(std::sync::atomic::AtomicBool::new(true));
        {
            let (out, theirs, alive, me, key) = (out.clone(), theirs.clone(), alive.clone(), me.to_string(), *key);
            std::thread::spawn(move || while alive.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_secs(5));
                let f = json!({ "from": me, "at": now(), "sid": theirs, "ping": Instant::now().elapsed().as_millis() as i64 });
                let line = crypto::seal(&key, "lan", "frame", &f.to_string());
                let mut o = out.lock().unwrap();
                if o.write_all(line.as_bytes()).and_then(|_| o.write_all(b"\n")).is_err() { break; }
            });
        }
        while let Some(f) = read(&mut reader, MAX_FRAME) {
            if f["sid"].as_str() != Some(mine.as_str()) || f["from"].as_str() != Some(device.as_str()) { continue; }
            if let Some(p) = f.get("ping").filter(|p| !p.is_null()) {
                if !send(&out, &theirs, json!({ "pong": p })) { break; }
            }
        }
        alive.store(false, Ordering::SeqCst);
        let _ = out.lock().unwrap().shutdown(std::net::Shutdown::Both);
        Some(device)
    }
}
