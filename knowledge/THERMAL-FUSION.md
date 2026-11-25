# ACE7pro 融合温控与风扇档位：实测与逆向结论

取证时间 2026-11-22。设备 OnePlus ACE7pro（PMS110 / OP64F1L1），Android 17 (SDK 37)。
horae Config version `2026101501`，原厂服务 1.4.6。
来源：`/system_ext/bin/horae` Ghidra 反编译 + 运行期 logcat + sysfs 逐秒采样。

---

## 1. 控制链（两段，不在同一进程）

```
/system_ext/bin/horae            ← 真正的温控算法（native）
  FanSmartControl::controlThreadLoop        每 5 s 一轮
  ├─ MultiHotPotsImpl::updateMultiPotsListData   多热源融合
  ├─ HoraeState::updateShellTemps                AlgoType 选算法
  ├─ MultiHotPotsImpl::initConfig                读 temp_level_config
  └─ FanSmartControl::parseStartupConfig         读 fan_startup_config
        │ Binder IFanSceneControlService.setFanSceneControl("AutoControlMode", enable, speedLevel)
        ▼
com.oplus.exsystemservice        ← 执行器
  Strategy.d(thermalLevel) 查 fan_configs 表 → min/max
  OplusFanController.i(int) → /sys/class/leds/fan/{level,brightness}
        ▼
soc:pwm_fan → PWM duty → 风扇
```

**关键：原厂风扇服务不读任何温度传感器。**它只接收 horae 给出的 `speedLevel` 整数再查表。
所以「融合温控权重」属于 horae，不属于 exsystemservice。

---

## 2. 融合算法（反编译 `HoraeState::updateShellTemps` + `calWeightAverage`）

`AlgoType` 枚举，`type` 字段（int）选择：

| type | 算法 | 公式 |
|---|---|---|
| 1 | MAX | `max(所有 pots 温度)` |
| 2 | FRAME | 按权重数组第 1 个元素**指定的 tz index** 取单点 |
| 3 | AVG | `Σ所有 pots 温度 / pots 数量` |
| 4 | WEIGHTED | `Σ(w[i] × T[i])`，要求 `Σw == 1.0` 严格成立 |

`calWeightAverage` 实际代码（`0x00132210`）：

```c
dVar = 0;
for (i = 0; i < min(tempCount, weightCount); i++)
    dVar += weight[i] * temp[i];
return dVar;   // 若 tempCount > weightCount，还补加 weight[last] 项
```

**降级规则（强约束）**：
- `type==4` 但 `Σw != 1.0` → 打印 `weight sum is not 1, trying algo:MAX`，**回退 MAX**
- `type==4` 但权重数组为空 → `WRONG PARA, failed using algo: FRAME`
- `type==2` 且权重为空 → 同上 FRAME 失败
- 权重个数校验：`type==2` 时 `(temp数)+1 == (weight数)`；`type==1` 时 `weight数 == 1`

`initConfig` 的模型名解析（`0x00159980`）：
```
键路径: fan_low_ambient → temp_level_config
每个 pots: index(int), model(string), shell_hotpots(int数组), coefficient(double数组)
model 查表 → 得 type
type==2 时校验 coefficient 数 == shell_hotpots 数 + 1
type==1 时校验 coefficient 数 == 1
```

配置键名取自 `/system_ext/bin/horae` .rodata：
`temp_threshold_startup`、`temp_threshold_shutdown`、`fan_level_rate_up`、
`fan_level_rate_down`、`fan_startup_config`、`stepwise_config`、`kp_control_config`、
`power_control_config`、`fan_level_offset`、`fan_default_level`、`fan_max_level`、
`fan_shell`、`ambient_fan_shell`、`ambient_fan_shell_charge`。

---

## 3. 本机实际融合结果（实测，非推断）

逐秒采样 14 个热区 170 组，与 horae 每 5 s 的 `kpTemp` 对齐 34 组：

| 通道 | thermal type | 与 kpTemp 相关系数 | 单通道回归 R² | 偏差 |
|---|---|---|---|---|
| **battery** | battery | +0.8052 | 0.648 | **+0.087 °C** |
| mos | svooc_mos_btb_usr | +0.9159 | 0.839 | +3.43 |
| sys6 | sys-therm-6 | +0.9008 | 0.811 | +3.94 |
| board | board_temp | +0.7497 | 0.562 | +0.86 |
| fan | fan_temp | +0.1925 | 0.037 | +0.23 |
| sys9 | sys-therm-9 | −0.1694 | 0.029 | +44.3 |

**`kpTemp` ≈ `battery` 单通道，RMSE = 0.152 °C，平均偏差 +0.087 °C。**

对比：`kpTemp` 与「12 通道取 max」相差 **−6.2 至 −9.2 °C**，明显不是 MAX。
两通道加权扫描：最佳解是 `1.00×batt + 0.00×其他`，即**退化为单通道**。

反证：`type=3 (AVG)` 若用全部 pots 平均，结果应接近 38 °C（各通道均值 34.7–42.4 的算术平均），
实测 34.88 °C，与 battery 34.97 °C 一致，**不支持 AVG**。

内存侧证据（HoraeState 单例 `0x799fe5f000` 及其引用链）：
`AlgoType` 节点 `0x799fe8a2c0` 的 `type = 3`，两个权重指针相等（权重数组为空）。
`type=4` 节点 `0x799feed300` 权重同样为空。

→ **本机实际生效的是「单 pots、AVG/退化」路径，权重数组为空，不参与加权。**
「融合」在语义上存在，但本机配置没有启用真正的多源加权。

---

## 4. 档位与转速表（device-tree 硬编码，实测）

`/sys/firmware/devicetree/base/soc/pwm_fan/fan,device_id0/`：

- `duty-config` (20 × u32)：`5,10,15,…,100` (%)
- `target-rpm-config` (20 × u32)：`3500,4500,5500,6500,7500,8500,9500,10500,11500,12500,13500,14500,15300,16200,17200,18200,19100,20000,21000,22000`
- `rpm-offset-config` (6 × u32)：`40,1000, 50,1500, 100,1800`
- `pulses-per-revolution`：device_id0 = 2，device_id1 = 3
- `level-derate-floor` = 1，`level-derate-confirm` = 1

sysfs 运行时（`lookup_enable=1`，`derate_enable=1`，`check_period=5000`）：

| 档位 | duty | target rpm | 实测 rpm 节点 |
|---|---|---|---|
| 6 | 30% | 8500 | — |
| **7** | **35%** | **9500** | **10439** |
| 8 | 40% | 10500 | 11059 |
| 20 | 100% | 22000 | — |

实测 rpm 稳定高出 target 5–10%；同一档位 `rpm` 节点在 0 与 10k+ 之间抖动，
**rpm 不可作为转速计量**，只能当参考。

**日用档位 = 6–7 档（30–35% duty，8500–9500 rpm 目标）。**

---

## 5. 启动/关闭温度阈值（实测捕获）

`temp_threshold_startup` / `temp_threshold_shutdown` 数值存在 AES 加密的
`/odm/etc/horae/horae_target.conf`，无法直接读出。但状态机切换时明文打印：

```
FanSmartControl: STARTUP temp=%.2f fanAlreadyOn=%d currentSpeed=%d autoStarted=%d->%d startup=%.2f shutdown=%.2f
```

实测捕获到的启动过程：

```
05:02:28  auto=0  base=0.000  kpTemp=34.55  kpOffset=-0.001  powerOffset=0.000  target=0
05:02:33  auto=0  base=0.000  kpTemp=34.62  kpOffset=+0.063  powerOffset=0.000  target=0
05:02:38  auto=1  base=6.000  kpTemp=35.08  kpOffset=+0.188  powerOffset=2.000  target=6   ← 启动
```

停止过程（04:59:38 前一拍仍 auto=1，之后 auto=0 直到 05:02:38）：

```
kpTemp ≈ 33.4–33.8 °C 区间持续 autoStarted=0 base=0.000 target=0
```

**实测结论（本机，non-charging 放电态）**：
- 启动温度 ≈ **35.0–35.1 °C**（`kpTemp` 跨过该点即 `autoStarted 0→1`，`base 0→6`）
- 停止温度 ≈ **33.4–33.8 °C** 或更低（该区间已停转，未再往下测）
- 迟滞窗口至少 **1.3–1.7 °C**
- 启动不是从 1 档爬升，而是**直接置 `base=6`**（`fan_default_level=6`）

⚠ 这些是**可观测的切换点**，不是配置里的精确阈值；`startup=`/`shutdown=` 字段的
原始数值仍需在触发瞬间抓 `STARTUP` 日志才能读到。

---

## 6. 档位决策公式（反编译 + 实测确认）

`FanSmartControl::controlThreadLoop` 明文输出：

```
LEVEL_SUM autoStarted=%d default=%d stepwise=%d base=%.3f kpTemp=%.2f
          controlTemp=%.2f kpOffset=%.3f powerOffset=%.3f rawTarget=%.3f
          target=%d maxFan=%d
DECIDE controlTemp=%.2f pidTemp=%.2f ambient=%.2f saleMode=%d mode=%s
       autoStarted=%d chargingAc=%d level=%d
```

推导出的计算式（与全部 88 条日志吻合）：

```
base       = autoStarted ? fan_default_level(=6) : 0
controlTemp = kpTemp (+ fan_level_offset / ambient 修正，实测 0)
kpOffset   = PID(controlTemp, kp_control_up/down, sampling_time)
powerOffset= 整数功耗补偿（实测 0 / 1 / 2）
rawTarget  = base + kpOffset + powerOffset
target     = clamp(round(rawTarget), 0, maxFan=20)
             → 再受 fan_level_rate_up / fan_level_rate_down 限速
level      = target   (chargingAc=0, mode=default)
```

实测数值范围：`kpOffset` 在 −0.24 ~ +0.54 间小幅摆动（被 round 吸收），
**真正引起 6↔7 档跳变的是 `powerOffset`（0→1→2）**，不是温度。

---

## 7. 对改造方案的意义

旧版日用在 exsystemservice 候选层只抬原厂正档；这不等于用户可设定实际启停温度。
从 `0.7.2` 起的日用规则、普通自动目标证据、原厂保护和验收边界见[日用风扇后端](DAILY-FAN.md)，不向 `horae` 写入设置。

**不需要**、也**不应**去改 horae 的加密配置或 native 二进制。

---

## 8. 复现方法

```sh
# 1. 抓 horae 决策日志（明文，只需 logcat 读权限）
logcat -b all -v time -s horae > h.log

# 2. 逐秒采样热区
for zn in 60 65 67 69 71 73 75 81 83 85 86 96 64 77; do
  cat /sys/class/thermal/thermal_zone$zn/temp
done

# 3. 反编译（Ghidra 12.1.4）
export GHIDRA_INSTALL_DIR=/opt/ghidra_12.1.4_PUBLIC
analyzeHeadless /tmp/ghproj p -import horae.bin -processor AARCH64:LE:64:v8A \
  -scriptPath . -postScript Decomp.java
```

关键函数入口（未重定位基址）：
- `HoraeState::updateShellTemps` @ `0x00128eec`
- `horae::calWeightAverage` @ `0x00132210`
- `MultiHotPotsImpl::updateMultiPotsListData` @ `0x0015a408`
- `MultiHotPotsImpl::initConfig` @ `0x00159980`
- `FanSmartControl::controlThreadLoop` @ `0x0015305c`
- `FanSmartControl::parseStartupConfig` @ `0x00150ca0`
- `HoraeState` 单例指针 @ `.data 0x0182818` → 运行期 `0x799fe5f000`
