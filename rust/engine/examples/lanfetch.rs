//! Asks a jaskTV device (or core) at <ip:port> for a playlist's TMDB part, as another device would; prints row counts.
//! The account key comes from JASKTV_KEY (base64), never the command line.
fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let key = jasktv_engine::crypto::key_from_text(&std::env::var("JASKTV_KEY").expect("JASKTV_KEY")).expect("key");
    let mut counts = std::collections::BTreeMap::new();
    let r = jasktv_engine::lan::fetch(&key, "test-device", None, a[0].parse().unwrap(), &a[1], "tmdb", |c| {
        *counts.entry(c["table"].as_str().unwrap_or("?").to_string()).or_insert(0) += c["rows"].as_array().map_or(0, |r| r.len());
    });
    println!("{r:?} {counts:?}");
}
