# Mobile and tablet layout direction

## Shared structure

Movies is the initial destination. All touch windows use one header and one centred floating bar: Live TV, Movies, Shows, Library. Tablets do not inherit the TV sidebar. The actual TV shell remains separate. Content has one vertical scroll surface with top and bottom scroll padding; media can pass behind the glass chrome. No spotlight is shown until factual inventory-qualified candidates exist.

`JaskChromeFrame` records the content into one Compose graphics layer. `JaskFrostSurface` draws the corresponding region beneath chrome and applies a 20dp GPU RenderEffect blur on API 31+ except low-RAM devices. A tinted translucent surface and gradient highlight/border form the cheaper fallback. This is real source sampling, not blur applied to icons/text, and does not use bitmap readback. No refraction. This implementation uses existing Compose Foundation rather than upgrading the whole toolchain for the current Backdrop checkout.

`JaskHeader` is one 52dp row: compact jaskTV wordmark, expanding 48dp search surface with adjacent filter icon, and a 48dp account target. Account contains profiles, settings, source setup and refresh. Search opens its focused search flow. The search flow itself is still a separate navigation destination; integrating its results into the same persistent shell is a remaining step.

`JaskFloatingNavigation` is 66dp high, at most 432dp wide, 12dp above the already-consumed safe-area bottom. Four equal targets retain labels. Selection animates the periwinkle fill; typography/artwork stay sharp. Catalogue scroll padding reserves 104dp below content, so the last row clears the bar.

## Layout prototypes

| Window | Header | Poster grid | Navigation |
|---|---|---|---|
| Phone portrait 390×844dp | One 52dp row; search takes remaining width | 2 columns, 16dp margins | Width constrained by 16dp margins |
| Tablet portrait 800×1280dp | Same row, expanded search | Approximately 4 columns at 176dp minimum | Centred, max 432dp |
| Tablet landscape 1280×800dp | Same row; no sidebar | Approximately 6 columns | Centred, max 432dp |

Native `@Preview` functions in JaskLayoutPrototypes.kt use the same chrome and poster components. They are fixtures for geometry, not a production catalogue or fabricated recommendations. The production Movies screen continues collecting paged canonical Room data. No demo array enters that query path.

## Reload behaviour

Background matching invalidates Room queries. The existing grid inserted a loading component above retained cards on every invalidation, causing repeated vertical movement that looked like reloads. Retained cards now stay in place during refresh. A failed provider import also used to clear vodRequested, permitting another full attempt after screen re-entry. Automatic attempts now remain latched; explicit Retry/Refresh can try again. These fixes do not suppress missing-data errors or label indexing as a provider reload.

## Boundaries

Canonical Shows, local alias/typo FTS, a fully persistent all-content search shell, and canonical Library progress remain separate work. Raw Shows are not promoted into the default canonical catalogue. Existing source, playback, download and collection actions remain accessible. Do not claim the old TV rails or legacy provider-inventory screens meet this new touch layout.

## Verification status

The first Robolectric captures failed in Compose WindowCapture.forceRedraw with
“Condition still not satisfied after 2000 ms”. Semantics/layout assertions passed
before capture. This is not evidence of a device reload loop. Native View.draw
captures now pass at 390×844, 800×1280 and 1280×800. They show the cheap fallback
with fixture poster geometry. The API 31+ blur path also rendered on the paired
SM-S936B using real canonical poster data. This is a visual check, not a frame-time
benchmark.


[Phone portrait](prototypes/phone-portrait.png) · [Tablet portrait](prototypes/tablet-portrait.png) · [Tablet landscape](prototypes/tablet-landscape.png)

Background identity results now checkpoint in groups of eight, so the grid is not
invalidated for every individual match. Network calls occur outside Room write
transactions. Cancellation can repeat up to seven uncommitted matches; manual
links still override automatic links inside each transaction. The old per-card
loading-row refresh is gone. A failed initial provider import stays latched for
the current ViewModel session until explicit Retry/Refresh.
