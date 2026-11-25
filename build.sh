#!/bin/sh
set -eu
cd "$(dirname "$0")"
B="$PWD/build-tools"
MODE=signed
case "$#:${1:-}" in
    0:) ;;
    1:--unsigned) MODE=unsigned ;;
    *) echo "Usage: sh build.sh [--unsigned]" >&2; exit 2 ;;
esac
KEYSTORE="${ACEFAN_KEYSTORE_PATH:-$B/keys/pilot.jks}"
PASSWORD_FILE="${ACEFAN_PASSWORD_PATH:-$B/keys/pass.txt}"
APKSIGNER_JAR="$B/lib/apksigner.jar"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ "$MODE" = signed ] && { [ ! -f "$KEYSTORE" ] || [ ! -f "$PASSWORD_FILE" ]; }; then
    echo "Existing signing identity is required; refusing to generate replacement keys." >&2
    exit 1
fi
for tool in "$B/bin/aapt2" "$APKSIGNER_JAR"; do
    test -f "$tool" || { echo "Missing APK audit dependency: $tool" >&2; exit 1; }
done
command -v zipalign >/dev/null 2>&1 || { echo "zipalign not found on PATH" >&2; exit 1; }
python3 tools/project.py check
V=$(sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' src/dev/eta/acefan/BuildInfo.java)
test -n "$V"

# There is no javac/D8 fallback. Only the unsigned AGP Compose release is a packaging input.
sh tools/gradle-build.sh :assembleRelease
APK=build/outputs/apk/release/ace7pro-fan-release-unsigned.apk
test -f "$APK" || { echo "Missing AGP unsigned release output: $APK" >&2; exit 1; }
stage=$(mktemp -d "$PWD/build/.package.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 1' HUP INT TERM
zipalign -f 4 "$APK" "$stage/aligned.apk"

# Fail before touching signing material if the final manifest, classes or alignment are wrong.
python3 tools/project.py audit-apk --unsigned "$stage/aligned.apk"
if [ "$MODE" = unsigned ]; then
    out="build/ACE7pro_Fan_${V}-unsigned.apk"
    mv "$stage/aligned.apk" "$out"
    echo "Unsigned validation APK only: $out (not installable)."
    exit 0
fi

# This existing key uses the store password. Using a second file: option consumes another line.
"$JAVA" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign --ks "$KEYSTORE" --ks-key-alias acefan --ks-pass "file:$PASSWORD_FILE" --out "$stage/signed.apk" "$stage/aligned.apk"
python3 tools/project.py audit-apk "$stage/signed.apk"
out="build/ACE7pro_Fan_${V}.apk"
mv "$stage/signed.apk" "$out"
sha256sum "$out"
