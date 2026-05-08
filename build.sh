#!/usr/bin/env bash
# Build the mod jar for desktop AND Android.
# Final jar contains both .class files (desktop) and classes.dex (Android).
#
# Required env / tools:
#   - JDK 17+ on PATH (or JAVA_HOME set)
#   - Mindustry.jar at $MINDUSTRY_JAR (defaults to ../Mindustry.jar)
#   - Android SDK build-tools 30+ d8.jar at $D8_JAR
#   - Android platform jar at $ANDROID_JAR (any android-XX/android.jar)
set -euo pipefail

MINDUSTRY_JAR="${MINDUSTRY_JAR:-../Mindustry.jar}"
D8_JAR="${D8_JAR:-/tmp/bt34/android-14/lib/d8.jar}"
ANDROID_JAR="${ANDROID_JAR:-/mnt/c/Users/leosk/AppData/Local/Android/Sdk/platforms/android-29/android.jar}"
OUT_JAR="BetterServerBrowser.jar"
OUT="build/classes"

[[ -f "$MINDUSTRY_JAR" ]] || { echo "Mindustry.jar not found at $MINDUSTRY_JAR"; exit 1; }
[[ -f "$D8_JAR" ]]        || { echo "d8.jar not found at $D8_JAR"; exit 1; }
[[ -f "$ANDROID_JAR" ]]   || { echo "android.jar not found at $ANDROID_JAR"; exit 1; }

rm -rf build && mkdir -p "$OUT" build/dex

echo "Compiling (--release 8 for Android compat)..."
javac --release 8 -cp "$MINDUSTRY_JAR" -d "$OUT" src/betterserverbrowser/*.java

echo "Packaging desktop jar..."
cp mod.hjson "$OUT/mod.hjson"
jar cf "$OUT_JAR" -C "$OUT" .

echo "Dexing for Android..."
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api 14 \
    --classpath "$ANDROID_JAR" \
    --classpath "$MINDUSTRY_JAR" \
    --output build/dex \
    "$OUT_JAR"

echo "Repacking with classes.dex..."
cp build/dex/classes.dex "$OUT/classes.dex"
jar cf "$OUT_JAR" -C "$OUT" .

echo "Built $OUT_JAR — $(du -h "$OUT_JAR" | cut -f1)"
