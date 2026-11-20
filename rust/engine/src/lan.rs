//! The asking side of the app's `LanLink` bulk copy: dial a device of this account on the home network, prove we hold
//! the account key, and read the rows it sends (as `LanCatalogue` writes them). Frames are JSON lines, each sealed
//! with the account key as item "lan"/"frame":
//!
//! 1. we send `{from, at, nonce, bulk: {source, part}}`;
//! 2. it answers `{from, at, nonce, sid: <our nonce>}`;
//! 3. we confirm `{from, at, ping: 0, sid: <its nonce>}`, and it streams `{…, chunk: {table, columns, rows, done, found}}`.

use crate::crypto::{self, AccountKey};
use crate::random_hex;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const MAX_FRAME: usize = 64 * 1024 * 1024;

fn seal(key: &AccountKey, frame: &Value) -> String { crypto::seal(key, "lan", "frame", &frame.to_string()) }

fn open(key: &AccountKey, line: &str) -> Option<Value> {
    serde_json::from_str(&crypto::open(key, "lan", "frame", line.trim_end()).ok()?).ok()
}

fn now() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

/// Asks the device [device] (or whichever answers) at [addr] for [part] of the playlist [source] and hands each chunk (the JSON object:
/// table, columns, rows) to [on_chunk]. Ok(true): it arrived complete; Ok(false): that device doesn't have it.
pub fn fetch(key: &AccountKey, me: &str, device: Option<&str>, addr: SocketAddr, source: &str, part: &str,
             mut on_chunk: impl FnMut(&Value)) -> Result<bool, String> {
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(1500)).map_err(|e| format!("can't reach it ({})", e.kind()))?;
    stream.set_nodelay(true).ok();
    stream.set_read_timeout(Some(Duration::from_secs(60))).ok();
    let mut out = stream.try_clone().map_err(|e| e.to_string())?;
    let mut reader = BufReader::with_capacity(256 * 1024, stream);
    let mine = random_hex(16);
    let mut send = |f: Value| -> Result<(), String> {
        out.write_all(seal(key, &f).as_bytes()).and_then(|_| out.write_all(b"\n")).map_err(|e| format!("connection lost ({})", e.kind()))
    };
    send(json!({ "from": me, "at": now(), "nonce": mine, "bulk": { "source": source, "part": part } }))?;
    let mut theirs: Option<String> = None;
    let mut line = String::new();
    loop {
        line.clear();
        let n = std::io::Read::take(&mut reader, MAX_FRAME as u64).read_line(&mut line).map_err(|e| format!("connection lost ({})", e.kind()))?;
        if n == 0 { return Err("it hung up (not this account's device, or busy)".into()); }
        let f = open(key, &line).ok_or("a frame that isn't sealed with this account's key")?;
        // [device] None: whichever device answers (learned from its first frame).
        let from = f["from"].as_str().unwrap_or_default().to_string();
        if device.is_some_and(|d| d != from) || f["sid"].as_str() != Some(mine.as_str()) { return Err("a frame from the wrong link".into()); }
        if theirs.is_none() {
            let nonce = f["nonce"].as_str().ok_or("no handshake")?.to_string();
            send(json!({ "from": me, "at": now(), "ping": 0, "sid": nonce }))?;
            theirs = Some(nonce);
            continue;
        }
        let Some(chunk) = f.get("chunk") else { continue };
        if chunk["done"].as_bool() == Some(true) { return Ok(chunk["found"].as_bool().unwrap_or(true)); }
        on_chunk(chunk);
    }
}
