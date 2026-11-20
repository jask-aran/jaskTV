//! Prints the cleaned title, query and title key for each line of standard input (checks against the app by hand).
use jasktv_engine::matching::{clean, query, title_key};
use std::io::BufRead;
fn main() {
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        println!("{}\t{}\t{}", clean(&line), query(&line), title_key(&query(&line)));
    }
}
