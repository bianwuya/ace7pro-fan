# 0.9.1-kernel-rpm 交付核对

- 用户指定目标仓库：`bianwuya/ace7pro-fan`。本地工作区 `/workspace/ace7pro-fan`，不是Eta源码工程。
- 源码先本地提交：`658b0d8960f9ff031dcba19a67abb64bfa31a050`；已正常推送修复分支 `fix/kernel-daily-rpm-code28`，GitHub API回读提交相同。没有强推、改main或删除旧功能。
- [统一包预发布](https://github.com/bianwuya/ace7pro-fan/releases/tag/kernel-0.9.1-code28)：tag `kernel-0.9.1-code28` 精确指向该源码提交。使用独立验收tag，未触发旧v标签的云端签名流程，未改变签名Secrets。
- 发布asset `ACE7Pro_Fan_0.9.1-kernel-rpm-code28.zip`，GitHub asset ID `800160022`，8,210,348字节，状态uploaded。SHA256：`155a6abe82c47a5b610af8b5ee6c360e21b2ac6de5e655e3a68a49dbacaa31ef`。
- GitHub API的asset digest匹配，随后实际重新下载ZIP并计算SHA256，与本地已安装包完全一致。不只依据上传命令退出或网页文件名认定成功。
- 公开资产严格限于统一ZIP、SHA256SUMS、BUILD_IDENTITY和NOTES；没有上传密钥、原始设备日志、私有OEM分析样本或整个build目录。ZIP包含用户要求的目标驱动，不包含原始分析样本。
- 真实KSU安装退出0。App/code28及Root RPM回传已运行并实测；新的日用阶梯KO只已登记下一启动，用户尚未重启加载该版本，不冒称已实机升到16档。限制见 [版本说明](NOTES.md)。

交付目录：`Download/eta/工作区产物/ace7pro-fan/0.9.1-kernel-rpm/`。
私有验证位置：`build/root-fg-fix/github-release-readback.json`、`github-download-verification.json`、`unified-installed-readback.txt`。这些原始记录不作为公开资产上传。
