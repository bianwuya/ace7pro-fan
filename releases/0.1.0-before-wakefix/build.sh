#!/bin/sh
set -eu
cd "$(dirname "$0")"
B="$PWD/build-tools"
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
if [ ! -f "$B/keys/pilot.jks" ]; then
    python3 - <<'PY'
from pathlib import Path
import secrets,os
p=Path('build-tools/keys/pass.txt');secret=secrets.token_urlsafe(32);p.write_text(secret+'\n'+secret+'\n');os.chmod(p,0o600)
PY
    keytool -genkeypair -keystore "$B/keys/pilot.jks" -storetype JKS -alias acefan -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=ACE7pro Fan Local Pilot' -storepass:file "$B/keys/pass.txt" -keypass:file "$B/keys/pass.txt" >/dev/null 2>&1
    chmod 600 "$B/keys/pilot.jks"
fi
java -cp "$B/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar" com.android.apksigner.ApkSignerTool sign --ks "$B/keys/pilot.jks" --ks-key-alias acefan --ks-pass "pass:$(head -n 1 "$B/keys/pass.txt")" --key-pass "pass:$(head -n 1 "$B/keys/pass.txt")" --out build/ACE7pro_Fan_0.1.0-pilot.apk build/aligned.apk
java -cp "$B/apksigner-extracted/usr/share/java/apksigner-35.0.2.jar" com.android.apksigner.ApkSignerTool verify --verbose build/ACE7pro_Fan_0.1.0-pilot.apk
zipalign -c 4 build/ACE7pro_Fan_0.1.0-pilot.apk
aapt dump badging build/ACE7pro_Fan_0.1.0-pilot.apk | head -8
sha256sum build/ACE7pro_Fan_0.1.0-pilot.apk
