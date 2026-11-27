# code29 独立源码分发记录

## 本地交付

目录：`/storage/emulated/0/Download/eta/工作区产物/ace7pro-fan/0.9.2-game-compat/源码/`

- 源码ZIP：`ACE7Pro_Fan_0.9.2-game-compat-code29-source-906f56c.zip`
- 大小：2664524字节。
- SHA-256：`b6116c8e931f2f95e40da373a9b1e95b38be1515eadcda7084ac841c1808cf6f`。
- 配套：`SOURCE_IDENTITY.json`、`SOURCE_README.md`、`SOURCE_SHA256SUMS.txt`，均与云端下载逐字节一致。

源码快照固定为提交`906f56ce6972d283c79b33079945cd0d39ab060f`，包含该提交全部229个Git跟踪文件，另加2个明确标注的打包说明/逐文件身份清单，共231个文件。对应原安装包功能源码提交为`2994b0ea1fdc15a9531a87b2af7f6780c99079c2`；两提交之间只有Markdown文档变化，App/内核/加载器/安装及构建测试代码相同。后续交付记录提交不改变这个冻结快照，不为追赶文档重新打包。

## 安全与构建边界

按现有源码管理规则检查全部路径、Git类型/权限、文件大小、UTF-8文本与敏感模式。原文件全部与固定提交逐字节相同，ZIP CRC及重复/穿越路径检查通过；实际下载包再次通过CRC与哈希。

包中包含App/Compose、Root/FG、内核策略与适配、preinit加载器源码、安装模板、测试、构建工具、维护文档和既有设计资源。不含.git历史、签名私钥/口令、认证配置、原始设备日志/数据库、构建缓存/工具链、APK/KO、完整私有OEM二进制或回退归档。仅打包现有源码，未改业务代码、未重新构建安装包、未操作手机或重测硬件。

这不是完整离线环境镜像，也不保证接收者可立刻复现同签名APK或目标KO；原签名及精确OEM/ABI/BTF/config等私有构建输入需单独合法准备。敏感模式扫描有识别边界，不宣称绝对无隐私风险。未替用户新增许可证。

## GitHub实际结果

用户本次明确要求源码也上传GitHub，故将源码快照推至现有`bianwuya/ace7pro-fan`修复分支，并在原`kernel-0.9.2-code29`预发布（Release ID460666995）追加4个源码资产。未改main、未挪动原发布tag、未改变原安装包4个资产的ID/大小/digest/更新时间。

- 源码ZIP asset ID：801048635，uploaded。
- SOURCE_IDENTITY asset ID：801048652。
- SOURCE_README asset ID：801048653。
- SOURCE_SHA256SUMS asset ID：801051589。

4个资产均实际下载回读，名称、大小、服务端digest与本地SHA-256全部一致；不是仅提交上传请求。中文说明文件名首次上传被服务端规范化为default.md，已将该精确新资产改为ASCII文件名SOURCE_README.md，并仅重发本轮新源码校验清单；源码ZIP从未重建，旧安装资产不动。

私有验证：`build/source-export-code29-906f56c/validation.json`及`github-download-verification.json`，原始下载文件保存在其`github-verified/`。本记录、知识索引和任务交接按规则本地提交；用户已授权的源码上传不推及其他项目或未来远端动作。
