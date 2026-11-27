#!/system/bin/sh
# Runtime repair is restricted to verified battery power. No charging/service lifecycle writes.
MODDIR=${0%/*}
WATCH_LOG="$MODDIR/watchdog.log"
WATCH_STATUS="$MODDIR/watchdog.status"
PATCH_SHA=eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d
# Optional read-only FG debug relay; does not restore temperature while charging.
[ ! -f "$MODDIR/fg-debug.sh" ] || . "$MODDIR/fg-debug.sh"

source_present() { [ -e "/sys/class/power_supply/$1/online" ]; }
power_read() { cat "/sys/class/power_supply/$1/$2" 2>/dev/null; }
battery_only() {
    [ "$(power_read battery status)" = Discharging ] || return 1
    local source online found=0
    for source in usb ac wireless; do
        if source_present "$source"; then
            online=$(power_read "$source" online)
            [ "$online" = 0 ] || return 1
            found=1
        fi
    done
    [ "$found" = 1 ]
}
service_running() { [ "$(getprop init.svc.horae)" = running ]; }
horae_dump() { timeout 4 dumpsys horae 2>/dev/null; }
enable_testmode() { timeout 4 dumpsys horae testmode >/dev/null 2>&1; }
write_shell_temp() { printf '%s 29500\n' "$1" > /proc/shell-temp; }
read_shell_temp() { cat /proc/shell-temp 2>/dev/null; }
watch_status() {
    [ "${LAST_STATUS:-}" = "$1" ] && return
    LAST_STATUS=$1
    printf '%s\n' "$1" > "$WATCH_STATUS"
}
watch_log() {
    local size
    size=$(wc -c < "$WATCH_LOG" 2>/dev/null)
    [ "${size:-0}" -lt 65536 ] || mv -f "$WATCH_LOG" "$WATCH_LOG.1"
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$WATCH_LOG"
}
restore_once() {
    battery_only || { watch_status suspended_external_or_unknown; return 0; }
    service_running || { watch_status waiting_horae; return 0; }
    local dump temp pid current_shell i
    dump=$(horae_dump) || { watch_status read_failed; return 1; }
    temp=$(printf '%s\n' "$dump" | sed -n 's/^Temp:[[:space:]]*//p' | head -n 1)
    [ -n "$temp" ] || { watch_status read_failed; return 1; }
    current_shell=$(read_shell_temp)
    if [ "$temp" = 29.50 ] && [ "$current_shell" = 29500 ] && printf '%s\n' "$dump" | grep -q '^TestModeShellTemp:'; then
        watch_status healthy
        return 0
    fi
    # Revalidate immediately before EACH mutation; never start/stop horae or touch charge state.
    battery_only && service_running || { watch_status suspended_external_or_unknown; return 0; }
    if ! printf '%s\n' "$dump" | grep -q '^TestModeShellTemp:'; then
        enable_testmode || { watch_status repair_failed; return 1; }
    fi
    for i in 0 1 2; do
        battery_only && service_running || { watch_status suspended_external_or_unknown; return 0; }
        write_shell_temp "$i" || { watch_status repair_failed; return 1; }
    done
    watch_status repair_submitted
    watch_log "Restored shell temperature after drift/restart (previous=$temp)."
}
run_watchdog() {
    # Android mksh may close extra FDs on exec; use an atomic directory lock instead.
    local lock="$MODDIR/.watchdog.lockdir" owner command
    if ! mkdir "$lock" 2>/dev/null; then
        owner=$(cat "$lock/pid" 2>/dev/null)
        case "$owner" in ''|*[!0-9]*) owner='';; esac
        if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then
            command=$(tr '\000' ' ' < "/proc/$owner/cmdline" 2>/dev/null)
            case "$command" in *"$MODDIR/watchdog.sh"*) return 0;; esac
        fi
        rm -f "$lock/pid"
        rmdir "$lock" 2>/dev/null || return 0
        mkdir "$lock" 2>/dev/null || return 0
    fi
    printf '%s\n' "$$" > "$lock/pid"
    trap 'watch_status stopped; exit 0' TERM INT
    trap 'if [ "$(cat "$MODDIR/.watchdog.lockdir/pid" 2>/dev/null)" = "$$" ]; then rm -f "$MODDIR/.watchdog.lockdir/pid"; rmdir "$MODDIR/.watchdog.lockdir" 2>/dev/null; fi' EXIT
    local pid verified_pid='' actual
    while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
        if command -v publish_fg_debug >/dev/null 2>&1; then publish_fg_debug; fi
        if battery_only && service_running; then
            pid=$(pidof horae 2>/dev/null)
            if [ -n "$pid" ] && [ "$pid" != "$verified_pid" ]; then
                actual=$(sha256sum "/proc/${pid%% *}/exe" 2>/dev/null | awk '{print $1}')
                case "$actual" in "$PATCH_SHA"|394ed4b2b152c1fac1c76294cc9d33fcfb6199e391c7854b7db7a53391c2bca0) verified_pid=$pid;; *) verified_pid='';; esac
            fi
            if [ -n "$verified_pid" ]; then restore_once; else watch_status unsupported_horae; fi
        else
            watch_status suspended_external_or_unknown
            verified_pid=''
        fi
        sleep 5
    done
    watch_status disabled
}
[ "${HFS_LIBRARY:-0}" = 1 ] || run_watchdog
