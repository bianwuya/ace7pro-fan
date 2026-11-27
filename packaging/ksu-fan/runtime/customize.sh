#!/system/bin/sh
# KernelSU's built-in installer extracts non-META-INF members before sourcing this script.
[ "$KSU" = true ] || abort '仅支持 KernelSU 安装。'
[ "$ARCH" = arm64 ] || abort '此版本仅支持本机 ARM64。'
[ "$(uname -r)" = '6.12.69-android16-6-g242e1a07f878-ab15865446-4k' ] || abort '内核版本不匹配，未安装。'
[ "$(getprop ro.boot.prjname)" = 25841 ] || abort '设备项目不匹配，未安装。'
[ "$(getprop ro.product.model)" = PMS110 ] || abort '设备型号不匹配，未安装。'
cd "$MODPATH" || abort '安装目录不可读。'
[ -r SHA256SUMS ] || abort '缺少文件校验清单。'
while read -r EXPECTED NAME; do
    case "$NAME" in
        META-INF/*) ACTUAL=$(unzip -p "$ZIPFILE" "$NAME" | sha256sum);;
        *) [ -f "$NAME" ] || abort '包内文件缺失。'; ACTUAL=$(sha256sum "$NAME");;
    esac
    [ "${ACTUAL%% *}" = "$EXPECTED" ] || abort '文件校验失败，未安装。'
done < SHA256SUMS
[ -f /data/adb/ksud ] || abort 'KernelSU 服务不可用。'
PREINIT=$(/data/adb/ksud module preinit status 2>&1)
case "$PREINIT" in *unsupported*|*Unsupported*|*disabled*) abort '当前 KernelSU 不支持早期加载，未安装。';; esac
case "$PREINIT" in *bootcount*) ;; *) abort '无法确认早期加载支持，未安装。';; esac
FREE=$(df -k /metadata | tail -n 1 | awk '{print $4}')
case "$FREE" in ''|*[!0-9]*) abort '无法确认早期存储空间。';; esac
[ "$FREE" -ge 12288 ] || abort '早期存储剩余不足 12 MiB。'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in service.sh uninstall.sh action.sh watchdog.sh fg-debug.sh; do
    [ ! -f "$MODPATH/$script" ] || set_perm "$MODPATH/$script" 0 0 0755
done
set_perm "$MODPATH/preinit/bin/fan-preinit" 0 0 0755
set_perm "$MODPATH/preinit/bin/oplus_fan.replacement" 0 0 0644
[ "$(cat "$MODPATH/allow-control")" = first-control ] || abort '缺少明确的控制启用配置，未安装。'
# Keep this boot's existing driver, horae bind and watchdog running. KSU stages the update for next boot.
ui_print '正在安装配套 App，保留原数据和设置…'
# Package Manager receives only pipes, not private installer directory/log FDs.
# Streaming also lets its system process install without traversing /data/adb.
APK_SIZE=$(wc -c < "$MODPATH/app/ACEFan.apk")
PM_OUTPUT=$(cat "$MODPATH/app/ACEFan.apk" | /system/bin/pm install -r --user 0 -S "$APK_SIZE" 2>&1)
PM_RESULT=$?
printf '%s\n' "$PM_OUTPUT" > "$TMPDIR/eta-fan-pm.log"
[ "$PM_RESULT" -eq 0 ] || {
    ui_print "$PM_OUTPUT"
    abort 'App 升级失败；内核模块未激活。'
}
INSTALLED=$(/system/bin/pm path dev.eta.acefan 2>/dev/null)
case "$INSTALLED" in package:/*) INSTALLED=${INSTALLED#package:};; *) abort '无法回读 App 安装路径。';; esac
BUNDLED_SHA=$(sha256sum "$MODPATH/app/ACEFan.apk")
INSTALLED_SHA=$(sha256sum "$INSTALLED")
[ "${BUNDLED_SHA%% *}" = "${INSTALLED_SHA%% *}" ] || abort 'App 安装结果校验失败。'
ui_print 'App 已安装；风扇驱动替换在下一次正常重启生效。'
ui_print '无需刷写 boot 或 vendor_boot；充电模块不作修改。'
