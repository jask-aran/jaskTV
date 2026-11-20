# Operator rules

Inspect the current source and diff before continuing; the working tree contains substantial uncommitted work.

The user ships a lot of code but is not a professional software engineer. They read prose closely and diffs shallowly. Put risk in words: what behaviour changed, what you verified and how, and what is still unproven.

Keep “ran it, output below” and “not run, expected X” apart. Quote failures exactly. If you have not read an API, flag or config key in this repository or its documentation, say you are guessing.

Before anything that cannot be undone (deleting data or branches, force push, history rewrite, migrations, writing outside the project, sending data to a network endpoint), name the exact command and what it destroys, and wait. Standing authorizations: querying the user's provider, reading public references and library repos, and installing, launching and testing the debug app (`app.jasktv.debug`) on the paired phone. Installing on the Fire TV needs the user to say so (see below).

Use the real code and design names. When the user describes a component, state its real name and code name once, then use those names. When a name does not exist or code and design disagree, say so and suggest one. Use plain words elsewhere.

## Working practice

- Continue in the existing Kotlin/Compose stack and WSL workspace. Preserve playback, sources, downloads, Library and sync.
- **Short loops.** Change → one build → install → look. The repo has no tests by the user's choice (2026-11-18); don't add test suites, multi-round A/Bs or long measurement runs. Anything over about 2 minutes: say how long it will take and ask first.
- Preserve other work in the shared working tree. Stage focused, understood changes; do not reset or indiscriminately commit the whole tree. Commit or push only when asked.
- Keep credentials, stream URLs and private provider data in ignored `.local/` (mode 700, secrets 600). Never put secrets in documentation, source, logs or commits, and never print them to the session.
- Project Markdown is only README.md and AGENTS.md. Don't create others; keep this file short.
- Do not spawn subagents unless the user or applicable instructions explicitly ask for delegation.
- UI: neutral glass, grey/white highlights. Don't reintroduce purple selection fills.

## Builds

- **Windows (preferred):** `tools/winbuild.sh <gradle args>` syncs the tree to `C:\Users\jaska\jaskTV-build\tree` and builds there with its JDK 21. Android Studio's JDK 25 fails with "25.0.3". It uses incremental Kotlin and copies `app/build/outputs/apk` back. A one-line change takes about 50 s; an unchanged rebuild takes about 9 s.
  - Leftover Gradle daemons can lock lint jars ("being used by another process"). Stop them with that Gradle version's `--stop`. Never kill all `java.exe`.
  - Defender exclusion for `jaskTV-build` and `.gradle` was still pending on 2026-11-18. It needs admin, and the user runs it.
- **WSL:** `./gradlew :app:assembleDebug`. It is slower: `gradle.properties` uses in-process Kotlin to save memory, which disables incremental compilation. Run one heavy job at a time (Gradle plus an emulator once crashed WSL), and stop Gradle and the Kotlin daemon afterwards.
- **`-PjaskPerf`:** same package, key and data as the debug build, but not debuggable, so ART optimises it. Use it for anything the user looks at or anything performance-related; debuggable builds overstate jank roughly 5×. `run-as` doesn't work on it.
- **Release:** `tools/winbuild.sh :app:assembleRelease` signs with the release key. The key is `.local/release/jasktv-release.jks` (alias `jasktv`, SHA-256 `0d3d1a…c4f4`), with a copy plus `signing.bat` in `jaskTV-build\release`.
  - Losing the key means installed apps can never update; keep it backed up.
  - CI uses the GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
  - Releases are tags `vX.Y.Z` on `jask-aran/jaskTV`; the app's updater reads `update.json` from the latest release (release builds only).
- **Desktop (Windows):** `tools/winbuild.sh :desktop:createDistributable`, then run `C:\Users\jaska\jaskTV-build\run-desktop.bat` (data in `jaskTV-build\desktop-home`). It uses libmpv; `desktop/libs/mpv/windows-x64/libmpv-2.dll` is gitignored.
- `app/src/main/baseline-prof.txt`: an ART profile generated on 2026-11-18. It showed no measurable gain on the emulator; the generator module was removed.

## Devices

- **ADB from WSL:** use the Windows binary `/mnt/c/Users/jaska/AppData/Local/Android/Sdk/platform-tools/adb.exe`; Linux adb can't pair. Always pass `-s`. Find serials with `adb.exe devices -l` or `adb.exe mdns services`.
  - If the server won't answer: `adb.exe kill-server; (adb.exe -a nodaemon server start &)`.
  - Pass APK paths through `wslpath -w`. Screenshots finish writing about 2 s after `exec-out screencap` returns.
- **Fire TV 4K Max (AFTKRT, Android 11):** it's on the LAN and in daily use.
  - Don't send keys, play or cast to it. Install only when the user says it's free, and preferably while it's asleep.
  - It outputs 4K at 59.94 Hz while the UI renders at 1080p. Debug and perf builds install as `app.jasktv.debug`, next to the release app `app.jasktv`.
- **Phone:** Samsung SM-S936B, wireless debugging paired with Windows adb. On loss of pairing: Developer options → Wireless debugging → Pair with code, then `adb.exe pair` and `adb.exe connect`.
- **Emulator (Windows, with a window you can drive):** AVD `jasktv_firetv` (Android 11 TV, 1080p, 2 GB, 4 cores, modelled on the stick; restored app data, not signed in). Start: `emulator.exe -avd jasktv_firetv -no-snapshot-save -gpu angle_indirect` (the default GPU mode flickered). Serial `emulator-5554`; if adb says "unauthorized", `adb.exe -s emulator-5554 reconnect`. `.local/w name [keycodes]` presses keys and screenshots to `.local/shots/`. It can't see the TV or phone (its own network), so LAN sync can't be tested on it. The WSL emulators were deleted on 2026-11-18 (disk full).
- **Phone emulator (Windows):** AVD `jask_phone` (Android 34 `google_apis`, Pixel 7 profile 1080x2400, 3 GB, 4 cores; `adb root` works). Start: `emulator.exe -avd jask_phone -no-snapshot-save -no-boot-anim -gpu angle_indirect`; serial `emulator-5556` when the TV emulator is up. Its app data is a copy of the TV emulator's (copied with `run-as` from a debuggable build), so it likely shares that install's device identity. `.local/pe.sh name "tap x y" "hold x y" "up" "key N" "sleep N"` drives it (coordinates on the 540-wide screenshot) and saves to `.local/shots/`. The older `Pixel_10_Pro_XL` AVD is slow and starts an old Conduit build; don't use it.
  - Emulator noise is large; use it for "does it work", not for small performance differences.
- Package `app.jasktv.debug`, activity `app.opentv.MainActivity`. Don't install over other packages.

## Local material (`.local/`, ignored)

- `provider.json` holds provider credentials; read it in code and never print it.
- `tmdb-api-key`.
- Private provider samples and DB snapshots.
- `release/`: the release key and its passwords.

## Open items (2026-11-18)

- Chromecast/DLNA casting is blocked on the user. Questions asked: do they have a Cast device; default or custom receiver; OK to download the Cast SDK.
- Not yet run on the real stick:
  - frame-rate matching (`JaskFrameRateMatch`, `FrameClock`);
  - Play on between two real devices;
  - the Compose 1.11 / AGP 8.13.2 / Gradle 8.14.7 upgrade.
- Kotlin 2.0.21 warns that it is untested with AGP 8.13.2; the fix is upgrading Kotlin.
- Not checked since the Compose 1.11 upgrade: the player, Live and Settings.
- Main-thread cost on TV scroll: composition about 43%, bring-into-view scroll about 21%, draw about 20%.
- Many uncommitted changes; commit in focused pieces when the user asks.
