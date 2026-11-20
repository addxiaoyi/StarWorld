# 测试结果：StarWorld 0.2.0

## 最终构建与身份

执行 `.\mvnw.cmd -B --no-transfer-progress clean verify`，Microsoft OpenJDK **21.0.13.1+2**、Maven **3.9.11**，构建完成于 **2026-11-20 01:25:29 Asia/Shanghai**，BUILD SUCCESS。

`target/starworld-0.2.0.jar` SHA-256：

```text
3940fdf96c82bcb25be75a30d034615047d23545d48781ea290e4dfcafd654f6
```

源码 JAR 与当前全部 Java 源码逐字节一致。主插件 ZIP CRC 正常，包含插件描述符、六份内置资源、SQLite JDBC 及原生库；没有打包 StarCore 类、验收夹具、测试替身或测试类。`life.yml` 由完整默认值首次生成，配置示例随包交付。

## 自动化测试

**172 项，0 失败、0 错误、0 跳过。** 20 组完整结果：

| 测试组 | 数量 |
| --- | ---: |
| ConfigLoaderTest | 9 |
| SimulationTest | 13 |
| EvolutionServiceTest | 9 |
| NationalEvolutionTest | 10 |
| CustomItemsReflectionTest | 3 |
| CustomItemsTest | 6 |
| ResourceOutboxTest | 10 |
| StarCoreBridgeTest | 12 |
| StarCoreCombatContributorTest | 6 |
| StarCoreExtensionsTest | 8 |
| LifeDefinitionsTest | 3 |
| LifePlatformTest | 6 |
| LifeServiceTest | 16 |
| LogisticsConfigTest | 2 |
| LogisticsControllerTest | 3 |
| LogisticsServiceTest | 18 |
| WorldStoreTest | 14 |
| ExpansionRuntimeTest | 6 |
| WorldRuntimeTest | 11 |
| CityMenuTest | 7 |

机器摘要：[unit-tests.json](../evidence/history/0.2.0/unit-tests.json)。原始 Surefire XML/文本与 JaCoCo HTML/XML 分别位于 `target/surefire-reports/`、`target/site/jacoco/`，由证据脚本核对当前JAR、逐组计数和覆盖率后收录到证据包。

覆盖的关键约束包括：实际 SQLite 多连接竞争、事务失败全回滚、独立子进程 Runtime.halt 后 WAL 恢复、旧库与配置迁移、高版本拒绝、同周期/同请求去重、物品隔离凭据、共享地区恢复一次、贸易双向托管与共享运力、战争/归属变化、国家出口失ACK和稳定回执、迟到生活与贸易贡献恰好消费一次、国家专长条件和已解散城市持久排除。周期长度变更保持逻辑 period 与事件时间线；容量下降保留既有物品并允许取出，凭据退款不制造额外资源。

平台权限、GUI、核心公开API及CE物品/家具边界有 Mockito 契约测试；它们不能替代真实服务器验收。JaCoCo 行覆盖为1800/2333，分支覆盖为1611/2993，覆盖数字不作为功能已验证的替代证据。

## 当前构建的实服回归状态

本次修复精确绑定 `ItemStackUtils.getBukkitStack(CE Item)`，并将 Tavern 默认消耗改为真实成品 `kaleidoscope_tavern:wine`。当前构建已通过172项自动化测试、20阶段国战扩展、15阶段森罗联调、15阶段基础玩法，以及无依赖两次启动/SQLite恢复冒烟。以下所有当前证据均对应上文同一JAR哈希。

## 最终构建的真实 Paper / StarCore 扩展验收

**20/20 阶段全部通过**，实际时间 **2026-11-20 07:51:15–07:56:25 Asia/Shanghai**。机器证据：[expansion-client.json](../evidence/history/0.2.0/expansion-client.json)，其中每一步均为passed，实际StarWorld哈希与上文最终构建一致。使用 Paper **1.21.11 build132**、Java21、真实 StarCore `f769e62799e769a18fe51305b545869a17f80cd1`，无核心服务替身。

StarCore JAR SHA-256 为 `953f926f12384e0cfafae6f55d266a10c7156468e7e28be75518c3f7b413884b`；出处见[构建来源](../../tools/paper-fixture/starcore-build-provenance.json)。测试只通过核心公开服务建立国家/城市/双层领土、军团、战争与资源事务，不直接修改权威表。

20阶段覆盖两国两城、各四座维护设施、知识/军需/容量、规划/文明/三级升级、国家发展门槛、厨师宴会、国家专长实际增产、真实军团作战扩展、道路/跨国商路、双方实物托管、按地形运时交付和文化传播、资源出口与同事务重放、撤领封锁、真实WarService宣战封锁、停战续运、两次正常关闭及重启恢复。最后通过公开 `CityService.deleteCity` 解散城市，等待正常有界扫描，核对国家知识和全部倾向归零、专长选择保留但加成停止；城市设施/库存/历史保留且跨期不再产出。

实际观察：三级农场厨师宴会前后小麦 **78→81**，国家农业专长前后 **82→91**。国家出口把真实 `agricultural` 从0增至8，同事务重放保持8，重启后仍有相同持久回执。军队计算包含 `starworld:city_readiness` 来源和受限军需修正；完整输入/输出见机器证据。

隔离配置明确使用十秒周期、关闭随机事件、零认领/宣战费用与新国家保护、固定锚点群系；夹具只保活十个测试区块，不属于插件加载策略。所有投入均标记为fixture seed，物品必须经过真实玩家背包存入，不能将投入算作产量。每次新世界/新DB，配置和JAR指纹写入机器证据。

## 森罗真实联调

**15/15阶段全部通过**，实际时间 **2026-11-20 07:46:44–07:51:13 Asia/Shanghai**，实际StarWorld JAR哈希与上文一致。机器证据：[senluo-client.json](../evidence/history/0.2.0/senluo-client.json)。固定最新审计 `SenLuo_Plugin@6a432fe54097a3ebf22ba5757e016b602c711f28`，Cookery/Tavern/Deco/Music候选及原始内容包逐个匹配报告哈希，在真实CE26.9.1/Paper132/Java21独立隔离服验证；没有转引上游PASS。

真实背包物品经玩家命令入库；Cookery宴会消耗4包子+1油并增加25文化，Tavern消耗成品葡萄酒+4面包并增加20文化。真实橡木椅登记扣4木板，维护每期扣1面包并加1文化；撤领连续两周期不收费/不贡献，复领后同家具恢复一次，实际拆除后停止。真实森罗乐器与同城听众完成67732毫秒、14音符/5音高受控演奏，扣8面包、文化47→67；重复完成和重启后的再次完成均不重复奖励，完整库存与职业恢复。两次Paper正常关闭。

完整15阶段、独立配置、CE内部映射补齐和测试夹具 `ground,false` 放置入口修正、此前失败与执行中断均见[森罗验收](senluo-acceptance-0.2.0.md)。这些工具/环境修正未修改上游权威数据或放宽产品验证。资源包画面、真人听觉、热重载和Folia仍未验收。

## 最终构建的基础玩法回归

**15/15阶段全部通过**，实际时间 **2026-11-20 07:56:33–07:58:53 Asia/Shanghai**，JAR与上文同一SHA。机器证据：[baseline-client.json](../evidence/history/0.2.0/baseline-client.json)。全新世界/城市/库存，无初始生产物资，真实玩家视线命令登记农场、林场、工坊后，实际周期形成粮食、木板和军工箭三链。取4箭进入客户端背包、存回后背包归零；GUI Shift不能抽走展示物，普通创始人不能触发管理员事件；16普通小麦与7命名小麦的存入流程保留特殊物品。

真实国家撤领使工坊连续两个周期停产，复领后恢复；管理员干旱事件实际持久化。正常关闭并重启后，同一核心城市、设施/仓储/结算账本、完整事件时间线和背包状态恢复，已存物品没有重放。两次Paper正常退出。周期10秒、随机事件0、干旱12周期和2周期余波均为隔离测试配置，原始指纹在机器证据中。

## 无依赖独立安装与恢复

**两次启动全部通过**，实际时间 **2026-11-20 07:58:53–08:00:13 Asia/Shanghai**。全新隔离Paper132目录只放最终StarWorld JAR，缺StarCore/CE/四森罗插件；成功加载、生成配置与SQLite、执行控制台status并明确降级，两次均正常关闭。第一次关闭后仅在本插件DB插入唯一测试审计标记，重启后只读核对schema2及该标记恰好一条；这是持久化探针，不是游戏资源种子。机器证据：[no-dependency-smoke.json](../evidence/history/0.2.0/no-dependency-smoke.json)。两份原始日志和SHA收录于证据包，复现工具为[smoke-paper.ps1](../../tools/smoke-paper.ps1)。缺核心时不创建替代国家，城市玩法等待真实StarCore。

## 第一阶段历史证据

[evidence/paper-client.json](../evidence/history/0.2.0/paper-client.json) 保留 **0.1.0** 的15阶段真实生产/背包/GUI/事件/撤领/重启验收，其 JAR SHA 为 `7e962b132c474519fea4f7d2d215ae07766baf6e3e2b1a48b0f16ccec9da6040`。它不是本次0.2.0最终产物证明。旧版无依赖两次启动冒烟同样只算历史记录。

## 验证边界

Folia完成架构和调度规划，当前因StarCore可变模型缺区域线程契约而明确停用；没有声明Folia可运行。物流采用有限显式节点，不自动识别整图道路或航道。战斗通过核心公共army_combat贡献点接入真实军队计算，核心最高伤害聚合规则可能覆盖减伤；不影响玩家PVP或接管核心补给。

MusicPlus无公开完成API；受控演奏验证StarWorld自己的时长、音高、听众和真实声音调用，不能视为自动监听上游 `/kmusic`。协议客户端不证明资源包GPU画面、真人听觉、CE热重载或所有上游家具玩法。大规模城市性能仍需服主实际负载基线。

主shade仅有标准 `META-INF/MANIFEST.MF` 重叠提示；测试夹具有泛型/注解处理提示。曾因宿主机内存压力使验收夹具编译JVM native allocation失败，原诊断保留在独立工具目录；降低仅本任务编译堆/C1后成功，没有停止其他服务器，也没有把失败冒充通过。
