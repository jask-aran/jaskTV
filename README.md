# jaskTV

An IPTV player for Android phones, tablets and TVs (Google TV, Fire TV). Bring your own provider: jaskTV supplies no channels.

## What it does

- **Live TV** with a programme guide, logos, previews and a sports view: what's on now and this week, by league, race weekend or cricket series, with the channels showing it.
- **Movies and shows** from your provider, matched to TMDB for artwork, cast and ratings; resume where you left off, collections and favourites.
- **Recordings** to the device, a USB drive or a NAS, plus downloads for offline viewing.
- **Profiles** with their own history, and a PIN for parental controls.
- **One account, every device** (optional). Sign in and your playlists (logins included), settings, guide setup and watch history follow you. A new TV signs in by scanning a QR code with your phone, then downloads the rest itself.
- **Phone as a remote** on the same Wi-Fi: arrows, play/pause, skip and volume; send what you're watching to the TV, or pick up on the phone where the TV is.

## Privacy

The account server stores only data encrypted on your devices with a key it never sees: playlists, credentials and history are unreadable to it. Remote control and live "now playing" travel directly between your devices on the local network, sealed with the same key. Signing in is optional; without it, everything stays on the device.

## Install

Download `jasktv.apk` from the [latest release](https://github.com/jask-aran/jaskTV/releases/latest) and install it on your phone or TV (allow installing from your browser or file manager when asked). The same APK runs on phones, tablets and TVs, and updates itself from new releases after you confirm.

Needs Android 6.0 or newer (Fire OS 6 or newer on Fire TV).

## Build

```sh
./gradlew :app:assembleDebug        # debug APK in app/build/outputs/apk/debug
```

Requires JDK 17. To enable accounts in a local build, add the account server to `local.properties`:

```
jasktv.accountServer=https://your-worker.example.workers.dev
```

The account server lives in `server/` (Cloudflare Workers, D1 and Durable Objects):

```sh
cd server && npm install
npx wrangler dev                     # local server on :8787
```

## jasktv-core

An optional home server for jaskTV, in Rust (`rust/core`, sharing logic with every client through `rust/engine`). It joins your jaskTV account as its own device (kind `core`) and writes a record to the account's sync (collection `core`): its addresses, port and access secret. Signed-in devices read that record and use core on their own; anything core can't do, or a core that's off, falls back to the device doing the work itself.

What it does today:

- **Provider lists:** keeps a copy of each Xtream playlist's film and series lists and their categories, refreshed every six hours (only while the account has no stream open). Devices load those four lists from core instead of the provider and parse them with their own code. Live channels, sign-in checks and per-title info stay direct from the provider.
- **TMDB matching:** matches every film and checks every show hint, with the app's rules ported exactly (`rust/engine/src/matching.rs`), using the TMDB key from your synced settings. Devices copy the results as they would from a nearby device, and only what changed since their last copy.
- **Copies from torrents:** finds films and episodes through Prowlarr and streams them through TorrServer; also answers as a Stremio add-on.
- **Runs Prowlarr and TorrServer for you:** `jasktv-core setup` downloads both for this machine (Linux x86-64 or ARM64, Windows x86-64) into `services/`; core starts them, restarts one that stops, and reads Prowlarr's API key itself. `jasktv-core update-services` fetches newer builds. TorrServer comes from its MatriX line (the newer "Silo" line has a different API). Nothing to sign in to: core turns off Prowlarr's sign-in and keeps it on that machine only, and an empty Prowlarr gets a starter set of public trackers (those that pass Prowlarr's own test from your network). Add or remove indexers at `http://localhost:9696` on that machine if you want; core leaves them alone after that.
- **On the home network like a device:** core announces itself as devices do and answers their direct links, so any device copies core's TMDB matches the way it copies another device's. It also copies devices' matches into its own set (every six hours, before matching anything itself).

Signing it in: `jasktv-core login` (email and password on standard input; core keeps its own session, never the password), or start it and approve the code it shows (console, and `http://<machine>:7000/`) on your phone under **Settings → Account → Sign in a TV**. `jasktv-core logout` takes it off the account; `jasktv-core secret` prints the access secret; `jasktv-core new-secret` replaces it.

```sh
cd rust && ./build.sh                 # dist/: Linux x86-64 (here) and Windows x86-64 (Windows Rust toolchain)
./build.sh clean                      # empty the build folders (kept outside the repo)
```

Put `jasktv-core.json` beside the binary (or set `JASKTV_*` environment variables):

```json
{ "listen": "0.0.0.0:7000", "account_server": "https://…", "prowlarr_url": "http://127.0.0.1:9696",
  "prowlarr_key": "…", "torrserver_url": "http://127.0.0.1:8090" }
```

Core keeps its sign-in, the account key and its access secret in `jasktv-core-state.json`, and its lists and matches under `cache/`, beside the binary; treat that machine like a signed-in phone. Everything but the setup page and `/health` needs the access secret. On Windows, allow `jasktv-core.exe`, `TorrServer.exe` and `Prowlarr.exe` through Windows Firewall on private networks.

To start core with the machine: `jasktv-core install-service` (Linux: a systemd user service, then `sudo loginctl enable-linger $USER` so it runs without a login; Windows: a hidden launcher in the Startup folder).

On a new Linux machine: copy `jasktv-core-linux-x86_64` (or the ARM64 build) and a `jasktv-core.json` with `account_server` into a folder, then run `jasktv-core login`, `jasktv-core setup` and `jasktv-core install-service`. Run Prowlarr, TorrServer and core behind a VPN: TorrServer's address joins every torrent it streams.

## Releases

Pushing a tag like `v1.2.3` builds a signed APK in GitHub Actions and publishes it with `update.json`, which installed apps check for updates. The version comes from the tag.

## Project layout

| Path | What |
|------|------|
| `app/` | The Android app (Kotlin, Jetpack Compose) |
| `shared/` | Data layer: Room database, models, settings |
| `player-core/` | The player contract (Media3 / ExoPlayer) |
| `desktop/` | Desktop build of the shared layer |
| `server/` | Account and sync server (Cloudflare) |
| `rust/` | `engine` (shared Rust logic) and `core` (the optional home server) |

## Licence

GPL-3.0-or-later. jaskTV is derived from [OpenTV Mobile](https://github.com/opentv-mobile/opentv-mobile); see `LICENSE`.
