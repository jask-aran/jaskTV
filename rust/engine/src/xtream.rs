//! Reading an Xtream provider's film and series lists the way the app's `XtreamApi.streamRows` does: each element's
//! fields as strings (numbers and booleans as written; an array's first non-blank string; "" and "null" are none),
//! non-objects skipped, read as a stream so a 77 MB list never sits in memory as a tree.

use crate::matching::{whole_int, whole_long};
use serde::de::{Deserializer, IgnoredAny, MapAccess, SeqAccess, Visitor};
use serde_json::Value;

#[derive(Debug, Clone)]
pub struct VodRow { pub stream_id: String, pub name: String, pub year: Option<i32>, pub tmdb_id: Option<String>, pub added_millis: i64 }

#[derive(Debug, Clone)]
pub struct SeriesRow { pub series_id: String, pub name: String, pub year: Option<i32>, pub tmdb_id: Option<String>, pub added_millis: i64 }

/// The app's `readField`.
fn field(v: Value) -> Option<String> {
    match v {
        Value::String(s) => (!s.is_empty() && s != "null").then_some(s),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Array(items) => items.into_iter().find_map(|i| match i {
            Value::String(s) if !s.trim().is_empty() && s != "null" => Some(s),
            _ => None,
        }),
        _ => None,
    }
}

/// Calls [each] with the wanted fields of every object in a JSON array (by key; the last duplicate wins).
fn each_object<R: std::io::Read>(reader: R, keys: &'static [&'static str], mut each: impl FnMut(Vec<Option<String>>)) -> Result<(), String> {
    struct Rows<'a, F> { keys: &'static [&'static str], each: &'a mut F }
    struct Row { keys: &'static [&'static str] }
    impl<'de> Visitor<'de> for Row {
        type Value = Option<Vec<Option<String>>>;
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { f.write_str("an element") }
        fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
            let mut row = vec![None; self.keys.len()];
            while let Some(k) = map.next_key::<String>()? {
                match self.keys.iter().position(|w| *w == k) {
                    Some(i) => row[i] = field(map.next_value::<Value>()?),
                    None => { map.next_value::<IgnoredAny>()?; }
                }
            }
            Ok(Some(row))
        }
        // Anything that isn't an object is skipped, as the app does.
        fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> { while seq.next_element::<IgnoredAny>()?.is_some() {} Ok(None) }
        fn visit_str<E>(self, _: &str) -> Result<Self::Value, E> { Ok(None) }
        fn visit_i64<E>(self, _: i64) -> Result<Self::Value, E> { Ok(None) }
        fn visit_u64<E>(self, _: u64) -> Result<Self::Value, E> { Ok(None) }
        fn visit_f64<E>(self, _: f64) -> Result<Self::Value, E> { Ok(None) }
        fn visit_bool<E>(self, _: bool) -> Result<Self::Value, E> { Ok(None) }
        fn visit_unit<E>(self) -> Result<Self::Value, E> { Ok(None) }
    }
    impl<'de> serde::de::DeserializeSeed<'de> for Row {
        type Value = Option<Vec<Option<String>>>;
        fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> { d.deserialize_any(self) }
    }
    impl<'de, F: FnMut(Vec<Option<String>>)> Visitor<'de> for Rows<'_, F> {
        type Value = ();
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { f.write_str("a JSON array") }
        fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
            while let Some(row) = seq.next_element_seed(Row { keys: self.keys })? { if let Some(r) = row { (self.each)(r); } }
            Ok(())
        }
    }
    let mut de = serde_json::Deserializer::from_reader(std::io::BufReader::with_capacity(1 << 16, reader));
    de.deserialize_seq(Rows { keys, each: &mut each }).map_err(|e| format!("not a readable list: {e}"))
}

/// A cached `get_vod_streams`, as the app's `movieFrom` reads it (only the fields matching uses).
pub fn vod_rows<R: std::io::Read>(reader: R) -> Result<Vec<VodRow>, String> {
    const KEYS: &[&str] = &["stream_id", "name", "year", "added", "tmdb_id", "tmdb"];
    let mut out = Vec::new();
    each_object(reader, KEYS, |mut r| {
        let (Some(stream_id), Some(name)) = (r[0].take(), r[1].take()) else { return };
        out.push(VodRow { stream_id, name, year: whole_int(r[2].as_deref()), added_millis: whole_long(r[3].as_deref()).map(|s| s * 1000).unwrap_or(0),
            tmdb_id: r[4].take().or(r[5].take()) });
    })?;
    Ok(out)
}

/// A cached `get_series`, as the app's `seriesFrom` reads it.
pub fn series_rows<R: std::io::Read>(reader: R) -> Result<Vec<SeriesRow>, String> {
    const KEYS: &[&str] = &["series_id", "name", "year", "releaseDate", "last_modified", "tmdb_id", "tmdb"];
    let mut out = Vec::new();
    each_object(reader, KEYS, |mut r| {
        let (Some(series_id), Some(name)) = (r[0].take(), r[1].take()) else { return };
        let year = whole_int(r[2].as_deref()).or_else(|| r[3].as_deref().and_then(|d| d.chars().take(4).collect::<String>().parse().ok()));
        out.push(SeriesRow { series_id, name, year, added_millis: whole_long(r[4].as_deref()).map(|s| s * 1000).unwrap_or(0),
            tmdb_id: r[5].take().or(r[6].take()) });
    })?;
    Ok(out)
}
