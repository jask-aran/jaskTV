//! A client for the jaskTV account server: sign-in by pairing (as a TV does), who's signed in, and sync.
//! Blocking calls; run them off any thread that serves requests.

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::time::Duration;

#[derive(Debug)]
pub struct AccountError { pub status: u16, pub code: String, pub message: String }

impl std::fmt::Display for AccountError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        if self.status == 0 { write!(f, "{}", self.message) } else { write!(f, "{} (HTTP {}, {})", self.message, self.status, self.code) }
    }
}
impl std::error::Error for AccountError {}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PairStart { pub code: String, pub secret: String, pub expires_at: i64 }

#[derive(Deserialize, Serialize, Debug, Clone)]
pub struct User { pub id: String, pub email: String, #[serde(default)] pub name: Option<String> }

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PairResult { pub token: String, pub device_id: String, pub user: User, pub phone_key: String, pub sealed_key: String }

#[derive(Deserialize, Debug, Clone)]
pub struct PairTake { pub state: String, #[serde(default)] pub result: Option<PairResult> }

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SignIn { pub token: String, pub device_id: String, pub user: User }

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RemoteItem { pub kind: String, pub id: String, pub rev: i64, pub updated_at: i64, pub deleted: bool, #[serde(default)] pub data: Option<String> }

#[derive(Deserialize, Debug, Clone)]
pub struct PullResult { pub rev: i64, pub items: Vec<RemoteItem> }

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PushItem { pub kind: String, pub id: String, pub base_rev: i64, pub updated_at: i64, pub deleted: bool, pub data: Option<String> }

#[derive(Deserialize, Debug, Clone)]
pub struct Accepted { pub kind: String, pub id: String, pub rev: i64 }

#[derive(Deserialize, Debug, Clone)]
pub struct PushResult { pub rev: i64, pub accepted: Vec<Accepted>, pub conflicts: Vec<RemoteItem> }

pub struct AccountClient { base: String, http: ureq::Agent }

impl AccountClient {
    pub fn new(base: &str) -> Self {
        AccountClient {
            base: base.trim_end_matches('/').to_string(),
            http: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(30)).build(),
        }
    }

    pub fn base(&self) -> &str { &self.base }

    pub(crate) fn call<T: serde::de::DeserializeOwned>(&self, method: &str, path: &str, token: Option<&str>, body: Option<serde_json::Value>) -> Result<T, AccountError> {
        let mut req = self.http.request(method, &format!("{}{path}", self.base));
        if let Some(t) = token { req = req.set("Authorization", &format!("Bearer {t}")); }
        let result = match body { Some(b) => req.send_json(b), None => req.call() };
        match result {
            Ok(resp) => resp.into_json().map_err(|e| AccountError { status: 0, code: "bad_response".into(), message: e.to_string() }),
            Err(ureq::Error::Status(status, resp)) => {
                let body: serde_json::Value = resp.into_json().unwrap_or_default();
                Err(AccountError {
                    status,
                    code: body["error"].as_str().unwrap_or("error").to_string(),
                    message: body["message"].as_str().unwrap_or("The account server refused").to_string(),
                })
            }
            Err(e) => Err(AccountError { status: 0, code: "network".into(), message: format!("Can't reach the account server: {e}") }),
        }
    }

    /// Stores an account file (already sealed with the account key) as [name].
    pub fn put_blob(&self, token: &str, name: &str, bytes: &[u8]) -> Result<(), AccountError> {
        let agent = ureq::AgentBuilder::new().timeout_connect(std::time::Duration::from_secs(15)).timeout(std::time::Duration::from_secs(600)).build();
        match agent.put(&format!("{}/v1/blobs/{name}", self.base)).set("Authorization", &format!("Bearer {token}"))
            .set("Content-Type", "application/octet-stream").send_bytes(bytes) {
            Ok(_) => Ok(()),
            Err(ureq::Error::Status(status, resp)) => {
                let body: serde_json::Value = resp.into_json().unwrap_or_default();
                Err(AccountError { status, code: body["error"].as_str().unwrap_or("error").into(), message: body["message"].as_str().unwrap_or("refused").into() })
            }
            Err(e) => Err(AccountError { status: 0, code: "network".into(), message: format!("Can't reach the account server: {e}") }),
        }
    }

    /// An account file's sealed bytes, or None when there's no such file.
    pub fn get_blob(&self, token: &str, name: &str) -> Result<Option<Vec<u8>>, AccountError> {
        let agent = ureq::AgentBuilder::new().timeout_connect(std::time::Duration::from_secs(15)).timeout(std::time::Duration::from_secs(600)).build();
        match agent.get(&format!("{}/v1/blobs/{name}", self.base)).set("Authorization", &format!("Bearer {token}")).call() {
            Ok(resp) => {
                let mut out = Vec::new();
                std::io::Read::read_to_end(&mut std::io::Read::take(resp.into_reader(), 200 << 20), &mut out)
                    .map_err(|e| AccountError { status: 0, code: "network".into(), message: e.to_string() })?;
                Ok(Some(out))
            }
            Err(ureq::Error::Status(404, _)) => Ok(None),
            Err(ureq::Error::Status(status, _)) => Err(AccountError { status, code: "error".into(), message: format!("HTTP {status}") }),
            Err(e) => Err(AccountError { status: 0, code: "network".into(), message: format!("Can't reach the account server: {e}") }),
        }
    }

    /// Starts a sign-in as a new device of [kind]; the phone approves the returned code.
    pub fn pair_start(&self, device_key: &str, name: &str, kind: &str) -> Result<PairStart, AccountError> {
        self.call("POST", "/v1/pair/start", None, Some(json!({ "tvKey": device_key, "device": { "name": name, "kind": kind } })))
    }

    /// Whether the phone has approved yet ("pending", "approved" with the result, or "expired").
    pub fn pair_take(&self, code: &str, secret: &str) -> Result<PairTake, AccountError> {
        self.call("POST", &format!("/v1/pair/{code}/take"), None, Some(json!({ "secret": secret })))
    }

    /// Signs in with email and the login key from [crate::crypto::password_keys], as a new device of [kind].
    pub fn login(&self, email: &str, login_key: &str, name: &str, kind: &str) -> Result<SignIn, AccountError> {
        self.call("POST", "/v1/auth/login", None, Some(json!({ "email": email.trim(), "loginKey": login_key, "device": { "name": name, "kind": kind } })))
    }

    /// Ends this device's session (it leaves the account's device list).
    pub fn sign_out(&self, token: &str) -> Result<serde_json::Value, AccountError> { self.call("POST", "/v1/auth/signout", Some(token), Some(json!({}))) }

    pub fn me(&self, token: &str) -> Result<serde_json::Value, AccountError> { self.call("GET", "/v1/me", Some(token), None) }

    pub fn pull(&self, token: &str, since: i64) -> Result<PullResult, AccountError> {
        self.call("GET", &format!("/v1/sync?since={since}"), Some(token), None)
    }

    pub fn push(&self, token: &str, items: Vec<PushItem>) -> Result<PushResult, AccountError> {
        self.call("POST", "/v1/sync", Some(token), Some(json!({ "items": items })))
    }

    /// The page a phone opens for a pairing QR code (the same link the TV shows).
    pub fn pair_link(&self, code: &str, device_key: &str) -> String { format!("{}/pair#c={code}&k={device_key}", self.base) }
}
