# 远端备份与恢复

核验日期：2026-11-04。用户明确要求公开本仓库，GitHub API 与无认证请求均已核实公开访问。

## 已配置的远端

- 账号：`bianwuya`；仓库：[bianwuya/ace7pro-fan](https://github.com/bianwuya/ace7pro-fan)。GitHub API 已核实为 **public**，无需仓库协作者权限即可访问源码和历史；公开不等于正式发布 APK 或指定开源许可证。
- 本地 `origin`：`https://github.com/bianwuya/ace7pro-fan.git`；本地 `main` 跟踪 `origin/main`。
- 使用已有 GitHub CLI 认证；Git credential helper 仅配置在本仓库，不改全局 Git 设置、不创建新令牌、不把令牌放入远端 URL、文档或提交。
- 首批推送包含 `0a2ad177fa5b40f8de519f0010d43e08ba21de34` 及之前全部5个提交，之后的备份说明作为单独提交同步。最新源版本以本地/远端 `main` 的精确提交号为准，不把本文件中的旧检查点当成实时 HEAD。

## 备份范围与隐私

备份包含 Git 已纳管的源码、资源、测试、设计材料、维护文档、CI/反馈模板和完整提交历史。首次推送前检查了全部5个历史提交中的98个唯一文件对象（约2.6MB），未命中已知敏感模式或被禁止的私有/生成物路径；其中7个设计图片只做格式/路径检查，没有逐张视觉审查。2026-11-04 公开前再次扫描全部可达历史（26个提交、242个唯一文件对象、约3.97MB），未命中凭据/私钥模式或禁止入库路径。模式扫描不是绝对无敏感信息的证明。

**以下内容不在 Git 源码与历史：** `build-tools/` 及签名密钥/口令、构建输出与APK、`dist/`、私有OEM参考APK、设备数据库/日志、本地回退归档、GitHub认证配置。公开证书指纹不是私钥，可随工程记录。

这是**源码与项目历史备份，不是可立即复现同签名APK的完整开发环境镜像**。设备损坏时，仍需单独安全保管的原签名密钥、口令及本地构建依赖。不要为“补全备份”把这些私有材料提交到源码仓库或公开上传；需要离机保存时另行设计加密备份并取得用户授权。

## 已执行的恢复读取验证

- 从 GitHub 重新克隆到忽略目录 `build/remote-restore-check.git`，不是从现有本地目录复制。
- 初次克隆的 `main` 与本地精确提交一致；5个提交、145个可达Git对象集合一致，`git fsck --full` 通过。
- 云端 [Source and offline tests 首次运行](https://github.com/bianwuya/ace7pro-fan/actions/runs/41631656221) 在 `0a2ad17` 通过：源码/工程门禁、管理工具及纯Java测试。云端没有私有OEM参考样本，该项明确SKIPPED；没有签名构建、设备安装或风扇物理验收。

上述是真实历史验证；后续每次推送的状态须重新检查，不能用首轮结果替代最新CI。

## 后续同步

先检查实际远端、工作树与提交内容，保留未提交工作。变更按 [工作流程](WORKFLOW.md) 做分级验证、暂存审计与小提交后，按明确任务手动推送：

```sh
python3 tools/project.py status
python3 tools/project.py check
git status --short
git push origin main
git rev-parse HEAD
git ls-remote --heads origin refs/heads/main
```

后两个提交号一致才说明当前源版本已同步；CI结论另用GitHub Actions核实。没有配置定时任务、常驻备份、自动push、force push 或公开Release；本次可见性变更已获用户明确授权，并非同步命令的隐含动作。`tools/project.py release` 仍只创建本地验证包，不偷偷联网。

## 从远端恢复源码

在能访问 GitHub 的机器上，无需认证即可选一个**新的空目录**克隆，不覆盖现有工程：

```sh
git clone https://github.com/bianwuya/ace7pro-fan.git ace7pro-fan-restored
cd ace7pro-fan-restored
git fsck --full
python3 tools/project.py check
python3 tools/project.py test
python3 tools/project.py install-hooks
```

需要Git、Python3、JDK8+（CI使用17）；实际完整构建还需要原有依赖与签名材料。新克隆不会继承本地 `.git/config`、认证和hook启用状态；`install-hooks` 仅配置本地钩子且拒绝覆盖已有自定义hook。根据 [AGENTS.md](../AGENTS.md) 与 [知识索引](INDEX.md)继续维护，不盲目重放旧设备操作。
