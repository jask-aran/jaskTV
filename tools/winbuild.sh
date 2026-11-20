#!/bin/bash
# Builds the Android app with Gradle on Windows instead of inside WSL, so a build can never exhaust WSL's
# memory (WSL + emulator + Gradle together crashed it). Usage, from anywhere in WSL:
#   tools/winbuild.sh :app:assembleDebug -PjaskPerf
# Syncs the source to C:\Users\jaska\jaskTV-build\tree (build outputs, .git, .local and server/ left out),
# runs gradlew.bat there with the JDK 21 in jaskTV-build\toolchain (Android Studio's bundled JDK 25 breaks
# Kotlin 2.0.21's build scripts with "25.0.3"), then copies app/build/outputs/apk back into this tree, so
# the usual install commands keep working. Debug builds are signed with a copy of WSL's debug key (kept under
# jaskTV-build\android-home), so they install over builds made in WSL; Android Studio's own key is untouched.
# Release builds use the release key in jaskTV-build\release (signing.bat sets KEYSTORE_PATH and passwords).
# Desktop app: jaskTV-build\run-desktop.bat (data in jaskTV-build\desktop-home).
set -e
SRC=$(git -C "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" rev-parse --show-toplevel)
# Refuse anything that isn't this project: a wrong SRC once copied a whole home folder to C:.
if [ ! -f "$SRC/settings.gradle.kts" ] || [ ! -f "$SRC/tools/winbuild.sh" ] || ! grep -q 'include(":app")' "$SRC/settings.gradle.kts"; then
  echo "winbuild: '$SRC' is not the jaskTV project; stopping." >&2; exit 1
fi
WIN=/mnt/c/Users/jaska/jaskTV-build
WINDOWS_DIR='C:\Users\jaska\jaskTV-build'
mkdir -p "$WIN/tree" "$WIN/android-home/.android"

rsync -a --delete \
  --exclude '/.git/' --exclude '/.local/' --exclude '/server/' --exclude '/local.properties' \
  --exclude 'build/' --exclude '.gradle/' --exclude '.kotlin/' \
  "$SRC/" "$WIN/tree/"

# Same account server and client id as WSL builds, plus the Windows SDK.
{ [ -f "$SRC/local.properties" ] && cat "$SRC/local.properties"; echo 'sdk.dir=C\:\\Users\\jaska\\AppData\\Local\\Android\\Sdk'; } > "$WIN/tree/local.properties"
cp -n "$HOME/.android/debug.keystore" "$WIN/android-home/.android/debug.keystore"

cat > "$WIN/gradle.bat" <<EOF
@echo off
set "JAVA_HOME=$WINDOWS_DIR\toolchain\jdk-21.0.13+9"
set "ANDROID_HOME=C:\Users\jaska\AppData\Local\Android\Sdk"
set "ANDROID_USER_HOME=$WINDOWS_DIR\android-home\.android"
rem Release key (copied from .local/release with the user's approval, 2026-11-18): release builds are
rem signed with it; debug builds ignore it.
if exist "$WINDOWS_DIR\release\signing.bat" call "$WINDOWS_DIR\release\signing.bat"
cd /d "$WINDOWS_DIR\tree"
call gradlew.bat %*
EOF

cd /mnt/c
# Kotlin daemon, not in-process: in-process (gradle.properties, to save WSL memory) disables incremental
# compilation ("REBUILD_REASON: In-process execution"), so every change recompiled the whole app (~45 s).
cmd.exe /c "$WINDOWS_DIR\\gradle.bat" "$@" --max-workers=6 -Pkotlin.compiler.execution.strategy=daemon 2>&1 | tr -d '\r'
status=${PIPESTATUS[0]}

if [ -d "$WIN/tree/app/build/outputs/apk" ]; then
  mkdir -p "$SRC/app/build/outputs"
  rsync -a "$WIN/tree/app/build/outputs/apk/" "$SRC/app/build/outputs/apk/"
fi
exit $status
