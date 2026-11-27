# 内核后端当前检查点

记录：2026-11-27 19:05:21 +08:00。此页替代旧准备阶段执行入口；不是持续监测状态。

## 最新：0.9.1/code28 RPM已实装运行，日用升档新驱动已登记

- 用户报告融合40°C仍10档，经实读确认40597mC却demand10，确为独立daily只剩floor的移植缺口，不是normal斜坡或硬件降额。已从本机既有解密配置/反编译取回普通阶梯：trigger35/37/39/40/41/43°C、clear33/35/37/39/40/41°C、档6/10/14/16/18/20。现以用户四路融合温度运行独立阶梯状态并max(floor,step)，保留用户启停/1°C30秒回差、充电计算及所有保护；不声称复刻原厂ShellTemp/KP/功耗模型。
- 新真实KO152576字节，SHA256 `6c7f8d320d89c969d10679e39755fd6dfac8330b06c659f370ecd3bfe697c49a`。40.597°C升温目标16等回归通过，核心优化/UBSan各129139断言、adapter14项、relink24项通过；85原函数/1406原RELA/8批准变更/89CRC/12CFI门禁保持。
- RPM修复已进入本地源码与App/code28，原签名APK SHA256 `e6f41bd3ffff70b789baa6116c8dda105d2d0793a7bcd6e6d39787bc4f08f3d6`。独立Root接收原驱动FG事件，不读旧缓存或用日志推算，保留旧Hook。先实际被动probe实收13039RPM，再只更新本模块APK并替换Root relay进程，未卸载驱动/改配置/重启。Root服务当前由一次更新接续进程 `dm_ecf9db17` 运行，下次启动仍由KSU service负责，不另加开机任务。
- 正式Provider已有13条held实测回传，包含10档13119RPM、9档12059RPM；后续同档波动不覆盖首次有效保持，换档重新获取、停机清空均有实际数据。App数据入口字段未变，前端未做屏幕视觉复核。原39项测速保持、23项新事件解析/联动、175项Root桥及2项路径检查通过。
- **统一ZIP已实际安装成功**：`Download/eta/工作区产物/ace7pro-fan/0.9.1-kernel-rpm/ACE7Pro_Fan_0.9.1-kernel-rpm-code28.zip`，SHA256 `155a6abe82c47a5b610af8b5ee6c360e21b2ac6de5e655e3a68a49dbacaa31ef`。metadata真实新helper `ff41b64a5b911510010a414cd4780f6ffb26e9c479fbbaa5e47e39d0f713e55f`及新KO哈希/正确vendor_file标签均回读；KSU退出0/登记ok。
- 运行boot仍 `a7d4c185-a558-4bb9-8b8a-d995f80a3993`，当前驻留仍旧stage4 KO `982b...`；因此RPM修复已运行，但新的日用阶梯要用户自行重启后才生效。未装载/卸载新旧硬件驱动，不以主机回归冒充新KO实际升到16档。日用v8/充电v1所有原设置保持，充电项目未改。
- 用户要求所有修改落本地工作区后将包上传现有风扇仓库 `bianwuya/ace7pro-fan`，不是Eta仓库；本地origin和实际GitHub仓库写权限已核对。GitHub完成状态单独以交付记录/远端回读为准，不把准备上传说成已上传。源码/包不得包含密钥、私有原始日志或OEM分析样本。
- 证据：`build/root-fg-fix/` 的实际RootFG/probe、正式Provider历史提取、签名构建、联合包校验、KSU安装和下一启动readback；新驱动证据 `build/kernel-replace-audit/driver-relink/stage5-daily-step/`。详细身份与限制见 [本版说明](../releases/0.9.1-kernel-rpm/NOTES.md)。

## 历史：自然起转、升降档、降温停机及再次起转已有真机证据

- 用户报告“现在触发了”后，实时内核status及独立硬件节点一致：target/output/requested/effective均10、physicalState1、NORMAL/present1，owned/ready1、fault0；4轮连续状态正常，未改阈值或写控制节点。
- 读取Provider最多4000条原有有界历史，在本boot筛得206条真实Root内核回传，未把旧boot/Hook数据混入。最近一次自然起转elapsed937705ms输出6，随后942705/947705/952707/957707ms依次7/8/9/10；同configRevision5、ownerEpoch3、owned1/fault0。实际约5秒一档，已证实原厂normal升档路径，不只是源码推断。起始瞬间effective0不冒充已达6档，后续7–10硬件回读相符。
- 同一运行历史还有自然降温过程：847702ms融合34.960°C且cooling1，之后约每5秒回传仍低于35°C并保持目标10；877702ms（距首个观测到cooling1约30秒）target转0。之后原厂normal输出10→9→8→7，917703ms实际state0/effective0，再次升温后重新6→10。5秒发布间隔不能证明每个瞬时传感样本或捕获所有中间档位，但已观察自然回差确认、降档、停机及可再次起转。
- 驱动真实FG timer日志保留7条计数窗口，每窗口约1000ms、654–655脉冲，实测13079–13099 RPM；与后续fan_status_work记录相符。不是目标转速表的12569 RPM，也未读旧rpm/speed/duty缓存。**这只证明驱动已有真实测速；Root事件接入及App RPM显示仍未完成**，不得据日志测速冒称界面已经接通。
- 当前启动阈值36°C、最低10档，实时融合约35.7°C但仍运行，属于已起转后的回差保持，不能按单个当前温度判断触发阈值失效。没有连续高精度跨阈值采样，不把5秒回传中35.982°C的首个正目标误说成阈值恰好在35.982°C触发。
- 仍待充电切换、故障/租约失效安全交还、独立Root FG/RPM及前端显示验收；不将本轮自然场景全部称为生产完整通过。未重装、重启、制造故障或改设置。当前ZIP保持r5原字节。
- 证据：`build/first-kernel-control/reboot-boota7d4/natural-start/` 的实时status、原驱动FG日志、真实Provider历史及提取的`current-boot-policy-observations.json`、`current-boot-control-transitions.json`、`positive-runtime-verification.json`。大原始历史仅留私有build，不进入共享交付。

## 历史：r5真实入核、零需求持有控制权及Root回传已通过

- 用户自行重启，当前boot `a7d4c185-a558-4bb9-8b8a-d995f80a3993`。preinit在0.836722秒回报 `applied exit=0`；实际文件为正确vendor_file标签及候选哈希 `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`。`oplus_fan 86016 ... Live`，soc:pwm_fan绑定pwm-fan且driver/module指向oplus_fan；eta_policy节点确实存在。本轮已不再是只有文件挂载成功。
- Root relay进程PID5439（本次观察）运行，Provider.kernelBackend连续回报active=true；实际最新回传 `backend=kernel_eta_policy_v2`、`hook=内核控制`、nodeControl.ready/controlling=true、daily.state=kernel_control，与当前boot吻合。已核实Provider回传，不代表做过前端屏幕视觉检查。
- 4轮只读状态观察跨度12112ms：每轮live/configured/owned/ready=1、fault/blocked=0、strategy2、四路tempValidMask15、硬件readbackMask7。sampleAt持续前进，configRevision始终2，leaseUntil持续更新且保持nowMs后不超过5000ms；确认采温及续租不是静止快照。
- 当前自然条件：日用，融合34.684–34.904°C，低于36°C启动阈值，target/output/effective均0、physicalState0、NORMAL。内核持有合法零目标，不能因风扇停转就说没接管；但也不能据此声称实际正需求起转/斜坡已经验证。日用v8（36°C/最低10档/60:0:40:0）与充电v1（33°C）回读保持。
- 顶层active/phase/fused字段沿用充电场景语义，日用看daily及nodeControl；本轮回传顶层active=false不等于kernelBackend或nodeControl失效，未为此改算法或配置。原追加service.log有前两次启动的stock错误行，本轮没有新relay错误；不可把历史行当本次失败。
- 尚未观察自然正需求、实际起转/斜坡、充电切换、30秒降温停机、故障/租约过期交还。**当前Root回传的FG observer明确为unavailable，实测RPM通道尚未接通**，不是已测速但缺验收。旧可选Hook存在不代表Root进程的FG通道已有数据。
- 当前安装和交付仍为原r5/code27，不重建、不重装、不改阈值或强制起转，也未停止进程/卸载驱动/重启设备。发布ZIP保留原始生产验收false字段，不因这次有限通过冒称完整生产运行或篡改交付字节。
- 证据：`build/first-kernel-control/reboot-boota7d4/runtime-identity.txt`、`status-renewals.txt`、`latest-publication.json`、`live-verification.json`。后续从已运行的内核控制链继续真实场景验收及Root FG功能，不重做早期替换或要求无关重启。

## 历史：r4已挂载但加载域无权读取；原厂恢复，r5标签修复已安装

- 用户再次自行重启，当前boot `7064f3a7-5214-4030-81fe-02cd3c68a129`。实际preinit日志为0.853002秒 `applied exit=0 reason=replacement-bound-readonly`，候选驱动文件哈希正确，但 `/sys/module/oplus_fan` 和LED节点均不存在；不能把挂载成功说成驱动已入核。
- 已查明确定阻塞：metadata候选的实际标签是 `u:object_r:vold_metadata_file:s0`；通过设备 `SELinux.checkSELinuxAccess` 只读查询，`vendor_modprobe` 对该类型的 getattr/open/read/map 及 system:module_load全部拒绝，对原厂 `vendor_file` 全部允许。未切进程身份、未改策略或禁用校验。无启动期AVC日志不等于没有权限问题。
- 为先恢复原有风扇功能，在确认oplus_fan完全未驻留、目标挂载源属于本模块且候选哈希精确相符后，只撤掉PID1中的该文件bind。露出的stock哈希为原固定值；经原厂vendor modprobe加载成功，`oplus_fan 61440 ... Live`，LED节点/present1/NORMAL恢复。没有卸载任何运行中的驱动。r5安装后最新实读state1/level10/level_eff10/NORMAL，说明本次原厂风扇路径已恢复工作，不能标成新内核后端控制通过。
- r5仅补全已验证payload的原厂文件标签准备：先全量SHA/ELF/CRC校验，再经持有FD设置固定 `u:object_r:vendor_file:s0`，回读标签，并重读全量字节及stat以固定新ctime快照；最终bind前和只读挂载后再次检查标签。任何失败保留stock，不改SELinux规则、DAC权限、身份、原厂文件或App/驱动字节。service对驱动缺失改报unavailable，不再误报stock。
- helper新增10项标签回归，总166项通过。相同生产标签准备函数已在Android对新payload、随后对实际metadata inode运行并回读成功，原厂文件哈希及驻留模块不变。已核对精确ACK `242e1a07f878` 的SELinux源码：初始化策略前setxattr由owner检查，getsecurity允许回退读取磁盘xattr；这只是源码佐证，r5的下一次preinit实际运行仍待观察。
- **r5统一包已实际安装成功，KSU退出0**。metadata helper哈希 `49318ef4802635ee2b2dda68c8e2d1bd6be24b378f0f2c6c24f93c5e777b576b`；payload仍为 `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`，标签 `vendor_file`、root/0644/单硬链接。下一启动登记ok不当作运行验收。
- 当前ZIP：`Download/eta/工作区产物/ace7pro-fan/0.9.0-kernel-code27/ACE7Pro_Fan_0.9.0-kernel-code27-r5.zip`，SHA256 `bbfe43b52cedf74484836ce303d2b3077cbcf60d0e973e9f47e0f2b688450370`。r4归档，App仍原签名code27，驱动原始候选字节不变；未改充电项目。
- 未自行重启，也未加载新候选来替代已恢复stock。下一次由用户自行重启后，必须分别检查preinit挂载、oplus_fan驻留/eta_policy、relay和真实风扇控制；内核接管、保护/交还、RPM均仍未验收。不能承诺这一个已修复阻塞等于所有后续运行验证必过。
- 证据：`build/first-kernel-control/reboot-boot7064/` 的启动、权限矩阵与原厂恢复日志；`preinit-r5/` 的166项回归、静态构建、实际标签准备、KSU安装、metadata/stock回读与ZIP校验。

## 历史：首次重启未接管，r4时序修正已重装

- 用户已自行重启，当前 boot 为 `7825ae3c-99fc-43e9-89ee-80d914e1255d`。启动日志明确：helper 在1.416411秒被旧1.2秒守门拒绝，`exit=22 reason=preinit-boot-deadline`；vendor.modprobe 是2.325284秒才启动，原风扇probe在4.850042秒。上一轮preinit登记ok只说明下一启动文件已登记，不证明该次执行成功。
- 本次只改 helper 的额外时间上限为10秒。保留同步KSU环境、Root、PID1命名空间、精确内核/哈希/CRC与 `/proc/modules`、`/sys/module` 双重未加载检查，并在唯一bind前复检。无已加载驱动热替换或保护删减；驱动和App字节完全不变。
- r4统一包已由真实KSU安装成功，退出0，下一启动登记 `horae_fan_spoof ok`。metadata中真实helper SHA256为 `cd96c81398c3104437661d9c761f2a0260cee0ab522381f30f709e1d83f291a5`，driver仍为 `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`；文件权限及单硬链接回读通过。
- 当前交付 `Download/eta/工作区产物/ace7pro-fan/0.9.0-kernel-code27/ACE7Pro_Fan_0.9.0-kernel-code27-r4.zip`，SHA256 `46a7b6ae45e40da6c1155a33d09138ceeb817dfc78569091e0fb0da983b1c528`。r3已归档，不再作为当前包。
- 156项helper回归通过，含实际1.416秒、5秒、10秒边界、已加载仍拒绝和最终复检；启用版helper及真实payload已在Android做只读验证，完整151040字节/89 CRC/72字节manifest相符。未调用apply或挂载来假装下一启动成功。
- 当前仍stock、eta_policy不存在、kernelBackend.active=false；配套App/code27哈希及日用v8/充电v1设置回读保持。未重启、未卸载原驱动、未改原厂模式或其他项目。旧充电模块路径在本轮安装前已不存在，故本轮不声称其哈希已核对，亦未对其执行任何修改。
- 用户明确自行重启，不再反复问重启授权。下一步在其下一次重启后先读 `ksu_preinit` 的真实结果，再核验驱动与relay，不以登记ok代替运行成功。真实控扇/保护/交还/RPM仍未验收。
- 证据：`build/first-kernel-control/reboot-boot7825/preinit-failure-and-order.txt`、`preinit-r4/` 下构建、156项回归、Android只读、KSU安装及设备readback日志。

## 上次安装历史检查点（13:28，以下boot/哈希不代表最新helper）

- App：`dev.eta.acefan`，`0.9.0-kernel`，versionCode **27**，原签名。设备 base.apk、固定构建副本及 KSU 包内 APK 哈希一致：`a235bd231f73aea06caf176d70f8f5271cae31537e69b03ee2188b79d992b593`。保留数据；日用 v8（36°C、10档、60:0:40:0）与充电 v1（33°C）回读不变。
- 统一包：`Download/eta/工作区产物/ace7pro-fan/0.9.0-kernel-code27/ACE7Pro_Fan_0.9.0-kernel-code27-r3.zip`，8,207,389 字节，SHA256 `f2e66ad3fa13f472b9ca755f4d9b9a0bd10ce27446628501cb593edaa4ed7e6b`。
- 已实际执行 `ksud module install`，退出 **0**，回报 `Module installed successfully!`；`ksud module preinit status` 为 `bootcount 0/3`、`horae_fan_spoof ok`。模块更新在 `/data/adb/modules_update/horae_fan_spoof`，不是仅生成 ZIP。
- KSU 已复制下一启动所需文件到 `/metadata/vold/ksu/preinit/horae_fan_spoof/`。加载器 root:root/0755/单硬链接，780344 字节，SHA256 `58684c3adff92fb2cedddc88245c4bc1c74b80e3b2983d8623ec768270583491`；完整驱动 root:root/0644/单硬链接，151040 字节，SHA256 `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`；配置只有 `exec bin/fan-preinit --apply`。
- 当前 boot 未变：`2f554681-bce5-4d4d-b76e-e94c5eff92f5`。原驱动仍 live，文件哈希仍 `a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd`；eta_policy 尚不存在。末次回读 state0/level6/level_eff0/NORMAL。旧温度守护 PID5469 仍存活；本轮没有停止它、horae 或系统服务。充电模块五个输入文件哈希前后相同。

## 本轮实机修复

Root 普通 ContentResolver 被 ActivityManager 拒绝。现通过 `getContentProviderExternal` 和真实 UID0 attribution 调用，finally 释放；配置、13项 Settings 及监听注册/注销均已在设备通过。只读死亡最多重连一次，pushRoot 不重放结果未知的写。未放宽 Provider UID 边界、App 权限或 SELinux。

口袋传感器只提供 wake-up 型，已改精确查询；实际值5。已对照原厂 `fan/scenes/g$a.smali` 的零比较：零为入袋、非零为离袋，而非只接受1。缺样本及非有限值仍未知。性能模式按原厂 `d$a.smali` 的 `startsWith("true")` 语义解码，正确处理设备实际的 `false#…` 字符串，不误阻止全部控制。

安装器首次直接重定向 pm 的调用失败；第二次流式调用已进入服务但本 ROM 拒绝额外 `-` 参数。r3 使用无路径的 `pm install -r --user 0 -S SIZE` 标准输入流及管道输出，**在真实 KSU 安装器内成功安装并回读 APK 哈希**，不是跳过安装。旧失败包仅归档，不作为当前交付。生命周期脚本不在更新时停止旧守护；relay 使用固定 nice-name 与校验 PID，卸载不动 App 数据或运行中的硬件驱动。

## 验证与仍未验收

原签名 Android release/lint/DEX 审计通过；175项 Root桥断言、18项原厂门控解码断言、2项测量路径检查通过。只重测受影响部分。真实 Root probe、15个 URI及 thermal/audio/USB/pocket/broadcast 注册/清理成功；口袋回调值5现正确解码为离袋。启用版 helper 的只读 check-only 已在 Android 退出0，未挂载。

完整驱动的采温、融合、目标、斜坡和执行均已有真实代码；但 **尚未加载该完整驱动、未启动正常 relay、未进行实际风扇接管/保护/交还验收，也没有验证本版真实RPM显示**。不能把此前只读 observer 成功当成控制通过。当前 ZIP 是明确的首次控制验收包，manifest 保持 `candidate_control_tested=false`、`production_runtime_verified=false`；正式发布门禁没有被伪造通过。

下一步需要用户允许正常重启和首次实际控制验收，不能自行重启、卸载当前驱动或启用临时硬件写入来凑结果。启动后先核对 preinit 结果、运行驱动身份、eta_policy/status及 relay 发布，再按真实场景验收；若早期替换拒绝，保留 stock 并读明原因，不声称接管。

精确日志、构建与安装身份在忽略目录 `build/first-kernel-control/`；原模块备份 `prior-horae-module.tar` 也在该私有目录，不进入共享ZIP。原始OEM样本、密钥、真实原始日志不打包。源码未提交或推送；不重置既有改动。`/workspace/PROJECTS.md` 不存在，未创建平行状态表。
