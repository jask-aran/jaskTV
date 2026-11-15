# Catalogue identity and Movies vertical slice

## Audit of the working tree

The app uses Kotlin 2.0.21, Room 2.6.1, Compose BOM 2024.11.00, Paging 3.3.2,
Coil 2.7.0 and Media3 1.5.0. `ServiceLocator.Graph` owns repositories.
`MoviesScreen` and `SeriesScreen` collect paged Room queries from `VodViewModel`.
`MovieBrowseKey` groups cleaned provider names and years; it is a presentation
heuristic, not canonical identity. Unknown-year names can collide. Series remain
provider records. `TmdbClient.searchId` accepted the first result. Detail enrichment
writes into provider rows. `CollectionEntry` uses sourceId/itemKey; playback and
downloads use provider row keys. Those personal-data contracts require a separate
migration before they can become canonical.

The working tree already contained extensive browsing, theme, translation and
profile changes before this task. Preserve those changes.

## Three stores

1. **Provider inventory**: existing Movie, Series and Episode records. Retain raw
   names, IDs, categories and URLs. Availability means the asset exists in the latest
   successful inventory, not that its endpoint has been probed successfully.
2. **Canonical catalogue**: `CanonicalMovie` keyed by validated TMDB movie ID;
   `MovieIdentityMatch` keyed by (sourceId, streamId), independent of volatile row
   IDs. Metadata belongs here. Match provenance, confidence, evidence fingerprint
   and manual decisions belong on the link. No cascade from inventory to identities.
3. **Personal Library**: existing favourites, collections, downloads and positions.
   Do not silently reinterpret their keys. A later additive canonical-membership
   table must preserve unavailable saved titles and bridge legacy entries.

Movie and TV IDs occupy distinct namespaces. Proposed series identity:
`tmdb:tv:<id>`; episode identity adds numeric season and episode. Names never
identify episodes. Editions belong to assets, distinct from quality variants.

## Matching contract

Validate explicit positive numeric TMDB IDs against the movie endpoint. Otherwise
search with a cleaned title/year. Accept only a unique exact normalised title or
original-title match with the same known year; retain ambiguous and unmatched
results. Do not drop the year to accept a different remake. Unknown years require
manual confirmation. Search result order and popularity are not evidence.

Manual links override automatic links and survive source replacement. Automatic
links are re-evaluated when raw matching evidence changes. Store failed decisions
so an empty search does not run on every app launch. Network errors must leave
work pending. Serial background work, bounded batches, delay and WorkManager
backoff bound traffic. Ordinary grid/detail reads use Room only.

Future work: IMDb /find, translated alias retrieval, calibrated similarity,
manual-confirmation UI, persistent shared lookup cache, metadata refresh TTL,
series/episode matching, source-observed tracks, regional and playback preferences.
Cinemeta may supplement IMDb resolution; it must not replace TMDB identity. OMDb
continues to provide optional source-labelled review scores.

## Query contract

Canonical browse contains only titles linked to current inventory. Use EXISTS for
availability and a stable representative asset. Search canonical and original
names; title order is the default. Original language filters use TMDB metadata,
never provider prefixes. All languages is explicit. Unmatched inventory remains
separate and accessible through existing provider browsing. A manual link may
remain stored while its asset is absent; it must not make a card playable.

Trending is deliberately omitted until a bounded cached TMDB identity list can
be intersected with available high-confidence links and language preferences.
No globally popular fallback. Release date and inventory-added date stay distinct.

## Rollout and verification

The additive Room schema is implemented in source; do not run it against a user's
installed database without approval. Test upgrades in temporary databases. Keep
legacy playback, download and collection keys in this slice. This means personal
organisation is not yet canonical and source removal still has legacy limitations.

Prove identity collisions, remakes, unknown years, ambiguous results, cross-provider
versions, missing inventory, manual-link survival and local paging using fixtures.
Build and unit tests do not prove D-pad behaviour, player tracks or frame timings.
Phone and TV device checks remain separate acceptance gates.

## Real inventory audit

The authorised Xtream inventory was fetched over HTTPS after HTTP returned 403.
Credentials and raw JSON are stored only in ignored `.local/`; the credential file
has mode 0600 and its parent mode 0700. No provider URLs or credentials are included
in this document.

- Movies: 187,486 assets, 446 categories; 173,218 positive numeric TMDB hints,
  representing 62,182 distinct hinted IDs. Largest group: 41 assets per hinted ID.
- Series: 50,281 records, 386 categories; 45,179 positive numeric hints,
  representing 18,696 distinct hinted IDs. Largest group: 31 records per hinted ID.
- Movie TMDB fields mix integer and string JSON values. XtreamApi already handles
  both via `asStringOrNull` and imports `tmdb_id` or `tmdb`.
- 146,859 movie names contain a parenthesised year. This is name evidence, not
  proof of identity or verified stream language.

These are raw provider claims, not validated canonical counts. Validate each
unique TMDB movie hint once and reuse persisted metadata across all copies.
At this size cold enrichment takes sustained background work; never block import,
card rendering or playback on completing enrichment of the entire inventory.
