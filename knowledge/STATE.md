# 项目状态快照

**管理审计日期：2026-11-03。** 这是有时点的记录，不是实时设备状态。兼容矩阵以 [COMPATIBILITY.md](../COMPATIBILITY.md) 为主；待办见 [TASKS.md](../TASKS.md)。

## 仓库和管理

- 项目原位 `/workspace/ace7pro-fan`，分支 `main`；用户授权后已配置私有 `origin`：[bianwuya/ace7pro-fan](https://github.com/bianwuya/ace7pro-fan)。源码已备份但没有公开。初始提交为 `5f7fe0c`。
- 原来未提交的 0.2.1 采样与 0.3.0 兼容改动已经独立保存为 **`1e8bf9c`**，未 reset、丢弃或混作本次管理功能。
- 新增统一入口 [AGENTS.md](../AGENTS.md) / [知识索引](INDEX.md)、架构、流程、任务、变更记录，以及可运行的管理命令。
- `check` 使用版本/权限/作用域契约、常见敏感材料与生成物隔离、语法和本地文档链接检查；`--staged` 读取真正的暂存快照。它不是完备密钥审计或风扇安全证明。
- 管理实现提交为 `b9f8fd2`；单行口令文件在 apksigner 多次读取时耗尽的问题已通过 `577a057` 修复并加回归检查。
- 提交钩子已在本仓库本地配置 `core.hooksPath=.githooks`。实际提交触发检查通过；在一次性测试仓库中，把暂存内容设为违规而工作区改安全，真实 Git 提交被阻止，敏感值未输出。未改全局 Git 配置。
- 本地 release 已端到端通过，精确源提交为 **`577a0571a2e787051904c6e7f1cbdfe1d2890525`**。最终收尾只更新管理证据/任务文档，不改运行代码。
- GitHub CI 已在首批远端提交 `0a2ad17` 上实际运行通过：[run 41631656221](https://github.com/bianwuya/ace7pro-fan/actions/runs/41631656221)。它不含签名/设备验证，私有OEM fixture 在云端明确SKIPPED。未代选License、创建公开Release或改变设备。
- 首批完整历史推送前审查5个提交/98个唯一文件对象；远端重新克隆后提交与145个可达对象一致，`fsck`通过。后续同步与恢复边界见 [远端备份](REMOTE_BACKUP.md)，不把备份理解为含签名私钥的完整环境镜像。

## 本轮实际执行的离线验证

- 管理工具 **24 项 Python 自测通过**，包含暂存区/工作树不同内容、敏感内容不回显、权限/API/作用域变化、坏链接、语法、既有 Hook 不覆盖、构建锁和 APK 框架类隔离等合成用例。
- `python3 tools/project.py verify` 通过：项目检查、24项管理自测、原纯 Java 回归、22项兼容合成检查、真实参考 APK 11处指纹及合成变体、完整 Android 源码编译。
- `audit-apk` 对先前交付的 0.3.0 包通过：精确包名/版本、公开证书固定身份、v3签名、对齐、权限/入口和 API compileOnly 隔离。原交付哈希见 [交付记录](../releases/0.3.0-compat/RECORD.md)。
- `release --output dist/0.3.0-compat-management-check` 通过完整离线验证、重新构建、签名/包审计、源码归档与 SHA256 清单核验。此目录被 Git 忽略，包含 `BUILD_INFO.json`，明确标记离线验证、未安装与真实 OEM fixture 可用。
- 此本地流程验证包 SHA256 为 `a7e9a7be6a4a5a3a549984d9ada1d5084b862811d5b0dad236f02b4244304cd6`。它不是先前已安装/分享的那一份 APK；原分享包 SHA256 经复核保持不变。没有上传或发布这个流程测试包。
- 本轮不修改 `src/`、资源或运行 Manifest，不更改 App / Hook 版本，不安装、不重启、不杀服务。管理构建脚本的变化不等于设备已加载新内容。

详细本地日志在忽略的 `build/project-management-verify.log`、`build/project-management-release.log`；日志路径仅用于本机排查，不纳入源码或公开分发。

## 设备状态的历史证据

截至 **2026-11-03 19:21**，0.3.0-compat 已签名覆盖安装，但原厂服务 PID `6166` 未重启，仍加载旧 Hook `0.2.1-eco`。本次管理没有重新查询设备；此记录不说明当前 PID、加载版本或风扇实际状态。必须在用户正常重载后验证运行 Hook 和既有功能。

没有其他真实 ROM/框架组合的功能实测证据；新版重载后的增强、锁屏衔接、真正解锁后2秒交还、关闭/拔线、兼容页和导出等仍在任务中。离线编译、指纹与合成测试都不能代替物理验收。
