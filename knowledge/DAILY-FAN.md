# 日用风扇后端与前端接入契约

后端从 `0.6.1-daily-backend` 起提供；`0.7.0-miuix` 接入相同的配置契约。审核图仍只是示例。**日用独立配置，默认关闭；旧版充电模式、开关、曲线和唤醒交还不变。**

## 控制语义

- 仅接受明确的未接电、存在电池且正在放电；有线、无线、底座、充满、暂停充电和未知电源状态不借道日用。日用不根据屏幕亮灭放行，仍由原厂场景决定（本版只放行 `Common`）。插拔电、供电报告变化和配置变化撤销旧的日用快照；15 秒以上的样本不用作控制。
- 只在原厂总开关允许、未暂停、自动模式与自动调速开启、无已知保护限制、健康状态为 NORMAL 且无原厂故障事件时考虑。采样或原厂必要接口失败就保留原结果。
- 启停温度可配 `20.0–50.0 °C`，初值 `33.0 °C`；起转最低档 `1–20`，初值 `6`。高于阈值至少请求配置档位，保留更高的原厂正档，并按实际原厂上限约束。
- 四路权重按**电池、主板、风扇周边、充电 MOS**排序，每项 `0–100`，可用小数，不要求和为 100，至少一项大于 0。`Σ(T×w)/Σw` 只用有效且权重为正的读数；缺测、陈旧电池广播和工作带 `0–65 °C` 外剔除并重新归一；无有效读数不增强。默认 `1/0/0/0`，无隐藏温度偏移。
- `0.6.4-daily-hysteresis`：停转态只有融合温度严格高于设定值才起转。起转后，新鲜样本显示融合温度不高于设定值减 1°C 时开始计时，连续满足满 30 秒后的首个样本才请求 0；回温超过停转线重计时。配置、供电、传感器有效性或样本连续性变化会撤销旧的保转状态。只有在同轮观察到 `FanMode` 普通自动目标被候选直接返回时才可双向改档；其他 ROM 或调用维持原有正档增强，不将未知零档当停机温度结果。
- 双向启停要求系统热状态为 `NONE`、四个原始传感器有效，并且电池低于 `45 °C`、其余低于 `55 °C`。这是退出日用并交还原厂的保守条件，不改变原厂保护阈值，不受用户权重影响。原厂降噪、手柄、使能和驱动保护随后照常执行；不写 sysfs，不伪装温度，不直接调用 OEM 启停命令。

## Provider 接口

`ContentResolver.call(content://dev.eta.acefan.data, method, null, extras)`：

| 方法 | 访问 | 输入和结果 |
|---|---|---|
| `config` / `setConfig` | 既有 | 旧版充电接口与回执保持不变。 |
| `dailyConfig` | App、自有系统/Root 读取范围不变 | `enabled: Boolean`、`startTemp: Double`、`minLevel: Int`、`batteryWeight`/`boardWeight`/`fanWeight`/`mosWeight`（Double）、`version: Long`、`proportions: Double[4]`（0–1）。 |
| `setDailyConfig` | 仅 App 所有者或既有 Root 写入范围 | 上述 7 个配置字段全填，另带 `expectedVersion: Long`。一次事务、返回完整持久化行加 `ok`。过期版本 `ok=false, error=version_conflict`，附当前行，不覆盖更新。成功只通知 `content://dev.eta.acefan.data/daily-config`。 |
| `previewDailyConfig` | 同读取 | 配置字段加 `readings: Double[4]`，缺测用 NaN。只计算，不保存也不控制风扇；回执 `hasTemperature`、存在时 `fusedTemp`、`contributorMask`、配置 `proportions`、本次 `usedProportions`。调用方须用同一份新鲜遥测作为输入。 |

DB v3 新增独立的 `daily_config` 单行表，从 v1/v2 升级保留历史与充电设置。业务命令顺序：保存回执 → 同配置版本的 Hook 遥测 → OEM 候选 → 同轮控制请求 → 驱动有效档。只有用户在 App 明确打开日用开关才写入启用状态。

## 遥测与审核稿显示范围

沿用原充电遥测；另有 `controlMode=charge|daily|oem`（电源路由，不代表正在增强）及日用嵌套对象 `daily`。对象含 `version`、`state`、`startTemp`/`minLevel`、`fusedTemp`、`contributorMask`、`demand`；存在同轮观测才给 `oem`、`candidate`、`maxLevel`、`request`。缺测为空或字段缺失。`effective` 才是驱动报告档位，仍不是转速。

- 概览直接显示融合温度、有效档位和四路传感器温度，状态是一行小字；无运行回报时，当前真实读数计算的结果标为“预览”。趋势只用样本当时的融合值、日用阈值和有效档位；权重在设置编辑器，诊断和原始趋势按需展开。
- `ready` 是有温度需求、尚未见原厂决策；`cooling` 是仍有正档需求、正在确认连续降温。`waiting_oem` / `auto_target_unverified` 表示未观察到能作双向启停的自动目标；`thermal_blocked` 表示热状态异常或未知。`stop_candidate` 与 `stop_requested` 分别表示候选为 0、原厂同轮请求为 0；是否停转看 `effective`。`candidate_applied`、`request_confirmed` 也不代替驱动实测；`oem_limited` / `oem_stopped` / `oem_override` 保留原厂处理。
- 从 Hook `0.6.2-daily-feedback` 起，日用启用且放电时，稳定回报间隔约 10 秒；配置版本回报可以比实时温度保留更久，不用过期回报解释当前档位。`decisionElapsed` 与 `decisionFusedTemp` 是最近一次原厂决策的时间（开机后 ms）和当时融合温度。需求档、配置版本、参与通道或供电变化后不沿用不匹配的决策。
- 编辑器可显式「保存并开启」，仅保存参数不改变开关。概览仅列真正参与计算的通道；例如权重 `1/0/0/0` 时，读数就是电池温度，即使另外三路已显示或超过设定阈值。
- 现有设置项显示「日用启停温度」「起转最低档位」，不把目标档位当驱动档位。审核稿示例值不代表实测、默认或生效状态。

## 原厂起转通路的证据边界

PMS110 `.159` 的 OEM 服务 1.4.6 V3：`i9.h.q()I` 在 `Common` 返回 `FanMode` 的自动目标；`i9.h.c()` 在候选之后仍负责降噪和最终使能，手柄提前退出。Hook 观察调用链而非绑定混淆名，不修改 `AutoControlMode` 的 Binder 输入，也不改手机已有的 `horae` 覆盖模块。连接手机读到暖机前正常零档不是新 Hook 真机验收。
