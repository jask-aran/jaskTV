# Canonical Movies verification

## Executed in WSL

- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`
- Full unit suite: 166 tests, zero failures at the last completed run.
- API 23 Room fixtures cover ten copies, remakes, original-language filtering,
  unknown-language policy, manual correction, stale evidence, missing assets,
  interrupted snapshots, low confidence, stable provider row IDs and favourites.
- Resolver HTTP-interceptor fixtures cover explicit IDs, mismatched IDs, a wrong-year
  first search result and HTTP 429. These tests make no external requests.
- Temporary-database upgrades from v13/v14 validate the full schema through v19
  and preserve existing collection/profile/channel data.
- Debug APK installed beside the release app. Its real v18 database opened with
  the v19 build, retaining the existing 187,487 movie rows.
- A bounded live TMDB sample validated eight provider-hinted IDs. Inception (2010)
  has 25 provider assets with that hint; The Thing (1982) has eight. These are not
  a catalogue-wide matching-accuracy measurement.

## Database performance

`python3 tools/benchmark-catalogue.py` constructs a credential-free synthetic
fixture of 187,486 assets and 62,182 identities, with active inventory presence.
The default query walks `index_canonical_movies_title`; link, source and presence
lookups use indexes. In one WSL run: first 48 rows 1.9ms, offset-30,000 page
110.3ms, total count 215.8ms (medians of three runs). A prior provider-shaped
fixture measured 2.1ms / 89.9ms / 178.3ms before snapshot checks. Timing varies
with data distribution and host load. The original grouping query measured
326.4ms / 559.9ms / 631.3ms.

These are desktop SQLite timings, not Android TV frame measurements. Release-year,
rating and provider-date sorts still aggregate the available catalogue and need
separate device profiling. Contains-search scans canonical metadata; it does not
call TMDB. Deep OFFSET paging still costs more than the first page.

## Pending acceptance gates

Live provider cards and the canonical detail page were observed on the paired
SM-S936B, including artwork, original-language metadata, source labels and return
navigation. The device check found and corrected an unknown-quality label and
theme inconsistency. The Play action opened the existing player, but the sampled stream stayed black
with a loading indicator during observation; successful video playback is unproven.
Downloads and collection save need separate checks. Validate D-pad focus restoration, TalkBack,
TV safe margins and frame timings on an Android TV device. Canonical personal
Library keys, show/episode identity, translations and the remaining filter facets
are outside this Movies slice. See catalogue-identity.md for the precise scope.
