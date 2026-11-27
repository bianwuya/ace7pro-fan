#!/system/bin/sh
# Restore the previously deployed fan-only testmode bypass, never the control policy.
# No service start/stop, temperature writes, kernel reloads, or system-partition writes.
OEM_STOCK_SHA=394ed4b2b152c1fac1c76294cc9d33fcfb6199e391c7854b7db7a53391c2bca0
OEM_PATCH_SHA=eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d
OEM_TARGET=/system_ext/bin/horae
OEM_TMP=/dev/eta_acefan_oem_compat
OEM_STATE=/data/adb/eta_acefan
OEM_BB=/data/adb/ksu/bin/busybox

oem_sha() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }
oem_patch_copy() {
    # Derive only from the pinned original, never patch an unknown/new-OTA artifact.
    [ "$(oem_sha "$1")" = "$OEM_STOCK_SHA" ] || return 1
    cp "$1" "$2" || return 1
    # Exactly one old, verified AArch64 branch: file offset0x5328c, b 0x532dc.
    printf '\024\000\000\024' | dd of="$2" bs=1 seek=340620 conv=notrunc 2>/dev/null || return 1
    [ "$(oem_sha "$2")" = "$OEM_PATCH_SHA" ]
}
oem_note() { printf '%s\n' "$*" > "$OEM_STATE/oem-fan-compat.status"; }
oem_running_hash() {
    local p
    p=$(pidof horae 2>/dev/null)
    case "$p" in ''|*[!0-9]*) return 1;; esac
    oem_sha "/proc/$p/exe"
}
oem_compat_apply() {
    local before actual mounted=0
    umask 077
    [ ! -L "$OEM_STATE" ] || return 1
    mkdir -p "$OEM_STATE" || return 1
    chmod 0700 "$OEM_STATE" || return 1
    before=$(oem_sha "$OEM_TARGET")
    if [ "$before" = "$OEM_PATCH_SHA" ]; then
        if [ "$(oem_running_hash)" = "$OEM_PATCH_SHA" ]; then oem_note active_existing;
        else oem_note bound_waiting_normal_service_start; fi
        return 0
    fi
    [ "$before" = "$OEM_STOCK_SHA" ] || { oem_note refused_unknown_stock; return 1; }
    # Private fixed mountpoint; never use an existing symlink or overwrite foreign data.
    [ ! -e "$OEM_TMP" ] && [ ! -L "$OEM_TMP" ] || { oem_note refused_existing_mountpoint; return 1; }
    mkdir -m 0755 "$OEM_TMP" || return 1
    if ! mount -t tmpfs -o rw,seclabel,suid,exec,size=4M,mode=0755 tmpfs "$OEM_TMP"; then
        rmdir "$OEM_TMP" 2>/dev/null
        oem_note tmpfs_failed; return 1
    fi
    if oem_patch_copy "$OEM_TARGET" "$OEM_TMP/horae" &&
       chown 0:2000 "$OEM_TMP/horae" && chmod 0755 "$OEM_TMP/horae" &&
       chcon u:object_r:horae_exec:s0 "$OEM_TMP/horae" &&
       mount -o remount,ro,seclabel,suid,exec "$OEM_TMP" &&
       [ "$(oem_sha "$OEM_TARGET")" = "$OEM_STOCK_SHA" ] &&
       [ "$(oem_sha "$OEM_TMP/horae")" = "$OEM_PATCH_SHA" ]; then
        if mount --bind "$OEM_TMP/horae" "$OEM_TARGET"; then mounted=1; fi
    fi
    if [ "$mounted" = 1 ] && [ "$(oem_sha "$OEM_TARGET")" = "$OEM_PATCH_SHA" ]; then
        actual=$(oem_running_hash)
        if [ "$actual" = "$OEM_PATCH_SHA" ]; then oem_note active_verified;
        else oem_note bound_waiting_normal_service_start; fi
        # Keep the read-only source mount until reboot, avoiding detached/uncertain inodes.
        return 0
    fi
    if [ "$mounted" = 1 ]; then
        umount "$OEM_TARGET" || { oem_note bind_rollback_unconfirmed; return 1; }
    fi
    umount "$OEM_TMP" || { oem_note tmpfs_cleanup_unconfirmed; return 1; }
    rmdir "$OEM_TMP" 2>/dev/null
    oem_note refused_or_mount_failed
    return 1
}
if [ "${OEM_FAN_COMPAT_LIBRARY:-0}" != 1 ]; then
    [ -x "$OEM_BB" ] || exit 1
    if [ "${1:-}" != --global ]; then
        exec "$OEM_BB" nsenter -t 1 -m -- /system/bin/sh "$0" --global
    fi
    oem_compat_apply
fi
