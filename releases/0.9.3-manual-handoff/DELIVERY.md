# code30 本地交付与运行身份

## 实际产物

- 源码提交 `f73b679c35acdcab8d64e846f19ea18efe5df8e3`；分支 `fix/kernel-daily-rpm-code28`，本轮未推送或上传。
- 统一ZIP `ACE7Pro_Fan_0.9.3-manual-handoff-code30.zip`，8217172字节，SHA256 `fc66c215a799f37203653cd173914f413d2436c3a02d6f38626bb01b61ec16d0`。
- 同批文件保存在 `/storage/emulated/0/Download/eta/工作区产物/ace7pro-fan/0.9.3-manual-handoff/`：ZIP、BUILD_IDENTITY.json、NOTES.md、README.md、INSTALLATION.md、SHA256SUMS.txt。ZIP CRC、内嵌APK/KO/helper逐字节及版本/原签名校验通过。
- APK SHA `23e5003058ddca0636613871a3876a76f146dfe869dce2059dc74f2897a02e6f`；原证书SHA `2623f23ea49724141fb2e194f835947e7b4cd74da37f7887c22947fc7775808d`。
- stage6 KO SHA `b0b57952bd1522ea45cb5de2f11edd5cf2c11e2233a7c55a0ada6749f069bd24`；新helper SHA `825b6baed8b710778ffbde6778531756c7f6bac4777f1e3fd88256d38258a378`。

## 已安装，不等于已驻留

2026-11-28 01:11附近（设备本地Asia/Shanghai）KSU真实安装退出0，回报Module installed successfully。App versionCode30/versionName与包内APK哈希读回一致；用户日用v8（36°C/最低10/60:0:40:0）及充电v1（33°C/最低6/每度2/上限20）保持。

`/data/adb/modules_update/horae_fan_spoof` 新helper/KO，以及 `/metadata/vold/ksu/preinit/horae_fan_spoof/bin/` 的对应SHA均与包相同，preinit状态ok，执行命令`exec bin/fan-preinit --apply`。不只是更新了App。

未重启、未热卸载驱动、未强杀或启停原厂服务。核对时boot仍 `a7d4c185-a558-4bb9-8b8a-d995f80a3993`，驻留仍stage4，运行文件SHA `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`，现有Root仍code29；新Hook也尚未重载。系统模式已恢复智能0，customize仍100，dbg_rpm仍-1。新驱动不能通过热更新App获得。

## 正式发布条件

用户已授权修好后正式发布、不要预发布；README已改为功能与实现说明。但新KO/Hook必须正常重启后验证，尚未满足正式发布条件，因此本轮尚未创建code30 GitHub正式或预发布，也没覆盖旧资产。

下一步：用户正常重启后核对stage6真实驻留、Root/Hook30与配置；测试智能直接极限第一下、静音/极限互切、返回智能；在手动owned0时检查四路sampleAt连续推进、Provider新增有效温度/融合及曲线。通过后再正式发布；若BLOCKED依原厂处理，不清保护凑验收。真正任意内核通用未完成，helper ABI保护保留。

原始私有日志在 `build/manual-fix-audit/`，运行状态详见 [手动修复](../../knowledge/MANUAL-HANDOFF.md)。
