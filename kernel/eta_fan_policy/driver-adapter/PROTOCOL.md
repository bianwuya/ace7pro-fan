# NONRELEASE 配置/状态协议 v2（兼容 v1，尚未部署）

Stage4追加字段/采样时序及CONTROL_TEST_PENDING实际编译profile见
[STATUS-STAGE4.md](STATUS-STAGE4.md)。原键/权限不变；融合/需求只读内核输出，App不得重算。

本轮 full-owner 替换原上游温度计算控制；原 stock 仍是未启用/未知/手动/失联/故障的回退路径。
默认 `enabled=0,strategy=0`，不偷偷配置或启用。真实候选标识为 NONRELEASE_UNLOADED，
离线测试通过不等于允许安装、可发布或设备验收。

## RootSettingsRelay 的职责与权限

只负责完整设置/gates/lease 通信及状态转发，不采温度、不计算目标、不输出风扇档位。
`eta_policy/` 建在 probe 捕获的**原 LED device** 下，不能由未核验的固定路径猜测设备。

| 属性 | mode | 用途 |
|---|---|---|
| `config` | root `0200` | 一次完整写入原子配置/续租；没有宽松 UID 或公开写入 |
| `config_snapshot` | root `0400` | 当前配置的规范一行快照，可按同版本语法解析；读取不续租 |
| `status` | root `0400` | 内核采样、需求/输出、实际驱动读回、所有权与期限；读取不续租 |

root relay 只将上述只读结果推给既有受保护 Provider。不得为方便 UI 放宽节点、Provider 或 SELinux。

## 配置一整行：v1 26 整数，v2 追加一个 strategy

索引 0..25 在两版完全相同，最后四项是 zone 名字。v2 在这26整数后、zone名字前追加strategy：

```text
version revision enabled lease_ms main_enabled mode auto_enabled gate_mask thermal_valid thermal_status daily_enabled daily_start_mc daily_floor dw0 dw1 dw2 dw3 charge_enabled charge_start_mc charge_floor gain_milli ceiling cw0 cw1 cw2 cw3 [strategy，仅v2] battery_zone board_zone surround_zone charge_mos_zone
```

| strategy | 含义 |
|---|---|
| 0 | pass-through；即便其余 enabled/gates 有效也不接管 |
| 1 | additive；保留阶段二 OEM 请求下限、无新增需求不接管的旧行为 |
| 2 | full-owner；有效接管时目标**只来自 policy_core**，包括拥有零需求并抑制 OEM 温度请求 |

- `version=1` 不带strategy，明确映射strategy=1以保持旧协议；`version=2`必须带0/1/2。
  新App/relay明确选v2 strategy2，不能靠默认启用full-owner。冷启动零初始化仍strategy0。
- `revision=1..UINT32_MAX`，是配置版本而非心跳序号。**完全相同的配置以同revision续租**，
  不更改policy context、ownerEpoch或采样epoch；同revision改内容、旧revision返回`-ESTALE`。
  设置/gates/mode/strategy发生变化必须递增revision。新配置原子commit前先停止/释放旧owner。
- `enabled=0/1`；`lease_ms=1..5000`（disabled可0），以内核ktime单调毫秒计算绝对leaseUntil。
  relay可提前续租，但不能将lease无限延长。config_snapshot读取本身不续租。
- `main_enabled/auto_enabled`三态：0 unknown、1 no、2 yes；mode=0 unknown、1 auto、2 manual、3 other。
  接管要求yes/auto/yes。新有效manual/disabled/缺gate配置同步停止并释放，之后原manual写完全放行。
- `gate_mask`位0..6：UI/protection/abort/noise/handle/hardware/common_scene clear。
  必须完整127，缺位按unknown拒绝。实际霍尔present/status由原callback另读，不由App代替。
- thermal_valid必须1、thermal_status必须0；其它有效值表示不允许接管。
- 两种settings的enabled=1 no/2 yes；start=20000..50000mC，floor=1..20，
  四权重各0..100000000且至少一项非零；charge gain_milli=1..8000、ceiling=floor..20。
- zone各1..63字节，只允许字母数字、`_`、`-`，必须不同；拒绝shell/skin/virtual/fake/horae名字。
  本轮指定 `battery / board_temp / fan_temp / svooc_mos_btb_usr` 均通过解析。
  使用这些名字不声称其为硅片raw；来源映射由主代理证据确认，本模块不轮询shell伪装温度。
- 输入最长1024字节；未知版本、负数/溢出/额外token/缺字段/非法名字均`-EINVAL`。
  临时结构完整校验后一次commit，失败不改变原配置或lease。

### 不启用的 v2 full-owner 配置样例

以下只是供relay开发的禁用快照，不是安装/启用指令；实现没有内置这些默认zone：

```text
2 1 0 0 2 1 2 0 1 0 2 30000 12 600000 0 400000 0 2 30000 6 1000 20 600000 0 400000 0 2 battery board_temp fan_temp svooc_mos_btb_usr
```

未配置时config_snapshot返回：`UNCONFIGURED protocol=2 strategy=0 enabled=0`，
不是可以直接写回的完整配置。已配置时返回原版本的规范行，v1无strategy，v2含strategy。
`stage3-independent/relay-fixtures.txt` 提供真实host harness生成的禁用/持有状态样例，
明确标为HOST_MOCK_ONLY_NOT_DEVICE，不能当作现场值。

## full-owner 的控制语义

有效enabled+auto+完整gates+fresh lease、内核全部采样成功并通过core保护后才拥有输出。
**最终需求只来自policy_core，绝不max旧OEM温度目标。** 原实际request只用来初始化normal ramp，
使物理档位连续；这不是最终目标的下限。低于自定义阈值时target=0，仍持有零需求并拒绝OEM抢写。
normal ramp严格执行原core：正向从6开始、普通升5s/档降10s/档、降至6后下一次才停，
温度阈值/回差/30秒cooling不另改。hardguard/失效退出不走慢降，立即通过原state_store停转释放。

内部仍调用原level_store/state_store，保留RPM lookup/derate/Hall/OCP/原上下电逻辑。
仅request/enable必要边沿调用；effective降低不补写、不取消正常derate。
zero输出用实际state=0表示；原level属性可能保留上个非零请求，不能误判为风扇仍在转。

full-owner期间原sysfs level/state写返回`-EBUSY`且不会进入原函数；未持有时参数和返回值原样放行。
lease到期的store wrapper先释放一次再转发；后续worker不会再停止后来manual/OEM owner。
manual/unknown/disabled/故障/PM失联均保留stock回退，不复活已陈旧的OEM enable快照。
v1/strategy1继续保留旧additive能力，不把上述full-owner语义隐式应用到旧配置。

## 电源：battery + 必需usb + 可选ac/wireless

仅使用power_supply_get_by_name/get_property/put读接口；不读取battery ONLINE，不触碰充电控制。

1. 必须取得battery、读取PRESENT==1及STATUS；任何缺失/错误/未知值不接管。
2. usb provider必须存在且ONLINE可读为0/1。
3. ac、wireless的NULL表示可选provider不存在，可忽略；**ERR_PTR或已存在provider的ONLINE读取失败/
   非0/1是错误，不当作离线**，即便另一个source在线也将整体power置UNKNOWN。
4. 任一存在且可读source ONLINE=1为外电；只有所有存在source都可读为0且STATUS=DISCHARGING才BATTERY。
5. STATUS=CHARGING且至少一个外电1才CHARGING；FULL/NOT_CHARGING且外电1为EXTERNAL_IDLE。
   外电0却充电、外电1却DISCHARGING等矛盾均UNKNOWN，拒绝接管。

source位序：bit0 usb、bit1 ac、bit2 wireless。
`sourcePresentMask`为实际成功取得provider，`sourceOnlineMask`为ONLINE=1，
`sourceFailedMask`为getter错误/已存在源读失败/非法值。
USB NULL表现为present bit0缺失，不与optional NULL混同；battery失败单独显示字段-1。
观察到分类、source存在/在线/失败mask、battery状态/present变化均推进powerEpoch，
包括USB→无线但同为CHARGING的切换。采样间完全未观察到的A→B→A不伪称已捕获。

## status 协议（root0400，一行key=value）

固定前缀`NONRELEASE protocol=2`。relay需按键解析、允许未来新增键，不靠字段位置。

| 键 | 意义 |
|---|---|
| configProtocol/configRevision | 当前配置版本及revision；未配置为0 |
| strategy/configured | 选择的策略和enabled值；不是自动表示已经持有 |
| leaseUntil/nowMs | ktime单调毫秒；PM后lease作废，不能按wall time解释 |
| ownerEpoch/owned | 本次模块生命周期内每次取得/释放所有权推进epoch，包含零需求与rebind；续租不推进 |
| ready | 最后一次core/采样有效、lease/gates仍有效、无fault、读回匹配；不是发布ready |
| target | ready时core需求0..20；未知/过期/未ready为-1，不冒充有效0 |
| output | **实际驱动state归一化的输出请求**：state0为0，state1为读回level；读回未知为-1 |
| requestedLevel/physicalState/effective | 原driver callback当前真实读回，effective仅诊断、不做目标反馈 |
| readbackMask | bit0请求、bit1state、bit2effective有效；无效字段-1 |
| temp0..temp3/tempValidMask | battery/board/surround/charge MOS顺序的最后内核采样mC，各无效为-1；位mask对应有效性 |
| sampleAtMs | 上述采样起点；结合nowMs/ready判断时效，不能把旧mask当新采样 |
| power/powerEpoch | core电源枚举及观察epoch：0未知、1电池、2充电、3外电idle |
| sourcePresentMask/sourceOnlineMask/sourceFailedMask | 三源状态，见上 |
| batteryStatus/batteryPresent | 实际属性读回，失败为-1；STATUS标准0未知/1充电/2放电/3未充/4满 |
| live/blocked/fault/reason | 生命周期/阻塞/错误及最后core reason；ready=0时不要将旧reason解释为正在执行 |

status读回会读取原level/state/effective callback，不计算温度或接管，也不续租。
ready是采样证据状态，不是硬实时物理安全承诺；调度/原驱动API可能延迟。
worker按min(1000ms,remaining lease)安排下一次，慢采样后和执行输出前再次检查lease。

## PM、清理、构建与验证

suspend/remove/shutdown先串行quiesce、停止/释放/重置policy，再调用全部原callback。
resume保留原逻辑，要求新的有效lease；相同revision可重新续租，但旧采样/owner不继承。
自己的devm清理先于原资源，取消work/移除sysfs不持锁，正常清理后rebind默认仍disabled/pass-through。

```sh
python3 -B tools/build_fan_driver_candidate.py --out-dir build/kernel-replace-audit/driver-relink/<新目录>
python3 -B tools/test_fan_driver_adapter.py
```

stage3输出目录为`build/kernel-replace-audit/driver-relink/stage3-independent/`，不覆盖stage2。
host测试都是kernel/OEM替身，候选未加载。原85函数/CFI、1406未批准改动的RELA、原数据和85CRC保护不变。
正式发布仍须主代理独立审核、App/relay接线、身份强校验及后续明确加载授权与实机验证。
