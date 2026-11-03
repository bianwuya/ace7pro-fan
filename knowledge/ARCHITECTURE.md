# 架构与运行诊断

仅在原厂 `com.oplus.exsystemservice` 主进程工作，目标 ACE 7 系列。具体范围与验证事实见 [COMPATIBILITY](../COMPATIBILITY.md)。

## 数据路径

原厂初始化 → `FanServiceCompat` 解析实际对象及接口 → `FanHook` 接入候选层 → 正常使用场景中的候选应用 → 原厂下游执行 → 同轮控制请求及状态反馈。

- `FanServiceCompat`：读取实际实例、继承链、类型/参数与字段；档位上限、场景可直接读取。控制器反馈、健康字段等单独解析。没有 APK/DEX/参考固件核对依赖。
- `FanHook`：保留使用条件与曲线，在原厂自然候选调用后增风；以线程内决策周期关联后续控制请求，不主动调用 setter 试转、不跳过原厂下游。多个候选先观察策略读取。
- `RuntimeEvidence`：按环节记录调用、成功/失败、实际值、时间与异常。最近失败独立保留，不因后来成功丢失。不承担“满 5 次全局熔断”。
- `DataProvider`：既有 UID 校验与配置/遥测存储。报告扩展到有界 32KiB JSON，字段上限不放开，权限不放开。
- `DiagnosticReport` / `MainActivity`：报告运行证据、失败详情与可复制/分享/保存出口；不在 UI 内控制风扇，不自动发送或上传。
- `FrameworkCompat`：仅诊断运行框架和 Manager，不以 Manager 启动结果判断模块可用性。

## 行为边界

候选层增强，保留原厂下游处理；有线充电/息屏/自动模式；确认后的会话可锁屏延续，解锁后最多 2 秒请求原厂重算。缺少确认能力则不建立锁屏会话。本次必要读取失败保留原始候选，可选读取失败仅影响该证据。

不写风扇/温控 sysfs，不改温控或系统身份，不恢复 RPM/duty/speed，也不把请求档位视为物理转速。指纹工具移到 `tools/legacy-profile/`，不进入运行 APK。

状态与任务见 [STATE](STATE.md)、[TASKS](../TASKS.md)。
