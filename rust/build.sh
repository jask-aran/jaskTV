#!/usr/bin/env bash
# Builds jasktv-core for Linux (here, in WSL) and Windows (with the Windows Rust toolchain) into dist/.
# Build output stays outside the repo (CARGO_TARGET_DIR) so the project folder stays small; `./build.sh clean` empties it.
set -euo pipefail
cd "$(dirname "$0")"
target="${CARGO_TARGET_DIR:-$HOME/.cache/jasktv-core-target}"
win_target='C:\Users\jaska\jaskTV-build\jasktv-core-target'
win_src=/mnt/c/Users/jaska/jaskTV-build/jasktv-rust-src
if [ "${1:-}" = clean ]; then rm -rf "$target" "$(wslpath -u "$win_target")" "$win_src"; echo "cleaned"; exit 0; fi
mkdir -p dist
CARGO_TARGET_DIR="$target" cargo build --release --quiet -p jasktv-core
cp "$target/release/jasktv-core" dist/jasktv-core-linux-x86_64
# Windows: build from a copy of the workspace on the Windows side (cargo.exe is slow on WSL paths).
rm -rf "$win_src" && mkdir -p "$win_src" && cp -r Cargo.toml Cargo.lock engine core tray "$win_src/"
(cd /mnt/c && cmd.exe /c "cd /d $(wslpath -w "$win_src") && set CARGO_TARGET_DIR=$win_target&& %USERPROFILE%\.cargo\bin\cargo.exe build --release --quiet -p jasktv-core -p jasktv-tray")
cp "$(wslpath -u "$win_target")/release/jasktv-core.exe" dist/jasktv-core-windows-x86_64.exe
cp "$(wslpath -u "$win_target")/release/jasktv-tray.exe" dist/jasktv-tray-windows-x86_64.exe
for f in dist/jasktv-*; do printf "%s %.1f MB\n" "$f" "$(stat -c %s "$f" | awk '{print $1/1048576}')"; done
