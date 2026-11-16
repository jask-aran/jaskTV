# jaskTV

jaskTV is a Kotlin/Jetpack Compose IPTV application for Android phones, tablets and Android TV, derived from OpenTV Mobile.

The current touch interface has four destinations: **Live TV, Movies, Shows and Library**, with a compact search header and floating frosted navigation. Development is moving Live TV toward a personal programme guide and Movies toward a deduplicated local canonical catalogue. Provider integrations, Media3 playback, downloads and personal Library remain part of the app.

## Development

Read [HANDOFF.md](HANDOFF.md) for the current implementation, installed debug build, verification results, known failures and next actions. Read [AGENTS.md](AGENTS.md) before making changes.

Build from the WSL project directory with the configured Android SDK and Java toolchain:

```sh
./gradlew --offline :app:assembleDebug --console=plain
```

Offline builds require the existing dependency cache. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Device installation instructions and public reference links are in HANDOFF.md. Credentials belong only in ignored local storage and must never be committed.

## Documentation

This project keeps three Markdown documents: README.md for orientation, AGENTS.md for operator rules, and HANDOFF.md for current work and reference material. The former setup, architecture, testing, release, privacy, design and changelog documents are preserved in HANDOFF.md's reference appendix.

## Licence and credits

GPL-3.0-or-later; see [LICENSE](LICENSE). jaskTV builds on [OpenTV Mobile](https://github.com/opentv-mobile/opentv-mobile) and [OpenTV](https://github.com/opentvproject/opentv). Prior credits and release history remain in the handoff reference appendix.
