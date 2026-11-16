#!/bin/sh
# Syncs the desktop app into the Windows build copy (Gradle on Windows can't build from the WSL share).
# The copy builds :desktop, :player-core and :shared for the JVM only (no Android SDK needed).
set -e
SRC=/home/jask/jaskTV
WIN=/mnt/c/Users/jaska/jaskTV-win
rsync -a --delete $SRC/desktop/src/ $WIN/desktop/src/
cp $SRC/desktop/build.gradle.kts $WIN/desktop/build.gradle.kts
cp $SRC/gradle/libs.versions.toml $WIN/gradle/libs.versions.toml
mkdir -p $WIN/shared/src
rsync -a --delete $SRC/shared/src/commonMain/ $WIN/shared/src/commonMain/
rsync -a --delete $SRC/shared/src/jvmMain/ $WIN/shared/src/jvmMain/
rsync -a --delete $SRC/shared/schemas/ $WIN/shared/schemas/
rsync -a --delete $SRC/player-core/src/commonMain/ $WIN/player-core/src/commonMain/
