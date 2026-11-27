# code30：手动交还与独立遥测

## 已确认的问题

用户从系统磁贴智能直接切极限，首切不启动，静音绕行或来回切才启动；同时手动时概览四温度及曲线失效。

本轮旧code29/驻留stage4沿同一系统设置进行受控测试（不是GUI实际点磁贴）：原 `mode_v3=0,customize=100`，改mode1后 `configured=0 owned=0 state=0 effective=0 status=NORMAL`；采样时间停在23227472，随后Provider sampleAge达35948ms且四温度均null。完成后mode恢复0，未改customize、用户日用/充电配置，未清故障或强制使能。完整只读证据 `build/manual-fix-audit/code29-live-transition.txt`，未上传。

代码确认两类缺口：原厂首写可能早于Root异步禁用，被owned期间的EBUSY拒绝；release会停转，但原厂同档成功缓存可能阻止再次使能。原时序测试只覆盖先release再OEM写，没有覆盖这条交叉路径。精确是哪一次HAL结果被缓存未取得同次原厂日志，不作超证据断言。

温度断流的因果已闭合：worker在采样前因manual/disabled权限失败退出，且不再排队。Provider仍继续写新记录，但温度为null；不是数据库整体停止。

## 最小修复及依赖边界

- `KernelSettingsRelay`：保持原十个系统安全键索引；尾部新增customize观察。原writer只负责配置/lease，没有Provider IPC。独立交还线程在disabled提交后读回精确revision/owned0/configured0，使用实际读取前的原始elapsed时间；按配置/设置代次最多三次新证明，不无休止刷新。
- `RootProviderAccess`/`DataProvider`：只将新方法加入窄allowlist；凭据只允许UID0写入，带副作用调用不进行不确定重放；OEM UID1000仅能确认消费。令牌在内存、过期无效，不改数据库、权限或现有接口。
- `KernelOemHandoff`/`FanServiceCompat`：仅解析原初始化/refresh与控制器确认字段，不依赖无关Strategy。原厂handler核实时效、模式、customize和精确release证明后，只使成功缓存失效、调用原完整refresh；不指定/保存/重放目标，不写level/state，不清健康事件。成功才标消费，异常不伪报。
- `driver_adapter.c`：读遥测与控制租约独立。已知失权先release，非控制状态仍内核采四温度；采样前后硬件保护检查保留。故障/安全位/过期均不取owner。独立展示融合保留原fusionValid的控制证据语义；配置变更和PM清缓存，resume只恢复观测。
- `KernelReport`：支持可选新字段，有界15秒内核新鲜度并校验时间一致性；旧status兼容。原UI/Provider历史格式无需迁移；缺测不填零、不补历史。
- 安装器只认ACE7Pro，不再按项目号/uname拒安装；helper依然受已验证ABI绑定。真正跨任意内核未完成：节点同名不能替代CRC/CFI/布局证明，未删保护或强装。

## 验证

- adapter测试14项；真实C host优化/UBSan与新增遥测回归52,521断言通过。覆盖manual/game/disabled/每个gate/fault/lease、先释放后读、缺测/换通道/配置和PM。
- Java遥测契约640、手动凭据/有界重试34、兼容44；Root反射桥209断言通过。
- 真实桥host执行17用例250断言：同档缓存停转复现→根确认→原handler refresh→启动，过期/模式/档位变化/未交还拒绝、异常重试、消费回执、Strategy缺失；这是host fakes，不是Android实测。
- 原打包87项、Horae兼容17和watchdog26项通过；Android全源码编译、原签名v3/DEX/权限/入口审计通过。原保护/独立充电源码保留。
- stage6新KO：b0b57952bd1522ea45cb5de2f11edd5cf2c11e2233a7c55a0ada6749f069bd24；85个原函数、1406个原RELA、8处批准重定向、89个CRC、12个CFI匹配通过。新helper绑定新KO，不复用旧payload身份。
- 过程与最终日志在 `build/manual-fix-audit/`。旧失败/中间构建保留，不当最终通过。

## 交付、当前运行与下一步

实际包路径、安装回执及驻留身份以 [DELIVERY](../releases/0.9.3-manual-handoff/DELIVERY.md) 为准。覆盖App/登记新KO不等于运行了新驱动；禁止热卸载/自动重启/强杀原厂服务。

用户已授权修复验收后正式发布、不要预发布。正常重启后要确认新KO与Hook30实际驻留，再验：智能直接极限首切、静音/极限互切、返回智能、四温度sampleAt持续前进、Provider新增有效温度和融合历史。原厂BLOCKED照实保留，不能为了验收清除。未经这一步不创建正式或预发布、不宣称全部完成；通过后按当次授权正式发布同一ZIP并实际下载核验。
