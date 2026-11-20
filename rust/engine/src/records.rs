//! Account sync v2 ("records"), as the app's `SyncEngine` does it: every fact is one record, sealed with the account
//! key; the server keeps the newest stamp per record.
//!
//! - A record's id: HMAC-SHA256(account key, "<collection>\0<key>"), first 18 bytes, base64url without padding.
//! - Its data: `{"c": collection, "k": key, "s": stamp, "v": value}` (no "v": removed), sealed as item "r2"/<id>.
//! - A stamp: "<13-digit ms>-<4 hex counter>-<8-char device tag>"; they compare as text in time order.
//!
//! Core only reads the app's collections and only writes its own: a wrongly stamped write to a collection the app
//! owns would override what the person set on every device.

use crate::account::{AccountClient, AccountError};
use crate::crypto::{self, AccountKey};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::Sha256;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Deserialize, Serialize, Debug, Clone)]
pub struct WireRecord { pub id: String, pub stamp: String, pub data: String, #[serde(default)] pub seq: i64 }

#[derive(Deserialize, Debug)]
pub struct PullPage { pub seq: i64, pub records: Vec<WireRecord>, pub more: bool, pub now: i64 }

#[derive(Deserialize, Debug)]
pub struct Accepted { pub id: String, pub seq: i64 }

#[derive(Deserialize, Debug)]
pub struct Rejected { pub id: String, pub reason: String }

#[derive(Deserialize, Debug)]
pub struct PutResult { pub seq: i64, pub accepted: Vec<Accepted>, pub stale: Vec<WireRecord>, #[serde(default)] pub rejected: Vec<Rejected>, pub now: i64 }

#[derive(Serialize, Deserialize)]
struct Sealed {
    c: String,
    k: String,
    s: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    v: Option<Value>,
}

/// One opened record.
#[derive(Debug, Clone)]
pub struct Record { pub coll: String, pub key: String, pub stamp: String, pub value: Option<Value>, pub seq: i64 }

pub fn id_of(account_key: &AccountKey, coll: &str, key: &str) -> String {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(account_key).expect("any key length");
    mac.update(format!("{coll}\u{0}{key}").as_bytes());
    URL_SAFE_NO_PAD.encode(&mac.finalize().into_bytes()[..18])
}

/// Opens a record and checks it is what it claims (its stamp and id are inside the seal, so the server can't swap them).
pub fn open(account_key: &AccountKey, w: &WireRecord) -> Option<Record> {
    let plain = crypto::open(account_key, "r2", &w.id, &w.data).ok()?;
    let s: Sealed = serde_json::from_str(&plain).ok()?;
    (s.s == w.stamp && id_of(account_key, &s.c, &s.k) == w.id)
        .then(|| Record { coll: s.c, key: s.k, stamp: s.s, value: s.v.filter(|v| !v.is_null()), seq: w.seq })
}

pub fn seal(account_key: &AccountKey, coll: &str, key: &str, stamp: &str, value: Option<Value>) -> WireRecord {
    let id = id_of(account_key, coll, key);
    let plain = serde_json::to_string(&Sealed { c: coll.into(), k: key.into(), s: stamp.into(), v: value }).expect("serialises");
    WireRecord { data: crypto::seal(account_key, "r2", &id, &plain), id, stamp: stamp.into(), seq: 0 }
}

/// Stamps from this machine's clock, corrected by the server's, never going backwards.
pub struct StampClock { tag: String, state: Mutex<(i64, i64, u32)> } // (offset, last ms, counter)

impl StampClock {
    pub fn new(tag: &str) -> Self {
        assert!(tag.len() == 8 && tag.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'), "stamp tag");
        StampClock { tag: tag.into(), state: Mutex::new((0, 0, 0)) }
    }
    pub fn observe_server(&self, server_now: i64) {
        let mut s = self.state.lock().unwrap();
        let measured = server_now - wall();
        s.0 = if s.0 == 0 { measured } else { (s.0 * 3 + measured) / 4 };
    }
    pub fn next(&self) -> String {
        let mut s = self.state.lock().unwrap();
        let ms = (wall() + s.0).max(1);
        if ms > s.1 { s.1 = ms; s.2 = 0 } else { s.2 += 1 }
        format!("{:013}-{:04x}-{}", s.1.min(9_999_999_999_999), s.2.min(0xffff), self.tag)
    }
}

fn wall() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

impl AccountClient {
    pub fn records_pull(&self, token: &str, since: i64, limit: u32) -> Result<PullPage, AccountError> {
        self.call("GET", &format!("/v2/records?since={since}&limit={limit}"), Some(token), None)
    }
    pub fn records_put(&self, token: &str, records: &[WireRecord]) -> Result<PutResult, AccountError> {
        let list: Vec<Value> = records.iter().map(|r| json!({ "id": r.id, "stamp": r.stamp, "data": r.data })).collect();
        self.call("POST", "/v2/records", Some(token), Some(json!({ "records": list })))
    }

    /// Every record after [since] (all pages), opened; unreadable ones are skipped. Returns the newest seq seen.
    pub fn records_all(&self, token: &str, key: &AccountKey, clock: &StampClock, mut since: i64) -> Result<(Vec<Record>, i64), AccountError> {
        let mut out = Vec::new();
        loop {
            let page = self.records_pull(token, since, 1000)?;
            clock.observe_server(page.now);
            let last = page.records.iter().map(|r| r.seq).max().unwrap_or(since);
            out.extend(page.records.iter().filter_map(|w| open(key, w)));
            since = since.max(last);
            if !page.more || page.records.is_empty() { break; }
        }
        Ok((out, since))
    }
}
