# code29 实际交付记录

## 安装与本boot实施

- 统一ZIP：`ACE7Pro_Fan_0.9.2-game-compat-code29.zip`，8212505字节，SHA-256 `f6c093437d217136ad15b7c45c5004672effbc0ded8cbb7b1aa4cfa3c57a72bf`。
- 原签名App实装versionCode29；KSU实际安装退出0并回报Module installed successfully，更新目录`/data/adb/modules_update/horae_fan_spoof`，preinit登记ok；metadata helper与新KO哈希一致。不是只生成包。
- 本boot已只读bind回旧Horae风扇真实拟合温度解耦，目标eae728…哈希核实。应用及最后读回时电池Charging、Horae stopped且无进程；本任务未拉起/强杀服务，因此运行进程身份仍待它自然恢复后验证。
- 只停止本项目旧Root relay，code29 relay已启动接续。正式Provider回传0.9.2-game-compat及真实BLOCKED；日用v8/充电v1原设置保留。下一启动由KSU service启动，不新增Eta开机任务。
- 本boot与驻留stage4未变；stage5驱动逐字节复用code28，登记到下一启动，未卸载/热换驱动。未清故障、强制state/level、重启设备，未改独立充电或EXGT。

## 源码与云端

- 本地源码提交：`2994b0ea1fdc15a9531a87b2af7f6780c99079c2`，分支`fix/kernel-daily-rpm-code28`。提交22个明确项目源码/测试/说明文件，不含私有日志、OEM样本、APK/KO或密钥。
- 已推送到用户已有风扇库`bianwuya/ace7pro-fan`，未改main或Eta库。tag `kernel-0.9.2-code29`精确指向上述源码。
- Release ID `460666995`；`prerelease=true`、`draft=false`，未设为latest。ZIP asset ID `800700926`，uploaded。
- 已实际下载全部4个资产（ZIP、BUILD_IDENTITY、SHA256SUMS、NOTES），逐文件与本地对比：文件名、字节大小、SHA-256、远端digest及实际下载内容全部一致。没有在云端重新构建、覆盖code28或删除历史版本。
- 本地路径：`/storage/emulated/0/Download/eta/工作区产物/ace7pro-fan/0.9.2-game-compat/`。

## 验证与明确未通过事项

兼容脚本Linux/Android各17项、真实stock派生与旧patched逐字节相同、新硬件状态16项、Root桥552/Provider175等Java回归、打包87项、adapter14项、完整签名构建/DEX审计/ZIP CRC及哈希通过。

游戏物理起转、实际进入/退出及同档交还尚未验收；最终硬件仍报BLOCKED，因此不能将恢复缺失温度链称为已经解决当前全部停转。等Horae正常恢复后，先核验进程eae728…身份和原厂游戏实际请求/硬件状态，不用日用温度策略接管游戏，不清故障、不重放陈旧OEM快照。所有生产/游戏运行验收字段保持false。

原始私有证据在`build/game-regression-audit/`，包括code29安装/回传/metadata读回、签名构建及`code29-download-verification.json`。本交付记录不包含可随时失效的当前实时断言，精确继续点见项目`knowledge/GAME-REGRESSION.md`。
