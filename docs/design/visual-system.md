# Visual system and reference review

## Direction

Warm charcoal canvas, restrained periwinkle focus/accent, artwork as the main
colour. Phone: 16dp margins, two 2:3 posters, 12dp gutters, two-line titles and one
metadata line. TV: Foundation lazy layouts, visible border plus modest focus scale,
safe margins and a direct full-grid browser. Keep artwork sharp. Floating surfaces
use dark translucent fill first; enable backdrop effects only after device profiling.
Use 180–280ms motion for decoration; focus feedback must remain immediate.

## Component inventory

Existing: `PosterCard`, `DetailBackdrop`, `DetailButton`, `CatalogueControls`,
`PagedVodGrid`, `CatalogueSearchField`, `SegmentedControl`, OpenTV theme.
Introduce source-owned `JaskPosterCard` and `JaskMetadataRow` at the canonical
browser boundary. Keep feature screens independent of third-party theme APIs.
Next: JaskTheme/Typography/Surface/FrostSurface, FilterSheet, Navigation,
FocusIndicator, VersionSelector and Spotlight. Avoid renaming legacy components
until each feature migrates.

## References inspected

- [Apple TV Library](https://support.apple.com/guide/tv/view-movies-and-tv-shows-in-your-library-atvba6a2c11c/tvos):
  adopt artwork-led inventory, clear categories and direct access to saved media.
  Do not copy Apple purchase/store navigation or imply the full inventory is personal Library.
- [Infuse 8 preview](https://community.firecore.com/t/infuse-8-coming-this-fall-2024/49497):
  adopt modular browsing and poster density. Do not force a home dashboard before the grid.
- [Infuse focus discussion](https://community.firecore.com/t/highlight-selected-item-better-in-user-interface/36643):
  scale alone was reported hard to distinguish. Add a visible local border; do not
  rely on a distant title to explain which card has focus.
- [Plex detail reference](https://mobilesyrup.com/2024/11/22/plex-rebuilding-apps-new-design-faster-navigation/):
  adopt artwork, concise metadata and primary playback actions. Avoid commercial
  discovery clutter. This is a secondary article, not an API specification.
- [Jellyfin versions](https://jellyfin.org/docs/general/server/media/movies/):
  adopt one identity with playable versions; quality and edition are separate.
- [TMDB finding data](https://developer.themoviedb.org/docs/finding-data): search
  considers original/translated/alternative titles; /find resolves external IDs.
  Neither search order nor streaming-provider data proves local availability.
- [TV lists](https://developer.android.com/training/tv/playback/compose/lists):
  use current Foundation lazy layouts with focus-driven scrolling.
- [Coil](https://github.com/coil-kt/coil): retain existing shared ImageLoader and
  disk/memory caches; constrain requests to rendered bounds. No second loader.

## Component candidates

- [Compose Unstyled](https://composeunstyled.com/docs/components/): behavioural
  primitives with separate modules. Preferred candidate for custom sheets and
  controls. Compatibility must be checked against this project's older Kotlin/Compose.
- [Composables UI](https://composables.com/ui): source-owned copy/paste components
  built on Unstyled; preferred starting point, subject to licence and dependency review.
- [RikkaUI](https://github.com/rainxchzed/RikkaUi): alternative source patterns;
  do not import its full theme alongside our theme.
- [Backdrop](https://kyant.gitbook.io/backdrop) /
  [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass): effect layer,
  not application components. Avoid refraction, poster blur and per-focus expensive
  rendering. Flat/translucent fallback is mandatory.

Source clones and exact compatibility findings are recorded separately after local
inspection. A docs review is not a compiled representative-screen evaluation.

## Local source evaluation

Cloned into ignored `.local/references/` (not app dependencies):

| Project | Inspected revision | Source constraints |
|---|---|---|
| Compose Unstyled | efc4f33e6160925f44cadf2c7424ad8086663a76 | Kotlin 2.4.0, Compose 1.12.0, AGP 9.1.1 |
| Composables UI | bbaf1ba5b4c1af32d48dcef14f426f1f562e7ebc | Kotlin 2.4.0, Compose 1.11.1, Unstyled 2.8.0 |
| RikkaUI | 43ac1bcb2534a3f292a652cdaf3754b476350bc0 | Kotlin 2.3.0, Compose 1.10.0; foundation minSdk 24 versus app minSdk 23 |
| Backdrop | eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5 | Kotlin 2.4.10, Compose 1.12.0, compileSdk 37 |

Inspected Unstyled's Button and FocusRing, Composables UI's Button/theme imports,
Rikka's foundation/theme/focusRing, and Backdrop's DrawBackdropModifier and Android
Platform implementation. Unstyled separates behaviour from styling. Composables
UI's Button depends on its theme tokens and Unstyled content providers; copying a
single file is not dependency-free. Rikka's focusRing reads RikkaTheme directly;
adopt the visible-border idea through our own token instead. Backdrop records and
replays graphics layers; RenderEffect requires API 31 and runtime shaders API 33.
The cheap surface must remain useful on API 23 and low-end TV devices.

Decision: do not upgrade the entire app toolchain for this slice. Implement the
representative canonical poster grid in existing Foundation and Coil, behind
app-owned component names. The source patterns are useful; the current libraries
are not drop-in dependencies for the existing versions. A pinned older release
might work but has not been compiled here. No library performance claim is made.
