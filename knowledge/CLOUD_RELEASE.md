# 云端构建与草稿发布

> **能力状态以 [状态快照](STATE.md) 为准。** 本文记录目标流程与安全边界，不是云端工作流已成功运行、签名密钥已托管或 Release 已发布的证明。新增实现与实际运行证据须由状态快照记录后，才能据此宣称已验证。

## 当前工作流现状

已有 [源码检查工作流](../.github/workflows/check.yml) 保持轻量检查/测试。新增 [APK与草稿工作流](../.github/workflows/build.yml) 及脚本已落地，并在本地通过发布说明、版本标签与权限边界自测，以及无密钥打包/审计。首轮 [GitHub实际构建41635143748](https://github.com/bianwuya/ace7pro-fan/actions/runs/41635143748) 已通过，源码提交为 `144a291`；下载的unsigned产物已复核SHA256、构建记录、说明结构和APK审计。签名job因非版本tag正确SKIPPED，不能算同签名Draft已验证；状态以 [STATE.md](STATE.md) 为准。

上游仓库应继续保持私有：`bianwuya/ace7pro-fan` 的 GitHub Release 和附件继承仓库访问权限，不会因创建 Draft 而公开给仓库外人员下载。本文不授权改变仓库可见性。

## 触发器与产物边界

- `main` 的 push、Pull Request 和手动触发只运行无密钥构建：执行源码检查/测试，构建未签名 APK，检查包名、版本、权限、对齐及 APK 完整性，再上传明确列出的 Actions artifact。PR 作业不得读取任何签名 Secret、不得进入 `release-signing` 环境、不得创建或改写 Release；签名作业的权限与触发条件须和 PR 作业隔离。
- 推送形如 `v<APP_VERSION>` 的版本 tag 才进入签名及 Draft 流程。必须先完成下节的所有 tag 检查；检查失败、签名失败或密钥缺失时，立即失败且不创建 Draft。该流程只创建 GitHub **Draft Release**，不会自动正式发布，也不会自动创建 tag。
- Actions 上传必须使用显式文件白名单，只包含预期 APK 与必要的校验清单；禁止把整个 `build/`、工作区、临时目录或密钥目录打包上传。普通构建产物明确标为 unsigned。未签名 APK 不能直接覆盖设备上已安装的同包名签名版；不要把它当成正式更新包安装。

## 版本、说明与签名前置校验

正式 Release 的说明唯一来源是 `releases/<APP_VERSION>/NOTES.md`，不得以自动生成的提交日志替代。该文件第一行必须是 `# <APP_VERSION>`，并必须具备 `## 新增`、`## 修复`、`## 验证与限制` 三个段落；每段至少一个非空 bullet。若某一类没有内容，明确写 `- 无`。禁止 TODO 占位或把提交列表冒充修复说明。生成 Draft 时使用该文件内容作为说明，并校验 tag、版本与文件路径一致。

签名前，tag job 至少依次验证：

1. tag 的版本号去掉 `v` 后，必须与 `src/dev/eta/acefan/BuildInfo.java` 的 `APP_VERSION`、`AndroidManifest.xml` 的 `versionName` 及 `releases/<APP_VERSION>/NOTES.md` 路径一致；NOTES 结构也必须符合上面的规则。
2. tag 对应提交必须已位于 `main` 历史中；不得从未合入 main 的提交发布。
3. 当前 `AndroidManifest.xml` 的 `versionCode` 必须严格大于之前版本 tag 的 `versionCode`；不能复用或降低已发布版本号。
4. 构建与签名结果的公开证书 SHA-256 必须匹配 [`project.json`](../project.json) 中固定的 `signer_certificate_sha256`。该值是公开身份校验，不是密钥。当前固定指纹为 `e3977f9ff13304e9a48106cd8b2c3e68abe8ea50cbba5ad90b118500265898d9`。

版本更新时同步修改 `APP_VERSION` 与 Manifest 的 `versionName`，并增加 Manifest `versionCode`；当 Hook 代码/协议发生变化时更新 `HOOK_VERSION`。新增和修复要逐项写入对应版本的 NOTES，并准确列出验证与尚未验证事项。

## 签名 Secret 与临时文件

签名 job 通过 GitHub Actions 的 `release-signing` environment 引用 `ACEFAN_KEYSTORE_B64` 与 `ACEFAN_STORE_PASSWORD`。**这两个 Secret 目前为空，且尚未获准上传私钥；需用户另行明确同意在 GitHub 加密托管后才能配置。** 在获得单独同意之前，tag 签名流程必须失败关闭，不得切换其他密钥、生成替代证书、用 debug key 签名或绕过指纹校验。

若用户之后单独同意，先创建 `release-signing` 环境，按账号套餐支持情况限制为版本tag并设置审批（个人私有仓库并非都支持人工审批保护），再在已认证的本地终端通过 stdin 设置环境 Secret；以下仅为命令形式示意，本次没有读取任何密钥/口令文件，也没有执行上传：

```sh
gh secret set ACEFAN_KEYSTORE_B64 --repo bianwuya/ace7pro-fan --env release-signing < /secure/local/keystore-base64.txt
gh secret set ACEFAN_STORE_PASSWORD --repo bianwuya/ace7pro-fan --env release-signing < /secure/local/store-password.txt
```

不要把密钥、base64、口令或 token 粘贴到聊天、Issue、PR、日志，也不要提交进 Git。Runner 上只在系统临时目录恢复 keystore 与口令文件，限制为当前 runner 用户可读（`umask 077`、文件权限 `0600`），不得写入仓库或 artifact；签名Python进程在调用任何子进程前即从环境移除两个Secret，避免传给git/Java/native子进程。无论成功、失败或取消，都以Python finally和 `always()` 清理临时材料（runner被硬终止时仍依赖托管临时机销毁）。Secret 缺失、解码/读取失败或签名身份不匹配均必须拒绝发布。若原签名密钥遗失，不能自动换签；必须先找回同一签名身份，否则该包不能作为已安装应用的升级包。

## 固定 CI 工具链

云端构建使用固定的 Ubuntu 24.04 与 JDK 17；资源打包固定 Android 28，编译固定 Android 35；R8 固定 `9.6.0-alpha09`；Xposed API 固定为 82，并验证仓库/脚本声明的 SHA-256 checksum。`tools/prepare_ci.py` 负责准备并核验构建依赖，摘要不符或工具版本漂移时失败，不静默接受替换依赖。构建作业不使用浮动的 `ubuntu-latest` 代替该固定组合。

云端入口为 `tools/cloud_release.py plan|package-unsigned|sign|draft`：无密钥的计划/未签名打包阶段与受限的 tag 签名/Draft 阶段分开。实现及 CI 成功记录仍以 [STATE.md](STATE.md) 的证据为准。

## 后续版本操作示例

以 `0.3.1` 为例，先更新 `APP_VERSION`、Manifest `versionName`/`versionCode`，若 Hook 代码/协议有变化再更新 `HOOK_VERSION`；新建 `releases/0.3.1/NOTES.md`，按固定三段写明新增、修复、验证与限制；本地运行 `python3 tools/project.py check`、`python3 tools/project.py test`，再审查差异并提交、推送到 `main`。确认目标提交已在远端 `main` 历史后，才由获授权的人创建并推送 `v0.3.1` tag，例如：

```sh
git tag -a v0.3.1 -m "ACE7pro 风扇助手 0.3.1"
git push origin v0.3.1
```

Actions 校验通过并完成固定身份签名后只创建 Draft。人工核对 tag/提交、APK 包名与版本、签名指纹、哈希、NOTES 及验证边界；确认无误后再由有权限的人手动 Publish。不要把 Draft 当正式发布，也不要将私有仓库改为公开来解决下载问题。

## 验证边界

云端静态检查、合成测试、打包与签名只证明对应离线流程，不等于目标 ROM/LSPosed 的真机认证或物理风扇效果。详细兼容证据以 [COMPATIBILITY.md](../COMPATIBILITY.md) 为准。目前记录中的 `0.3.0-compat` 已在本机安装，但目标服务加载新 Hook 与用户手动功能复测仍待完成；本次文档或云端构建不能补作该验收，也不承诺自动重发或重新发布 0.3.0。
