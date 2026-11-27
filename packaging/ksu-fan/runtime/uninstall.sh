#!/system/bin/sh
MODDIR=${0%/*}
# Stop only our settings relay. It relinquishes; even SIGKILL cannot extend its <=5s kernel lease.
STATE=/data/adb/eta_acefan
p=$(cat "$STATE/relay.pid" 2>/dev/null)
case "$p" in ''|*[!0-9]*) ;; *)
    c=$(tr '\000' ' ' < "/proc/$p/cmdline" 2>/dev/null)
    case "$c" in 'eta.acefan.relay'|'eta.acefan.relay '*) kill -TERM "$p" 2>/dev/null;; esac
;; esac
if [ -f "$MODDIR/.watchdog.lockdir/pid" ]; then
    p=$(cat "$MODDIR/.watchdog.lockdir/pid" 2>/dev/null)
    case "$p" in ''|*[!0-9]*) ;; *)
        c=$(tr '\000' ' ' < "/proc/$p/cmdline" 2>/dev/null)
        case "$c" in *"$MODDIR/watchdog.sh"*) kill -TERM "$p" 2>/dev/null;; esac
    ;; esac
fi
# Never unload a live hardware driver or uninstall the user's App/data.
# KSU removes its staged preinit entry; next boot uses the unchanged stock driver.
