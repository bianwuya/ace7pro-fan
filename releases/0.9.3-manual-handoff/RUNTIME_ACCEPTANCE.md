# code30 重启后实机验收

2026-11-28，Asia/Shanghai。仅本机 ACE 7 Pro / PMS110、当前内核，短时回归，不是其他ROM/内核或长时稳定承诺。

## 驻留身份

- boot已变化为`33cd1f53-48ce-4c21-8509-eac6e2dc30d2`；当前驱动SHA256 `b0b57952bd1522ea45cb5de2f11edd5cf2c11e2233a7c55a0ada6749f069bd24`，新遥测扩展字段实际存在并推进，确认stage6运行，不只根据挂载文件推断。
- 正式Provider回传`0.9.3-manual-handoff`。原厂进程消费UID0 release令牌为true并实际恢复使能，证明新交还桥运行；不是仅App显示新版本。
- 内核日用自然6→7→8→9→10及低温目标0已观察；本轮不改用户阈值、权重、充电配置，不写硬件level/state。

## 请求问题的回归结果

| 场景 | 实际结果 |
|---|---|
| 智能直接切极限 | revision228，owned0、configured0；原厂state1/effective20/NORMAL，release凭据consumed=true。第一次没有静音绕行 |
| 静音/极限互切 | 实际有效档20→6→20，控制权仍归原厂 |
| 智能停转后再直接极限 | 先revision233 owned1/state0/target0；再revision239 owned0/state1/effective20/NORMAL；实测21819RPM，凭据已消费 |
| 手动温度和融合历史 | 完整配置后的38条非接管记录均有四温度与融合值，sampleAt严格前进；最大样本年龄973ms，Provider最大发布间隔5004ms |
| App概览 | 手动20档时四路温度和融合读数实见，FG实测22099RPM可见 |
| App趋势 | 趋势页原生曲线控件及四通道系列存在；本轮以真实历史记录验证曲线数据链，没有声称完整像素级绘制验收 |
| 恢复原状态 | 已恢复智能mode0、customize100，日用v8及充电v1不变；08:15读回owned1/target0/state0/NORMAL，四温度仍新鲜 |

测试走系统磁贴使用的同一Settings键，**不是GUI实际点击磁贴**，不把两者混同。38条统计排除启动时configRevision0、尚未配置的一条空样本，未删除/回填旧数据。

## 已知问题与明确限制

连续原厂手动运行中仍出现一次`BLOCKED`窗口，7条Provider回传、约35秒；发生时owned0，随后原厂自行恢复NORMAL。模块没有清故障、强写档位或绕过保护。**根因未确认，不能从owned0推导与模块无关，也不能声称长时稳定**。该问题仍在游戏/原厂保护跟进项中保留。

本次两项请求的范围内回归通过；没有完成全部安全组合、充电实机重测、多游戏、卸载/回退或任意内核验证。安装器去掉额外项目号/uname白名单，但helper ABI保护仍在，不冒称通用内核。

本地原始证据在`build/manual-fix-audit/code30-reboot/`，不上传原始日志、数据库或设备私有文件。对外只提供范围明确的本报告和摘要JSON。已安装ZIP字节不变，包内first-control/production未验的构建时快照不重写；本报告补记真实启动后的有限观察，**不伪造打包器生产验收记录**。
