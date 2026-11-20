//! Prints "<stream id>\t<evidence>" for every film in a cached get_vod_streams (checks against the app by hand).
fn main() {
    let path = std::env::args().nth(1).expect("a get_vod_streams.json");
    for r in jasktv_engine::xtream::vod_rows(std::fs::File::open(path).unwrap()).unwrap() {
        println!("{}\t{}", r.stream_id, jasktv_engine::matching::evidence(&r.name, r.year, r.tmdb_id.as_deref()).replace('\n', "\\n"));
    }
}
