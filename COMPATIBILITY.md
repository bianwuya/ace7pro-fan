# ACE7pro 风扇助手 0.3.0-compat：系统与框架版本兼容性

核查日期：2026-11-03。**“框架支持 Android”“模块接口匹配”“真机增强效果通过”是三个不同结论。** 不增加充电/亮屏/无线等控制场景，不宣称已实测绝大多数 ROM。

## 本次实际修复

- 不再把 SDK 37 / Android 17 作为 Hook 的硬条件。App 安装下限仍是 Android 9 / API 28，没有人为添加 Android 版本上限。
- 不再因原厂服务 APK 整包哈希变化就拒绝所有固件。保留已知整包哈希作为“参考固件”标记；同硬件、接口和关键代码相同的其他固件也能通过。
- 按原厂 Aeolus 的实际 `mOrchestrator` 类型解析候选层，不再只按 `i9.h` 这个混淆类名定位；若候选/入口/选择器签名唯一，可处理部分混淆更名。返回类型、参数、修饰符不匹配或多个候选时拒绝。
- 完整解析后，对候选、后续控制/降噪、场景选择、控制器健康/请求、最大档位及原厂重算共 **11 个方法**做标准化 DEX 指纹检查。忽略池索引、调试偏移和已识别角色的符号更名，保留指令、寄存器、常量、分支、成员引用及异常处理结构。只有通过后才安装控制 Hook；不能凭“有个 int getter”猜测。
- 指纹只核查参考保护链中的关键方法，不等于证明整个未知系统的行为。ROM 重新优化、改算法、改变设置键、拆分 APK 或更换风扇架构都可能不通过，需要真实版本样本继续适配，不自动绕过。
- `xposedminversion` 从 93 降到本模块实际使用的传统 API 82；编译依赖仍 compileOnly，不把框架类打进 APK。**不依赖 modern libxposed API**。
- API 33+ 锁屏监听器隔离加载，旧系统使用广播、实时状态读取及既有采样兜底；旧系统不调用 API 33 的接收器重载。
- 增加“设置 → 版本兼容性”，展示支持依据、当前回报、系统/原厂服务、匹配档案、失败原因及运行 Xposed Bridge API。框架的语义版本仅在框架公开时显示；未知就明确标未知，不冒用 Manager 版本。
- 独立 Manager 不可启动时，不再断言未安装 LSPosed；增加用户主动选择的寄生入口尝试及手动进入说明。不请求 Root、不自动启用模块/改作用域。
- 修复 30 秒空闲采样与旧 UI 的 20 秒新鲜度判断冲突，避免本地观测误盖掉有效注入状态；历史/跨启动回报不用于证明当前激活。

## 系统版本：哪些可以用

| 范围 | 本版处理 | 证据/限制 |
|---|---|---|
| Android 9+，任意机型 | 可安装与只读观测 | 可读数据取决于系统权限；不等于有风扇控制能力 |
| ACE7pro PMS110 / OP64F1L1，原厂 Oplus 风扇 V3 | 不按 Android 主版本封锁；运行时检查接口及关键代码 | 通过标“接口匹配·待实测”，非保证所有 ColorOS/OxygenOS 版本 |
| PMS110_17.0.0.159(CN01)，Android 17 / SDK 37，OplusExSystemService 1.4.6 / 10050 | 已知参考固件 | 本地真实 OEM APK、旧模块注入/遥测日志，以及本版离线 11 处指纹检查 |
| 其他 Android/ColorOS/OxygenOS 具体版本 | 尚无真实固件样本可认证 | **不列为已实测**；同架构代码匹配者可通过，不匹配者只观测 |
| 其他型号、AOSP、原厂服务缺失、风扇 V1/V2 或保护链不同 | 不接管 | 硬件与接口均不靠猜；失败原因显示在诊断页 |

只勾选 `com.oplus.exsystemservice`。不支持把模块勾选到 system_server 以“扩大兼容”。设备身份被第三方修改也可能被硬件校验拒绝；本版不去绕过或更改身份。

## LSPosed 版本：哪些可以用

| 框架 | 上游声明的 Android 范围 | 本模块传统接口结论 | 模块实机证据 |
|---|---|---|---|
| 官方 LSPosed 1.8.6 | 8.1–13 | API93 框架，传统模块候选 | 没有该组合实测；不要装到 Android 17 后期待可用 |
| 官方 LSPosed 1.9.2 | 8.1–14 | 提供传统模块兼容，也引入 modern API100；模块不使用 modern 入口 | 没有该组合实测 |
| JingMatrix LSPosed 1.10.2 | 8.1–16 | 上游声明兼容原版 Xposed 模块 | 没有该组合实测 |
| JingMatrix Vector 2.x | 当前 README 到 Android 17 Beta | 上游声明 legacy / modern 均支持；各发布版范围需单独核对 | 没有该组合实测；不能把别的分支同号版本自动归为 Vector |
| 开发参考机的 LSPosed 2.2.1 (7912)，更新源 lsposed.zip | 其本地 module.prop 写 Android 9–17 | 取得旧版 0.2.1 的真实注入/遥测日志 | **旧版注入/遥测有证据；本版尚需重载与功能复测** |
| 其他分支 | 以该分支发行说明为准 | 框架先支持系统，且保留传统 Xposed API ≥82，则为接口候选 | 未认证，不因版本字符串相同承诺兼容 |
| 只支持 modern、不兼容传统模块的框架 | 不适用 | 不支持本模块入口 | 无 |

模块安装下限是 Android 9，表中框架能运行 Android 8.1 不会降低模块自己的安装下限。Xposed Bridge 返回 93/100 等 API 数字，不是 LSPosed 的 1.x/2.x 语义版本；也不要混淆 legacy 兼容与 modern API100/101/102。

本机核查恰好发现：**实际框架 2.2.1 (7912)，独立 Manager 2.1.0 (7769)**，两者不一致。因此本版绝不把 Manager 的 `versionName` 当作实际框架版本。`BuildConfig.VERSION_NAME` 反射仅是可选诊断，可能被内联、混淆或隐藏；不依赖它控制功能。

### 原始来源

- [官方 LSPosed 1.8.6 README](https://github.com/LSPosed/LSPosed/blob/v1.8.6/README.md)
- [官方 LSPosed 1.8.6 API code](https://github.com/LSPosed/LSPosed/blob/v1.8.6/build.gradle.kts)
- [官方 LSPosed 1.9.2 README](https://github.com/LSPosed/LSPosed/blob/v1.9.2/README.md)
- [官方 LSPosed 1.9.2 XposedBridge](https://github.com/LSPosed/LSPosed/blob/v1.9.2/core/src/main/java/de/robv/android/xposed/XposedBridge.java)
- [JingMatrix LSPosed 1.10.2 README](https://github.com/JingMatrix/LSPosed/blob/v1.10.2/README.md)
- [JingMatrix Vector README](https://github.com/JingMatrix/Vector) / [各版本发行说明](https://github.com/JingMatrix/Vector/releases)
- [XposedBridge API82](https://github.com/rovo89/XposedBridge/tree/v82)
- [可选框架版本字段生成](https://github.com/LSPosed/LSPosed/blob/v1.9.2/core/build.gradle.kts)；[release 混淆规则](https://github.com/LSPosed/LSPosed/blob/v1.9.2/core/proguard-rules.pro)
- [寄生管理器入口在部分 ROM 失效的上游记录](https://github.com/JingMatrix/Vector/issues/691)：`org.lsposed.manager.LAUNCH_MANAGER` 是 category，不是 action。尝试入口不等于确认已成功打开；以实际画面为准。

## 实际验证与尚未验证

已完成的离线检查：

- ChargeCurve、WakeHandoff、Sampling 原测试通过；硬件/API门槛、唯一/歧义/缺字段接口、回报过期/跨启动等 22 项合成检查通过。
- 真实 OEM 1.4.6 APK 中的 11 个参考方法指纹通过。
- 合成“整包变化但风扇代码不变”样本通过，证明确实移除了整包哈希硬锁。
- 候选方法和后续保护方法的合成代码篡改均被检测；坏 DEX 被拒绝。**这些变体不是其他系统版本，不算多版本真机实测。**
- Java8 编译、D8、资源/Manifest 打包、签名及对齐检查通过；未增加权限。

尚未验证：其他真实系统固件、不同框架的实机运行；本版重载后的增强、锁屏衔接、2秒交还、关闭/拔线等物理效果；独立/寄生管理器各 ROM 的跳转；新版兼容页的实际屏幕布局。功能操作由用户手动验收，源码/单测/构建不代替这些验收。

## 加载、诊断与回退

覆盖安装不会令已经运行的原厂进程立刻重载 Hook。正常重启后，先看运行 Hook 是否为 `0.3.0-compat`，再看“参考固件 / 接口匹配 / 不支持”，最后按原有条件做功能测试。本包不会自动重启、杀掉原厂系统服务、修改 LSPosed 作用域、写 sysfs 或改温控节点。

失败反馈请提供“版本兼容性/模块诊断”的系统、原厂服务版本、运行 Hook/API、适配原因。继续适配新固件需要对应版本的原厂服务 APK/代码证据；不要直接删除检查或加任意 int getter 来强行接管。

可在 App 关闭增强，或在 LSPosed 禁用模块后正常重启。开发机保留本地同签名的 0.2.1 回退包；不为降级自动卸载/清数据。
