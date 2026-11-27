#!/bin/sh
# All files are temporary fixtures; mount and process inspection are mocked.
set -eu
cd "$(dirname "$0")/.."
OEM_FAN_COMPAT_LIBRARY=1 . packaging/ksu-fan/runtime/oem-fan-compat.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
N=0
assert() { N=$((N+1)); "$@" || { echo "FAIL $N: $*" >&2; exit 1; }; }
# A synthetic fixture verifies the exact one-instruction derivation without OEM files.
dd if=/dev/zero of="$T/original" bs=1 count=340640 2>/dev/null
printf '\210\002\000\066' | dd of="$T/original" bs=1 seek=340620 conv=notrunc 2>/dev/null
cp "$T/original" "$T/expected"
printf '\024\000\000\024' | dd of="$T/expected" bs=1 seek=340620 conv=notrunc 2>/dev/null
OEM_STOCK_SHA=$(oem_sha "$T/original")
OEM_PATCH_SHA=$(oem_sha "$T/expected")
assert oem_patch_copy "$T/original" "$T/derived"
assert cmp "$T/derived" "$T/expected"
assert test "$(wc -c < "$T/derived" | tr -d ' ')" = 340640
assert test "$(oem_sha "$T/original")" = "$OEM_STOCK_SHA"
printf changed > "$T/wrong"
if oem_patch_copy "$T/wrong" "$T/must-not-exist"; then assert false; fi
assert test ! -e "$T/must-not-exist"
OLD=$OEM_PATCH_SHA; OEM_PATCH_SHA=invalid
if oem_patch_copy "$T/original" "$T/bad-result"; then assert false; fi
OEM_PATCH_SHA=$OLD
# Simulated bind alters only the temporary target. No real mount/chcon/service operations.
OEM_TARGET=$T/target
OEM_TMP=$T/private-mount
OEM_STATE=$T/state
cp "$T/original" "$OEM_TARGET"
MOUNTS=0; BINDS=0
mount() {
    MOUNTS=$((MOUNTS+1))
    if [ "$1" = --bind ]; then BINDS=$((BINDS+1)); cp "$2" "$3"; fi
    return 0
}
umount() { return 0; }
chcon() { return 0; }
chown() { return 0; }
oem_running_hash() { printf '%s\n' "$RUNNING_HASH"; }
RUNNING_HASH=$OEM_STOCK_SHA
assert oem_compat_apply
assert test "$BINDS" = 1
assert test "$(cat "$OEM_STATE/oem-fan-compat.status")" = bound_waiting_normal_service_start
assert test "$(oem_sha "$OEM_TARGET")" = "$OEM_PATCH_SHA"
# Already bound is not rebound and must not be misreported as a running patched process.
assert oem_compat_apply
assert test "$BINDS" = 1
assert test "$(cat "$OEM_STATE/oem-fan-compat.status")" = bound_waiting_normal_service_start
RUNNING_HASH=$OEM_PATCH_SHA
assert oem_compat_apply
assert test "$(cat "$OEM_STATE/oem-fan-compat.status")" = active_existing
assert test "$BINDS" = 1
printf unknown > "$OEM_TARGET"
if oem_compat_apply; then assert false; fi
assert test "$(cat "$OEM_STATE/oem-fan-compat.status")" = refused_unknown_stock
assert test "$BINDS" = 1
printf 'OEM game-temperature compatibility: %s assertions PASS (fixtures only)\n' "$N"
