#!/usr/bin/env bash
# Compiles the privileged server into a single dex that the manager app ships in its assets.
#
#   ./scripts/build-server-dex.sh [output.dex]
#
# Requirements: JDK 11+ (javac must be able to target 8) and Android SDK build-tools (d8).
# ANDROID_HOME / ANDROID_SDK_ROOT is used to locate android.jar and d8.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/manager/src/main/assets/server.dex}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?ANDROID_HOME or ANDROID_SDK_ROOT must point at the Android SDK}"

PLATFORM="${PLATFORM:-android-34}"
BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"
ANDROID_JAR="$ANDROID_HOME/platforms/$PLATFORM/android.jar"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8"

for f in "$ANDROID_JAR" "$D8"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

BUILD="$ROOT/server/build"
CLASSES="$BUILD/classes"
rm -rf "$CLASSES" "$BUILD/dex"
mkdir -p "$CLASSES" "$BUILD/dex"

echo "==> compiling (javac, target 8)"
find "$ROOT/common/src/main/java" "$ROOT/server/src/main/java" -name '*.java' > "$BUILD/sources.txt"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -encoding UTF-8 -d "$CLASSES" @"$BUILD/sources.txt" 2> "$BUILD/javac.log"; then
  cat "$BUILD/javac.log" >&2
  echo "compilation failed" >&2
  exit 1
fi
grep -v 'bootstrap class path' "$BUILD/javac.log" || true

echo "==> dexing (d8, min-api 26)"
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  $(find "$CLASSES" -name '*.class')

mkdir -p "$(dirname "$OUT")"
cp "$BUILD/dex/classes.dex" "$OUT"
echo "==> wrote $OUT ($(stat -c%s "$OUT") bytes)"
