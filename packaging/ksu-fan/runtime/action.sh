#!/system/bin/sh
MODDIR=${0%/*}
echo '风扇控制'
if [ -r /sys/class/leds/fan/eta_policy/status ]; then
    cat /sys/class/leds/fan/eta_policy/status
else
    echo '原厂驱动运行中；内核后端尚未加载。'
fi
echo
[ ! -f "$MODDIR/watchdog.status" ] || { printf '温度显示: '; cat "$MODDIR/watchdog.status"; }
