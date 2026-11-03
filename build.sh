#!/bin/sh
set -eu
cd "$(dirname "$0")"
B="$PWD/build-tools"
V=$(sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' src/dev/eta/acefan/BuildInfo.java)
test -n "$V"
if [ ! -f "$B/keys/pilot.jks" ] || [ ! -f "$B/keys/pass.txt" ]; then
    echo "Existing signing identity is required; refusing to generate replacement keys." >&2
    exit 1
fi
python3 tools/project.py check
# These are disposable compiler outputs only; retain old signed APKs and signing keys.
rm -rf build/classes build/dex
mkdir -p build/classes build/dex "$B/keys"
chmod 700 "$B/keys"
find src -name '*.java' -print > build/sources.txt
javac --release 8 -encoding UTF-8 -Xlint:-options -classpath "$B/platforms/android-35/android.jar:$B/lib/xposed-api-82.jar" -d build/classes @build/sources.txt
jar cf build/code.jar -C build/classes .
java -cp "$B/lib/r8.jar" com.android.tools.r8.D8 --min-api 28 --lib "$B/platforms/android-35/android.jar" --classpath "$B/lib/xposed-api-82.jar" --output build/dex build/code.jar
aapt package -f -M AndroidManifest.xml -I "$B/platforms/android-28/android.jar" -S res -A assets -F build/resources.apk
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
p=Path('build')
with ZipFile(p/'resources.apk') as source,ZipFile(p/'unsigned.apk','w',ZIP_DEFLATED) as target:
    for i in source.infolist(): target.writestr(i,source.read(i.filename))
    target.write(p/'dex/classes.dex','classes.dex')
with ZipFile(p/'unsigned.apk') as z:
    assert z.testzip() is None
    assert 'assets/xposed_init' in z.namelist()
    assert not any('xposed-api' in n for n in z.namelist())
PY
zipalign -f 4 build/unsigned.apk build/aligned.apk
# This existing key uses the store password; let apksigner reuse it. A second file:
# option consumes another line and fails for a one-line password file.
java -cp "$B/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar" com.android.apksigner.ApkSignerTool sign --ks "$B/keys/pilot.jks" --ks-key-alias acefan --ks-pass "file:$B/keys/pass.txt" --out build/ACE7pro_Fan_$V.apk build/aligned.apk
java -cp "$B/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar" com.android.apksigner.ApkSignerTool verify --verbose build/ACE7pro_Fan_$V.apk
zipalign -c 4 build/ACE7pro_Fan_$V.apk
aapt dump badging "build/ACE7pro_Fan_$V.apk" | grep -q "versionName='$V'" || { echo "versionName does not match BuildInfo: $V" >&2; exit 1; }
aapt dump badging "build/ACE7pro_Fan_$V.apk" | head -8
sha256sum build/ACE7pro_Fan_$V.apk

python3 tools/project.py audit-apk "build/ACE7pro_Fan_$V.apk"
