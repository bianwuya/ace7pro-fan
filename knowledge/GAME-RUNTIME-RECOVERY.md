# code29：正常恢复与真实游戏进入/退出实测

## 结论与范围

在用户明确允许短暂使用主屏后，本机完成一次真实王者荣耀前台进入/返回桌面的控制权切换。未手工写gamemode_delayed、风扇state/level或故障节点；未登录、进入对局、修改游戏设置或重启设备。主屏已返回桌面并释放操作权。

这证明本boot的“日用内核 → 原厂游戏 → 日用内核”实际接续已恢复，不再仅是源码或挂载验证。它不代表同档竞态、所有游戏/ROM、长时高负载或下一启动stage5驱动已完成生产验收。

## 已核实的阶段

1. 自然恢复Horae：此前的一次性只读事件任务`job_96f5c3aa`已正常完成。进程PID5599的`/proc/<pid>/exe` SHA-256为`eae728ebe22e5662ce46fc7e5bc9f887d923f369e8c5bd9e1c25612ae8e5f73d`，即旧单指令风扇温度解耦，不再只是磁盘bind。没有由本任务启停Horae。
2. 拔线放电后的日用：驱动由此前BLOCKED自然恢复NORMAL；configured/owned/ready=1，真实state1、effective10。code29 Provider收到同档FG实测保持13439RPM。外部显示Temp29.50同时原厂FanSmartControl日志的kpTemp约38°C，证明风扇输入没有随外部伪装锁在29.5°C。
3. 后台虚拟屏试验：启动王者荣耀后系统gamemode_delayed仍为0，不能当作真实游戏场景验收。因而未伪造参数冒充通过，而是向用户取得主屏许可。
4. 主屏真实游戏：系统自动gamemode_delayed=1；内核configRevision104、ownerEpoch50、configured0/owned0/ready0、fault0，Root gates63（缺common_scene位）。真实风扇state1、requested/effective17，硬件NORMAL；正式FG事件在17档保持值19839RPM，后续raw19799。多个正式Provider回传证实内核在游戏时没有继续接管，实际正档由原厂控制。原厂日志还有16/17目标变化，不是本任务强制固定17档。
5. 返回桌面：HOME后确认launcher在主屏，系统gamemode_delayed自动回0；configRevision105、ownerEpoch51、configured/owned/ready=1、fault0，gates127、日用target10。完整正式Provider历史显示实际17→16→15→14→13→12→11→10，约10秒降1档，最终确已回到日用10档；不是立刻硬切或强制停转。
6. 设置未改：日用v8仍36°C、最低10、权重60:0:40:0；充电v1仍33°C、最低6、gain2、ceiling20。没有改独立充电/EXGT模块。

## 软件及运行身份

App及Root回传`0.9.2-game-compat/code29`。boot仍`a7d4c185-a558-4bb9-8b8a-d995f80a3993`，实际驻留stage4 KO `982b21f26d733e4fbf6056eb288a5df9245e81a503302e6d5532059dac8c7600`，没有在本轮热加载stage5。因此本结果只覆盖当前驱动+code29温度解耦/回传组合；待启动stage5虽复用同一接管门禁，仍需正常重启后另验，不能套用本结果。

## 后续观察：不能把一次成功当长时无故障

本轮完整历史在上述成功退出后，还记录了新的scene/gates变化以及一次真实硬件BLOCKED。新的game1阶段内核仍owned0，原厂曾实际9→16档，此后驱动报BLOCKED；不能据此认定是本模块强占或物理损坏，也不能把已证实的一次恢复宣称为全部故障消失。本任务没有清该故障或强制写入。分发材料已明确限定为验收版，用户可分发同一ZIP，但不包装成稳定通用版。

## 未验边界与下一步

- 本次日用10与游戏17目标不同，尚未故意构造“游戏目标恰好等于交还前档位”的缓存竞态，不把静态风险当作已经全覆盖或已经发生。保持故障/失联退出保护，不盲重放旧OEM enable快照。
- 没有长时间游戏压力/充电中游戏/手柄/降噪等全部组合验收，不把一次成功称为生产全通过。
- 不重建、不替换已交付code29 ZIP，也不回改原BUILD_IDENTITY中当时观察为false的字段。新结果作为本地独立运行记录；按现行本地收尾规范提交，不自动推送/上传或改变历史Release。

原始有界证据仅在私有`build/game-regression-audit/`：`code29-normal-horae-recovery.log`、`code29-main-game-entry.txt`、`code29-main-game-steady.txt`、`code29-main-game-oem-log.txt`、`code29-main-game-exit.txt`、`code29-game-cycle-history.txt`、`code29-game-cycle-final.txt`、`code29-game-cycle-complete-history.txt`、`code29-game-cycle-transitions.json`及`code29-game-cycle-summary.json`。其中完整历史确认退出后已到10档，也保留后续再次出现BLOCKED的观察，不以文件名或预设场景标签代替实际字段。原始日志及UI截图不公开上传。实际待机与游戏状态都是观察时点，不是持续监测保证。
