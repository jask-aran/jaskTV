//! The network's TV guide. The provider's own guide covers few channels (147 of ~670 real US channels on the test
//! account), so core adds a free public guide (epgshare01, the same files the app offers in Settings) for the regions
//! the account watches, made for the account's own channel list:
//!  - Each guide channel is matched to the account's streams: US and Canadian local stations by call sign
//!    ("NBC WSTM Syracuse" ↔ WSTM-DT), everything else by the cleaned name in the same region ("UK: SKY ATLANTIC
//!    HEVC 4K" ↔ Sky Atlantic HD). A name that fits two guide channels is left out, never guessed.
//!  - Only matched channels are kept, with three days of programmes, and each one also lists the matched streams'
//!    own names, so the app's guide matcher pairs them exactly (and its check that names agree still holds).
//!  - The core that builds the account's TMDB match table builds this too (one place does the work), serves it at
//!    `/guide/<playlist key>` and puts an encrypted copy in the account's files. Every other core copies that copy,
//!    and devices with no core answering read it from the account: the whole network shares one guide.
//! Once a day, on a timer.

use crate::link::Link;
use crate::matcher::Matcher;
use crate::provider::Provider;
use jasktv_engine::crypto;
use quick_xml::events::{BytesEnd, BytesStart, BytesText, Event};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io::{BufReader, Read, Write};
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const BASE: &str = "https://epgshare01.online/epgshare01/epg_ripper_";

/// The guide files per region (the app's region codes; GB is the provider's "UK").
fn files(region: &str) -> &'static [&'static str] {
    match region {
        "US" => &["US2", "US_LOCALS1", "US_SPORTS1"],
        "GB" => &["UK1"],
        "AU" => &["AU1"],
        "CA" => &["CA2"],
        "IN" => &["IN1", "IN2", "IN4"],
        _ => &[],
    }
}

pub struct Guides { link: Arc<Link>, matcher: Arc<Matcher>, provider: Arc<Provider>, dir: PathBuf }

fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

impl Guides {
    pub fn new(link: Arc<Link>, matcher: Arc<Matcher>, provider: Arc<Provider>, dir: PathBuf) -> Arc<Self> {
        let _ = std::fs::create_dir_all(&dir);
        Arc::new(Guides { link, matcher, provider, dir })
    }

    /// The guide made for a playlist, for serving (gzip XMLTV).
    pub fn file(&self, source: &str) -> Option<PathBuf> {
        let valid = source.len() == 64 && source.bytes().all(|b| b.is_ascii_hexdigit());
        let p = self.dir.join(format!("{source}.xml.gz"));
        (valid && p.is_file()).then_some(p)
    }

    pub fn run(self: Arc<Self>) {
        std::thread::sleep(Duration::from_secs(120)); // after the lists and the builder claim settle
        let mut built: HashMap<String, i64> = HashMap::new();
        loop {
            let building = self.matcher.may_match.load(Ordering::SeqCst);
            let regions = self.regions();
            for source in self.provider.sources() {
                // Built per playlist and set of regions: ticking a region in the app rebuilds within ten minutes.
                let id = format!("{source}:{}", regions.join(","));
                let r = if building {
                    if now_ms() - built.get(&id).copied().unwrap_or(0) < 24 * 3_600_000 { continue; }
                    let started = std::time::Instant::now();
                    self.build(&source, &regions).map(|(chans, streams, bytes)| {
                        built.insert(id.clone(), now_ms());
                        eprintln!("guides: {chans} guide channels for {streams} streams ({:.1} MB) in {:.0} s", bytes as f64 / 1e6, started.elapsed().as_secs_f64());
                    })
                } else { self.follow(&source) };
                if let Err(e) = r { eprintln!("guides: {e}"); built.insert(id, now_ms() - 23 * 3_600_000); }
            }
            std::thread::sleep(Duration::from_secs(10 * 60));
        }
    }

    /// The account's Live TV regions (its setting); every supported one if none is set.
    fn regions(&self) -> Vec<String> {
        let set: Vec<String> = self.link.synced(|s| s.settings.get("liveRegions").cloned())
            .and_then(|v| match v { Value::String(t) => serde_json::from_str::<Vec<String>>(&t).ok(), Value::Array(a) => Some(a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()), _ => None })
            .unwrap_or_default();
        let set: Vec<String> = set.into_iter().filter(|r| !files(r).is_empty()).collect();
        if set.is_empty() { ["US", "GB", "AU", "CA", "IN"].iter().map(|s| s.to_string()).collect() } else { set }
    }

    /// Builds the guide for one playlist and publishes it. (guide channels kept, streams matched, bytes).
    fn build(&self, source: &str, regions: &[String]) -> Result<(usize, usize, u64), String> {
        let live = self.provider.file(source, "get_live_streams").ok_or("no copy of the live list yet")?;
        let cats = self.provider.file(source, "get_live_categories").ok_or("no copy of the live categories yet")?;
        let streams = streams(&live, &cats)?;
        let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(20)).timeout(Duration::from_secs(600)).user_agent("jasktv-core").build();
        let tmp = self.dir.join("download");
        let _ = std::fs::create_dir_all(&tmp);
        let mut matched: HashMap<String, Vec<String>> = HashMap::new(); // guide channel id -> stream names
        let mut downloaded = Vec::new();
        for region in regions {
            let mine: Vec<&Stream> = streams.iter().filter(|s| &s.region == region).collect();
            if mine.is_empty() { continue; }
            let mut channels: Vec<(String, Vec<String>)> = Vec::new();
            for f in files(region) {
                let path = tmp.join(format!("{f}.xml.gz"));
                let resp = agent.get(&format!("{BASE}{f}.xml.gz")).call().map_err(|e| format!("guide {f}: {}", short(e)))?;
                let mut out = std::fs::File::create(&path).map_err(|e| e.to_string())?;
                std::io::copy(&mut resp.into_reader().take(400 << 20), &mut out).map_err(|e| format!("guide {f}: {e}"))?;
                channels.extend(read_channels(&path)?);
                downloaded.push(path);
            }
            // Name keys and call signs to guide channels; a key two guide channels share is ambiguous.
            let mut by_name: HashMap<String, HashSet<String>> = HashMap::new();
            let mut by_call: HashMap<String, Vec<String>> = HashMap::new();
            for (id, names) in &channels {
                if let Some(c) = guide_call(id) { by_call.entry(c).or_default().push(id.clone()); }
                for n in names { let k = key(n); if k.len() >= 3 { by_name.entry(k).or_default().insert(id.clone()); } }
            }
            // A station's main channel (-DT, -TV) before its subchannels (-DT2).
            for ids in by_call.values_mut() { ids.sort_by_key(|i| (!(i.contains("-DT.") || i.contains("-TV.")), i.len())); }
            for s in mine {
                let call = if region == "US" || region == "CA" { call_sign(&s.name) } else { None };
                let hit = call.and_then(|c| by_call.get(&c).and_then(|ids| ids.first().cloned()))
                    .or_else(|| by_name.get(&key(&s.name)).filter(|ids| ids.len() == 1).and_then(|ids| ids.iter().next().cloned()));
                if let Some(id) = hit { matched.entry(id).or_default().push(s.name.clone()); }
            }
        }
        if matched.is_empty() { return Err("no guide channel matched this playlist's streams".into()); }
        // The trimmed guide: matched channels (their names plus the streams'), then their programmes for 3 days.
        let out_path = self.dir.join(format!("{source}.xml.gz.part"));
        let mut w = quick_xml::Writer::new(flate2::write::GzEncoder::new(std::io::BufWriter::new(std::fs::File::create(&out_path).map_err(|e| e.to_string())?), flate2::Compression::default()));
        let wr = |w: &mut quick_xml::Writer<_>, e: Event| w.write_event(e).map_err(|e| e.to_string());
        wr(&mut w, Event::Start(BytesStart::new("tv").with_attributes([("generator-info-name", "jasktv-core")])))?;
        let names_by_id: HashMap<String, Vec<String>> = downloaded.iter().filter_map(|p| read_channels(p).ok()).flatten().collect();
        let mut ids: Vec<&String> = matched.keys().collect();
        ids.sort();
        for id in &ids {
            wr(&mut w, Event::Start(BytesStart::new("channel").with_attributes([("id", id.as_str())])))?;
            let mut seen = HashSet::new();
            for n in names_by_id.get(*id).into_iter().flatten().chain(matched[*id].iter()) {
                if !seen.insert(n.clone()) { continue; }
                wr(&mut w, Event::Start(BytesStart::new("display-name")))?;
                wr(&mut w, Event::Text(BytesText::new(n)))?;
                wr(&mut w, Event::End(BytesEnd::new("display-name")))?;
            }
            wr(&mut w, Event::End(BytesEnd::new("channel")))?;
        }
        let keep: HashSet<&str> = matched.keys().map(String::as_str).collect();
        let (from, to) = (now_ms() - 2 * 3_600_000, now_ms() + 3 * 86_400_000);
        for path in &downloaded { copy_programmes(path, &keep, from, to, &mut w)?; }
        wr(&mut w, Event::End(BytesEnd::new("tv")))?;
        let gz = w.into_inner();
        gz.finish().map_err(|e| e.to_string())?.flush().map_err(|e| e.to_string())?;
        let final_path = self.dir.join(format!("{source}.xml.gz"));
        std::fs::rename(&out_path, &final_path).map_err(|e| e.to_string())?;
        for p in downloaded { let _ = std::fs::remove_file(p); }
        let bytes = std::fs::metadata(&final_path).map(|m| m.len()).unwrap_or(0);
        self.publish(source, &final_path)?;
        Ok((matched.len(), matched.values().map(Vec::len).sum(), bytes))
    }

    /// An encrypted copy in the account's files, and a record saying which and when.
    fn publish(&self, source: &str, path: &std::path::Path) -> Result<(), String> {
        let (client, token, me, key) = self.link.file_access().ok_or("not signed in")?;
        let name = format!("guide-{}", &source[..16]);
        let plain = std::fs::read(path).map_err(|e| e.to_string())?;
        let sealed = crypto::seal_bytes(&key, "blob", &name, &plain);
        client.put_blob(&token, &name, &sealed).map_err(|e| e.message)?;
        self.link.put_record("snap", &format!("guide:{source}"), json!({ "by": me, "file": name, "at": now_ms() }))
    }

    /// Not the builder: copy the builder's guide from the account when it's newer than ours.
    fn follow(&self, source: &str) -> Result<(), String> {
        let Some(snap) = self.link.shared("snap", &format!("guide:{source}")) else { return Ok(()) };
        let at = snap["at"].as_i64().unwrap_or(0);
        let path = self.dir.join(format!("{source}.xml.gz"));
        let have = std::fs::metadata(&path).and_then(|m| m.modified()).ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as i64).unwrap_or(0);
        if have >= at { return Ok(()); }
        let file = snap["file"].as_str().ok_or("no file in the guide record")?;
        let (client, token, _, key) = self.link.file_access().ok_or("not signed in")?;
        let sealed = client.get_blob(&token, file).map_err(|e| e.message)?.ok_or("the guide copy is missing")?;
        let plain = crypto::open_bytes(&key, "blob", file, &sealed).map_err(|_| "couldn't open the guide copy")?;
        let tmp = path.with_extension("part");
        std::fs::write(&tmp, &plain).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
        eprintln!("guides: copied the account's guide ({:.1} MB)", plain.len() as f64 / 1e6);
        Ok(())
    }
}

fn short(e: ureq::Error) -> String { match e { ureq::Error::Status(c, _) => format!("HTTP {c}"), ureq::Error::Transport(t) => t.kind().to_string() } }

struct Stream { name: String, region: String }

/// The playlist's live channels with the app's region (the category's prefix; Indian languages under ASIA are IN),
/// leaving out event slots, replays and 24/7 loops.
fn streams(live: &std::path::Path, cats: &std::path::Path) -> Result<Vec<Stream>, String> {
    let cats: Value = serde_json::from_reader(BufReader::new(std::fs::File::open(cats).map_err(|e| e.to_string())?)).map_err(|e| e.to_string())?;
    let id = |v: &Value| v.as_str().map(str::to_string).or_else(|| v.as_i64().map(|i| i.to_string()));
    let names: HashMap<String, String> = cats.as_array().into_iter().flatten()
        .filter_map(|c| Some((id(&c["category_id"])?, fold(c["category_name"].as_str()?).to_uppercase()))).collect();
    let list: Value = serde_json::from_reader(BufReader::new(std::fs::File::open(live).map_err(|e| e.to_string())?)).map_err(|e| e.to_string())?;
    let india = ["HINDI", "TAMIL", "TELUGU", "PUNJABI", "MALAYALAM", "MARATHI", "KANNADA", "BENGALI", "GUJARATI", "INDIA"];
    Ok(list.as_array().into_iter().flatten().filter_map(|s| {
        let cat = names.get(&id(&s["category_id"])?)?;
        let words: Vec<&str> = cat.split(|c: char| !c.is_ascii_alphanumeric() && c != '/').collect();
        if words.iter().any(|w| matches!(*w, "PPV" | "EVENT" | "EVENTS" | "REPLAY" | "24/7")) { return None; }
        let prefix = cat.split('|').next()?.trim();
        let region = match prefix {
            "UK" => "GB".to_string(),
            "ASIA" if india.iter().any(|w| cat.contains(w)) => "IN".to_string(),
            p => p.to_string(),
        };
        Some(Stream { name: s["name"].as_str()?.to_string(), region })
    }).collect())
}

/// Superscript letters and digits the provider decorates names with ("ᴿᴬᵂ") as plain ones.
fn fold(s: &str) -> String {
    const FROM: &str = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ⁰¹²³⁴⁵⁶⁷⁸⁹";
    const TO: &str = "ABDEGHIJKLMNOPRTUVWabcdefghijklmnoprstuvwxyz0123456789";
    let map: HashMap<char, char> = FROM.chars().zip(TO.chars()).collect();
    s.chars().map(|c| *map.get(&c).unwrap_or(&c)).collect()
}

const QUALITY: &[&str] = &["UHD", "FHD", "HD", "SD", "4K", "8K", "HEVC", "RAW", "60FPS", "50FPS", "VIP", "LQ", "HQ", "BACKUP", "H265", "1080P", "720P", "EAST"];

/// A name's matching key: up to two leading tags ("US:", "AT&T:") off, bracketed parts and quality words dropped,
/// letters, digits and '+' kept, lower case. "UK: SKY ATLANTIC HEVC 4K" and "Sky Atlantic HD" → "skyatlantic".
fn key(name: &str) -> String {
    let mut n = fold(name).to_uppercase().replace('&', " AND ");
    for _ in 0..2 {
        if let Some(i) = n.find(':') {
            let tag = n[..i].trim();
            if (2..=10).contains(&tag.len()) && tag.chars().all(|c| c.is_ascii_alphanumeric() || " AND+-".contains(c)) { n = n[i + 1..].to_string(); }
        }
    }
    let mut out = String::new();
    let mut depth = 0;
    let mut word = String::new();
    let flush = |word: &mut String, out: &mut String| { if !QUALITY.contains(&word.as_str()) { out.push_str(word); } word.clear(); };
    for c in n.chars() {
        match c {
            '(' | '[' => { flush(&mut word, &mut out); depth += 1; }
            ')' | ']' => { depth = (depth - 1).max(0); }
            _ if depth > 0 => {}
            c if c.is_ascii_alphanumeric() || c == '+' => word.push(c),
            _ => flush(&mut word, &mut out),
        }
    }
    flush(&mut word, &mut out);
    out.to_lowercase()
}

const NETWORKS: &[&str] = &["NBC", "CBS", "FOX", "ABC", "CW", "PBS", "TELEMUNDO", "UNIVISION", "UNIMAS", "MYNETWORK", "MYTV", "ION", "CTV", "GLOBAL", "CITY", "CBC", "IND"];
const NOT_CALL: &[&str] = &["WEST", "WILD", "KIDS", "WWE", "WNBA", "WIDE", "WEEK", "WAVE", "WIRE", "WINE", "WISE", "WEB", "KING"];

/// A local station's call sign from a stream name that also names a network ("NBC 4 (KNBC) LOS ANGELES" → KNBC).
fn call_sign(name: &str) -> Option<String> {
    let upper = fold(name).to_uppercase();
    let words: Vec<&str> = upper.split(|c: char| !c.is_ascii_alphanumeric()).filter(|w| !w.is_empty()).collect();
    if !words.iter().any(|w| NETWORKS.contains(w)) { return None; }
    words.into_iter().find(|w| (3..=4).contains(&w.len()) && (w.starts_with('K') || w.starts_with('W')) && w.chars().all(|c| c.is_ascii_uppercase()) && !NOT_CALL.contains(w))
        .map(str::to_string)
}

/// A guide channel id's call sign: "WPTA-DT2.us_locals1" → WPTA.
fn guide_call(id: &str) -> Option<String> {
    let (call, rest) = id.split_once('-')?;
    let ok = (3..=4).contains(&call.len()) && (call.starts_with('K') || call.starts_with('W')) && call.chars().all(|c| c.is_ascii_uppercase());
    let suffix = rest.split('.').next().unwrap_or("");
    (ok && ["DT", "TV", "CD", "LD", "D"].iter().any(|s| suffix.starts_with(s))).then(|| call.to_string())
}

fn gz_reader(path: &std::path::Path) -> Result<quick_xml::Reader<BufReader<flate2::read::GzDecoder<std::fs::File>>>, String> {
    let f = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut r = quick_xml::Reader::from_reader(BufReader::with_capacity(1 << 16, flate2::read::GzDecoder::new(f)));
    r.config_mut().trim_text(true);
    Ok(r)
}

/// A guide file's channels: id, then its display names and the id itself as words ("ESPN2.HD.us2" → "ESPN2 HD").
fn read_channels(path: &std::path::Path) -> Result<Vec<(String, Vec<String>)>, String> {
    let mut r = gz_reader(path)?;
    let mut buf = Vec::new();
    let mut out: Vec<(String, Vec<String>)> = Vec::new();
    let mut in_name = false;
    loop {
        match r.read_event_into(&mut buf) {
            Ok(Event::Start(e)) if e.name().as_ref() == b"channel" => {
                let id = attr(&e, b"id").unwrap_or_default();
                let words = id.rsplit_once('.').map(|(a, _)| a).unwrap_or(&id).replace('.', " ");
                out.push((id, vec![words]));
            }
            Ok(Event::Start(e)) if e.name().as_ref() == b"display-name" => in_name = true,
            Ok(Event::Text(t)) if in_name => { if let (Some(last), Ok(text)) = (out.last_mut(), t.unescape()) { last.1.push(text.into_owned()); } }
            Ok(Event::End(e)) if e.name().as_ref() == b"display-name" => in_name = false,
            Ok(Event::Start(e)) if e.name().as_ref() == b"programme" => break, // channels come first
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("guide file unreadable: {e}")),
            _ => {}
        }
        buf.clear();
    }
    Ok(out)
}

fn attr(e: &BytesStart, name: &[u8]) -> Option<String> {
    e.attributes().flatten().find(|a| a.key.as_ref() == name).and_then(|a| a.unescape_value().ok().map(|v| v.into_owned()))
}

/// XMLTV time ("20261119180000 +0000") as epoch ms.
fn xmltv_ms(t: &str) -> Option<i64> {
    let d = t.get(..14)?;
    let n = |a: usize, b: usize| d.get(a..b)?.parse::<i64>().ok();
    let (y, mo, da, h, mi, s) = (n(0, 4)?, n(4, 6)?, n(6, 8)?, n(8, 10)?, n(10, 12)?, n(12, 14)?);
    // Days from the civil date (Howard Hinnant's algorithm).
    let (y2, m2) = if mo <= 2 { (y - 1, mo + 9) } else { (y, mo - 3) };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * m2 + 2) / 5 + da - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    let mut ms = ((days * 24 + h) * 60 + mi) * 60_000 + s * 1000;
    if let Some(off) = t.get(14..).map(str::trim).filter(|o| o.len() == 5) {
        let sign = if off.starts_with('-') { -1 } else { 1 };
        let oh: i64 = off[1..3].parse().ok()?; let om: i64 = off[3..5].parse().ok()?;
        ms -= sign * (oh * 60 + om) * 60_000;
    }
    Some(ms)
}

/// Copies the programmes of [keep]'s channels in [from, to) into [w], as written.
fn copy_programmes<W: Write>(path: &std::path::Path, keep: &HashSet<&str>, from: i64, to: i64, w: &mut quick_xml::Writer<W>) -> Result<(), String> {
    let mut r = gz_reader(path)?;
    let mut buf = Vec::new();
    let mut depth = 0usize; // > 0 while copying a programme
    loop {
        let ev = r.read_event_into(&mut buf).map_err(|e| format!("guide file unreadable: {e}"))?;
        match &ev {
            Event::Eof => break,
            Event::Start(e) if depth == 0 && e.name().as_ref() == b"programme" => {
                let ch = attr(e, b"channel").unwrap_or_default();
                let start = attr(e, b"start").and_then(|t| xmltv_ms(&t)).unwrap_or(0);
                let stop = attr(e, b"stop").and_then(|t| xmltv_ms(&t)).unwrap_or(start);
                if keep.contains(ch.as_str()) && stop > from && start < to { depth = 1; w.write_event(ev.borrow()).map_err(|e| e.to_string())?; }
                else { r.read_to_end_into(e.name().to_owned(), &mut Vec::new()).map_err(|e| e.to_string())?; }
            }
            _ if depth > 0 => {
                match &ev { Event::Start(_) => depth += 1, Event::End(_) => depth -= 1, _ => {} }
                w.write_event(ev.borrow()).map_err(|e| e.to_string())?;
            }
            _ => {}
        }
        buf.clear();
    }
    Ok(())
}
