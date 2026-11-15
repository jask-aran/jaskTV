# jaskTV

A simplified IPTV application for Android phones and Android TV, derived from [OpenTV Mobile](https://github.com/opentv-mobile/opentv-mobile) under GPL-3.0-or-later.

The app deliberately keeps the mature upstream M3U, Xtream, Stalker, player, guide import, recording, offline-download and device-adaptive infrastructure. The regular interface is organised around **Live TV**, **Movies & Shows**, and **Library**, not recommendations or a timeline EPG.

## What the foundation branch implements

- **Live TV:** provider channels, favourites, recent channels, category filters and sorting; phone view defaults to the channel list rather than a guide timeline. Global search is one touch away.
- **Movies & Shows:** one top-level tab, switchable movie/series grids, full text search, original title detail/playback/download actions. Curated shelves are bypassed.
- **Library:** favourites, all members of user-created collections and completed offline downloads in All. Create, rename and delete collections; save titles directly from detail pages or organise channels and downloads in Library. Membership uses provider-stable IDs across catalogue refreshes. The existing Downloads manager still handles queue operations.
- **Broadcast discovery:** local XMLTV programme search plus optional TheSportsDB broadcaster listings. A cached IPTV-org `channels.json` catalogue supplies official identities and alternate channel names. Users can manually map a playlist channel to an IPTV-org identity; mappings survive refreshes and participate in both direct channel search and event-to-broadcaster discovery. Candidate streams are never claimed to be verified.
- **Third-party metadata:** original TMDB metadata enrichment and IMDb identifier resolution are retained. An optional, user-keyed OMDb API client adds separately labelled IMDb-reported score/votes, Rotten Tomatoes and Metacritic results to movie and series detail pages. jaskTV never scrapes IMDb pages. The Library's "Rating" sort continues to use locally held provider/TMDB ratings, without mass-calling external APIs.
- **Phone and TV:** both form factors use the same shared catalogue, discovery, library and player. Compose layouts remain device-adaptive and remote focus handling is inherited where possible.
- **Identity:** Android application ID `app.jasktv`, independent of upstream OpenTV. jaskTV should not install OpenTV release APKs as updates.

## What discovery does *not* guarantee

An EPG listing can be incorrect. An external network rights-holder may not carry a particular event on a particular feed. jaskTV distinguishes **EPG-listed**, **external event listing** and **possible broadcaster** rather than claiming any candidate stream has been verified. Users select and play a candidate themselves.

TheSportsDB's public v1 key is `123`, with free-tier result limits and incomplete coverage. The backend may return no useful event; there is no subscription or user API key in this implementation. Only the search terms, not playlist credentials, are sent to TheSportsDB. IPTV-org aliases are reference identities, not actual stream URLs or a guarantee of regional rights. This branch does not yet ingest fixture-specific official broadcaster listings or perform automated stream health measurements.

## External source configuration and privacy

IPTV-org's Unlicense channel directory is fetched on demand and cached on-device for roughly one day; it is not a playlist provider. TheSportsDB receives only the event search term. Neither service receives IPTV credentials. TMDB and OMDb are opt-in per-user API keys stored in the app's local settings; OMDb receives movie/show titles or IMDb identifiers when a title detail is opened. OMDb aggregates ratings reported by other organisations; it is not an official IMDb data licence. Consult each provider's licence and terms before public or commercial redistribution.

The jaskTV Room database moves from v15 to v16 through an additive migration for collections and manual channel identities; keep this migration when rebasing upstream database changes.

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
- [ ] Create/rename/delete collections; add from VOD detail; confirm membership survives a source resync
- [ ] Confirm a collection member appears in Library All even without a favourite star
- [ ] Verify Room v15→v16 migration against a populated database, including retained favourites
- [ ] Test IPTV-org alias lookups with and without country hints; verify manual override selection and persistence
- [ ] Configure TMDB and OMDb keys; check metadata fallback, response attribution and rate limits
- [ ] Test EPG result with a known current programme and channel mapping
- [ ] Check broadcaster-hint empty/mismatch cases; verify labels do not overstate certainty
- [ ] Verify independent jaskTV package name and correct update/download links

## Next work

Search result stream reliability and quality observations, official broadcaster fixture mapping, smarter region/feed disambiguation, local collection reordering and cover art, stronger third-party metadata cache storage, TV-focused library layout, and jaskTV release signing/launcher branding. Keep these as incremental PRs so upstream fixes can continue to be merged.
