# 0.9.2-game-compat / code29

## 修复范围

- 补回升级内核版时遗漏的原厂风扇温度解耦。保持既有29.5°C外部伪装，同时让原厂FanSmartControl保留真实拟合温度路径；不接管、不改写原厂游戏表或游戏目标。
- 从精确匹配的本机stock Horae派生此前已用的单指令修复，双SHA-256验证后只读bind；不在安装包中分发完整Horae，不写系统分区，不启停或强杀Horae。通过post-fs-data进入启动链；若Horae已运行，明确等待下一次正常服务启动，不把挂载当成内存中代码已更换。
- 修复Root回传错误：不再以策略fault0/live1推断风扇NORMAL，改读原驱动真实status；BLOCKED等故障原样回传，缺证据为UNKNOWN。FG实测通道、原配置和Provider权限保留。
- 原code28日用阶梯KO/helper逐字节复用，不通过改游戏算法或扩大日用接管范围掩盖遗漏。所有原硬件保护保留。

## 本轮实际部署与验证

- 原签名App/versionCode29构建、DEX/v3固定证书审计通过；统一KSU包已实际安装，App实装versionCode29，待启动KO/helper/脚本哈希回读正确。
- 本boot已仅对/system_ext/bin/horae进行只读兼容挂载，目标哈希为此前patched身份；应用时Horae已因当前充电处于stopped，本任务没有启动、停止、重启它。当前记录为bound_waiting_normal_service_start，必须等其正常恢复后核实/proc/<pid>/exe。
- 正式Root relay已接续code29。Provider真实发布hookVersion=0.9.2-game-compat，实际硬件BLOCKED不再显示NORMAL；日用v8（36°C、最低10、权重60:0:40:0）及充电v1（33°C等）保持。
- Linux及Android各17项兼容脚本模拟回归通过，实际stock派生结果与旧归档patched逐字节一致；硬件状态16项、Root桥552项、Provider175项及既有Java回归通过；打包87项、adapter14项通过；完整Android编译/签名审计及包内CRC/哈希通过。

## 未完成验收和重要限制

- 没有重启整机或卸载正在运行的驱动。本boot仍驻留stage4，复用的stage5日用阶梯须用户下一次自行重启后验证，不是本轮热加载。
- 当前实际驱动报告BLOCKED、停转，本任务没有清故障/强制state或档位。恢复缺失温度链不等于证明当前故障已消失，不能据此宣称原厂游戏已经起转。
- 原厂游戏进入/退出及同档接续仍需真实场景验证。内核stop+release与OEM同档缓存的风险保持公开，尚未证明此次一定触发，不用陈旧OEM快照强拉风扇。
- 这是明确的兼容恢复验收版，production_runtime_verified=false；不会覆盖旧版本资产或把旧code28标成游戏修复版。
- 精确适配仍为PMS110/project25841、内核6.12.69-android16-6-g242e1a07f878-ab15865446-4k及原件哈希。仅恢复原功能，不放宽硬件/ABI/签名/SELinux或系统版本门禁。充电模块与EXGT未改。

## 产物身份

- ZIP：ACE7Pro_Fan_0.9.2-game-compat-code29.zip，8212505字节。
- ZIP SHA-256：f6c093437d217136ad15b7c45c5004672effbc0ded8cbb7b1aa4cfa3c57a72bf。
- APK SHA-256：58263172ee66ef95dcd7e8c46904ebc7a761e2f3be73924ba276b0904149d2ad。
- KO：6c7f8d320d89c969d10679e39755fd6dfac8330b06c659f370ecd3bfe697c49a；helper：ff41b64a5b911510010a414cd4780f6ffb26e9c479fbbaa5e47e39d0f713e55f。
- Horae原件：394ed4b2b152c1fac1c76294cc9d33fcfb6199e391c7854b7db7a53391c2bca0；派生修复：eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d。

本地统一交付目录：`/storage/emulated/0/Download/eta/工作区产物/ace7pro-fan/0.9.2-game-compat/`。云端仍使用用户已有风扇库`bianwuya/ace7pro-fan`，只上传同一ZIP及对应身份/说明/校验，上传后实际下载核对；部署事实不等于完整游戏运行验收。实际上传结果见交付记录。
