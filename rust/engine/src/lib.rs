//! jaskTV's shared engine: logic that core and (later) every client run the same way.
//!
//! - [`crypto`]: the account's end-to-end encryption, byte-compatible with the Android app's `AccountCrypto`.
//! - [`account`]: a client for the account server (sign-in by pairing, sync).
//! - [`lan`]: copying rows from a device on the home network (the app's `LanLink` bulk copy).
//! - [`matching`]: provider titles to TMDB, the app's rules ported exactly.
//! - [`records`]: account sync v2, as the app's `SyncEngine` writes it.
//! - [`xtream`]: reading a provider's film and series lists as the app does.
//! - [`release`]: release names: resolution, HDR, episodes, ranking.

pub mod account;
pub mod crypto;
pub mod lan;
pub mod matching;
pub mod records;
pub mod release;
pub mod xtream;

/// [bytes] random bytes as lower-case hex.
pub fn random_hex(bytes: usize) -> String {
    use rand_core::RngCore;
    let mut b = vec![0u8; bytes];
    rand_core::OsRng.fill_bytes(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}
