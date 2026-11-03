# ACE 7 系列风扇助手：维护入口与约束

本文件适用于整个 `ace7pro-fan` 仓库。先读 [knowledge/INDEX.md](knowledge/INDEX.md)，按任务读取相关文档，不靠聊天摘要猜工程状态。

## 工程定位

- 本地路径 `/workspace/ace7pro-fan`；Android 对应 `/data/local/tmp/eta/ace7pro-fan` 是同一物理目录，不做跨环境重复复制。工程原位维护，不随意迁目录。
- 包名 `dev.eta.acefan`，作用域只允许 `com.oplus.exsystemservice`。安装/观测能力与风扇增强能力分开。
- **版本兼容不等于扩大控制使用场景**。用户要求更多系统/LSPosed 版本时，只改适配及诊断，不擅自加入亮屏、无线、手动等新控制场景。
- 唯一兼容事实入口是 [COMPATIBILITY.md](COMPATIBILITY.md)；当前进度看 [TASKS.md](TASKS.md)，历史看 [CHANGELOG.md](CHANGELOG.md)，设备证据看 [knowledge/STATE.md](knowledge/STATE.md)。状态文档有时点，不是实时设备状态。

## 不可暗中改变的边界

1. 按用户明确需求仅覆盖 ACE 7 系列：读取型号/市场名称与实际原厂接口。候选层注入，直接读取与调用确认；不再将 APK/DEX 指纹、参考代码相同或全量接口齐全作为启动条件。可选能力失败单独报告，必要读取本次失败保留本次原厂结果；多个候选先观察原厂调用与策略读取，不随机挑 getter。
2. 保留原厂降噪/使能/手柄/故障和底层保护；不直接写风扇/温控 sysfs，不伪装温度，不改充电/关机保护、SELinux、系统身份或原厂服务。
3. 保持息屏有线充电、原厂自动模式、已生效会话锁屏衔接及解锁后最多 2 秒交还。变更这些行为必须是单独明确需求。
4. RPM 与 duty/speed 读取已移除；不重新加入，也不以档位换算伪称实测转速。缺测显示未知，不填假零。
5. UI 不参与风扇控制；Provider 的 UID 边界不能为兼容而放开。App 无网络权限，数据默认不上传。
6. 区分 Manager 语义版本、运行框架版本、Xposed Bridge API。注入、接口接入、候选应用、控制反馈、功能实测分别表述；合成测试或构建通过不是多 ROM 真机验证。

## 开发与验证

- Git、Java/Python、构建、APK 分析用 Linux；设备包、进程、日志检查用 Android。管理工具不自动调用 Android 命令，不常驻、不定时运行。
- 开始先执行 `python3 tools/project.py status` 和 `git status --short`。不 reset/revert/stash 掉已有工作，不为凑“干净”强制清文件。
- 小改动、分级验证：`check` 是轻量静态检查；`test` 加管理工具及纯 Java 回归；`verify` 再做全源码 Android 编译。修改运行逻辑后，仅做文档/静态检查不算验收。
- 提交前运行 `python3 tools/project.py check --staged`；Git hook 检查暂存区内容，而不是用未暂存文件掩盖错误。不在每次提交自动做签名构建。
- APK 的 `APP_VERSION` 与 Manifest 必须一致，`versionCode` 增加；Hook 有变化时更新 `HOOK_VERSION`，只改文档不升级 APK。
- 使用本地既有签名，密钥缺失时停止，不自动换签。`project.json` 只保存公开证书指纹和工程约束，不保存任何口令。
- `build-tools/`、`build/`、`dist/`、APK/DEX、密钥、数据库、真实设备日志、私有 OEM 样本和回退归档均不得新入库或进入源码分发。历史已跟踪的只读源码快照不擅自删除。
- 无需在本任务自动重装；确有应用更新任务时按用户授权核对包名/原签名再操作。绝不擅自重启整机、强杀原厂系统服务、卸载/清数据或改 LSPosed/安全配置。

## 完成与发布

- 变更须更新相关任务、验证证据和变更记录；所有检查未跑/跳过/失败都如实标明。
- 分开提交：先保存已有功能基线，再提交管理/文档，不混入无关项目。检查 staged diff 后再 commit，不 `git add -A` 收进未知文件。
- `release --output DIR` 从干净提交创建本地验证包，审计包名、版本、签名、权限、入口及源码隔离；**不等于物理验收，不自动 tag/push/install**。
- 未配置远端就明确写未配置，不编造 GitHub 地址、不擅自公开或推送；License 未确定，不替用户选许可证。
- 结束同步 `knowledge/STATE.md`、`TASKS.md` 及 `/workspace/PROJECTS.md` 的入口信息。文档保持轻量，避免重复粘贴完整兼容矩阵。
