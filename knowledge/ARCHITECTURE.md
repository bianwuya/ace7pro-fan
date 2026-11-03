# 架构与保护边界

## 作用范围

这是面向 OnePlus ACE7pro 的传统 Xposed 模块与本地观测 App。运行目标严格限于 `com.oplus.exsystemservice`；硬件型号、原厂风扇接口和传统 Xposed API 门槛均须通过检查。不得把模块作用域扩展到 `system_server` 或其他应用。具体支持版本、矩阵和证据以 [COMPATIBILITY.md](../COMPATIBILITY.md) 为准。

## 组件职责与数据流

1. **FanHook** 在目标原厂服务进程内工作。等待原厂风扇初始化后，协调硬件/API 门槛、适配解析与代码指纹验证；通过后只在原厂候选档位层接入既有策略。它不是独立风扇驱动，也不替代原厂控制器。
2. **FanServiceCompat** 将原厂 V3 服务的候选、选择器、控制器及保护链成员解析成有类型的适配绑定。字段/方法需要唯一匹配；缺失或歧义时拒绝接管，不靠猜测混淆名或任意 `int` getter。
3. **DexFingerprint** 对参考保护链的 11 个关键方法作有界、只读的 DEX 指纹核验。**ReferenceFanProfile** 提供已知参考代码档案；指纹相符只说明关键代码与参考档案相符，不证明未知系统整体行为，也不等于跨 ROM 实测。
4. **DataProvider** 是跨进程数据与配置入口：管理采样、配置和读取/清理请求，并按调用 UID 校验访问。它承载数据和访问控制，不执行 Hook 或风扇策略。
5. **MainActivity** 是前台 UI/观测层：从 Provider 读取状态与历史、展示图表和诊断，并承载用户触发的配置/导出入口；不加载 Hook、不决定接管策略，也不访问风扇或温控节点。
6. **FrameworkCompat** 提供运行框架 API/可选版本和 Manager 入口诊断。Manager 是否可启动不是模块激活条件；Manager 版本不得冒充运行框架版本。

主数据路径：原厂服务初始化 → `FanHook` / `FanServiceCompat` 解析 → `DexFingerprint` 保护链校验 → 仅候选层策略 Hook；状态/配置通过 UID 校验的 `DataProvider` 与前台 `MainActivity` 交换。框架诊断只提供辅助信息，不改变上述接管门槛。

## 不可越过的行为边界

- 仅 ACE7pro PMS110 / OP64F1L1 硬件与经检查的原厂 V3 兼容路径；不支持的平台保持仅观测或拒绝接管。
- Hook 只作用于 `com.oplus.exsystemservice` 的候选层。保留后续原厂噪声过滤、场景选择、控制器状态、档位上限、重算及底层保护。
- 不写 fan/thermal sysfs，不改温控策略，不伪装温度，不绕过原厂保护。
- 不扩展现有允许接管的使用场景；不得新增亮屏、无线、手动等控制模式。
- 不恢复 RPM、duty 或 speed 的读取/展示，也不把请求档位换算成物理转速。
- 遇到未知接口、代码指纹不匹配或歧义时失败关闭；适配应以新的原厂代码证据和人工验收为依据，而不是删掉保护检查。

## 相关文档

- 支持范围与验证事实：[COMPATIBILITY.md](../COMPATIBILITY.md)
- 用户行为与使用边界：[README.md](../README.md)
- 当前未验收项：[TASKS.md](../TASKS.md)
