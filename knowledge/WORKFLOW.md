# 开发与维护流程

## 开工与范围控制

- 先看 `git status`、分支和目标文件差异；保留现有工作，不执行 `reset`、`clean`、覆盖式回退，也不把既有改动混入本次任务。
- 先确定变更类型与用户授权。文档任务只改文档；代码变更、安装、重启、发布分别处理，不因“验证”自动扩展成设备操作。
- 兼容性结论与版本矩阵只维护在 [COMPATIBILITY.md](../COMPATIBILITY.md)。README、架构、任务与变更记录只做必要摘要并链接到该文件。
- 本工程远端仓库和 CI 不由目录名推断；确需远端操作时先核实实际配置、目标 ref 与授权。不得臆造 GitHub 仓库、CI 或发布结果。

## 分级验证

| 变更类型 | 最小验证 | 不代表什么 |
|---|---|---|
| 纯文档 | 检查 Markdown 相对链接、事实来源和相邻文档一致性 | 不需要整包构建或设备安装 |
| 纯 Java 逻辑/测试 | 运行 `sh tests/run.sh`，记录失败或可选真实 APK fixture 的 SKIPPED | 合成测试不代表 OEM ROM、框架或风扇效果实测 |
| Android UI、资源、Manifest 或应用代码 | 做相关测试与离线编译/打包；不自动安装 | 构建成功不代表设备 UI 或权限行为验收 |
| Hook、原厂服务或框架兼容 | 先核对 `COMPATIBILITY.md`；分别记录静态/合成测试、真实 APK 指纹、设备加载和人工功能验收 | 已安装 APK、旧进程回报或一项指纹通过不能替代其他层级 |
| 本地发布包 | 仅按明确要求生成、检查包和签名清单；安装、推送、打 tag 需分别授权 | 生成产物不等于发布或实机验收 |

项目开发与脚本在 Linux 环境进行；真实 Android 设备核验单独进行。当前已知构建组合为 Android 35 编译工具、Android 28 的 AAPT1 资源打包。构建依赖与签名密钥留在本地，不纳入仓库；不得读取、回显或写入密钥口令/secret。输出、参考 APK 和私有构建材料不应意外提交。

## 项目管理命令

统一入口为 `tools/project.py`，不联网、不操作设备，不自启后台任务：

```sh
python3 tools/project.py status
python3 tools/project.py doctor
python3 tools/project.py check
python3 tools/project.py check --staged
python3 tools/project.py test
python3 tools/project.py verify
python3 tools/project.py release --output DIR
python3 tools/project.py install-hooks
python3 tools/project.py audit-apk build/ACE7pro_Fan_0.3.0-compat.apk
```

- `status` 汇报源码版本、分支、工作树和远端数量，不查询设备；验证证据在状态文档。`doctor` 只检查本机工具、构建文件与密钥是否存在，不输出口令。
- `check` 检查包名/版本/SDK/API/作用域/权限契约、文档链接、语法和常见敏感材料；`--staged` 读取整个暂存区快照，不被未暂存的“正确副本”误导。它不执行测试/完整构建，也不是完备密钥审计或安全证明。
- `test` 运行管理工具的 Python 自测及离线纯 Java 测试，不调用 Android 系统命令。真实 OEM fixture 缺失时明确 SKIPPED；公开 CI 也不能靠合成测试宣称真实固件验证。
- `verify` 执行完整离线检查、测试与编译，不安装到设备。
- `release --output DIR` 要求工作树干净且有提交；执行 verify、构建并审计包名/版本/固定公开证书指纹/v3签名/对齐/权限/入口/框架类隔离，生成 APK、仅含 Git 源码的 source.zip、说明、精确提交号和校验清单。仓库内输出限 `dist/`，既有输出拒绝覆盖。不自动安装、push 或 tag。
- `audit-apk` 可检查既有 APK，不生成新包。签名证书指纹只作为公开身份约束存于 `project.json`，它不是签名密钥。`build.sh` 不再在密钥缺失时自动换签，且使用 apksigner 的 file 参数避免把口令放进进程参数。
- `install-hooks` 只在明确执行时安装轻量 staged check；不得覆盖已有 hook，且安装后需验证实际生效状态。

完整离线检查包括测试和全源码编译，但 `verify` 本身不签名/打包；需要签名包时显式使用 `release`。测试/构建命令通过进程级文件锁防止同时运行，无常驻服务。

```sh
python3 tools/project.py release --output dist/0.3.0-compat-local-check
```

本地发布包包含 `BUILD_INFO.json`（提交、版本、SHA256、公开签名身份、OEM fixture 是否存在、离线验证边界）和 `SHA256SUMS.txt`。物理验收仍是独立步骤，不能因生成了此包而把兼容结论升级。重复运行请换新目录，不覆盖已交付包。

用户已授权通过账号备份；当前远端是私有 [bianwuya/ace7pro-fan](https://github.com/bianwuya/ace7pro-fan)，`main` 跟踪 `origin/main`。首次 GitHub CI 已实际通过（精确提交/运行链接见 [状态快照](STATE.md)）。它只跑无私有依赖的检查和测试，不持有签名材料，缺少OEM样本的测试明确SKIPPED；不冒称完整构建或设备验收。备份、恢复、范围和后续同步见 [REMOTE_BACKUP.md](REMOTE_BACKUP.md)。没有配置自动push或定时任务；许可证仍未代选。

命令实现状态、验收证据及剩余管理任务见 [TASKS.md](../TASKS.md)。

## Git 与交付

- 保留既有未提交改动；按任务切分变更，只纳入明确范围的文件。已有功能基线与本次管理分开提交，提交前看暂存差异；不强推/重写历史。
- 不假设远端、分支保护或 CI；执行前检查实际配置。不要自动创建后台定时任务。
- 交付时分别报告文档/代码变更、测试、离线构建、设备安装与实机验收证据；未做的层级明确写为待办。
- 详细当前证据与时点：[STATE.md](STATE.md)；待办与验收标准：[TASKS.md](../TASKS.md)。

## GitHub自动构建

版本包自动化入口与新增/修复说明规范见 [CLOUD_RELEASE.md](CLOUD_RELEASE.md)。普通push/PR无密钥，版本tag才走原签名Draft；缺Secret明确阻止，不能自动换签。此独立工作流不改变本地 `release` 不联网/不发布的边界。
