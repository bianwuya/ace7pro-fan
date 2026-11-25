# 风扇测速证据与保持语义

## 0.7.4 集成与原始需求边界

2026-11-26 用户要求：新增实际转速读取、弃用旧不稳定读取；传感器曲线及充电模式不折叠，传感器位于档位曲线上方。用户补充：本档位实际测试到的速度持续显示，到下一次档位更新才更新测速曲线。

此次基于用户指定云端 `fix/fan-0.7.3-hysteresis` 的 `691bbe4` 增量合并；日用双向启停、1°C 回差和 30 秒降温确认、原厂保护与 GitHub 入口保留。最终 App `0.7.4-rpm-hold` / versionCode 21，Hook `0.6.5-rpm-hold`。原 `0.7.2-rpm-events` 是未安装的中间交付。

实施前已核对调用方与数据链：`FanHook` / `Telemetry` → UID 校验的 `DataProvider` JSON → `FrontModel` / `FrontPresentation` → Compose 概览与 `FanTrends`，CSV/报告由 `FrontExtras` 导出。只追加可选遥测字段，不改数据库 v3、配置字段/默认值、历史数据、日用与充电控制策略、原厂保护、权限或监听生命周期。基线原有 19 份受影响文件与哈希保存在忽略目录 `build/0.7.2-rpm-events/before/`，不丢弃既有未提交改动。

## 已核实的读取来源

本机 `/sys/class/leds/fan` 指向 `/sys/devices/platform/soc/soc:pwm_fan/leds/fan`，模块为 `oplus_fan`。有界排查 hwmon/platform/I²C/proc 未发现已证实稳定的替代 `fan*_input`；不将此范围等同于穷尽系统。

- 已加载模块与 `/vendor_dlkm/lib/modules/oplus_fan.ko` 的 Build ID 相同：`077e120af195b1be803ac4efc26308c9af4a4108`。文件 SHA256：`a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd`。
- `pulse_handler` 累加 FG 脉冲，`sample_timer` 按 `脉冲数×60000000000/(时间差ns×每转脉冲数)` 算 RPM；当前设备 ID 1、每转脉冲数 3。`speed` 为 PWM duty，目标 RPM 表不是实测。
- 旧 `rpm_show` 与健康检查共用 `chip+640` 缓存。状态检查开启约 1200ms 测速，随后停用；定时器发现停用会清零缓存。因此任意时刻轮询旧 rpm 会遇到窗口外假零，读取本身不造成清零。
- 状态检查在 `.text+0x1634…0x16c0` 统一构造 `FAN_STATE`、`FAN_RPM`、`FAN_TARGET_RPM` 等字段，再发内核 uevent。`FAN_RPM` 是窗口结束时快照，仍共享同一测速硬件与缓存，不是独立精度更高的传感器。一般约 1.2 秒采样 + 5 秒等待；不是保证周期。
- 原厂现有回调：`com.oplus.exsystemservice.fan.controller.OplusFanController$1.onUEvent(com.oplus.wrapper.os.UEventObserver$UEvent)`，返回 void；通过 `get(String)` 读取，DEVPATH 必须精确为 `/devices/platform/soc/soc:pwm_fan/leds/fan`。该回调 NORMAL 分支提前返回，但事件已经包含 RPM；只观察故障统计内部类会漏正常测速。霍尔事件同 DEVPATH 但没有 RPM，不能当测量。
- 核对对象 `/system_ext/priv-app/OplusExSystemService/OplusExSystemService.apk` SHA256：`3eeee6cd69d22fad8ddf6b341c54128ca9c0fb505c6e24ce85ffdd2ef780544c`；`classes2.dex` 回调 code_off `0x349a88`，故障统计构造器 `0x349960`。
- `FAN_RPM=0` 可以真实发送，零值被分类为 DAMAGED，不证明实际停转。非负 `dbg_rpm` 会覆盖测速，因此当前读取必须确认 `dbg_rpm=-1` 才采用；不写该节点。

## 实现与数据兼容

- 仅 Hook 原厂已有回调，原参数/返回值/原厂方法照常；不调用 startObserving、自检或 setter，不新增进程/Root 监听。可选回调缺失只使测速不可用，不阻止原控制能力。
- `FanRpm` 在有效档位 1–20、使能 1、当前/同包状态 NORMAL、无调试覆盖下，接受本代首次有效正整数 RPM，保存接收时的 wall/elapsed。后续同档位正常事件只更新原始诊断，不替换保持值。0 RPM 不补成停转数据。
- 有效档位改变、停转请求、停用、故障、霍尔变化、未知使能/档位、调试覆盖、时钟回退或观察失败会使保持值失效；不能将旧代测量带入新档位。非零请求变化但有效档位不变时保留首次保持值，仅拒绝跨请求的在途读取；换档后至少等待 2 秒的稳定窗口才采用新事件。历史断线与 generation 变化分开绘制。
- 保持值没有短 TTL，按用户要求持续；但当前驱动状态/Hook 遥测必须仍新鲜。风扇使能期间每 5 秒被动检查档位，约 10 秒内回传，不改变控制策略；当前遥测超过既有 15 秒显示窗口即隐藏，不能靠旧事件无限证明风扇还在运行。
- `fanRpm` 可选 JSON 对象字段：source、observer、quality、value、raw、wall、elapsed、level、generation、state。source 固定 `oplus_fan:uevent_fg`，quality=held 才是可显示保持值。旧顶层 rpm、target、duty、档位从不补值。
- CSV 既有列和顺序不动，只在日用列后追加 `fanRpm_*`；旧样本输出空值。数据库及 UID 边界不变。概览与趋势注明「档位保持值」及测量时刻，不称实时速度。

## 验证范围与待验

纯 Java 回归覆盖首次测量、同档一小时保持、换档等待、停用/重启/未知/故障/调试/时钟/畸形输入/零值/霍尔失效、旧格式忽略和历史断线；原日用、充电、衔接、采样和配置回归一并运行。后端也校验 wall/elapsed 一致性，使系统时间调整后可由新事件恢复；读取前后有效档位/使能和缓存代次必须一致。事件协议没有窗口时间戳或硬件代次标识，接收时间及稳定窗口只是保守归属检查，不是物理准确度证明。完整编译及包身份结果写 [状态快照](STATE.md)。

自然只读观察 UTC 2026-11-25 16:32:31–16:33:01，7 次均 state=0/status=NORMAL/present=1、FG IRQ 总数 237411 不变；30秒被动监听未收到风扇事件，监听已结束。没有强制起转、扫档或改变风扇模式。

**尚未实收本版 Hook 的自然运行事件，无法宣称已找到稳定的新节点、已验证全档实测速表或物理准确度。** 原厂只有自检开启时才观察事件，不为补样本擅自开启。需要用户按正常流程加载 Hook 后，风扇自然运转时检查 observer、quality、真实 RPM 和档位变更。APK 构建、已安装版本与运行 Hook 是不同层级。
