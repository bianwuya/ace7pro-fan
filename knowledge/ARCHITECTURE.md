# 架构与运行诊断

仅在原厂 `com.oplus.exsystemservice` 主进程工作，目标 ACE 7 系列。具体范围与验证事实见 [COMPATIBILITY](../COMPATIBILITY.md)。

## 数据路径

原厂初始化 → `FanServiceCompat` 解析实际对象及接口 → `FanHook` 接入候选层 → 正常使用场景中的候选应用 → 原厂下游执行 → 同轮控制请求及状态反馈。

- `FanServiceCompat`：读取实际实例、继承链、类型/参数与字段；档位上限、场景可直接读取。控制器反馈、健康字段等单独解析。没有 APK/DEX/参考固件核对依赖。
- `FanHook`：保留使用条件与曲线，在原厂自然候选调用后增风；以线程内决策周期关联后续控制请求，不主动调用 setter 试转、不跳过原厂下游。多个候选先观察策略读取。
- `RuntimeEvidence`：按环节记录调用、成功/失败、实际值、时间与异常。最近失败独立保留，不因后来成功丢失；成功回执将对应环节标为已恢复，当前异常与历史失败分开。不承担“满 5 次全局熔断”。
- `DataProvider`：既有 UID 校验与配置/遥测存储。报告扩展到有界 32KiB JSON，字段上限不放开，权限不放开。
- `MiuixActivity` / `FrontModel` / `FrontPresentation`：Compose Miuix 界面、Provider 读写及缺测/历史投影；配置定义不依赖 UI。`FrontExtras` 复用 `DiagnosticReport` 生成报告和有界 CSV，只按用户操作复制/分享/本地保存；不自动发送或上传。
- `FrameworkCompat`：仅诊断运行框架和 Manager，不以 Manager 启动结果判断模块可用性。

## 独立日用策略

`DailyFanConfig` / `DailyFanPolicy` 处理四路权重、温度门槛、最低档和电源切换；`DataProvider` 加单独表与版本回执，不影响既有充电配置与数据。`FanHook` 在候选层只抬正档，日用反馈不建立充电锁屏会话。具体接口和诊断见 [日用风扇后端](DAILY-FAN.md)。

## 行为边界

候选层保留原厂下游处理；充电仍为有线充电/息屏/自动模式；日用仅未接电，在同轮确认普通自动目标且热状态正常时按融合温度启停。充电确认后的会话可锁屏延续，解锁后最多 2 秒请求原厂重算。缺少确认能力则不建立锁屏会话。本次必要读取失败保留原始候选，可选读取失败仅影响该证据。

不写风扇/温控 sysfs，不改温控或系统身份，不恢复旧 rpm 节点轮询或 duty/speed 读取，也不把请求档位视为物理转速。`FanRpm` 仅被动接收原厂 FG 事件，同档位保持首次有效实测；事件观察失败与策略门控隔离，详见 [测速设计](FAN-RPM.md)。指纹工具移到 `tools/legacy-profile/`，不进入运行 APK。

状态与任务见 [STATE](STATE.md)、[TASKS](../TASKS.md)。
