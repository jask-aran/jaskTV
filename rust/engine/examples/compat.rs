//! Checks the engine's encryption against the Android app's (run by hand with a Java copy of AccountCrypto):
//!   device                       print a pairing key, then read "phoneKey sealed code" and print the account key
//!   phone <deviceKey> <code> <key>  print "phoneKey sealed" (the phone's side)
//!   seal <key> <kind> <id> <text>   print the sealed item
//!   open <key> <kind> <id> <sealed> print the text
use jasktv_engine::crypto::{self, PairKey};
use std::io::BufRead;

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let key = |s: &str| crypto::key_from_text(s).expect("32-byte base64 key");
    match a[0].as_str() {
        "device" => {
            let k = PairKey::new();
            println!("{}", k.public_text());
            let mut line = String::new();
            std::io::stdin().lock().read_line(&mut line).unwrap();
            let p: Vec<&str> = line.split_whitespace().collect();
            println!("{}", crypto::key_to_text(&k.open_from_phone(p[0], p[2], p[1]).expect("opens")));
        }
        "phone" => { let (pk, sealed) = crypto::seal_for_device(&key(&a[3]), &a[1], &a[2]).unwrap(); println!("{pk} {sealed}"); }
        "seal" => println!("{}", crypto::seal(&key(&a[1]), &a[2], &a[3], &a[4])),
        "open" => println!("{}", crypto::open(&key(&a[1]), &a[2], &a[3], &a[4]).expect("opens")),
        "pwkeys" => { let k = crypto::password_keys(&a[1], &a[2]); println!("{} {}", k.login_key, crypto::key_to_text(&k.wrap_key)); }
        "unwrap" => println!("{}", crypto::unwrap_with_key(&a[2], &key(&a[1])).map(|k| crypto::key_to_text(&k)).unwrap_or_else(|| "none".into())),
        "wrap" => println!("{}", crypto::wrap_with_key(&key(&a[1]), &key(&a[2]))),
        "rid" => println!("{}", jasktv_engine::records::id_of(&key(&a[1]), &a[2], &a[3])),
        "rseal" => { let c = jasktv_engine::records::StampClock::new("abcd1234");
            let w = jasktv_engine::records::seal(&key(&a[1]), &a[2], &a[3], &c.next(), Some(serde_json::from_str(&a[4]).unwrap()));
            println!("{} {} {}", w.id, w.stamp, w.data); }
        "ropen" => { let w = jasktv_engine::records::WireRecord { id: a[2].clone(), stamp: a[3].clone(), data: a[4].clone(), seq: 0 };
            let r = jasktv_engine::records::open(&key(&a[1]), &w).expect("opens"); println!("{} {} {}", r.coll, r.key, r.value.unwrap()); }
        _ => panic!("unknown mode"),
    }
}
