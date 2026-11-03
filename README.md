# ACE7pro 风扇助手 0.3.3-report

OnePlus ACE7pro 的 LSPosed / 传统 Xposed 模块与本地观测 App。作用域仅 `com.oplus.exsystemservice`。无网络权限，不直接写风扇/温控 sysfs，不伪装温度，不关闭原厂保护。

## 项目维护入口

维护规则见 [AGENTS.md](AGENTS.md)，知识入口见 [knowledge/INDEX.md](knowledge/INDEX.md)。
进度用 [TASKS.md](TASKS.md)，历史用 [CHANGELOG.md](CHANGELOG.md)，验证与设备证据见 [状态快照](knowledge/STATE.md)。

```sh
python3 tools/project.py status       # 源码版本、分支、工作树；不读设备
python3 tools/project.py check        # 轻量规则、语法、文档链接与敏感材料检查
python3 tools/project.py test         # 管理工具与纯 Java 回归
python3 tools/project.py verify       # 再做完整 Android 源码编译，不安装
```

完整发布流程、签名约束、提交钩子和远端/CI边界见 [工作流程](knowledge/WORKFLOW.md)。

源码已备份至私有 [bianwuya/ace7pro-fan](https://github.com/bianwuya/ace7pro-fan)，备份范围与恢复见 [远端备份](knowledge/REMOTE_BACKUP.md)。签名私钥/口令、构建依赖、APK与私有OEM样本不在此源码备份中。

## 本版：系统与 LSPosed 版本兼容

- 去掉 Android 17 / SDK 37 唯一入口和原厂服务整包哈希硬封锁。仍校验 ACE7pro PMS110 / OP64F1L1 硬件，完整解析原厂 V3 风扇接口并检查 11 处关键代码指纹；同架构代码一致的不同固件可通过，不匹配则仅观测。
- 支持部分混淆类/候选/选择器更名的唯一签名解析，歧义不猜。不会把任意 `int` getter 当作风扇候选。
- 传统 Xposed 运行门槛调整为实际使用的 API82+；不依赖现代 libxposed 入口，不再绑死单一 LSPosed 分支或 Manager 包的可启动性。
- 补齐 API33 以下的广播注册与锁屏状态兜底，API33+ 监听器隔离加载。
- “设置 → 版本兼容性”可查看系统/服务版本、接口档案、实际 Xposed Bridge API、兼容原因、框架参考列表以及实测/未实测边界。框架未公开语义版本时显示未知，不用 Manager 版本冒充。
- 管理器跳转失败不再写“未找到 LSPosed”；提供用户主动选择的寄生入口尝试与手动入口说明。
- 修复 30 秒空闲采样被旧 UI 的 20 秒超时误判为未注入；历史回报明确标记，不代表当前激活。

**兼容矩阵、来源、验证与限制：[COMPATIBILITY.md](COMPATIBILITY.md)。** 安装/观测下限 Android 9，不代表所有 ROM 都支持增强；没有其他真实固件样本就不把它们写成已实测。

## GitHub 自动构建与版本发布

[APK 工作流](.github/workflows/build.yml) 在 main 提交、PR 或手动运行时生成 **unsigned 检查包**，不读取签名密钥；产物见 Actions 的 Artifacts，不能直接覆盖已安装的签名版。

推送 `v<APP_VERSION>` 标签时才进入原签名构建及 **Release 草稿**流程。发布说明必须来自 `releases/<APP_VERSION>/NOTES.md`，有“新增 / 修复 / 验证与限制”三个非空列表；缺失、版本/tag不一致或versionCode回退会阻止发布。草稿需人工审核后正式Publish。

签名环境 `release-signing` 的两个 Secrets 尚未配置，原私钥仍留本地；未获单独授权前不上传私钥。缺少Secrets时明确失败，绝不用另一把密钥或debug签名顶替。完整操作见 [云端构建与发布](knowledge/CLOUD_RELEASE.md)。实际运行证据见 [状态快照](knowledge/STATE.md)。

## 原有控制行为不变

- 只在有线充电中、息屏、原厂自动模式及原厂开关允许时按电池温度增强。
- 先前息屏增强真正生效后，亮屏仍锁屏可延续；真正解锁后最多 2 秒交还原厂。不增加亮屏、无线、手动等控制场景。
- 仍在候选层增强，后续噪声、使能、手柄、控制器、底层保护保留。口袋、通话、故障等场景不会因“扩大兼容”强行加入白名单。
- 曲线初值：33 / 35 / 37 / 39 / 41°C → 6 / 10 / 14 / 18 / 20 需求档。降档迟滞 1°C、保持 30 秒。这不是安全温度边界。
- 档位是控制请求，不等于物理转速；驱动可能降额。RPM 与 duty/speed 读取及展示仍保持移除，不用档位换算冒充转速。

## 换成别人的机器怎么办

增强仍只针对 ACE7pro（PMS110 / OP64F1L1）。别人装上不会自动生效，这时用 **设置 → 数据与诊断 → 设备兼容报告**（或右上角 ⋯）导出这份机器的适配证据：

- 设备与 ROM：型号/代号、Android 与 API、系统版本、指纹、硬件门槛结果。
- 框架与注入：是否真的注入、Xposed Bridge API、运行框架版本、Manager、作用域。
- 原厂服务：包名、版本、APK 路径与 SHA-256、是否与已知参考固件一致。
- 接口适配：结论（参考固件 / 接口匹配待实测 / 不支持仅观测 / 运行异常）、失败阶段、失败原因、解析轨迹。
- 当前策略与温度：开关、阶段、档位链路、四路温度、供电/锁屏状态。

报告末尾会按第一个阻塞项给出下一步需要的证据。适配只需要其中的硬件、版本和风扇接口信息，不含账号、位置或通讯录；App 无网络权限，导出后发给谁由使用者决定。**“接口匹配”不等于这台机器已经实测通过**，不要靠删校验或强接 int getter 让它生效。

## 界面与数据

概览/趋势/设置三页、自绘深浅主题。温度与档位独立卡片，缺测断线。App 只作前台观测，读不到显示“—”。

继承 0.2.1 的采样降耗：增强窗口 5 秒、具备条件未增强 10 秒、不可接管 30 秒；插拔电、息屏、解锁、电池事件补采。状态/档位变化或温度变化 ≥0.2°C 时写入，否则至少每 20 秒一条。新鲜度随采样周期调整。历史上限 10000 点/7天，界面最多4000点，曲线断点阈值60秒。CSV 不含转速列。

## 安装与使用

1. 安装 APK；框架必须先支持你的 Android，只有独立 Manager APK 不会激活框架。
2. 在 LSPosed 启用模块，只勾选 `com.oplus.exsystemservice`。不要勾选系统框架/system_server。
3. 正常重启。已运行的原厂服务不会因覆盖安装立刻加载新 Hook；App 显示“待重载”时请重启再看。
4. 检查“版本兼容性”：运行 Hook 为 `0.3.0-compat` 后，再区分“已识别参考固件”“接口匹配·待实测”“不支持·仅观测”。注入、匹配与物理效果分别判断。

功能验收由用户手动操作；本包不自动重启、杀掉原厂服务、改 LSPosed/安全设置，也不请求 Root 作为工作前提。回退可关闭增强，或在 LSPosed 禁用后重启；不自动卸载/清数据。

## 构建与测试

Linux ARM64：Java 编译到 Java8，D8/编译 Android35；AAPT1 使用 Android28 资源 JAR 打包，min28/target35。Xposed API82 仅 compileOnly，签名密钥只在本地 `build-tools/keys`。

```sh
sh tests/run.sh
sh build.sh              # 原签名本地构建
sh build.sh --unsigned   # 无密钥检查包，不可安装
```

产物：`build/ACE7pro_Fan_0.3.0-compat.apk`。纯 Java 测试涵盖曲线、锁屏衔接、采样、兼容门槛、合成解析与诊断新鲜度。有本地私有参考 APK 时还运行 11 处指纹及变体测试；参考文件不随源码分发，缺少时脚本明确显示 SKIPPED，不假称已执行。

已做离线测试和构建校验，不等于已做其他 ROM/框架或物理风扇效果实测。详细边界见兼容文档。
