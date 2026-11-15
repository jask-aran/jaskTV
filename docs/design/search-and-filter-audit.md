# Search and filter evidence audit

The raw provider inventory is a captured snapshot, not a claim about every IPTV provider. No credentials or provider URLs are included here.

| Field | Movies (187,486) | Shows (50,281) | What it proves |
|---|---:|---:|---|
| Separate year field | 0 (0.0%) | 0 (0.0%) | Absent in this response; a year in a name remains a hint. |
| Provider genre | 0 (0.0%) | 48,108 (95.7%) | Provider text, not independently validated canonical genre. |
| Provider release date | 0 (0.0%) | 47,837 (95.1%) | Provider claim; movie endpoint supplies none. |
| Provider rating | 164,481 (87.7%) | 45,315 (90.1%) | Unattributed provider score; do not call it TMDB. |
| Year in name | 146,859 (78.3%) | 30,763 (61.2%) | Parseable evidence only; reject remake conflicts. |
| Resolution token in name | 9,164 (4.9%) | 2,380 (4.7%) | Claimed resolution, not observed video dimensions. |

Neither raw response contains dedicated audio-language or subtitle-language fields. Country/language prefixes are not proof of track languages. The provider has 173,218 positive numeric movie TMDB hints (92.4%), but identity must still be validated.

## Fields we can promote

- **Source/provider:** structural source ID, stable across refresh within the installation. Offer inside the filter panel.
- **Media type:** inventory endpoint plus validated TMDB namespace. Four destinations separate movies, shows and live channels.
- **Original production language:** trustworthy only after a reliable canonical TMDB match. Label it Original language; never Audio. Offer as an optional multi-select in the panel, with All languages and an explicit unknown policy.
- **Release year, genre and TMDB rating:** canonical metadata only. Unknown stays unknown. Year and source-labelled rating sorting exist; genre is stored as text, not yet a canonical facet index.
- **Availability:** a successful-import snapshot plus enabled provider, not a successful stream probe. Pre-snapshot inventory remains provisional until a successful refresh.

## Fields we must not imply are complete

- **Available audio/subtitles:** Media3 exposes selected/available tracks during playback, but no per-asset observation store currently persists those facts. No audio-language filter is justified.
- **Observed quality/HDR:** player formats can supply dimensions and colour information; catalogue fields currently retain name-derived quality ranks only. No verified-quality filter.
- **Full release date/country:** CanonicalMovie stores year, not full date or production countries. Provider show dates are not a replacement. No date-range, regional or New Releases Available promise yet.
- **Watch state:** positions remain profile/provider-media-key based. A canonical watched/unwatched facet would be unreliable across versions until progress is bridged.
- **Recently added:** provider addedMillis is not first seen in the viewer’s inventory. Preserve the Provider added date label.

## Search implementation and gaps

Raw movies/series have Room FTS4 indexes on provider names. Existing repositories use word-prefix FTS then a substring fallback only when FTS returns no results. That misses some substring matches when other prefix matches exist. Canonical Movies browse originally searched canonical and original names with instr/lower, without FTS. Dedicated search originally returned raw movie rows and repeated provider versions.

This change makes touch and tablet movie search return canonical cards, including provider-name aliases through identity links. Canonical metadata and provider raw names are queried locally, and result assets are fetched in one bounded batch. Search keeps the existing 200ms debounce and saved recent searches. The touch search scope picker moves into its one header row; raw tag-chip rails are removed. Tablet search uses the touch keyboard path, not the TV keyboard.

Still absent: persisted translated/alternative alias rows, Unicode-normalised alias FTS, typo candidate retrieval, canonical TV identity and exhaustive all-content deduplication. Provider-show results must not be described as canonical. Programme/broadcaster discovery still has an external path; local channel search is distinct from validated channel mappings and observed programme-match confidence.

Next search index: source-owned alias table (canonical namespace/id, alias kind, normalised text, locale, provenance) with Room FTS. Populate canonical/original/translated aliases from bounded metadata jobs and raw/clean names from imports. Retrieve prefix/substring candidates locally, then apply bounded typo scoring to a limited candidate set. Manual aliases survive import refresh. Do not fuzzy-scan 200,000 rows on every keystroke or send each query to TMDB.

## UI policy

Search is the main header action. Filter access is one adjacent icon. Only source, canonical original language and existing truthful sorts are exposed in the representative Movies screen. No persistent filter rails, recommendation rows, rotating spotlight or unqualified audio/quality badges. Shows default to an explicit canonical-identification gap rather than a raw duplicated catalogue; legacy provider inventory remains reachable.

The current preview is an incremental UI build. Broad playback/download and TV regressions were not rerun for this iteration. Three native layout checks passed; the phone preview rendered with real canonical records.
