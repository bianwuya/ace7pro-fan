#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/eta_acefan
mkdir -p "$STATE"
chmod 0700 "$STATE"
LOG="$STATE/service.log"
# No mounts, module reload, horae start/stop, or kernel writes happen here.
# The only early replacement is the pinned preinit program on the next boot.
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
n=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    sleep 2
    n=$((n+1))
    [ "$n" -lt 150 ] || exit 1
 done
# Only restore the existing temperature display on verified battery power.
# This also works when the preinit replacement was refused and stock remains.
if [ -f "$MODDIR/watchdog.sh" ]; then sh "$MODDIR/watchdog.sh" >> "$STATE/spoof.log" 2>&1 & fi
POLICY=/sys/class/leds/fan/eta_policy
if [ ! -r "$POLICY/status" ] || [ ! -w "$POLICY/config" ]; then
    if [ -d /sys/module/oplus_fan ] && [ -r /sys/class/leds/fan/status ]; then
        printf '%s\n' 'kernel_backend=stock reason=early_replacement_not_loaded; no control writer started' >> "$LOG"
    else
        printf '%s\n' 'kernel_backend=unavailable reason=fan_driver_not_loaded; no control writer started' >> "$LOG"
    fi
    exit 0
fi
[ -f "$MODDIR/allow-control" ] || { echo 'kernel_backend=disabled reason=control_not_approved' >> "$LOG"; exit 0; }
# APK path is the original-signature copy inside this exact bundle; never shell-evaluate a setting.
export CLASSPATH="$MODDIR/app/ACEFan.apk"
printf '%s\n' "$$" > "$STATE/relay.pid"
exec /system/bin/app_process /system/bin --nice-name=eta.acefan.relay dev.eta.acefan.KernelSettingsRelay >> "$LOG" 2>&1
