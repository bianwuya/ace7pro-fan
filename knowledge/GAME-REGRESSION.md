# 游戏模式回归：code29单次真实游戏进入/退出已通过

## 最新实测：温度解耦已在进程生效，游戏有实际转速

用户允许主屏后，已真实进入王者荣耀：gamemode_delayed自动为1，日用内核owned0/gates63，原厂风扇17档、NORMAL，FG实测保持19839RPM。返回桌面后game0、owned1/gates127，日用target10并沿normal斜坡降档；原设置保持。此前Horae自然恢复的实际进程SHA已确认为eae728…，外部Temp29.50与原厂kpTemp约38°C同时存在。见[原始恢复与游戏周期记录](GAME-RUNTIME-RECOVERY.md)。本结果不是所有游戏/同档竞态或下一启动stage5的完整生产验收，不再沿用下方“当前充电/停止/BLOCKED”历史观察解释现状。

## 历史执行结果：从源码推进到code29实际安装

用户要求立即推进后，已构建并实际安装`0.9.2-game-compat/code29`统一包，安装器退出0、App实装versionCode29、待启动模块身份/哈希回读正确。复用原stage5 KO与helper，不修改内核游戏策略。新Root relay `dm_0b22c1cc`（核验时PID14913）接续code29，正式Provider回传真实BLOCKED；原日用v8/充电v1未改。只停止了本项目旧Root relay，不启停原厂服务、不卸载驱动。

已实际只读bind回旧Horae风扇解耦：挂载前stock394ed4…，挂载后eae728…，退出0；应用时当前充电且Horae已stopped，本任务没有拉起它，状态`bound_waiting_normal_service_start`。等独立充电模块正常恢复Horae或用户正常重启后，必须再验证`/proc/<pid>/exe`，不把磁盘bind当运行生效。当前真实驱动BLOCKED不清、不强启，故游戏物理恢复尚不能宣称通过。

新ZIP为8212505字节，SHA256 `f6c093437d217136ad15b7c45c5004672effbc0ded8cbb7b1aa4cfa3c57a72bf`，本地在`Download/eta/工作区产物/ace7pro-fan/0.9.2-game-compat/`；详见[code29说明](../releases/0.9.2-game-compat/NOTES.md)。源码2994b0e及同包已推到原风扇仓库，预发布kernel-0.9.2-code29全部4个资产实际下载校验与本地一致；身份见[交付记录](../releases/0.9.2-game-compat/DELIVERY.md)。驻留stage4与boot未变；stage5登记待启动，禁止热卸载试错。

## 用户授权范围

用户本轮再次明确：只接管日用，游戏保持原厂，不能把新内核日用策略扩展到游戏作为补救。维护既有充电功能，不修改独立充电模块、EXGT配置或系统风扇设置。当前任务不允许清除硬件故障、强制风扇档位、卸载驱动或重启整机。

## 本轮已核实

1. 本boot仍为a7d4c185-a558-4bb9-8b8a-d995f80a3993，驻留stage4 `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`；code28 stage5只在待启动目录。不能要求重启就称游戏问题已解决，stage5只修日用阶梯。
2. 旧原生Horae修复确实在内核统一打包时遗漏；但29.5°C伪装守护仍保留且允许stock Horae运行。这改变了用户原有组合，不是用户关闭游戏功能。
3. 从旧私有备份tar读取patched ELF并从当前/system_ext/bin/horae复制只读stock样本，比对两者：大小均549656，stock SHA `394ed4b2b152c1fac1c76294cc9d33fcfb6199e391c7854b7db7a53391c2bca0`，patched SHA `eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d`。仅file offset0x5328c的一条4字节AArch64指令不同（3个字节不同）：原`88 02 00 36`为`tbz w8,#0,0x532dc`，旧修复`14 00 00 14`为直接跳往0x532dc，绕过testmode壳温覆盖分支。该分支位于风扇计算循环，正常拟合壳温计算保留；不是全局取消29.5°C伪装。
4. 旧分析用的/workspace/ace7fan/horae.bin和/workspace/ace7oem/horae.bin本身也是patched身份，不能将其反编译当作当前stock实测。patched反编译`decomp2.txt:366–444`为风扇真实拟合温度输入，`:651–725`有原厂game表选择。stock+testmode会经过额外覆盖分支，原有解耦必须保留。
5. 查故障时实际驱动曾持续报告status=BLOCKED、state0、effective0、present1；内核configured0/owned0/fault0，relay gates95缺硬件健康位32。此时没有内核强占，但不能因此声称原厂游戏已接续。BLOCKED是驱动报告，不据此判断物理损坏或归咎用户；不重置/绕过保护。
6. KernelReport把`fault0 && live1`写成fanStatus NORMAL，是真实遥测错误；实际驱动健康是另一个状态，必须真实读取，失败UNKNOWN。
7. 获取本boot2200条Root历史后，观察到后续OEM在configured0/owned0下将请求/使能变为20/1，证明非owned时原写入口未全局被封死；不把该状态当成RPM实测或游戏自动曲线验收。详细时间仅保留在私有build。

## 尚未证实及不得省略的交还风险

内核eta_release_locked只stop+release；全权期间OEM写返回EBUSY，未保存新的OEM意图；原厂OplusFanController.i()同档且上次写成功时会早退。静态可构造“软件缓存同档已成功、内核交还时停机、游戏同档不重发”的失配路径。不能据此声称此次已实测走到该序列，也不能用陈旧saved_oem_*强行恢复，或移除故障门禁。正常游戏交还与故障停止应分开回归验证。

## 修改前依赖、行为与验证方法

- 原厂游戏调用方依赖Horae FanSmartControl的真实拟合温度与原厂game表；本轮只恢复已经用过且逐字节确认的单指令解耦，不改游戏表、不由Java/脚本算档、不让日用内核在game继续抢写。
- 新增只在post-fs-data调用的oem-fan-compat.sh：从精确stock派生旧patched映像，双SHA验证，仅只读bind；不分发OEM完整二进制，不写系统分区、不重启服务。若Horae已经启动，只记录等待下一次正常服务启动，不把磁盘挂载当运行生效。
- 保持驱动helper/KO/原配置协议、设置、FG通道、所有硬件保护及充电策略不变。
- 报告修改涉及KernelReport.fields与KernelSettingsRelay.reportLoop，新增真实硬件状态参数；旧调用保留兼容重载，未提供证据只能UNKNOWN。RPM消费相同真实状态，不从policy fault推断健康。
- 涉及安装打包器只增加明确文本脚本白名单及现有post-fs-data入口，不削弱原hash/ABI/签名/权限校验。
- 测试：临时目录重建patched字节与旧归档SHA完全一致，错误原件/结果拒绝；shell语法检查、模拟mount/服务状态测试；原Java回归及新状态用例。不能将这些当成真实游戏切换/交还或重启验收。

私有原始证据：`build/game-regression-audit/`。此页为当前回归入口；旧release资产不覆盖、不重发成“已解决”。源码阶段验证记录（其后已按上方结果构建安装code29）：恢复oem-fan-compat.sh与post-fs-data调用、打包文本白名单/schema/权限；旧stock可精确派生原补丁，不携带OEM二进制。KernelReport改为真实硬件状态参数，Root relay逐次读取原status；旧调用缺证据返回UNKNOWN，RPM消费同一真实状态。Linux与Android模拟挂载各17断言通过，实际stock派生结果与旧tar内patched逐字节一致；新增硬件状态16断言、既有Java回归（含Root桥552、Provider175等）通过；打包器87项通过；离线完整Compose/Kotlin/Java Android编译成功，没有签名/组APK/安装。一次打包回归揭示schema白名单未同步，已修正后完整87项重跑通过，未放宽检查。最终只读现场硬件已恢复NORMAL且实际有正档，属于现场自然状态变化，不是本轮源码已生效，也不证明原厂游戏恢复。正常游戏同档交还仍未验收；上方最新执行结果已推进到code29兼容恢复包的实际安装，仍不标“游戏物理起转已验证”。旧code28或同一待启动stage5本身不包含本轮启动兼容/报告修复，不能混淆。
