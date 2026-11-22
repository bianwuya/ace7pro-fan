#!/bin/sh
# Run the pinned Gradle project. Never install a local SDK, JDK, or Gradle distribution.
set -eu
cd "$(dirname "$0")/.."
GRADLE=${ACEFAN_GRADLE:-gradle}
command -v "$GRADLE" >/dev/null 2>&1 || {
    echo "Gradle 9.7.1 not found; set ACEFAN_GRADLE to its bin/gradle or add it to PATH." >&2
    exit 1
}
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ ! -f "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK}/platforms/android-37.2/android.jar" ] ||
   [ ! -f "$ANDROID_HOME/build-tools/36.0.0/source.properties" ]; then
    echo "Android SDK platform 37.2 and SDK Build Tools 36.0.0 are required." >&2
    exit 1
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$PWD/build/android-user-home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/build/gradle-home}"
if [ ! -f build-tools/lib/xposed-api-82.jar ]; then
    echo 'Missing build-tools/lib/xposed-api-82.jar (compileOnly, Xposed API 82).' >&2
    exit 1
fi
if [ "${ACEFAN_GRADLE_ONLINE:-0}" != 1 ]; then
    set -- --offline "$@"
fi
exec "$GRADLE" --no-daemon --max-workers=2 --no-parallel --no-configuration-cache --console=plain "$@"
