# 项目状态快照

**管理审计日期：2026-11-03。** 这是有时点的记录，不是实时设备状态。兼容矩阵以 [COMPATIBILITY.md](../COMPATIBILITY.md) 为主；待办见 [TASKS.md](../TASKS.md)。

## 0.9.1-kernel-rpm：RPM已运行，日用阶梯更新已装（2026-11-27 19:05 +08:00）

- 修复Root进程FG通道，App/code28及对应Root relay已实装；正式Provider已记录10档13119RPM、9档12059RPM，停机值清空通过。不是日志/档位估计，旧Hook保留，前端屏幕未复核。
- 用户实测40.597°C仍10档已确认为daily移植遗漏更高需求。新核心按四路融合使用本机普通阶梯并max(最低档,阶梯)，40°C升温目标16，启停/回差/充电/保护及normal斜坡保留。129139×2核心断言、adapter14及relink24项通过，新驱动仍待重启实机验收。
- 同签名App和新KO/helper统一ZIP已通过真实KSU安装，metadata身份/标签读回正确；本次boot及驻留stage4驱动未改变，因此新阶梯尚未生效，用户自行重启。所有修改落本地工程；包上传只去已核对的风扇项目仓库，不是Eta。精确身份、运行和上传边界见 [当前检查点](KERNEL-CURRENT.md) 与 [版本说明](../releases/0.9.1-kernel-rpm/NOTES.md)。

## 历史：0.9.0-kernel r5自然起转与升降档（2026-11-27 18:30 +08:00）

- 用户报告触发后，内核status及独立硬件节点均为目标/输出/有效10档、state1/NORMAL/fault0，内核owned/ready1。当前boot真实Root回传记录6→7→8→9→10、约5秒一档；也有低于35°C约30秒后目标0、降档停机及再次起转的自然过程，不再只是零需求验收。
- 原驱动FG timer实际1000ms计数654–655脉冲，对应13079–13099RPM，不是目标表转速。Root FG事件及App RPM显示仍未接通，充电切换/故障交还仍未验收；本轮未改设置/代码或强制起转。证据见 [内核检查点](KERNEL-CURRENT.md)。

## 历史：0.9.0-kernel r5入核与零需求核验（2026-11-27 18:13 +08:00）

- boot `a7d4c185-a558-4bb9-8b8a-d995f80a3993`：实际preinit应用成功，候选oplus_fan为86016字节驻留Live，设备已绑定，eta_policy存在。Root relay正常，Provider回传内核后端active=true及daily/nodeControl控制就绪。
- 4轮跨度12.112秒只读观察：四路温度有效，采样时间和同revision租约持续前进，owned/ready=1、fault0。自然融合34.684–34.904°C低于36°C，内核维持合法零目标并持有控制权；设置保持、不强制起转。
- 正需求实际起转/斜坡、充电切换、故障交还仍未验收；Root FG observer当前unavailable、实测RPM未接通，不能称全部生产运行通过。本轮只核验未改代码/设置、未重装或重启。完整身份和边界见 [内核检查点](KERNEL-CURRENT.md)。

## 历史：0.9.0-kernel r5标签修复及原厂恢复（2026-11-27 14:37 +08:00）

- boot `7064f3a7-5214-4030-81fe-02cd3c68a129` 已证实r4早期挂载成功，但原厂加载域不能读取metadata的vold_metadata_file类型，候选驱动没有入核、LED节点缺失。只读权限矩阵已核实与原厂vendor_file的差别，不将挂载成功当加载成功。
- 已在确认没有oplus_fan驻留后，撤除本模块唯一bind并加载精确哈希的原厂驱动，Live/present1/NORMAL与有效10档回读通过，没有卸载运行中的驱动。
- r5对经哈希/ELF/CRC校验的单个替换inode设置原厂vendor_file标签并重读全量字节/快照；保留全部早期/未加载/回退保护，不改策略或身份。166项回归与实际Android标签函数验证通过；r5统一包已实装，metadata标签及哈希回读正确。
- 最新入口：[内核检查点](KERNEL-CURRENT.md)。当前仍原厂驱动工作，新候选实际加载及控制仍待用户下一次自行重启后验收，本轮没有重启或改充电项目。

## 历史：0.9.0-kernel r4时序修正（2026-11-27 14:01 +08:00）

- 用户已重启，boot `7825ae3c-99fc-43e9-89ee-80d914e1255d`；真实日志证明旧helper在1.416秒被1.2秒固定守门误拒绝，新驱动没有加载。登记ok不等于执行成功，当前仍stock，不能称接管通过。
- 仅改helper额外时限为10秒，保留KSU同步前置/命名空间/未入核双检查及全部身份和保护；156项回归及Android真实文件只读核验通过。统一r4 ZIP已实装，metadata新helper哈希已回读；App与driver字节不变，日用v8/充电v1保留。
- 最新入口：[内核检查点](KERNEL-CURRENT.md)。用户自行重启，本轮没有重启、热卸载或修改充电项目；下一次启动后继续核实真实preinit结果、驱动和relay，不重复要求授权。

## 上次历史：0.9.0-kernel r3首次安装（2026-11-27 13:28 +08:00）

- 当前执行入口：[内核后端检查点](KERNEL-CURRENT.md)。App 原签名 versionCode27 已实装；统一 ZIP r3 已由真实 KSU 安装成功，preinit 状态 `horae_fan_spoof ok`，metadata 中 helper/完整驱动的权限、单硬链接和哈希已回读确认。
- 未重启；原驱动与旧守护仍运行，充电模块未改，日用v8及充电v1设置保持。Root配置访问、原厂口袋及带标签性能状态的解码已修复并实机验证。包位置、哈希、日志和明确的待验边界以该检查点为准。
- 完整控制驱动尚未加载，真实风扇接管、保护/交还及本版RPM显示仍未验收。首次控制包如实保留 production_runtime_verified=false；不把安装成功当成已控制。下一步须用户允许正常重启及首次实际控制验收。

## 历史准备阶段：单 ZIP 与定点核心准备（2026-11-27，已由上方检查点取代）

- 用户明确最终形式：一个KSU ZIP同时安装原签名App与独立内核模块；App只显示/设置，不重构或刷写整内核。`0.8.1-node-bridge`实验仍未安装，不作为最终方案。
- 新增纯整数核心 `kernel/eta_fan_policy/policy_core.{h,c}`，分离日用/充电需求与normal斜坡，保留原阈值/权重/1°C30秒回差、未知/缺测保护和上下文隔离；无浮点、I/O、Binder或horae调用。普通及UBSan各124320断言通过，主代理再跑no-sanitize通过；这是主机离线测试，不是目标内核Kbuild或物理验收。
- 新增 `tools/package_ksu_fan.py` 与两个输入schema，统一App/KO/模板/报告清单，校验原签名APK、AArch64 ELF/modinfo/MODVERSIONS/CRC及哈希，49项离线单测通过。缺真实KO、配套报告或完整已审阅安装脚本时拒绝发布；未产正式ZIP，不把合成测试ELF当可用内核。
- 官方OnePlus SM8850最近公开材料为common6.12.58、android16-6.12 KMI6及Clangr536225；不能因补丁级不同就否定兼容，但尚未取得并验证对应prebuilt/generated headers/CRC。已查目录未定位到当前oplus_fan源码，原驱动没有控制导出；LED brightness回调只打印，不能作控制入口。sysfs对象回调生命周期/独占仲裁仍未闭合，未编译或加载新KO。
- 后续仅定向补齐外部模块DDK/ABI与安全控制接口，不走刷内核、固定地址patch、卸载原风扇驱动的试错路线。当前设备仍0.8.0/versionCode22与伪装补丁1.1.0。
- 证据：`build/kernel-policy-core/REPORT.md`、`build/kernel-feasibility-20261127/source-match/REPORT.md`；代码范围与检查已读回，项目check151→162文件通过。原始OEM/线程数据与密钥未进入共享交付。

## 内核模块方向与性能基线（2026-11-27，实施暂停点）

- 正常重启后boot `2f554681-bce5-4d4d-b76e-e94c5eff92f5`已实读加载Hook0.8.0，但nodeControl因system_app无level/state写权限而不可用，安全回退。风扇伪装守护开机自启，Temp29.50°C；充电工程未改。
- 用户进一步明确要求不依赖整个OEM链，继而要求计算和实际控制都做成内核模块，App仅显示/设置；最新要求先测原厂频率和收益。因此0.8.1-node-bridge源码为**未完成、未构建安装、不得当成当前方案部署的实验分支增量**；也不继续Java/app_process daemon。保留现有文件，不回滚用户既有工作。root-src未创建，新增framework.jar仅分析副本，不是运行依赖。
- 当前源码版本暂为0.8.1-node-bridge、设备安装仍0.8.0/versionCode22，不把两者混同。新的FG只读转报辅助脚本仍仅在工程中，未部署；本次实收FG raw8839但system_app读调试参数受限，quality=debug_unknown，真实RPM显示未验收通过。
- 已完成08:14:01开始约60.8秒只读基线，首尾state0/level6/eff0/NORMAL，未改模式/阈值/节点或加载新代码。线程schedstat：horae fan-smart 4.756ms（单核0.0078%），ACEFan-Telemetr63.516ms（0.1045%），App稳定线程总23.760ms（0.0391%）。统计包含原有采样/回传，不能当作纯档位数学运算，也不能转换为耗电百分比。原生计算循环静态sleep5证据有效，cadence log为空，尚无本轮日志实测间隔。
- 原始样本及汇总 `build/kernel-feasibility-20261127/`；测量job_0748b7c5已完成，不常驻。本轮尚未做恒负载/屏幕状态受控A/B或功耗测量。
- 本机内核6.12.69 Android16，MODULES/MODVERSIONS/CFI/BTF开启，TRIM_UNUSED_KSYMS与模块符号保护存在；仅说明需核对，不代表可立即加载自编ko。原oplus_fan.ko无ksymtab导出段，现未取得匹配DDK/Module.symvers/驱动源码；没有实现或加载新ko，没有卸载原驱动。

## 0.8.0-direct：已安装，运行Hook待正常重载（2026-11-27）

- 基线 `a6f5f10` 工作树增量，App/Hook `0.8.0-direct`、versionCode22。独立软件目标经原厂普通升降档器后走OEM下游保护，精准fan-local `OplusFanController$b.n/l/i`接入level/state；不等待horae更新，不改充电模块。实际system_app写权限与物理效果仍待加载验收，不将Root的文件权限当成服务身份已验证。
- 完整47项Python、14组Java、Compose编译、Release/lintVital和固定原签名/v3 APK审计通过；最终追加两项运行路径测试后49项Python及14组Java再通过，新后端353断言、守护26断言。日志 `build/direct-control-v080/final-regression.log`；构建日志 `/data/local/tmp/eta/daemon/job_7deffd07.log`。增加15秒源样本租约、代次停机和state-only安全关闭，OEM handler自身阻塞不保证墙钟期限。
- 已以 `pm install -r --user 0` 覆盖安装，回读versionCode22；已装APK SHA256 `11840344deca348cd8b3de69fd91563720ff004b7ed4c750982d34b88522e9fc` 与构建一致。日用v8（开启、36°C、10档、60:0:40:0）和充电v1（开启、33°C）前后相同。未清数据、改作用域、重启整机或强杀原厂服务。
- 最新实读原厂PID8623、运行Hook仍 `0.6.5-rpm-hold`。请求重载授权未收到回答，未当作同意；直控节点、FG事件接收与新UI真机验收尚未完成。后台虚拟屏启动返回GUI_VISIBILITY_TIMEOUT且明确未执行，已释放GUI，不把源码/构建当视觉验收。
- FG调试参数改为真实 `/sys/module/oplus_fan/parameters/dbg_rpm`（实读-1），不再访问不存在的LED子路径；首页只显示数字RPM/已停止/等待测速，移除工程说明，未按档位或目标表伪造实测。
- `horae_fan_spoof`脚本增量1.1.0已部署并回读校验。仅明确拔线放电且供电检查通过时，5秒检查既有补丁进程的温度漂移；插电/充电/未知不写，不启停horae。新守护 `dm_c62955ab` 已运行，状态healthy；实读Temp29.50、shell-temp29500，horae PID1815未变。启动脚本接续守护，目录锁防重复；原脚本与元数据备份 `/data/adb/horae_fan_spoof_backups/20261127-direct080/`。
- 期间发现Android mksh调用flock不继承额外FD，首个守护未启动；已停止失败记录，改原子目录锁并实机读回运行。这是已修正的实施问题，不作为业务验收通过的替代。
- 源码与工程未提交/推送。交付目录 `Download/eta/工作区产物/ace7pro-fan/0.8.0-direct/`；只有签名APK、简短说明与校验，原始OEM样本/日志/密钥不交付。

## 0.7.4-rpm-hold：基于云端 0.7.3 的增量整合

- 云端来源为用户指定的 `origin/fix/fan-0.7.3-hysteresis`，提交 `691bbe4fe988fd15751da6bf780b87822052b553`；手机已有 App `0.7.3-fusion-hysteresis` / versionCode 20，已装 APK SHA256 `15bc31d5f0fdcf06188581aaf0c1df95cdc465bcf115131a7b7c1208e59cda40` 与该分支记录一致。原本的 `0.7.2-rpm-events` 中间包未降级安装。
- 先用 `68a1505` 保存本地已有功能与测速增量，再三方合入云端修复分支；完整改前快照在忽略目录 `build/incremental-0.7.4/before/`。无 reset/stash/强推，不丢弃已有源码。
- App `0.7.4-rpm-hold` / versionCode 21，Hook `0.6.5-rpm-hold`。云端 `DailyFanPolicy` 及其回归、`FanServiceCompat`、`ControlCycle`、`DataProvider`、`ChargeCurve` 均逐字保留，涵盖双向启停、1°C 回差和 30 秒降温确认、原厂保护与数据兼容；保留 GitHub 适配入口。只在遥测追加 FG 实测保持、取消两处折叠及调整曲线顺序。
- 构建源码提交 `b561b8dae1b0a324623217bccd038a7a71b4d53f`（双父合并，保留云端修复分支历史）；47 项 Python、11 组 Java 回归、Compose Kotlin/Java 编译、AGP Release/lintVital、unsigned 与原签名/v3 APK 审计全部通过。日用策略 341 项、测速 39 项、调用关联 13 项；构建日志 `/data/local/tmp/eta/daemon/job_62fbe7c6.log`。
- 2026-11-26 02:24:20 UTC+8，已以 `pm install -r --user 0` 从 0.7.3 同签名升级为 0.7.4 / versionCode 21，PackageManager 返回 Success。安装包及已装 base.apk 回读 SHA256 均为 `ca11e6a920b24e2e8414a1d93d6c468bf728ea5a7eb9236c2cc7548e2e838e94`；日用 v8（已启用、36°C、10 档、权重 60:0:40:0）和充电 v1（已启用、33°C）逐字回读一致，原厂服务 PID 8184 未变。安装后最新回报 Hook `0.6.4-daily-hysteresis`，不把安装当新 Hook 已加载；用户正常重启后再验收 `0.6.5-rpm-hold`。
- APK、说明、构建/安装身份及校验清单保存在 `Download/eta/工作区产物/ace7pro-fan/0.7.4-rpm-hold/`。未卸载/清数据、改 LSPosed/原厂开关、重启/强杀原厂服务；未实测新 UI 或本版 FG 接收稳定性。
- 用户已明确要求升级后直接合并为 GitHub `main`。在本人完成 GitHub CLI 官方设备授权后，已实际核实登录账号为 `bianwuya` 且具备仓库写权限，正常推送 `066b9cf..87df265` 成功；GitHub API 回读 main 为 `87df2657f2859f5c3ff6818178ec031d79501311`，与本地一致。整合提交 `b561b8d` 保留双方历史，云端修复分支 `691bbe4` 是 main 祖先；未强推、未删修复分支、未打 tag/发布 APK。本地 Git bundle 随安装交付；授权后的记录更新另随 main 正常推送，不重新构建或安装，不操作设备重启。测速运行与降温后的物理启停仍待用户重启后实测。

## 0.7.2-rpm-events：本地更新包已构建，测速运行待验（2026-11-26）

- 用户本轮要求取消传感器曲线和充电模式的折叠，传感器移到有效档位曲线之上；已在 Compose 实现。趋势顺序为融合温度 → 传感器曲线 → 有效档位 → RPM 保持曲线。
- 新增原厂已有 `FAN_RPM` FG 测速事件的被动接收，不恢复旧 rpm/speed 节点读取。按用户补充，同有效档位保持首次有效实测，换档后等待新测量；停用、故障、未知档位、调试覆盖或时钟异常不续用。采样代次、前后使能/档位一致性与 2 秒换档稳定窗口是保守软件检查，不是硬件窗口归属证明。正请求未改变有效档位时不清除首次保持值。详见 [测速证据与设计](FAN-RPM.md)。
- App `0.7.2-rpm-events` / versionCode 19；Hook `0.6.3-rpm-events`。基线 `baff141` 上的本地工作树，保留既有未提交改动；本轮最初 19 份文件备份位于忽略目录 `build/0.7.2-rpm-events/before/`。未提交、推送、打 tag 或发布。
- 完整验证通过 47 项 Python、11 组 Java 回归；最后边界修正后的测速测试 39 项全部通过，并重新完成 Compose Kotlin/Java、AGP Release/lintVital、unsigned 和固定原签名/v3 APK 审计。既有配置表、JSON 样本/字段、Provider UID 校验不变；CSV 既有字段顺序逐字核对相同，新字段仅末尾追加。
- 最终 APK SHA256 `52716ff604568ad2c6824e9f31fd8f8f524114d75364bfc6ef7e84dde01c730a`。交付目录 `Download/eta/工作区产物/ace7pro-fan/0.7.2-rpm-events/` 内含 APK、NOTES、测速证据、验证说明、公开构建身份、本轮差异和校验清单，逐文件回读验证。日志为 `build/0.7.2-rpm-events/java-test.log`，首次完整验证 `/data/local/tmp/eta/daemon/job_8fe49725.log`，最终重建 `/data/local/tmp/eta/daemon/job_6b5ded77.log`。
- **未安装本版、未改 LSPosed、未重启/强杀原厂服务、未扫档或主动起转；未做新 UI 真机与实际 CSV 导出验收。** 只读自然观察风扇始终 state=0，未收到测速事件；此方案尚未证明真实运行连续性、稳定性或物理准确度，不能称为已发现稳定的新节点。原厂不监听/不发事件时显示未知，需正常重载 Hook 后自然运行验收。
- 工作区没有 `/workspace/PROJECTS.md`，未为满足旧路径约定另造平行状态表；当前入口仍为本状态与知识索引。

## 0.7.3-fusion-hysteresis：恢复日用停转确认（2026-11-26 核验）

- PMS110 `.159` 放电样本确认 `0.6.3-daily-threshold` 下，36°C 设定在 36.20°C 请求 10 档，回落至 35.70°C 时原厂候选 9 被改为 0 并确认发送 0 档请求，没有等待降低 1°C。旧策略把当前样本直接映射为档位，没有保留起转后的回差状态；回归测试能在 35.9°C 复现提前归零。
- `0.7.3` 从 `066b9cf` 隔离修复。日用只在有效样本中继承本次起转状态；降至设定值减 1°C 后连续 30 秒才请求停转，超过停转线重计。新增 GitHub 源码与适配入口；不改手机保存的阈值、档位、权重、原厂保护或充电策略。
- WSL2 ext4 的 47 项 Python、10 组 Java 回归、`lintDebug` 与 Debug/Release 组装通过；签名包完成 v3、固定证书、权限和 APK 结构审计。手机覆盖安装 `0.7.3`（versionCode 20）并读回相同包哈希 `15bc31d5f0fdcf06188581aaf0c1df95cdc465bcf115131a7b7c1208e59cda40`，日用 v8（36°C、10 档、权重 `60:0:40:0`）和充电 v1 均未改变。
- 10 组回归在手机 Android Runtime 上再跑通过；实点「GitHub 源码与适配」确认系统接收该公开仓库的 HTTPS Intent。覆盖安装并不重载原厂服务，仍需读回新 Hook，并在真实放电下观测 30 秒保转、实际停转和重新接电交还，之后才发布。
- 2026-11-26 用户要求定位并提交源码：原始完整修改位于电脑隔离工作树 `ace7pro-fan-066b9cf`，基线为 `066b9cf81ec9507d094e86d5fdb2bfd661ce9f66`。核对原构建输入清单的 135 个文件，仅后续状态文档发生变化；手机已装包仍与上述签名 APK 同哈希，当前回读 Hook 为 `0.6.4-daily-hysteresis`，已确认新版加载。
- 从该基线建立独立 Linux ext4 副本，仅纳入 13 个明确源码、测试和文档文件，开发分支为 `fix/fan-0.7.3-hysteresis`。本轮重新通过项目检查、Python/Java 回归、Kotlin 编译、单测任务、`lintDebug`、Debug/Release 组装和手机同哈希签名包审计；详细输出保留在本机忽略目录 `build/delivery-validation.log`。源码提交与同步不代表正式 APK 发布；真实放电下的 30 秒驱动启停与接电交还仍待验收。

## 0.7.2-fusion-threshold：同签名包已交付，待加载与真机验收（2026-11-22）

- 原因：旧策略在阈值以下把原厂 `8` 档原样放过，在阈值以上也把原厂 `0` 档原样放过；权重改成四路等权不能改变这个行为。手机源码基线 `baff141af2720f1c96ef637b0507a1988f1a97f6` 加其未提交融合工作树，在电脑隔离目录修复，未改手机工程或推送。
- PMS110 `.159` 原厂服务 1.4.6 V3 中，`Common` 的候选可同轮观察到 `FanMode` 普通自动目标；下游再执行原厂降噪/手柄/使能等。新版仅在明确放电、日用开启、确认此目标且通过热状态检查时双向改档：`≤` 阈值请求 `0`，`>` 阈值可从 `0` 起转。充电、Provider/SQLite 格式和手机既有 `horae` 覆盖层未改。
- WSL2 ext4：47 项 Python、10 组 Java 回归、`lintDebug`、Debug/Release 组装与无签名 APK 审计全部通过。新包 v3 证书与手机已装版本一致，完整包审计通过。构建输入文件清单摘要 `137ca4ab10c45365ff50d000558c03be21bdbca57041cd25d5ae5de72e12ce0b`；测试、验签和清单留在忽略的 `build/0.7.2-fusion-threshold/`。
- App `0.7.2-fusion-threshold` / versionCode 19，Hook `0.6.3-daily-threshold`。签名 APK 已复制到手机 `Download/eta/工作区产物/ace7pro-fan/0.7.2-fusion-threshold/`，读回 SHA256 `cb14872da20fe9664d9cc9409e42a5e4470ae812887a7e8fe0df5151dba5dbd1`；`pm path` 回读的手机已装 `base.apk` 与该交付包同哈希，说明该版已同签名覆盖安装。
- **2026-11-23 已发布源码与可安装包**：同一快照提交 `8e39e8da063d497feb23268d98b9791de0fc0bdc` 已推送 `main` 并打 tag `v0.7.2-fusion-threshold`；GitHub [Release](https://github.com/bianwuya/ace7pro-fan/releases/tag/v0.7.2-fusion-threshold) 提供同签名 APK、说明与校验清单，匿名下载复核哈希一致。0.7.1 的 Release 也已替换为同签名可安装包并撤回 unsigned 校验包。
- 云端 tag 签名 job 仍未恢复：`release-signing` 环境 Secrets 是 0.3.x 的 `e397…98d9`，与 `project.json` 的 `2623…808d` 不符，会失败关闭不产包；本次可安装包来自本机同签名产物。pilot 密钥已在用户授权下只读取回电脑受控目录（不入库），后续无需再取。新 Hook 加载后的双向启停仍待 `DAY-005` 放电真机验收。

## 0.7.1-fusion-fix：日用已启用，配置修正后待放电验收（2026-11-22）

- 手机 Git `HEAD` 为 `baff141af2720f1c96ef637b0507a1988f1a97f6`，日用融合源码在该提交之上的未提交工作树；排查使用提交与手机源码快照，未改手机源码或同步到上游。
- PMS110（`PMS110_17.0.0.159(CN01)`）运行 Hook `0.6.2-daily-feedback`。15:39:02–15:39:31（UTC+8）七条明确放电且风扇健康状态为 `NORMAL` 的样本，均回报日用 v3 已启用、仅电池参与（`1:0:0:0`）、融合值 `32.1–32.7 °C`，低于 `36 °C`，需求为 0。
- 原样本改算四路等权为 `36.38–37.80 °C`。通过 `setDailyConfig` 按版本写入并读回 v4：只改日用权重为 `1:1:1:1`，保留开关、`36 °C`、`10 档`，充电配置读回未变化。新增实际样本回归，覆盖达到门槛、正档抬升、已足够档位、零档和外电隔离；10 组 Java 回归通过。
- 复查时手机仍以 USB 充电，未观察到 v4 的放电运行回报或实际抬档。未改全局默认、原厂场景或保护，未重新安装或重启。

## 0.7.1-fusion-fix：初次覆盖安装记录（2026-11-22）

- 排障实读：旧 Hook `0.6.1-daily-backend` 已注入，日用 v0–v2 均回报关闭，无配置读取异常。保存的 v2 为 `36 °C / 10 档 / 1:0:0:0`，只计算电池；采样融合温度低于门槛。保存参数不等于启用，原厂 0 档也不会被强制抬高。这不能证明用户点过开关或曾在已启用、达温时失效。
- 修复版可显式「保存并开启」，普通保存不动开关；区分配置同步、未达温、原厂停转和控制反馈，标明融合参与通道。Hook `0.6.2-daily-feedback` 改进日用回传和决策时点，不改原厂正档前提和充电策略。47 项 Python、10 组 Java（含日用 620 条断言）、Compose 编译、AGP Release/lintVital、unsigned/v3 APK 审计通过。日志 `build/0.7.1-verify.log`、`build/0.7.1-build.log`。
- 实机用户 0 于 15:32:08 UTC+8 同签名安装 `dev.eta.acefan` App `0.7.1-fusion-fix` / versionCode 18；回读 APK SHA256 `fb0e35a874ca74af589ef2fec34bf923926a8ec16d3e8cc695be30554ffe4e87`。安装前后 Provider 日用和充电设置一致，原厂 PID 未变；未卸载、清数据、重启或改作用域。虚拟屏确认概览参与通道、设置关闭状态和编辑两种保存按钮；未点击启用。运行仍为旧 Hook，App 提示待重载，未验收新版 Hook 正候选抬档或驱动效果。
- APK 与回读校验清单位于 `Download/eta/工作区产物/ace7pro-fan/0.7.1-fusion-fix/`。源码基线 `baff141`，更新仅在本地工作树，未提交、推送或云端发布。

## 0.7.0-miuix 本地交付（2026-11-22）

- App `0.7.0-miuix`（versionCode 17）；Hook `0.6.1-daily-backend`，原控制路径未改。实际 Compose Miuix UI + Preference `0.9.5-beta01`，唯一启动界面 `MiuixActivity`。首屏直接显示五个温度、真实有效档位和次级状态；日用三项参数及独立开关已接通，不主动启用。
- Linux aarch64/JDK 25 离线通过 47 项 Python 检查、10 组纯 Java 回归、完整 Compose 编译和 AGP Release 组装（含 lintVital）。无签名中间包与 v3 签名 APK 审计均通过；审计实际包名/版本/SDK、对齐、入口、权限、Xposed 元数据，并检查两个 DEX 均未夹带 Xposed API。与手机已装 `0.6.0-fusion` 同签名。
- 交付：`Download/eta/工作区产物/ace7pro-fan/0.7.0-miuix/ACE7pro_Fan_0.7.0-miuix.apk`；SHA256 `ea7b3e851d21cfda679b719d461a4d579e0f246bc2ed3d873834892b592de29e`。说明/元数据/校验清单同目录并已读回；源码为 `baff141` 基线上的本地工作树，未提交、未上云。
- 交付时设备仍为 `0.6.0-fusion`；此后已同签名覆盖为 `0.7.0-miuix`，复查确认 `0.6.1-daily-backend` Hook 已加载，更新情况见上。

## 0.6.1-daily-backend 本地交付（2026-11-22）

- 当前工作树：App/Hook `0.6.1-daily-backend`，versionCode 16，基线 `baff141`；保留原有未提交工作。设备只读确认仍安装 `0.6.0-fusion`，未自动安装、重启、改 LSPosed 或发布。
- 充电继续用现有策略；新增明确放电时的日用独立后端，默认关闭。阈值、最低正档、四路权重、DB v3 迁移及预览读写契约见 [日用风扇后端](DAILY-FAN.md)。原厂 0 档继续保留：只读 OEM 取证未证实受保护的第三方提前起转路径。
- `verify`：47 项项目和 SQLite 测试、9 组 Java 回归（含 610 条日用断言）、全源码编译通过。unsigned 和 v3 签名包分别通过审计；新证书和本机 0.6.0 已安装包一致。仅离线，无新 Hook 注入或物理验收。
- 交付 `Download/eta/工作区产物/ace7pro-fan/0.6.1-daily-backend/`；APK SHA256 `5fde4809c4d1a6c544267f935318104d5e01c00ec555cb51ce27ef8e01650c56`，同目录有回读校验清单。未修改运行前端；浅/深两份 Miuix 布局审核图为本地渲染备选，本轮无可调用的配置生图入口，未发送生图请求。

## 仓库可见性：已公开（2026-11-04）

- 用户明确要求后，将 `bianwuya/ace7pro-fan` 从 private 改为 public；GitHub认证查询与不带认证的API请求均返回 public。源码无需协作者权限即可访问。
- 公开前全部可达历史26提交/242个唯一文件对象的凭据/私钥模式与禁止路径扫描无命中；模式检查不等于全面安全保证。未上传本地密钥、构建环境、OEM样本或设备数据。
- main `cba6d93` 的云端源码检查与unsigned APK构建均已成功：runs 41668749260 / 41668749185。签名job为非版本tag跳过，未打新tag或Publish旧Draft；release-signing环境仍限制v*标签，未更改签名配置。
- 当前源码App0.5.2-guide/Hook0.5.1-runtime；公开不新增设备实测结论。License尚未指定。下方旧私有仓库描述属于历史时点。

## 当前前端：0.5.2-guide（2026-11-03）

- 概览增强开关下仅一行“原厂开关与生效说明”，设置用“使用与生效说明”替换旧锁屏行。复用 Ui/Sheet/深浅主题，无新依赖。
- 详情包含原厂风扇/充电风扇/自动模式与自动调速、有线充电/息屏、33°C曲线起点与迟滞、候选应用的绿色状态、解锁交还及趋势/报告的历史查看。避免把“已开启/已注入”当增强或把候选当物理转速。
- 仅App为0.5.2-guide/versionCode14；Hook仍0.5.1-runtime，控制源码不变；仅因本次UI更新无需重启Hook，尚未加载0.5.1者仍需正常重载。
- 发布源码 `310443a`；本地同签名包 `dist/0.5.2-guide`，APK SHA256 `4dddc44a4c404f69ec952bb4cc22ff7bac47051a7aa3f42a3b284edff216d781`。已复制到 `Download/ACE7pro改/风扇助手-0.5.2-guide/`，六项文件及哈希清单复核。
- 本版完整编译与现有回归通过；未安装/未做新布局真机检查，未改原厂设置/作用域/控制行为。

## 当前反馈修复：0.5.1-runtime（2026-11-03）

- 来源为用户转发截图（user_report），未独立操作对方设备：.152 / 原厂服务 1.4.6 / Bridge API102 / Hook0.5.0；候选 g9.h.q 实际调用13次，上限20、场景/重算成功；控制确认字段缺失。
- 截图中的 Provider Unknown authority 发生在 elapsed约13–20秒，约64秒时仍有后续回报且配置版本1；早期失败后通信至少部分恢复。原厂自动模式启动值false，当时非有线充电，增强计数0，无增强实测结论。
- 源码确认缺陷：Provider只记失败不记成功；顶部历史错误覆盖当前详情；失败被二次泛化异常遮盖。0.5.1改为首次解锁等待、真实回执成功/恢复记录、当前异常与历史分开，缺控制确认时附字段清单。不猜测对方字段含义，不放开Provider访问或改控制场景。
- 离线验证：44项管理测试、42项接口、160项报告、35项独立故障/恢复、10项调用关联及原曲线/衔接/采样回归、完整源码编译通过。等待/不可用状态不会冒充成功恢复，必须实际成功回执才能清除当前异常。
- 最终源码提交 `36c8f19`；同签名/版本权限对齐审计通过，APK SHA256 `42c1d0399153ea76b4b573186e590a33a0ae0ac2ee00ec739bc7a90ab0336003`。分发在 `Download/ACE7pro改/风扇助手-0.5.1-runtime/`，6项文件哈希复核，未安装/重启/推送。
- 对方首次解锁状态未取得，冷启动凭据存储不可用只是可能原因。修复后的对方启动/运行仍待其报告，编译/测试不能替代。

## 当前状态：0.5.0-runtime（2026-11-03）

- 按用户明确需求面向 ACE 7 系列，直接解析和读取原厂实际接口；运行 APK 中已移除 DEX/指纹/参考固件代码，不再有全量 11 方法启动条件。
- 健康 getter 改名/歧义与缺少确认等能力独立报告；候选正常场景允许尝试。原厂请求按同一决策周期关联，状态确认不冒充物理转速。不同位置的五次错误不再全局熔断。
- 报告 v2 包含每环节成员、实际值、调用/失败计数、最近异常及其时间；恢复后保留失败历史。复制/分享/保存出口沿用，App 不自动发送。
- 验证：44 项管理自测；42 项直接接口/部分能力/就绪恢复合成检查；144 项报告检查；14 项独立失败/恢复检查；10 项计算返回后控制应用、过期与跨线程关联检查；原曲线/衔接/采样回归；完整 Android 编译与原签名 APK 审计通过。
- 最终源码提交 `c936bb8`（主体重构 `ec66a2a`）；最终本地包 `dist/0.5.0-runtime-final`，APK SHA256 `24da9726e38ee952e4a05544566aefedbff6aa64a70a1b4227b9f268b7b820e7`。APK 内类定义再次审计：直接接口/证据/关联类存在，历史指纹类不存在。
- 已复制到共享目录 `Download/ACE7pro改/风扇助手-0.5.0-runtime/`，6 份文件及 SHA256SUMS 逐项核对通过，附用户失败报告导出说明；旧分发目录保留。
- 本轮仅本地源码重构与构建；未安装本版、未重启/杀原厂服务、未改变作用域、未推送/tag/发布。设备实际加载与物理效果尚未验证，QA-001/006/007 继续待办。
- 下文为历史证据；旧版“必须取得 APK/指纹才可尝试”的限制已被本版机制替代，不能套用为当前启动条件。

## 仓库和管理

- 项目原位 `/workspace/ace7pro-fan`，分支 `main`；用户授权后已配置私有 `origin`：[bianwuya/ace7pro-fan](https://github.com/bianwuya/ace7pro-fan)。源码已备份但没有公开。初始提交为 `5f7fe0c`。
- 原来未提交的 0.2.1 采样与 0.3.0 兼容改动已经独立保存为 **`1e8bf9c`**，未 reset、丢弃或混作本次管理功能。
- 新增统一入口 [AGENTS.md](../AGENTS.md) / [知识索引](INDEX.md)、架构、流程、任务、变更记录，以及可运行的管理命令。
- `check` 使用版本/权限/作用域契约、常见敏感材料与生成物隔离、语法和本地文档链接检查；`--staged` 读取真正的暂存快照。它不是完备密钥审计或风扇安全证明。
- 管理实现提交为 `b9f8fd2`；单行口令文件在 apksigner 多次读取时耗尽的问题已通过 `577a057` 修复并加回归检查。
- 提交钩子已在本仓库本地配置 `core.hooksPath=.githooks`。实际提交触发检查通过；在一次性测试仓库中，把暂存内容设为违规而工作区改安全，真实 Git 提交被阻止，敏感值未输出。未改全局 Git 配置。
- 本地 release 已端到端通过，精确源提交为 **`577a0571a2e787051904c6e7f1cbdfe1d2890525`**。最终收尾只更新管理证据/任务文档，不改运行代码。
- GitHub CI 已在首批远端提交 `0a2ad17` 上实际运行通过：[run 41631656221](https://github.com/bianwuya/ace7pro-fan/actions/runs/41631656221)。它不含签名/设备验证，私有OEM fixture 在云端明确SKIPPED。未代选License、创建公开Release或改变设备。
- 首批完整历史推送前审查5个提交/98个唯一文件对象；远端重新克隆后提交与145个可达对象一致，`fsck`通过。后续同步与恢复边界见 [远端备份](REMOTE_BACKUP.md)，不把备份理解为含签名私钥的完整环境镜像。

## 本轮实际执行的离线验证

- 管理工具 **24 项 Python 自测通过**，包含暂存区/工作树不同内容、敏感内容不回显、权限/API/作用域变化、坏链接、语法、既有 Hook 不覆盖、构建锁和 APK 框架类隔离等合成用例。
- `python3 tools/project.py verify` 通过：项目检查、24项管理自测、原纯 Java 回归、22项兼容合成检查、真实参考 APK 11处指纹及合成变体、完整 Android 源码编译。
- `audit-apk` 对先前交付的 0.3.0 包通过：精确包名/版本、公开证书固定身份、v3签名、对齐、权限/入口和 API compileOnly 隔离。原交付哈希见 [交付记录](../releases/0.3.0-compat/RECORD.md)。
- `release --output dist/0.3.0-compat-management-check` 通过完整离线验证、重新构建、签名/包审计、源码归档与 SHA256 清单核验。此目录被 Git 忽略，包含 `BUILD_INFO.json`，明确标记离线验证、未安装与真实 OEM fixture 可用。
- 此本地流程验证包 SHA256 为 `a7e9a7be6a4a5a3a549984d9ada1d5084b862811d5b0dad236f02b4244304cd6`。它不是先前已安装/分享的那一份 APK；原分享包 SHA256 经复核保持不变。没有上传或发布这个流程测试包。
- 本轮不修改 `src/`、资源或运行 Manifest，不更改 App / Hook 版本，不安装、不重启、不杀服务。管理构建脚本的变化不等于设备已加载新内容。

详细本地日志在忽略的 `build/project-management-verify.log`、`build/project-management-release.log`；日志路径仅用于本机排查，不纳入源码或公开分发。

## GitHub自动APK构建与Draft流程（2026-11-03）

- 实现提交 `144a291`：普通main/PR/manual进行无密钥APK构建；精确版本tag才允许原签名job及Release草稿。版本/tag/main祖先/versionCode递增校验，版本说明必须有“新增 / 修复 / 验证与限制”，不自动创建tag或正式Publish。
- 原有24项管理自测加18项发布门禁/隔离测试，合计 **42项通过**，其中验证Secret在任何子进程之前从环境移除。构建脚本新增显式unsigned模式，本地默认同签名行为保留。
- 真实云端 [APK工作流41635143748](https://github.com/bianwuya/ace7pro-fan/actions/runs/41635143748) 在 `144a29166c543439d4b1791e0c7d5f20849cabba` 成功；原有检查工作流41635143870也成功。Ubuntu24.04/JDK17/SDK工具35.0.0、SDK35编译+28资源、固定R8/Xposed下载摘要，确实完成javac、D8、资源打包、对齐和unsigned审计。
- 已下载该次Actions artifact到忽略的 `build/cloud-run-41635143748/`，逐项SHA256和源提交校验通过，NOTES有新增/修复/验证限制，APK再次本地unsigned审计通过。其SHA256是 `6e451db381cc4d90e5adb50bea64c9ef3097d8bcc10168d1cbb37cc63019cae9`；**未签名，不能作为已安装签名版的更新包**。
- 签名job在普通main push上正确SKIPPED；原密钥与口令尚未上传，两个Secrets为空。缺Secrets时阻止签名/草稿，不能用debug/换签代替。签名到Draft端到端仍待用户单独授权云端加密托管并验证，不虚称已完成正式包发布。
- 流程/操作入口 [CLOUD_RELEASE.md](CLOUD_RELEASE.md)，当前版本正文 [NOTES.md](../releases/0.3.0-compat/NOTES.md)。没有改runtime源码/资源/Manifest、安装、重启或改变仓库私有性。此云端只做离线验证，私有OEM样本在云端明确SKIPPED，不能补作真机验收。

## 云端同签名Draft（2026-11-03）

- 用户明确同意后，现有签名keystore与口令已通过stdin写入私有`bianwuya/ace7pro-fan`的`release-signing`环境Secrets，环境部署策略为自定义tag`v*`；密钥/口令未进Git、artifact、文档或聊天，临时文件在运行后清理。
- 受控tag`v0.3.0-compat`的[run 41638235843](https://github.com/bianwuya/ace7pro-fan/actions/runs/41638235843)在源提交`1ba05d4`全部成功，生成**Draft**（非正式发布）。下载的APK固定证书`e397…98d9`、v3、包版本/权限/对齐审计通过，SHA256 `9995352c…c0c`；四件资产及校验清单一致，新增/修复/验证限制说明存在。
- 首轮Draft的`.idsig`清单和不正确的prerelease标记已修复；未审核Draft已删除并由新构建替代。自动成功不替代资产审计。
- Secret与环境配置已就位；若日后要撤销，需用户确认。仓库仍私有。云端未获取私有OEM真实样本，也不是设备/风扇效果验收。正式Publish仍由用户审核。

## 设备状态的历史证据

截至 **2026-11-03 19:21**，0.3.0-compat 已签名覆盖安装，但原厂服务 PID `6166` 未重启，仍加载旧 Hook `0.2.1-eco`。本次管理没有重新查询设备；此记录不说明当前 PID、加载版本或风扇实际状态。必须在用户正常重载后验证运行 Hook 和既有功能。

没有其他真实 ROM/框架组合的功能实测证据；新版重载后的增强、锁屏衔接、真正解锁后2秒交还、关闭/拔线、兼容页和导出等仍在任务中。离线编译、指纹与合成测试都不能代替物理验收。
## 设备兼容报告 0.3.2-report（2026-11-03）

- 源码提交 `fcb95d9`；App `0.3.2-report` / versionCode 7，运行 Hook 仍为 `0.3.1-report`（本次 Hook 逻辑未再改）。
- 新增 `DiagnosticReport`（纯 Java 格式化，无 Android/Xposed 依赖）与 UI 出口（设置页 + ⋯ 菜单 → 复制/分享/保存 .txt）。`FanServiceCompat` 增加分阶段解析轨迹与失败阶段；`FanHook` 上报 `compatibilityStage`、`targetPath`、`targetSha256`、`knownApk`、`hardwareGate`、`compatibilityTrace`；Provider JSON 字段上限 64→96。
- 验证：`python3 -m unittest` 44 项管理自测、Java 回归（含真实 OEM APK 11 处指纹与篡改检测）、新增 `DiagnosticReportTest` 45 项、全源码编译、原签名构建与包审计均通过。
- 参考机实测：覆盖安装 0.3.2 后在后台虚拟屏完成三处出口，复制出的真实报告与设备/ROM/原厂服务 1.4.6 一致。**实测同时暴露并修复了一个真实缺陷**：旧 Hook（0.2.1-eco）运行中时接口段为空却给出“没有明显阻塞项”，现改为顶部警告 + 适配建议第一条说明“缺少上报不是接口通过”。
- 分发：`dist/0.3.2-report`（忽略目录，含 BUILD_INFO/VALIDATION/SHA256SUMS 与 source.zip）已生成；APK SHA256 `2f548051a46219e65c9947feb58fa18bd3016251953dd40cac0227dcbca043c7`。同一文件已复制到共享目录 `Download/ACE7pro改/风扇助手-0.3.2-report/`，附 `使用说明.md` 与 `SHA256SUMS.txt`。
- 仍未做：正常重启后的新 Hook 重载与增强/锁屏衔接/2 秒交还/拔线复测；外来机型上报告的真实内容；未推送远端、未创建 tag、未 Publish。
## 0.3.3-report：控制器方法改名容忍（2026-11-03）

- 现场报告（PMS110_17.0.0.152(CN01)，同型号不同构建，原厂服务同为 1.4.6）显示失败阶段为“控制器与档位上限方法”，原因是 `OplusFanController` 无 `s()Z`。参考固件（.159）该类的 DEX 方法表同时存在 `r()Z` 与 `s()Z`，说明混淆名在不同构建间并不固定。
- 代码：`FanServiceCompat.healthMethod` 在 `s()` 缺失时，仅当该类**有且仅有一个** public 无参 boolean 方法时按形状解析；两个及以上同形仍判歧义并拒绝。解析后 11 处代码指纹仍为硬门槛，改名容忍不放松代码级验证。新增 `candidates()` 把实际存在的同形方法写入失败轨迹。
- 修复伪报：`verifyApk()` 此前只在接口解析成功后执行，失败设备会把“从未计算”印成“参考固件哈希：不一致”，且 APK 路径恒为“暂无”。现改为 worker 启动即计算一次，并新增“未校验”状态。
- 版本：App `0.3.3-report` / versionCode 8，Hook `0.3.2-report`。`verify` 通过（44 项管理自测、CompatibilityTest 25 项含改名与歧义回归、DiagnosticReportTest 49 项、真实 OEM 11 处指纹、全源码编译）；本地原签名构建与包审计通过，SHA256 `f54dcba6c5aaa647ab70fe884948c0edd28b9a5a6fc4d4dde932bf514ea4fc59`。
- 仍未取得 `.152` 固件的原厂 APK，因此**没有**验证该固件改名后方法体是否与参考代码一致；该机型当前仍应为“仅观测”，不能视为已适配。未在本机重启重载，未做物理效果验收。
## 0.3.4 / 0.3.5-report：报告文案与事实对齐（2026-11-03）

- 0.3.4：旧 Hook 警告改为先比较 App 与 Hook 版本、再按报告是否真的含结论/轨迹决定措辞。此前写死“接口/哈希/轨迹都不会出现”，而参考机上旧 Hook `0.3.1-report` 实际三项都有，文案与正文自相矛盾。
- 0.3.5：接口通过时“失败阶段”显示“不适用（接口已通过）”，不再显示“未知”。
- 版本：App `0.3.5-report` / versionCode 10，Hook 仍 `0.3.2-report`。DiagnosticReportTest 53 项、`verify` 与本地 release 均通过；参考机三次覆盖安装后用后台虚拟屏导出真实报告逐条核对。
- 分发：`dist/0.3.5-report`；APK SHA256 `8c365b980c882ef2c5b64794d85118c97035788f29ac35d68229763b83c7fbf7`。已复制到 `Download/ACE7pro改/风扇助手-0.3.5-report/`（含 `使用说明.md` 与 `SHA256SUMS.txt`）。
- 仍未做：重启后新 Hook 重载与物理效果验收；`.152` 固件适配（缺其 APK，无法核对代码指纹）；未推送远端、未打 tag。
