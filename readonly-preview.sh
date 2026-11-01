#!/system/bin/sh
# Explicit, bounded, read-only device preview. Never controls fan or temperature nodes.
set -u
API=content://dev.eta.acefan.data
BOOT=$(cat /proc/sys/kernel/random/boot_id)
MODEL=$(getprop ro.build.display.id)
num() { timeout 1 cat "$1" 2>/dev/null | awk 'NR==1 {if ($0 ~ /^-?[0-9]+$/) print $0; else print "null"; found=1} END {if (!found) print "null"}'; }
temp() { timeout 1 cat "$1" 2>/dev/null | awk -v div="$2" 'NR==1 {if ($0 ~ /^-?[0-9]+$/) printf "%.3f\n",$0/div; else print "null"; found=1} END {if (!found) print "null"}'; }
BOARD= FAN= MOS=
for d in /sys/class/thermal/thermal_zone*; do
  case "$(cat "$d/type" 2>/dev/null)" in board_temp) BOARD="$d/temp";; fan_temp) FAN="$d/temp";; svooc_mos_btb_usr) MOS="$d/temp";; esac
done
echo 'ACEFan: starting 10-minute read-only preview; this does not activate Hook or change fan speed'
i=0
while [ "$i" -lt 120 ]; do
  L=$(content call --uri "$API" --method latest 2>/dev/null)
  case "$L" in *'已注入'*) echo 'Hook telemetry detected; preview stopped'; exit 0;; esac
  WALL=$(date +%s)000
  ELAPSED=$(awk '{printf "%.0f",$1*1000}' /proc/uptime)
  BAT=$(temp /sys/class/power_supply/battery/temp 10)
  B=$(temp "$BOARD" 1000); F=$(temp "$FAN" 1000); M=$(temp "$MOS" 1000)
  RPM=$(num /sys/class/leds/fan/rpm); LV=$(num /sys/class/leds/fan/level); EF=$(num /sys/class/leds/fan/level_eff); ST=$(num /sys/class/leds/fan/state)
  STATUS=$(timeout 1 cat /sys/class/leds/fan/status 2>/dev/null)
  case "$STATUS" in NORMAL|PRESENT|NOT_PRESENT|DAMAGED|OCP_ISSUE|BLOCKED|RPM_LOW) :;; *) STATUS=UNKNOWN;; esac
  CHARGING=false; [ "$(cat /sys/class/power_supply/battery/status 2>/dev/null)" != Charging ] || CHARGING=true
  INTERACTIVE=null
  case "$(timeout 3 dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')" in *Awake*) INTERACTIVE=true;; *Asleep*|*Dozing*|*Dreaming*) INTERACTIVE=false;; esac
  JSON="{\"wall\":$WALL,\"elapsed\":$ELAPSED,\"boot\":\"$BOOT\",\"battery\":$BAT,\"board\":$B,\"fan\":$F,\"mos\":$M,\"rpm\":$RPM,\"driver\":$LV,\"effective\":$EF,\"state\":$ST,\"fanStatus\":\"$STATUS\",\"hook\":\"Root只读预览\",\"active\":false,\"build\":\"$MODEL\",\"charging\":$CHARGING,\"interactive\":$INTERACTIVE,\"oem\":null,\"candidate\":null,\"request\":null,\"reason\":\"真实节点只读预览；LSPosed尚未接管风扇\",\"quality\":\"root_read_only\"}"
  R=$(content call --uri "$API" --method push --arg "$JSON" 2>&1)
  case "$R" in *'ok=true'*) :;; *) printf 'Preview write failed: %s\n' "$R"; exit 1;; esac
  [ "$i" -ne 0 ] || printf 'First live sample: battery=%s C, level=%s, effective=%s, RPM=%s\n' "$BAT" "$LV" "$EF" "$RPM"
  i=$((i+1))
  sleep 5
done
echo 'Read-only preview finished'
