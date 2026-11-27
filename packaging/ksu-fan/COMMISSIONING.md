# First-control commissioning 打包（非已验证生产版）

此通道只解决“真实完整控制代码尚未实际加载前，怎样如实打包首装验收 ZIP”。
它不代表候选驱动已加载、已接管或物理效果通过，不自动安装或重启。
默认普通 `--output` 仍要求原 `controlled-local-release`、独立 build/check/device_load 报告；
commissioning 记录、observer 报告不能满足该正式门禁。

## 新接口与保留项

- 只有 `--commissioning-record FILE --confirm-first-control` 同时出现才能启用；
  compatibility 必须显式 `strategy: "preinit-replace"`、`commissioning_profile: "first-control"`。
- APK 仍走现有 `project.audit_apk(unsigned=False)`，包名、版本、原证书、SHA-256 不变。
- KO 的真实 ELF/模块名/vermagic/全部符号 CRC/哈希、固定 stock target/hash、
  静态 helper ELF 与只读嵌入 manifest、helper 镜像 JSON 每个字段、preinit 总量限制全部保留。
  首控可逐项审核的现存 OEM uname/vermagic 差异、两类已知区段文件布局和重复 import_ns 见下文；
  不提供任意忽略 ELF 错误的开关，正式 release 对这些差异的原拒绝行为不变。
- META-INF 安装入口、全部生命周期模板、精确 `preinit/preinit.conf` 仍为显式输入，缺少就拒绝。
  builder 不生成安装脚本，不证明脚本语义、设备空间、KSU 生命周期或目标可加载性。
- 仅此 preinit commissioning profile 要求且允许额外 `fg-debug.sh` 与 `allow-control`。
  前者沿用脚本 shebang/no-op/禁止第二次 insmod/modprobe 等检查；后者是非可执行文本，
  **字节必须恰为 `first-control\n`**。二者与所有模板一起进入完整 SHA-256 清单。
  普通 preinit release 和 additive 均拒绝这两项；未知文件、符号链接、密钥、日志仍拒绝。
- 输出 manifest 的 kind 为 `ksu-fan-commissioning`，不是 `ksu-fan-release`；
  `production_runtime_verified=false`、`candidate_control_tested=false`、
  `automatic_install=false`、`automatic_reboot=false`。inspect 的 `release_ready` 永远为 false。
- 原 additive/普通 preinit-release 输出无新增字段；旧输入的确定性 ZIP 字节保持不变。

## 输入路径

在工程根目录 `/workspace/ace7pro-fan` 执行。已提供的真实 helper 输入：

- `build/first-kernel-control/preinit/bin/fan-preinit`
- `build/first-kernel-control/preinit/helper-enabled-manifest.json`

镜像 JSON 记录的当前替换 payload 身份是 SHA-256
`982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`、151040 bytes。
`--ko` 仍要求显式真实 `.ko` 路径；不要把 fixture 或 observer KO 当完整控制驱动。
APK、完整模板暂存目录、真实 build/check 报告由调用者提供；不要使用测试 fixture。
模板目录必须补齐由维护者审核的 META-INF installer、preinit.conf 与上述两个首控文件，
不能直接把含构建日志或二进制的 build 目录当 templates。

## compatibility 最小变化

沿用 `compatibility.schema.json` 的所有原有必填字段（硬件属性须来自真实目标证据），仅增加：

```json
{
  "strategy": "preinit-replace",
  "commissioning_profile": "first-control"
}
```

以上是合并片段，不是完整 manifest。`templates` 必须枚举暂存目录的每个实际文件及其 SHA-256；
APK/KO/helper/hash/CRC 等均填实际字节身份。compatibility binding 使用 canonical JSON
（UTF-8、sort_keys=True、indent=2、ensure_ascii=False、尾随 LF），不是原文件排版的哈希。

### 当前 OEM uname/vermagic 不同的处理

当前提供的 helper manifest 将目标 uname 固定为
`6.12.69-android16-6-g242e1a07f878-ab15865446-4k`，模块 vermagic 则为
`6.12.69-android16-6-o-gdea09c7a246a-4k SMP preempt mod_unload modversions aarch64`。
不能把其中一个改写成另一个来凑一致。仅 commissioning 允许保留这两个真实身份，且记录中必须增加
`vermagic_release_review`，逐字重复 target_kernel_release / expected_vermagic，提供具体 reason 和
`result: "pass"`。这表示调用者明确承担首控不确定性的离线审核，不表示驱动已成功加载。
普通 release 的 uname/vermagic 同值门禁未改变。helper 的编译嵌入字段仍必须分别精确匹配两者。

## commissioning record 最小结构

完整 schema：`packaging/ksu-fan/commissioning-record.schema.json`。
下列示例中的 `<...>` 必须以真实值替换；原样会被拒绝，不是可伪造验收的模板：

```json
{
  "schema": 1,
  "kind": "controlled-local-commissioning",
  "profile": "first-control",
  "compatibility_sha256": "<canonical compatibility SHA-256>",
  "production_runtime_verified": false,
  "candidate_control_tested": false,
  "acknowledgement": "first-control-unverified-no-auto-install-or-reboot",
  "template_review": {"reviewer": "<actual reviewer>", "result": "pass"},
  "preinit_helper": {
    "helper_sha256": "<actual helper SHA-256>",
    "helper_enabled_manifest_digest": "<actual mirror JSON bytes SHA-256>",
    "result": "pass"
  },
  "reports": {
    "build": {"sha256": "<actual build report bytes SHA-256>", "result": "pass"},
    "check": {"sha256": "<actual check report bytes SHA-256>", "result": "pass"}
  },
  "vermagic_release_review": {
    "target_kernel_release": "6.12.69-android16-6-g242e1a07f878-ab15865446-4k",
    "expected_vermagic": "6.12.69-android16-6-o-gdea09c7a246a-4k SMP preempt mod_unload modversions aarch64",
    "reason": "<concrete review of differing OEM uname/module labels and exact ABI evidence>",
    "result": "pass"
  },
  "elf_candidate_layout_review": {
    "file_alignment": [
      {"section": ".plt", "offset": 36296, "size": 1, "alignment": 16},
      {"section": ".gnu.linkonce.this_module", "offset": 41616, "size": 1600, "alignment": 64}
    ],
    "duplicate_import_namespaces": [{"namespace": "PWM", "occurrences": 3}],
    "reason": "<concrete review of actual ET_REL file offsets and repeated PWM import namespace>",
    "result": "pass"
  }
}
```

`elf_candidate_layout_review` 仅在实际 ELF 有对应观察时必须出现；无观察时不得加入。
两个数组必须与 parser 观察逐字段、类型、顺序完全一致，缺少的一类填 `[]`，不靠它修改 payload。
目前只允许审核 SHT_PROGBITS `.plt`（SHF_ALLOC）和 `.gnu.linkonce.this_module`
（SHF_ALLOC|SHF_WRITE）的文件偏移非对齐，以及相同 import_ns 的重复声明；
其它区段非对齐、元信息重复、区段越界/重叠、缺失/错误 CRC 等仍拒绝。
上面观察值已从所提供 SHA-256/151040B 的真实 payload 只读解析确认；
具体 reason 和 pass 仍应由实际审核者填写，不是 builder 代替认定加载成功。

`vermagic_release_review` 仅在 uname/vermagic 首 token 不同时必须出现；相同时不得加入。
`reports` 只允许 build/check，禁止 device_load。必须提供不同字节、至少 32 个 trim 后字符的
真实报告，结果实际审核通过，哈希逐项相符；TODO/STUB 等占位符拒绝。此通道允许报告如实写
UNVERIFIED，不允许把 false 改成 true、0、字符串或省略。JSON 自述不是经过认证的验收事实。

如需同时保留真实 observer 证据，额外传 `--observer-report "$OBSERVER_REPORT"`，并只增加：

```json
"observer_evidence": {
  "sha256": "<actual observer report bytes SHA-256>",
  "scope": "observer-only-not-control-load"
}
```

observer 文件和声明必须成对，且不能复用 build/check 字节；它永远不计为 candidate-control load/test。
禁止同时传 `--release-record` 或 `--load-report`。原始报告、记录和主机路径不进入 ZIP，仅保留哈希/审核摘要。

## 直接调用 CLI

先将以下 shell 变量设为已存在的真实文件/目录：`APK`、`KO`、`COMPAT`、`TEMPLATES`、
`COMMISSION_RECORD`、`BUILD_REPORT`、`CHECK_REPORT`、`OUTPUT_ZIP`。输出父目录必须已存在，
且 ZIP 不得已存在；对外交付置于 `Download/eta/工作区产物/ace7pro-fan/<任务>/`，不放下载根目录。

```sh
cd /workspace/ace7pro-fan
python3 -B tools/package_ksu_fan.py \
  --apk "$APK" --ko "$KO" --manifest "$COMPAT" --templates "$TEMPLATES" \
  --preinit-helper build/first-kernel-control/preinit/bin/fan-preinit \
  --helper-enabled-manifest build/first-kernel-control/preinit/helper-enabled-manifest.json \
  --commissioning-record "$COMMISSION_RECORD" --confirm-first-control \
  --build-report "$BUILD_REPORT" --check-report "$CHECK_REPORT" \
  --inspect
```

inspect 返回 `commissioning_ready: true` 只代表离线打包完整。确认后将最后的 `--inspect` 替换为
`--output "$OUTPUT_ZIP"`；其他参数不变。此命令只创建 ZIP，没有安装/重启能力。
如需 observer 证据，两次调用都添加对应参数。实际首次安装、重启、控制接管/斜坡/保护/交还验证，
以及后续正式发布的真实 device_load 验收，仍属于单独任务；此通道绝不将它们标成已完成。

## 本次定向验证

- 22 项小套通过：新增 first-control 的 17 项、旧 ZIP 字节兼容 1 项、原 release/schema 门禁 4 项；
  未重跑既有完整 68 项套件。测试中的签名审计使用 mock，fixture/临时 ZIP 全部自动删除，不是交付物。
- 旧 additive 固定 fixture ZIP：`c7fbf65a6d75294f25eae534ed0752a61b1754daf282902d70f3c65c2a9d008c`；
  普通 preinit-release：`4809197a4ca27212f42d22ac89a2d7927777fbce239440951b163782cced6051`；
  修改前后字节完全一致。
- 对所提供 stage4 真 payload 的只读独立检查通过：151040B、89 项 __versions，原始版本区段 SHA-256
  `743422d5c87a95381c72d6bdc3bd08c9432d2fbe764c5b7338e0f6f94e603847`；
  helper 为 780344B，SHA-256 `58684c3adff92fb2cedddc88245c4bc1c74b80e3b2983d8623ec768270583491`；
  helper 镜像 JSON SHA-256 `83bd9186b6d4be54c877d4e2b76421816d6a3b32f9c12555fad0959a4cad71a8`；
  编译嵌入只读 manifest 与 JSON、payload/CRC 身份逐字段相符。
  此项没有执行 helper、没有审计最终 APK、没有打包最终交付 ZIP、没有加载或安装。
