//! Matching provider titles to TMDB, ported line for line from the app (`VodTitleCleaner`, the parts of
//! `ChannelNameNormalizer` it uses, `MovieMatching`, `ShowMatching`, `Movie.identityEvidence`). Results must agree
//! with the app's exactly: a device takes core's matches only when its own evidence string for a title is the same.
//!
//! Java regex classes are ASCII unless asked otherwise (`\s`, `\d`), so the patterns spell those out; `\p{L}` and
//! `\p{Nd}` are Unicode in both.

use regex::Regex;
use std::sync::OnceLock;
use unicode_normalization::UnicodeNormalization;

const S: &str = r"[\t\n\x0B\x0C\r ]"; // Java's \s

fn re(cell: &'static OnceLock<Regex>, pattern: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(&pattern.replace(r"\s", S)).expect("pattern"))
}

// ---- ChannelNameNormalizer (the parts VodTitleCleaner uses) ----

fn quality_rank_of_token(token: &str) -> Option<u32> {
    Some(match token.to_uppercase().as_str() {
        "8K" => 500,
        "4K" | "UHD" | "FUHD" | "2160P" => 400,
        "FHD" | "1080" | "1080P" | "FULLHD" => 300,
        "HD" | "720P" | "720" => 200,
        "SD" | "LQ" | "480P" | "576P" => 100,
        _ => return None,
    })
}

fn is_stream_marker(token: &str) -> bool {
    matches!(token.to_uppercase().as_str(), "RAW" | "HEVC" | "H265" | "H.265" | "H264" | "H.264" | "AV1" | "50FPS" | "60FPS"
        | "25FPS" | "30FPS" | "FPS" | "HDR" | "DOLBY" | "VISION" | "PLUS")
}

pub fn fold_superscripts(raw: &str) -> String {
    const PAIRS: &str = "ᴬ:A,ᴮ:B,ꟲ:C,ᴰ:D,ᴱ:E,ꟳ:F,ᴳ:G,ᴴ:H,ᴵ:I,ᴶ:J,ᴷ:K,ᴸ:L,ᴹ:M,ᴺ:N,ᴼ:O,ᴾ:P,ꟴ:Q,ᴿ:R,ᵀ:T,ᵁ:U,ⱽ:V,ᵂ:W,ᔆ:S,\
        ᵃ:a,ᵇ:b,ᶜ:c,ᵈ:d,ᵉ:e,ᶠ:f,ᵍ:g,ʰ:h,ᶦ:i,ʲ:j,ᵏ:k,ˡ:l,ᵐ:m,ⁿ:n,ᵒ:o,ᵖ:p,ʳ:r,ˢ:s,ᵗ:t,ᵘ:u,ᵛ:v,ʷ:w,ˣ:x,ʸ:y,ᶻ:z,\
        ⁰:0,¹:1,²:2,³:3,⁴:4,⁵:5,⁶:6,⁷:7,⁸:8,⁹:9";
    static MAP: OnceLock<std::collections::HashMap<char, char>> = OnceLock::new();
    let map = MAP.get_or_init(|| PAIRS.split(',').filter_map(|c| {
        let mut it = c.trim().chars();
        let (a, _, b) = (it.next()?, it.next()?, it.next()?);
        Some((a, b))
    }).collect());
    raw.chars().map(|c| *map.get(&c).unwrap_or(&c)).collect()
}

// ---- VodTitleCleaner ----

const LANG_CODES: &[&str] = &["EN", "ENG", "ES", "ESP", "SPA", "LAT", "FR", "FRA", "FRE", "DE", "GER", "DEU",
    "IT", "ITA", "PT", "POR", "BR", "BRA", "NL", "DUT", "PL", "POL", "RU", "RUS",
    "AR", "ARA", "TR", "TUR", "SV", "SWE", "SE", "NO", "NOR", "DA", "DAN", "DK",
    "FI", "FIN", "EL", "GRE", "GR", "RO", "RON", "CS", "CZE", "CZ", "HU", "HUN",
    "HR", "SR", "BG", "SK", "SL", "UK", "GB", "US", "USA", "CA", "CAN", "AU", "AUS",
    "NZ", "IE", "IN", "IND", "MX", "MEX", "ZA", "JP", "JPN", "KR", "KOR", "CN",
    "VN", "TH", "ID", "PH", "IR", "IL", "HE", "HEB", "AF", "ALB", "MULTI", "VO", "SUBS", "SUB", "DUB",
    "KU", "KUR", "SO", "PK", "BN", "IS", "QC", "LA", "TL", "TA", "TU", "ML", "MM", "KN", "PI", "PA", "MR", "EG"];

const SERVICE_CODES: &[&str] = &["NF", "TOP", "SC", "SOC", "EX", "XXX", "AMZ", "AMZN", "PMV", "DSNY", "DNSP", "D+",
    "HBO", "HMAX", "MAX", "ATV", "ATVP", "A+", "PMT", "P+", "HULU", "PCOK"];

const EDGE_JUNK: &[char] = &[' ', '\t', '|', ':', '-', '–', '—', '.', ','];

fn is_lang(s: &str) -> bool { LANG_CODES.contains(&s) }
fn is_service(s: &str) -> bool { SERVICE_CODES.contains(&s) }

/// Kotlin's `String.trim()`: whitespace by `Char.isWhitespace`.
fn kt_trim(s: &str) -> &str { s.trim_matches(|c: char| c.is_whitespace()) }

/// A raw provider title, cleaned (see the app's `VodTitleCleaner.clean`). Never blank unless the input is.
pub fn clean(raw: &str) -> String {
    static MULTI_SPACE: OnceLock<Regex> = OnceLock::new();
    let folded_full = fold_superscripts(raw);
    let folded = kt_trim(&folded_full);
    if folded.is_empty() { return kt_trim(raw).to_string(); }
    let deprefixed = peel_prefix(folded);
    let dequalified = strip_stray_quality(&deprefixed);
    let detrailed = strip_trailing_codes(&dequalified);
    let spaced = re(&MULTI_SPACE, r"\s+").replace_all(&detrailed, " ").into_owned();
    let tidied = kt_trim(kt_trim(&spaced).trim_matches(EDGE_JUNK)).to_string();
    if tidied.trim().is_empty() { folded.to_string() } else { tidied }
}

fn peel_prefix(input: &str) -> String {
    static COMPOUND: OnceLock<Regex> = OnceLock::new();
    static BRACKET: OnceLock<Regex> = OnceLock::new();
    static PLUS: OnceLock<Regex> = OnceLock::new();
    static BARE: OnceLock<Regex> = OnceLock::new();
    let compound = re(&COMPOUND, r"^\s*([A-Z0-9]{1,4}\+?(?:-[A-Z0-9]{1,4}\+?)+)\s+[-–—|]\s+");
    let bracket = re(&BRACKET, r"^\s*[\[(]\s*([\p{L}\p{Nd}]{2,5})\s*[\])]\s*[|:\-–—]?\s*");
    let plus = re(&PLUS, r"^\s*(\p{L}{1,3}\+)\s*([|:\-–—])\s*");
    let bare = re(&BARE, r"^\s*([\p{L}\p{Nd}]{2,5})\s*([|:\-–—])\s*");
    let mut s = input.to_string();
    loop {
        if let Some(c) = compound.captures(&s) {
            let group = c.get(1).unwrap().as_str();
            if group.split('-').any(|p| { let u = p.to_uppercase(); is_lang(&u) || is_service(&u) }) {
                s = s[c.get(0).unwrap().end()..].to_string();
                continue;
            }
        }
        if let Some(c) = bracket.captures(&s) {
            if is_prefix_tag(c.get(1).unwrap().as_str(), true, None) { s = s[c.get(0).unwrap().end()..].to_string(); continue; }
        }
        if let Some(c) = plus.captures(&s) {
            if is_service(&c.get(1).unwrap().as_str().to_uppercase()) { s = s[c.get(0).unwrap().end()..].to_string(); continue; }
        }
        if let Some(c) = bare.captures(&s) {
            if is_prefix_tag(c.get(1).unwrap().as_str(), false, c.get(2).unwrap().as_str().chars().next()) {
                s = s[c.get(0).unwrap().end()..].to_string();
                continue;
            }
        }
        return s;
    }
}

fn is_prefix_tag(token: &str, bracketed: bool, separator: Option<char>) -> bool {
    if quality_rank_of_token(token).is_some() || is_stream_marker(token) { return true; }
    let upper = token.to_uppercase();
    if is_lang(&upper) || is_service(&upper) { return true; }
    let len = token.encode_utf16().count();
    let generic = (2..=3).contains(&len) && token.chars().all(char::is_alphabetic) && token == upper;
    generic && (bracketed || separator == Some('|'))
}

fn strip_stray_quality(input: &str) -> String {
    if input.trim().is_empty() { return input.to_string(); }
    input.split(' ').filter(|part| {
        let bare = part.trim_matches(&['.', ',', ':', ';', '|', '-', '(', ')', '[', ']'][..]);
        bare.is_empty() || quality_rank_of_token(bare).is_none()
    }).collect::<Vec<_>>().join(" ")
}

fn strip_trailing_codes(input: &str) -> String {
    static TRAILING: OnceLock<Regex> = OnceLock::new();
    let trailing = re(&TRAILING, r"\s*[\[(]\s*([A-Za-z]{2})\s*[\])]\s*$");
    let mut s = kt_trim(input).to_string();
    while let Some(c) = trailing.captures(&s) {
        if !is_lang(&c.get(1).unwrap().as_str().to_uppercase()) { break; }
        let start = c.get(0).unwrap().start();
        s = kt_trim(&s[..start]).to_string();
    }
    s
}

// ---- MovieMatching ----

/// Lower case, NFKC, everything but letters and digits as single spaces.
pub fn title_key(title: &str) -> String {
    static NON_WORD: OnceLock<Regex> = OnceLock::new();
    let normal: String = title.nfkc().collect::<String>().to_lowercase();
    kt_trim(&re(&NON_WORD, r"[^\p{L}\p{N}]+").replace_all(&normal, " ")).to_string()
}

/// The year the provider gave, else the last "(19xx)"/"(20xx)" in the name.
pub fn release_year(raw: &str, supplied: Option<i32>) -> Option<i32> {
    static YEAR: OnceLock<Regex> = OnceLock::new();
    supplied.or_else(|| re(&YEAR, r"\((19|20)[0-9]{2}\)").find_iter(raw).last().and_then(|m| m.as_str()[1..5].parse().ok()))
}

/// The search query for a raw title: cleaned, without a trailing "(year)".
pub fn query(raw: &str) -> String {
    static YEAR_END: OnceLock<Regex> = OnceLock::new();
    kt_trim(&re(&YEAR_END, r"\s*\((?:19|20)[0-9]{2}\)\s*$").replace(&clean(raw), "")).to_string()
}

#[derive(Clone, Debug)]
pub struct Candidate { pub id: i64, pub title: String, pub original_title: String, pub year: Option<i32> }

#[derive(Clone, Debug, PartialEq)]
pub enum Decision {
    Accepted { id: i64, confidence: f64, provenance: &'static str },
    Ambiguous(Vec<i64>),
    Unmatched,
}

pub fn explicit(c: &Candidate, expected_year: Option<i32>, cached: bool) -> Decision {
    if expected_year.is_some() && c.year != expected_year { Decision::Ambiguous(vec![c.id]) }
    else { Decision::Accepted { id: c.id, confidence: 1.0, provenance: if cached { "validated-provider-tmdb-cache" } else { "validated-provider-tmdb" } } }
}

pub fn decide(title: &str, year: Option<i32>, candidates: &[Candidate]) -> Decision {
    let key = title_key(&query(title));
    if key.trim().is_empty() { return Decision::Unmatched; }
    let mut exact: Vec<&Candidate> = Vec::new();
    for c in candidates {
        let fits = c.id > 0 && (title_key(&c.title) == key || title_key(&c.original_title) == key) && (year.is_none() || c.year == year);
        if fits && !exact.iter().any(|e| e.id == c.id) { exact.push(c); }
    }
    match (exact.len(), year) {
        (0, _) => Decision::Unmatched,
        (1, Some(_)) => Decision::Accepted { id: exact[0].id, confidence: 0.98, provenance: "exact-title-year" },
        _ => Decision::Ambiguous(exact.iter().map(|c| c.id).collect()),
    }
}

/// The app's `Movie.identityEvidence()`: name, year and the provider's TMDB id, joined by U+001F.
pub fn evidence(name: &str, year: Option<i32>, tmdb_id: Option<&str>) -> String {
    format!("{name}\u{1f}{}\u{1f}{}", year.map(|y| y.to_string()).unwrap_or_default(), tmdb_id.unwrap_or(""))
}

/// Copies of one film look it up once: the provider's TMDB id, or else the cleaned name and year.
pub fn group_key(name: &str, year: Option<i32>, tmdb_id: Option<&str>) -> String {
    match tmdb_id.and_then(|t| kt_int(kt_trim(t))).filter(|&i| i > 0) {
        Some(id) => format!("id:{id}"),
        None => format!("q:{}|{}", title_key(&query(name)), release_year(name, year).map(|y| y.to_string()).unwrap_or_default()),
    }
}

/// Kotlin's `String.toIntOrNull()` (32-bit, optional sign).
pub fn kt_int(s: &str) -> Option<i32> { s.parse::<i32>().ok() }

/// The app's `wholeInt`: the part before a '.', as an Int.
pub fn whole_int(s: Option<&str>) -> Option<i32> { s.and_then(|s| kt_int(s.split('.').next().unwrap_or(""))) }

pub fn whole_long(s: Option<&str>) -> Option<i64> { s.and_then(|s| s.split('.').next().unwrap_or("").parse().ok()) }

// ---- ShowMatching ----

/// Accept TMDB's show for a hint when a provider name equals its title or original title, or when the first-air
/// year matches and one title contains the other.
pub fn show_accepts(names: &[&str], year: Option<i32>, title: &str, original_title: &str, tmdb_year: Option<i32>) -> bool {
    let keys: Vec<String> = [title, original_title].iter().map(|t| title_key(t)).filter(|k| !k.trim().is_empty()).collect();
    if keys.is_empty() { return false; }
    names.iter().map(|n| title_key(&query(n))).filter(|k| !k.trim().is_empty()).any(|wanted| {
        keys.contains(&wanted) || (year.is_some() && year == tmdb_year && keys.iter().any(|k| k.contains(&wanted) || wanted.contains(k.as_str())))
    })
}
