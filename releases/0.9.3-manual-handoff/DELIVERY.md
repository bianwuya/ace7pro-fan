# code30 本地交付与运行身份

## 实际产物

- 源码提交 `f73b679c35acdcab8d64e846f19ea18efe5df8e3`；分支 `fix/kernel-daily-rpm-code28`，本轮未推送或上传。
- 统一ZIP `ACE7Pro_Fan_0.9.3-manual-handoff-code30.zip`，8217172字节，SHA256 `fc66c215a799f37203653cd173914f413d2436c3a02d6f38626bb01b61ec16d0`。
- 同批文件保存在 `/storage/emulated/0/Download/eta/工作区产物/ace7pro-fan/0.9.3-manual-handoff/`：ZIP、BUILD_IDENTITY.json、NOTES.md、README.md、INSTALLATION.md、SHA256SUMS.txt。ZIP CRC、内嵌APK/KO/helper逐字节及版本/原签名校验通过。
- APK SHA `23e5003058ddca0636613871a3876a76f146dfe869dce2059dc74f2897a02e6f`；原证书SHA `2623f23ea49724141fb2e194f835947e7b4cd74da37f7887c22947fc7775808d`。
- stage6 KO SHA `b0b57952bd1522ea45cb5de2f11edd5cf2c11e2233a7c55a0ada6749f069bd24`；新helper SHA `825b6baed8b710778ffbde6778531756c7f6bac4777f1e3fd88256d38258a378`。

## 原始安装观察（重启前）

2026-11-28 01:11附近（设备本地Asia/Shanghai）KSU真实安装退出0，回报Module installed successfully。App versionCode30/versionName与包内APK哈希读回一致；用户日用v8（36°C/最低10/60:0:40:0）及充电v1（33°C/最低6/每度2/上限20）保持。

`/data/adb/modules_update/horae_fan_spoof` 新helper/KO，以及 `/metadata/vold/ksu/preinit/horae_fan_spoof/bin/` 的对应SHA均与包相同，preinit状态ok，执行命令`exec bin/fan-preinit --apply`。不只是更新了App。

未重启、未热卸载驱动、未强杀或启停原厂服务。核对时boot仍 `a7d4c185-a558-4bb9-8b8a-d995f80a3993`，驻留仍stage4，运行文件SHA `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`，现有Root仍code29；新Hook也尚未重载。系统模式已恢复智能0，customize仍100，dbg_rpm仍-1。新驱动不能通过热更新App获得。

## 重启后实机与正式分发

2026-11-28已完成用户正常重启后的限定回归：boot `33cd1f53-48ce-4c21-8509-eac6e2dc30d2`，stage6驱动SHA与本包一致，新内核遥测扩展实际推进；正式Provider回报code30，原厂消费release令牌且实际启动20档。完整数据见 [实机验收](RUNTIME_ACCEPTANCE.md)。

智能直切极限、智能停转后直切极限、20/6/20互切及38条手动四温度/融合历史通过；设置测试不冒称实际GUI磁贴点击。测试后恢复智能mode0/customize100，日用v8/充电v1不变。原厂手动仍记录约35秒BLOCKED，owned0后自行恢复，根因未确认，未清保护或强制使能。

用户已授权正式发布、不发预发布。正式发布将复用本地同一ZIP与原签名，不重建或改写包内first-control/production未验的构建时快照；对外另附RELEASE_NOTES、RUNTIME_ACCEPTANCE及校验。正式分发不等于任意内核/全场景生产认证，当前helper ABI保护保留。

原始私有日志在`build/manual-fix-audit/code30-reboot/`，不上传数据库/原始日志/密钥。正式Release最终ID、tag、状态与实际下载一致性在完成后追加本页。
