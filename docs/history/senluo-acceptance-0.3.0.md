# 森罗物语真实联调验收

**0.3.0 当前候选 15/15 阶段全部通过**，实际时间 **2026-11-20 10:27:43–10:32:09 Asia/Shanghai**。机器证据：[senluo-client.json](../evidence/history/0.3.0/senluo-client.json)，包含逐阶段原始快照、实际插件哈希、隔离配置、资产准备来源与完整起止时间。两次 Paper 均正常关闭，运行器 stderr 为零字节。0.2.0 的不同构建结果保存在[历史报告](senluo-acceptance-0.2.0.md)。

实际 StarWorld JAR SHA-256：

```text
15946f82e248d08cabba45f4389d23297a9e27f574468658faa7edf6426382e1
```

固定最新上游 `SenLuo_Plugin@c3642ae22fc5252bf6da22ae745cd39be994b054`，使用已完成构建的 Cookery 候选及同源 `kitchen-crops-2.zip`，Tavern/Deco/Music 字节与上一固定候选保持相同。CraftEngine **26.9.1** JAR SHA 为 `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f`；Paper **1.21.11 build132**、Java21。旧 `g` 目录的 Minecraft1.21.1兼容构建及其他任务的历史 PASS 均未作为本次验收输入。

| 当前输入 | SHA-256 |
| --- | --- |
| Cookery JAR | `fc81eaf8b3769b93f00c6781086936807fa4d5ced2a5659e27947527d010a0d5` |
| 厨房资源 ZIP | `0385c35b62dd7a6869b8466ddf85ef6d7b9732e826860c61550309d5e9dd03e0` |
| Tavern JAR | `205b36165b519106cda696f0d48ab91e031b6b90ba6bef8ee0d2202155948033` |
| Deco JAR | `1db18976692f5de6b284b31cfc5ef40e14d60f86925f75e87fe7dc3cf807f263` |
| Music JAR | `983fb4b88b39bb5211e12ead23d3202222624e114e2ee4137f48d49d434b1078` |
| 0.3.0 SenLuo 测试夹具 | `4d05e2d53f8a28b5f2c8e829fea66698e84846a95ea1545cae80779f96291870` |

可复现工具见 [senluo-fixture/README.md](../../tools/senluo-fixture/README.md)。本次独立运行于 `senluo-final-04`、localhost25593、新世界及新 StarCore/StarWorld 数据库；未操作森罗原服务器、世界、数据库或 Java 进程。只复制已结束构建的静态 JAR/ZIP、配置和依赖缓存；没有导入国家、城市、国库、玩家或旧数据库。旧厨房资源保留在加载目录之外，新厨房的 configuration/resourcepack 来自同一 ZIP；CE 55 个 internal 文件逐项与相同 CE JAR 匹配。

本次目录为 `senluo-acceptance/2026-11-20T02-27-43-354Z`。`PREPARATION.json` SHA 为 `873d0ab4936e1d324765419c312ebf996c9dfc110480fda94cac245379649d39`，其内容与哈希随逐阶段证据保存；上游候选来源还记录已结束构建日志、生产源码未改与原资源物品定义。Cookery JAR 未内嵌源码提交，本次由来源审计与实际载入行为共同界定验证对象，未声称可重复构建证明。

## 实际观察

| 阶段 | 通过依据 |
| --- | --- |
| 1. 可选插件和内容 | CE与四森罗插件均启用，包子、油、成品葡萄酒和乐器真实定义可构造并精确匹配，橡木椅定义存在 |
| 2. 核心与听众 | 经StarCore公开API建立国家、城市和双层领土，两名真实协议客户端属于同城 |
| 3. 实物入库 | 夹具将4包子、1油、1成品酒、64面包、8木板放入真实玩家背包；逐笔 `/sw deposit` 后背包对应数量归零、仓库精确入账 |
| 4. Cookery宴会 | 厨师实际消耗4包子+1油，城市文化0→25，临时产业倍率1→1.08 |
| 5. Tavern宴会 | 酿酒师消耗 `kaleidoscope_tavern:wine` 1份+4面包，文化25→45；它是成品ID，不是 `barrel/wine` 配方ID |
| 6. Deco登记 | 公共CE API实际放置橡木椅，玩家视线经CE rayTrace取得相同UUID/ID；真实登记扣4木板，登记本身不奖励文化 |
| 7. 家具维护 | 下一有效周期实际扣1面包，文化45→46，当期装饰产业加成0.5% |
| 8. 撤领停贡 | 公开NationService取消家具区块国家领土，家具仍有效；连续两周期库存和文化均不变化 |
| 9. 复领恢复 | 公共核心重新认领后，同一家具继续扣1面包并只增加1文化，文化46→47 |
| 10. 实际拆除 | 经CE公共remove拆除家具后，连续两周期不再扣面包或贡献文化 |
| 11. 受控演奏 | 手持真实 `musicplus:handheld_instrument`，八格内有真实同城听众；14个有效音符、5种音高，实际68624毫秒，消耗8面包并增加20文化（47→67） |
| 12. 完成重放 | 同一演奏会再次finish，文化与所有库存保持不变 |
| 13. 首次关服 | 玩家退出、Paper正常关闭并提交状态 |
| 14. 重启恢复 | 相同城市、乐师职业、文化67和完整库存恢复；再finish原会话仍不重复收费或奖励 |
| 15. 第二次关服 | 第二个Paper进程正常退出 |

种子物品均明确标为测试投入，不能当作产业产量。每笔仓库信用仍须经过真实物理背包转移。GUI、无初始投入的基础生产链及完整国战扩展另由对应套件验证。

## 隔离设置与失败历史

仅隔离服将职业切换冷却从6周期调为1周期、生产周期设10秒、随机事件概率设0；演奏默认60秒和音符/听众约束保持。CE预先加载配置、关闭资源文件内存缓存、分配8000服务端方块槽，不发送客户端资源包。JVM使用768MiB堆、512KiB栈、单逻辑CPU、SerialGC/C1、64MiB code cache、384MiB metaspace，关闭仅此隔离服Spark采样。Node 使用 `--max-old-space-size=128 --max-semi-space-size=4`，实际参数和版本保留在 `testTuning`。启动前物理可用内存 2,352,390,144 bytes、可用提交量 2,339,909,632 bytes，预检随准备记录保存；未停止其他服务器或改变系统全局配置。

0.2.0 的 CE04/CE05 启动中断、宿主内存不足、错误 CE 转换重载与 Tavern 配方 ID、遗漏 internal 文件和简化 place 入口等历史失败，保留在[历史报告](senluo-acceptance-0.2.0.md)。当前源代码经过 232 项完整自动化测试，本次当前候选首次实际运行 15/15 通过；没有将旧通过数、旧 JAR 或旧运行替作当前证据。测试夹具通过公开 `place(Location,Key,"ground",false)` 放置椅子，没有放宽 StarWorld 的家具身份、距离或领土验证。

家具登记最终使用CE公开 `rayTrace(Player,double)`，六格内命中后通过 `getBukkitEntity()` 回查元数据实体，核对相同UUID/ID、成员和双重领土。每期继续验证原位置、已加载区块及当前归属；不扫描实体或加载区块。

## 验证边界

本次完成的是 StarWorld 与实际已加载 CE/森罗内容的服务器行为验收。新上游三作物的生长/采收/保护/重载仍属独立验收范围，不因包子和油的消费通过而改为通过。上游 MusicPlus 没有公开完成 API，演奏是 StarWorld 自己的受控现场活动；未自动监听 `/kmusic`。声音调用已实际执行，但协议客户端不证明真人听觉、资源包 GPU 画面、座位/碰撞体验、CE 热重载、所有上游家具或 Folia 兼容。
