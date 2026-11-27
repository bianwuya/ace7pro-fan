# Stage4首版控制测试：状态增量与显式候选profile

配置v1/v2语法、默认disabled/pass-through、最长5s lease、root权限及policy/core阈值均不变。
本轮不改App/packaging，不执行控制模块加载/挂载/卸载/重启。observer成功不等于控制链已验收。

## status追加键（原字段保留，root0400）

| 键 | 定义 |
|---|---|
| fusionValid | 1=当前ready且core融合有效；0=未知/失效，relay不要填假融合温度 |
| fusionMc | 直接来自core output.fusion.temperature_mc，mC；无效为-1；App不重新融合 |
| demand | 与ready时core target一致，0..20；无效-1；不是effective补偿或OEM基线 |
| policyKind | 0 none/not-ready，1 daily，2 charge；来自core output.policy |
| cooling | ready时core output.cooling，0/1；not-ready为0，不冒充正在冷却 |
| samplePeriodMs | 1000，worker完成一轮后下一次正常排队延迟上限，不是硬实时周期 |
| sampleAgeMs | nowMs减sampleAtMs（采样开始时间）；未采样/时间无效为-1 |
| sampleCompletedAtMs | 最近采样完成的ktime单调毫秒；未采样为0 |
| sampleDurationMs | 完成减开始，包含电源/温度/硬件健康读取耗时；未采样为-1 |
| sampleFreshMs | 15000，原core时效上限；没有修改保护阈值 |
| candidateLabel | 编译时嵌入NONRELEASE_UNLOADED或CONTROL_TEST_PENDING，非运行验收结果 |

真实执行周期约为一次采样/算法执行耗时+min(1000,remaining lease)ms，排队还可能被内核调度延迟。
5s是lease上限，不是内核温度采样period。relay不应再用额外`age<5000`作为UI融合可用条件；
使用内核fusionValid/ready与明确age/fresh元数据，不把过期数据重算成新样本。
原lease失效仍按5s上限释放，不因UI展示需求放宽。

## 读回故障与零需求

level_show读原requested level；state_show读原enabled state；level_eff_show仅为实际effective诊断。
三项任一失败均fail-closed：worker立即停止/释放并latch fault；status若先观察到失败，也只在仍owned时
做一次fail-safe stop后释放。随后的status/worker不会重复写，也不会停掉后来manual拥有者。
因此status权限仍0400，但读取可能触发已拥有输出的安全释放；不是普通控制命令。
成功读到effective降低（包括0）不算故障，不触发补写，不用于需求反馈。

full-owner下，合法core `has_target=1,target=0`维持owned+state0，保留normal ramp状态；
不会每轮从OEM残留level重新seed。charge低于阈值也如此。`has_target=0`是安全/无效退出，明确释放；
不能把它和合法零目标混同。新增回归覆盖两者、重复续租与无重复write。

## 实际编译profile，而非改JSON绕过报告

默认构建仍NONRELEASE_UNLOADED，旧默认文件名保持不变：

```sh
python3 -B tools/build_fan_driver_candidate.py --out-dir build/kernel-replace-audit/driver-relink/<新目录>
```

首版控制测试候选必须显式：

```sh
python3 -B tools/build_fan_driver_candidate.py \
  --candidate-label=CONTROL_TEST_PENDING \
  --out-dir build/kernel-replace-audit/driver-relink/stage4-first-control
```

输出`oplus_fan.CONTROL_TEST_PENDING.candidate.ko`。label通过编译器define进入实际ELF身份及status，
构建器核对二进制marker；result记录实际profile/命令/源hash。
两种profile均保留`runtime_verified=false`、`production_runtime_verified=false`、
`release_eligible=false`、`load_report_generated=false`，不生成伪加载报告。
label只表示供主代理组装并另行请求用户授权的控制测试包，不是production验收、加载或重启授权。
