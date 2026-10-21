#!/usr/bin/env bash
# Run-Karl-Run — build without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/36.1.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-36.1/android.jar}"

KS="${RUNKARL_KS:-}"
KS_PASS_FILE="${RUNKARL_KS_PASS:-}"

OUT="$ROOT/build"
DIST="$ROOT/dist"
NAME="RunKarlRun-1.0.apk"

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" \
         "$BUILD_TOOLS/apksigner" "$PLATFORM"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done
[ -n "$KS" ] || {
    cat >&2 <<'EOS'
RUNKARL_KS is not set. Point it at your signing keystore, e.g.

    keytool -genkeypair -keystore my.jks -alias runkarlrun \
            -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=your name"

    RUNKARL_KS=$PWD/my.jks RUNKARL_KS_PASS=$PWD/my.pass ./build.sh

RUNKARL_KS_PASS should point at a file containing the keystore password.
EOS
    exit 1
}
[ -f "$KS" ] || { echo "missing keystore: $KS" >&2; exit 1; }
[ -n "$KS_PASS_FILE" ] && [ -f "$KS_PASS_FILE" ] || {
    echo "RUNKARL_KS_PASS is not set, or does not name an existing password file" >&2
    exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "[1/5] aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[2/5] aapt2 link"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res.zip"

echo "[3/5] javac"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM" \
        -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    grep -v 'bootstrap class path' "$OUT/javac.log" >&2
    exit 1
fi
grep -v 'bootstrap class path' "$OUT/javac.log" || true

echo "[4/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/5] package, align, sign"
python3 - "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import shutil, sys, zipfile
src, dst, dex = sys.argv[1:4]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KS" \
    --ks-pass "file:$KS_PASS_FILE" \
    --ks-key-alias runkarlrun \
    --out "$DIST/$NAME" "$OUT/aligned.apk"

echo
"$BUILD_TOOLS/apksigner" verify --print-certs "$DIST/$NAME"
"$BUILD_TOOLS/aapt2" dump badging "$DIST/$NAME" | head -4
sha256sum "$DIST/$NAME"
