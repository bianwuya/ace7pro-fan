#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
HFS_LIBRARY=1 . modules/horae_fan_spoof/watchdog.sh
COUNT=0
assert() { COUNT=$((COUNT+1)); "$@" || { echo "FAIL $COUNT: $*" >&2; exit 1; }; }
source_present() { [ "$1" = usb ]; }
power_read() { case "$1/$2" in battery/status) echo "$BAT";; usb/online) echo "$USB";; esac; }
BAT=Discharging USB=0
assert battery_only
USB=1; assert test "$(battery_only && echo yes || echo no)" = no
USB=0 BAT=Charging; assert test "$(battery_only && echo yes || echo no)" = no
BAT=Full; assert test "$(battery_only && echo yes || echo no)" = no
BAT=Unknown; assert test "$(battery_only && echo yes || echo no)" = no
BAT=Discharging USB=unknown; assert test "$(battery_only && echo yes || echo no)" = no
USB=0
service_running() { [ "$RUNNING" = 1 ]; }
horae_dump() { printf '%s\n' "$DUMP"; }
read_shell_temp() { printf '%s\n' "$SHELL_TEMP"; }
write_shell_temp() { WRITES=$((WRITES+1)); }
enable_testmode() { ENABLES=$((ENABLES+1)); }
watch_status() { STATUS=$1; }
watch_log() { :; }
reset() { RUNNING=1; WRITES=0; ENABLES=0; SHELL_TEMP=29500; BAT=Discharging; USB=0; DUMP='Temp:29.50
TestModeShellTemp:35000'; }
reset; restore_once; assert test "$STATUS" = healthy; assert test "$WRITES" = 0; assert test "$ENABLES" = 0
reset; USB=1; DUMP='Temp:37.00'; restore_once; assert test "$WRITES" = 0; assert test "$ENABLES" = 0; assert test "$STATUS" = suspended_external_or_unknown
reset; BAT=Charging; DUMP='Temp:37.00'; restore_once; assert test "$WRITES" = 0; assert test "$ENABLES" = 0
reset; RUNNING=0; restore_once; assert test "$STATUS" = waiting_horae; assert test "$ENABLES" = 0
reset; DUMP='Temp:37.00'; restore_once; assert test "$ENABLES" = 1; assert test "$WRITES" = 3; assert test "$STATUS" = repair_submitted
reset; SHELL_TEMP=31000; restore_once; assert test "$ENABLES" = 0; assert test "$WRITES" = 3
reset; DUMP='invalid'; restore_once || :; assert test "$STATUS" = read_failed; assert test "$WRITES" = 0
# A plug edge between dump and repair must block both writes and testmode.
reset
horae_dump() { USB=1; printf '%s\n' 'Temp:37.00'; }
# command substitution isolates variables; model the edge in the next power gate instead.
GATES=0
battery_only() { GATES=$((GATES+1)); [ "$GATES" -lt 2 ]; }
restore_once; assert test "$WRITES" = 0; assert test "$ENABLES" = 0
# Plug edge during three-channel restoration must stop further writes.
reset; GATES=0
battery_only() { GATES=$((GATES+1)); [ "$GATES" -lt 4 ]; }
restore_once; assert test "$WRITES" = 1
printf 'horae watchdog: %s assertions PASS\n' "$COUNT"
