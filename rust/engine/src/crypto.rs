//! The account's end-to-end encryption, byte for byte the same as the Android app's `AccountCrypto`:
//!
//! - Synced items: AES-256-GCM with the account key, the item's "kind/id" as associated data,
//!   stored as `"js1:" + base64(nonce ‖ ciphertext ‖ tag)`.
//! - Sign-in by pairing: the new device makes a P-256 key pair and shows the public half (X.509 DER, base64url,
//!   no padding). The phone does ECDH with it, hashes the shared x-coordinate with `"jasktv-pair:<code>"`
//!   (SHA-256), and seals the account key (base64) as the item kind "pair", id <code>.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine as _;
use p256::pkcs8::{DecodePublicKey, EncodePublicKey};
use p256::{PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

#[derive(Debug)]
pub struct CryptoError(pub &'static str);

impl std::fmt::Display for CryptoError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { f.write_str(self.0) }
}
impl std::error::Error for CryptoError {}

pub type AccountKey = [u8; 32];

/// Seals [plain] for the item `kind/id`.
pub fn seal(key: &AccountKey, kind: &str, id: &str, plain: &str) -> String {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let cipher = Aes256Gcm::new(key.into());
    let aad = format!("{kind}/{id}");
    let sealed = cipher.encrypt(&Nonce::from(nonce), Payload { msg: plain.as_bytes(), aad: aad.as_bytes() })
        .expect("AES-GCM sealing can't fail with a 32-byte key");
    let mut out = nonce.to_vec();
    out.extend_from_slice(&sealed);
    format!("js1:{}", STANDARD.encode(out))
}

/// Seals bytes (an account file) for `kind/id`: nonce ‖ ciphertext ‖ tag, raw (no text encoding).
pub fn seal_bytes(key: &AccountKey, kind: &str, id: &str, plain: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let aad = format!("{kind}/{id}");
    let sealed = Aes256Gcm::new(key.into()).encrypt(&Nonce::from(nonce), Payload { msg: plain, aad: aad.as_bytes() })
        .expect("AES-GCM sealing can't fail with a 32-byte key");
    let mut out = nonce.to_vec();
    out.extend_from_slice(&sealed);
    out
}

/// Opens what [seal_bytes] made for the same `kind/id`.
pub fn open_bytes(key: &AccountKey, kind: &str, id: &str, sealed: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if sealed.len() <= 28 { return Err(CryptoError("truncated file")); }
    let aad = format!("{kind}/{id}");
    let nonce: [u8; 12] = sealed[..12].try_into().expect("12 bytes");
    Aes256Gcm::new(key.into()).decrypt(&Nonce::from(nonce), Payload { msg: &sealed[12..], aad: aad.as_bytes() })
        .map_err(|_| CryptoError("wrong key, or the file was changed"))
}

/// Opens what [seal] made for the same `kind/id`.
pub fn open(key: &AccountKey, kind: &str, id: &str, sealed: &str) -> Result<String, CryptoError> {
    let body = sealed.strip_prefix("js1:").ok_or(CryptoError("unsupported item"))?;
    let bytes = STANDARD.decode(body).map_err(|_| CryptoError("item isn't base64"))?;
    if bytes.len() <= 28 { return Err(CryptoError("truncated item")); }
    let cipher = Aes256Gcm::new(key.into());
    let aad = format!("{kind}/{id}");
    let nonce: [u8; 12] = bytes[..12].try_into().expect("12 bytes");
    let plain = cipher.decrypt(&Nonce::from(nonce), Payload { msg: &bytes[12..], aad: aad.as_bytes() })
        .map_err(|_| CryptoError("wrong key, or the item was changed"))?;
    String::from_utf8(plain).map_err(|_| CryptoError("item isn't text"))
}

/// The new device's side of a pairing: a one-time key pair.
pub struct PairKey { secret: SecretKey }

impl PairKey {
    pub fn new() -> Self { PairKey { secret: SecretKey::random(&mut OsRng) } }

    /// The public half as the app writes it: X.509 SubjectPublicKeyInfo DER, base64url without padding.
    pub fn public_text(&self) -> String {
        let der = self.secret.public_key().to_public_key_der().expect("P-256 public key encodes");
        URL_SAFE_NO_PAD.encode(der.as_bytes())
    }

    /// The account key the phone sealed for this device.
    pub fn open_from_phone(&self, phone_key: &str, code: &str, sealed: &str) -> Result<AccountKey, CryptoError> {
        let shared = pair_secret(&self.secret, phone_key, code)?;
        let text = open(&shared, "pair", code, sealed)?;
        let key = STANDARD.decode(text.trim()).map_err(|_| CryptoError("account key isn't base64"))?;
        key.try_into().map_err(|_| CryptoError("account key isn't 32 bytes"))
    }
}

impl Default for PairKey { fn default() -> Self { Self::new() } }

fn pair_secret(mine: &SecretKey, theirs: &str, code: &str) -> Result<AccountKey, CryptoError> {
    let der = URL_SAFE_NO_PAD.decode(theirs.trim_end_matches('=')).map_err(|_| CryptoError("phone key isn't base64"))?;
    let public = PublicKey::from_public_key_der(&der).map_err(|_| CryptoError("phone key isn't a P-256 key"))?;
    let shared = p256::ecdh::diffie_hellman(mine.to_nonzero_scalar(), public.as_affine());
    let mut hash = Sha256::new();
    hash.update(shared.raw_secret_bytes());
    hash.update(format!("jasktv-pair:{code}").as_bytes());
    Ok(hash.finalize().into())
}

/// The six digits both screens show while pairing, so a code typed by hand is checked against its device.
pub fn pair_fingerprint(public_text: &str) -> String {
    let h = Sha256::digest(public_text.as_bytes());
    let n = ((h[0] as u32) << 16) | ((h[1] as u32) << 8) | h[2] as u32;
    format!("{:06}", n % 1_000_000)
}

/// A random secret, base64url (for core's access secret).
pub fn random_secret(bytes: usize) -> String {
    let mut b = vec![0u8; bytes];
    OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

pub fn key_to_text(key: &AccountKey) -> String { STANDARD.encode(key) }

pub fn key_from_text(text: &str) -> Option<AccountKey> {
    STANDARD.decode(text).ok().and_then(|b| b.try_into().ok())
}

/// The phone's side of a pairing (what the app's `sealForTv` does). Core never approves anything; this exists so
/// the engine can check itself against the app's format.
pub fn seal_for_device(account_key: &AccountKey, device_key: &str, code: &str) -> Result<(String, String), CryptoError> {
    let phone = SecretKey::random(&mut OsRng);
    let phone_text = URL_SAFE_NO_PAD.encode(phone.public_key().to_public_key_der().expect("encodes").as_bytes());
    let shared = pair_secret(&phone, device_key, code)?;
    Ok((phone_text, seal(&shared, "pair", code, &STANDARD.encode(account_key))))
}

// ---- Email and password (as the app's `passwordKeys`, `unwrapWithKey` and `Vault`) ----

const PASSWORD_ITERATIONS: u32 = 300_000;

fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// PBKDF2-HMAC-SHA256, one 32-byte block (as the app writes it out).
pub fn pbkdf2(password: &str, salt: &[u8], iterations: u32) -> [u8; 32] {
    let mut first = salt.to_vec();
    first.extend_from_slice(&[0, 0, 0, 1]);
    let mut u = hmac_sha256(password.as_bytes(), &first);
    let mut out = u;
    for _ in 1..iterations {
        u = hmac_sha256(password.as_bytes(), &u);
        for (o, x) in out.iter_mut().zip(u.iter()) { *o ^= x; }
    }
    out
}

/// What a password gives a device: the login key (sent to the server) and the key that unlocks the account key
/// (never sent). Slow on purpose: 300,000 rounds.
pub struct PasswordKeys { pub login_key: String, pub wrap_key: AccountKey }

pub fn password_keys(email: &str, password: &str) -> PasswordKeys {
    let master = pbkdf2(password, format!("jasktv-account:{}", email.trim().to_lowercase()).as_bytes(), PASSWORD_ITERATIONS);
    PasswordKeys { login_key: STANDARD.encode(hmac_sha256(&master, b"jasktv-login")), wrap_key: hmac_sha256(&master, b"jasktv-wrap") }
}

/// The account key from the vault's password copy ("jk1:…"), or None when the password doesn't open it.
pub fn unwrap_with_key(sealed: &str, wrap_key: &AccountKey) -> Option<AccountKey> {
    let bytes = STANDARD.decode(sealed.strip_prefix("jk1:")?).ok()?;
    if bytes.len() <= 28 { return None; }
    let nonce: [u8; 12] = bytes[..12].try_into().ok()?;
    let plain = Aes256Gcm::new(wrap_key.into())
        .decrypt(&Nonce::from(nonce), Payload { msg: &bytes[12..], aad: b"jasktv-vault-pw" }).ok()?;
    plain.try_into().ok()
}

/// The vault's password copy: the vault is "<password copy>|<recovery copy>" (a bare "jv1:…" has no password copy).
pub fn vault_password_part(vault: &str) -> Option<&str> {
    let (password, _) = vault.split_once('|')?;
    (!password.is_empty()).then_some(password)
}

/// The app's `wrapWithKey` (core never writes a vault; this lets the engine be checked against the app).
pub fn wrap_with_key(account_key: &AccountKey, wrap_key: &AccountKey) -> String {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let sealed = Aes256Gcm::new(wrap_key.into())
        .encrypt(&Nonce::from(nonce), Payload { msg: account_key, aad: b"jasktv-vault-pw" }).expect("seals");
    let mut out = nonce.to_vec();
    out.extend_from_slice(&sealed);
    format!("jk1:{}", STANDARD.encode(out))
}
