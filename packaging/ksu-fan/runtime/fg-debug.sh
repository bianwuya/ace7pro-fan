#!/system/bin/sh
# Read-only measurement guard relay. This never writes any driver or temperature node.
fg_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
fg_elapsed() { awk '{printf "%.0f", $1 * 1000}' /proc/uptime 2>/dev/null; }
fg_flag() { cat /sys/module/oplus_fan/parameters/dbg_rpm 2>/dev/null; }
fg_publish() { setprop sys.eta.acefan.fgdebug "$1"; }
publish_fg_debug() {
    local boot elapsed flag
    boot=$(fg_boot)
    elapsed=$(fg_elapsed)
    flag=$(fg_flag)
    case "$boot" in ????????-????-????-????-????????????) ;; *) fg_publish ''; return 1;; esac
    case "$elapsed" in ''|*[!0-9]*) fg_publish ''; return 1;; esac
    case "$flag" in -1) ;; ''|*[!0-9]*) fg_publish ''; return 1;; esac
    fg_publish "$boot|$elapsed|$flag"
}
