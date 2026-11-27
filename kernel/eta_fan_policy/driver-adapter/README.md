# 保留原完整对象的风扇策略适配（NONRELEASE，未加载）

## 当前实际进度

第一阶段完成原 KO 的真实 `ld.lld -r` 与有界符号公开/改名保护证明。
第二阶段已实编 `driver_adapter.c + config_protocol.c + ../policy_core.c`；
第三阶段新增v2 full-owner，目标只来自core（包括拥有零需求），不再受OEM旧温度需求下限影响。
v1 additive能力明确保留；冷启动仍disabled/pass-through。实际增量链接产出
**NONRELEASE_UNLOADED 候选 KO**，不是仅改名的原件。
当前分析证据位于 `build/kernel-replace-audit/driver-relink/stage3-independent/`，
报告见分析目录 `REPORT-STAGE3.md`；stage2产物保留不覆盖。没有硬件加载或正式ZIP验收。

配置协议及回交语义见 [PROTOCOL.md](PROTOCOL.md)。默认 disabled；真实热区映射、
有效 power_supply、完整权限 gates 和短 lease 缺一不可。App/安全 helper 尚未接入。

## 输入与改动边界

原件只读 `build/direct-node-audit/oplus_fan.ko`，SHA256
`a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd`，任何时候不覆盖。
只新增/修改本目录和 `tools/` 下本任务工具；分析产物只落
`build/kernel-replace-audit/driver-relink/`。不改其它工程文件、App/版本/UI、原模块、
挂载器或现有 builders，不加载/卸载/unbind/真实挂载，不打 ZIP、不联网。

BTF generator 固定原机完整 BTF、running.config 和原 KO 哈希，检查实际所用
结构成员类型/偏移、大小、workqueue 枚举与直接导入 API 原型；生成最小
`target_abi.h` 投影和静态 assert。不声明、猜测或读写原 OEM private fan struct。
per-task stack canary 位置由同一 BTF 验证；新代码保留 KCFI/PAC、强栈保护并保留 x18。
这是特定内核输入的离线投影，不是可在其它 KMI/配置通用使用的头文件。

## 保留与执行路径

全部原 lookup、RPM 表、derate/FG/霍尔/OCP/故障、工厂模式、PM/cleanup 的代码和数据保留。
通过原 level/state callback 请求，不直接写 PWM，不读 rpm，不做 effective 补偿。
仅八条明确 RELA 改动，包含 probe、remove、shutdown、suspend、resume、两个 user store，
以及 probe 内唯一 sysfs_create_group 调用用于捕获真实 LED device。机器码不做固定地址 patch。

独立 workqueue 直接读 named thermal 与 power_supply；策略与 normal ramp 在内核。
只在 request/enable 边沿写；未持有时原用户 callback 参数/返回值完整放行，持有时仲裁拒绝。
自己的 devm cleanup 先于原清理，PM 后须新 lease。v2 full-owner仅使用core目标与normal ramp，
零需求仍抑制OEM抢写；v1/strategy1才保留旧additive基线。有效manual等配置立即释放，
之后不抢后来manual拥有者。硬退出停转、不复活陈旧enable快照；现场联调仍未验收。
电源综合必需usb与可选ac/wireless的ONLINE，缺可选源与读失败明确区分。
root0400的status/config_snapshot用于relay只读推送，不把温度计算挪回App。

原 84 imports / 85 versions 完整保留。新增四个 API 的 CRC 来自同机充电模块
`__versions`，并交叉核对其扩展版本记录；不改动充电文件/模块，不捏造 CRC。
原 `__this_module` 1600 字节与原 init/cleanup 保留，不另造模块注册结构。

## 本地复现

```sh
python3 -B tools/relink_fan_driver.py audit --out-dir build/kernel-replace-audit/driver-relink/<新的阶段一目录>
python3 -B tools/build_fan_driver_candidate.py --out-dir build/kernel-replace-audit/driver-relink/<新的阶段二目录>
python3 -B tools/test_relink_fan_driver.py
python3 -B tools/test_fan_driver_adapter.py
```

`test_adapter_host.c` 执行真实 adapter/core 源，但每个 kernel/OEM API 都由测试替身替代，
不是执行驱动或证明物理行为。`callback_abi_probe.c` 仍只是单独的离线类型探针，不链接到候选。
原 addrsig sh_link=0 的语义仍未知；旧签名不可转移到修改产物。未绕过内核签名、CFI 或安全校验。
