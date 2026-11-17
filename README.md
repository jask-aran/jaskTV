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
./gradlew :app:testDebugUnitTest    # unit and screen tests (Robolectric)
```

Requires JDK 17. To enable accounts in a local build, add the account server to `local.properties`:

```
jasktv.accountServer=https://your-worker.example.workers.dev
```

The account server lives in `server/` (Cloudflare Workers, D1 and Durable Objects):

```sh
cd server && npm install
npm test                             # runs in Cloudflare's local simulator
npx wrangler dev                     # local server on :8787
```

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

## Licence

GPL-3.0-or-later. jaskTV is derived from [OpenTV Mobile](https://github.com/opentv-mobile/opentv-mobile); see `LICENSE`.
