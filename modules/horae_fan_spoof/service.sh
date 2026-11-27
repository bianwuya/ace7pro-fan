#!/system/bin/sh
MODDIR=${0%/*}
LOGFILE="$MODDIR/status.log"
ORIG_SHA="394ed4b2b152c1fac1c76294cc9d33fcfb6199e391c7854b7db7a53391c2bca0"
PATCH_SHA="eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d"
TARGET="/system_ext/bin/horae"
PATCH_BIN="$MODDIR/horae_patched"
HFS_LIBRARY=1
. "$MODDIR/watchdog.sh"
unset HFS_LIBRARY

log_msg() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOGFILE"
}

rollback_horae() {
    log_msg "ROLLBACK: unmounting $TARGET and restoring stock horae"
    umount -l "$TARGET" 2>/dev/null || true
    stop horae 2>/dev/null || true
    sleep 1
    start horae 2>/dev/null || true
}

apply_testmode() {
    battery_only || return 0
    service_running || return 0
    enable_testmode || return 1
    for i in 0 1 2; do
        battery_only && service_running || return 0
        write_shell_temp "$i" || return 1
    done
}

run_worker() {
    : > "$LOGFILE"
    log_msg "Worker started (boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null))"

    # 1. Wait for full system boot completion first so boot process is never affected
    local waited=0
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 2
        waited=$((waited + 2))
        if [ "$waited" -ge 300 ]; then
            log_msg "ABORT: sys.boot_completed timeout after 300s"
            return 1
        fi
    done
    sleep 8
    # Do not mount/restart/restore while external power is present or unknown.
    while ! battery_only; do sleep 5; done

    # 2. Verify patched binary integrity
    local patch_cur_sha
    patch_cur_sha=$(sha256sum "$PATCH_BIN" 2>/dev/null | awk '{print $1}')
    if [ "$patch_cur_sha" != "$PATCH_SHA" ]; then
        log_msg "ABORT: horae_patched sha256 mismatch ($patch_cur_sha != $PATCH_SHA)"
        return 1
    fi

    # 3. Check current /system_ext/bin/horae sha256
    local target_cur_sha
    target_cur_sha=$(sha256sum "$TARGET" 2>/dev/null | awk '{print $1}')
    if [ "$target_cur_sha" = "$PATCH_SHA" ]; then
        log_msg "INFO: $TARGET is already mounted with patched binary"
    elif [ "$target_cur_sha" != "$ORIG_SHA" ]; then
        log_msg "ABORT: stock $TARGET sha256 changed ($target_cur_sha != $ORIG_SHA); ROM updated? Skipping mount for safety."
        return 1
    else
        # 4. Mount via suid+seclabel tmpfs to avoid /data nosuid_transition SELinux denial
        local tmp_mnt="/dev/horae_patch_tmpfs"
        umount -l "$tmp_mnt" 2>/dev/null || true
        mkdir -p "$tmp_mnt"
        if ! mount -t tmpfs -o rw,seclabel,suid,exec,size=4M,mode=0755 tmpfs "$tmp_mnt"; then
            log_msg "ABORT: failed to mount tmpfs at $tmp_mnt"
            rmdir "$tmp_mnt" 2>/dev/null || true
            return 1
        fi

        cp -f "$PATCH_BIN" "$tmp_mnt/horae"
        chown 0:2000 "$tmp_mnt/horae"
        chmod 0755 "$tmp_mnt/horae"
        chcon u:object_r:horae_exec:s0 "$tmp_mnt/horae"
        mount -o remount,ro,seclabel,suid,exec "$tmp_mnt"

        if ! mount --bind "$tmp_mnt/horae" "$TARGET"; then
            log_msg "ABORT: mount --bind failed"
            umount -l "$tmp_mnt" 2>/dev/null || true
            rmdir "$tmp_mnt" 2>/dev/null || true
            return 1
        fi

        umount "$tmp_mnt" 2>/dev/null || true
        rmdir "$tmp_mnt" 2>/dev/null || true

        stop horae
        sleep 1
        start horae
        sleep 3
    fi

    # 5. Health check with automatic rollback on any failure
    local pid1 pid2 svc_state
    pid1=$(pidof horae 2>/dev/null || true)
    svc_state=$(getprop init.svc.horae)
    if [ -z "$pid1" ] || [ "$svc_state" != "running" ]; then
        log_msg "ERROR: horae failed initial start (pid=$pid1, svc=$svc_state)"
        rollback_horae
        return 1
    fi

    sleep 2
    pid2=$(pidof horae 2>/dev/null || true)
    if [ -z "$pid2" ] || [ "$pid1" != "$pid2" ]; then
        log_msg "ERROR: horae unstable (pid1=$pid1, pid2=$pid2)"
        rollback_horae
        return 1
    fi

    if ! dumpsys horae 2>/dev/null | grep -q "^Temp:"; then
        log_msg "ERROR: dumpsys horae did not return Temp:"
        rollback_horae
        return 1
    fi

    log_msg "SUCCESS: patched horae running stably (PID=$pid2)"

    # 6. Enable testmode and 29.5C external spoofing, then wait 11s for first fitting tick
    apply_testmode
    sleep 11
    log_msg "AFTER APPLY_TESTMODE (11s): $(dumpsys horae | grep -E '^(Temp|TestModeShellTemp|ThrottlingLevel):' | tr '\n' ' ') shell-temp=$(cat /proc/shell-temp 2>/dev/null)"

    # 7. Wait until user unlocks credential storage, then re-verify after 50s
    while [ ! -d "/data/data/android" ]; do
        sleep 2
    done
    sleep 50
    if [ -z "$(pidof horae 2>/dev/null)" ]; then
        log_msg "ERROR: horae died after 60s check"
        rollback_horae
        return 1
    fi
    local spoofed
    spoofed=$(dumpsys horae | sed -n 's/^Temp:[[:space:]]*//p' | head -n 1)
    if [ "$spoofed" != "29.50" ]; then
        log_msg "INFO: re-applying testmode at 60s (Temp was $spoofed)"
        apply_testmode
        sleep 11
    fi
    log_msg "FINAL CHECK (60s): $(dumpsys horae | grep -E '^(Temp|TestModeShellTemp|ThrottlingLevel):' | tr '\n' ' ') shell-temp=$(cat /proc/shell-temp 2>/dev/null)"
}

( run_worker; sh "$MODDIR/watchdog.sh" ) >/dev/null 2>&1 </dev/null &
