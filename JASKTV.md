# jaskTV

A simplified IPTV application for Android phones and Android TV, derived from [OpenTV Mobile](https://github.com/opentv-mobile/opentv-mobile) under GPL-3.0-or-later.

The app deliberately keeps the mature upstream M3U, Xtream, Stalker, player, guide import, recording, offline-download and device-adaptive infrastructure. The regular interface is organised around **Live TV**, **Movies & Shows**, and **Library**, not recommendations or a timeline EPG.

## What the foundation branch implements

- **Live TV:** provider channels, favourites, recent channels, category filters and sorting; phone view defaults to the channel list rather than a guide timeline. Global search is one touch away.
- **Movies & Shows:** one top-level tab, switchable movie/series grids, full text search, original title detail/playback/download actions. Curated shelves are bypassed.
- **Library:** favourites across channels/movies/series plus completed offline downloads in All, and the upstream Downloads manager for queue management.
- **Broadcast discovery:** local XMLTV title search over the present/imminent guide; results are joined using existing provider, matched and manually overridden channel IDs. Optional TheSportsDB event TV listings supply additional broadcaster names, matched against IPTV channel names. If no event-specific data exists, a few explicitly labelled broadcaster hints are offered for F1/NBA.
- **Phone and TV:** both form factors use the same shared catalogue, discovery, library and player. Compose layouts remain device-adaptive and remote focus handling is inherited where possible.
- **Identity:** Android application ID `app.jasktv`, independent of upstream OpenTV. jaskTV should not install OpenTV release APKs as updates.

## What discovery does *not* guarantee

An EPG listing can be incorrect. An external network rights-holder may not carry a particular event on a particular feed. jaskTV distinguishes **EPG-listed**, **external event listing** and **possible broadcaster** rather than claiming any candidate stream has been verified. Users select and play a candidate themselves.

TheSportsDB's public v1 key is `123`, with free-tier result limits and incomplete coverage. The backend may return no useful event; there is no subscription or user API key in this implementation. Only the search terms, not playlist credentials, are sent to TheSportsDB. This branch does not yet use IPTV-org aliases, fixture-specific official listings or automated stream health measurements.

## Development

Requirements: JDK 17, Android SDK 35, Android Gradle plugin/Gradle wrapper from upstream.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK appears at `app/build/outputs/apk/debug/`. The `Build` GitHub Actions workflow also uploads the `jasktv-debug-apk` artifact on enabled workflows.

Use only playlists and broadcasts you are authorised to access. The app does not supply streams.

## Review checklist

- [ ] CI compilation and Room/KSP validation on Android SDK 35
- [ ] Phone portrait, landscape and search keyboard smoke test
- [ ] Android TV D-pad navigation (including Library rows and VOD switch)
- [ ] M3U and Xtream source setup, sync and playback
- [ ] Favourite/unfavourite channels, movies and shows; survive source resync
- [ ] Download one supported VOD file, relaunch and play offline in Library
- [ ] Test EPG result with a known current programme and channel mapping
- [ ] Check broadcaster-hint empty/mismatch cases; verify labels do not overstate certainty
- [ ] Verify independent jaskTV package name and correct update/download links

## Next work

User-defined Library collections, search result stream reliability and quality observations, IPTV-org alias normalization, fixture ingestion from official sources, richer TV-specific browsing, and jaskTV release signing/branding. Keep these as incremental PRs so upstream fixes can continue to be merged.
