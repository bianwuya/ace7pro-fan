#!/system/bin/sh
MODDIR=${0%/*}
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
# Preserve the old OEM game-temperature path before normal service startup.
# The helper only derives a hash-pinned image and binds it read-only; no service restart.
/system/bin/sh "$MODDIR/oem-fan-compat.sh"
