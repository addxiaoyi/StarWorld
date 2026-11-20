# StarWorld 0.3.0 测试与交付结果

## 当前构建

Java21、Maven3.9.11执行 `./mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`。**232项测试，0失败、0错误、0跳过**；证据记录于2026-11-20T02:20:25.145845+00:00。当前安装用JAR SHA-256：

```text
15946f82e248d08cabba45f4389d23297a9e27f574468658faa7edf6426382e1
```

0.2.0交付与全部旧版观察保存在[历史报告](history/test-results-0.2.0.md)，不会作为当前JAR运行证明。源码JAR与工作树一致性、主插件不混入核心或测试替身、证据和发布包内容会由打包脚本核对。

## 自动化验证

| 测试组 | 数量 |
| --- | ---: |
| ConfigLoaderTest | 9 |
| SimulationTest | 13 |
| BorderResourceEventTest | 7 |
| CultureDynamicsTest | 5 |
| EvolutionServiceTest | 9 |
| NationalEvolutionTest | 10 |
| RegionAtlasTest | 3 |
| BorderConflictCaptureTest | 5 |
| CustomItemsReflectionTest | 3 |
| CustomItemsTest | 6 |
| ResourceOutboxTest | 10 |
| StarCoreBridgeTest | 14 |
| StarCoreCombatContributorTest | 6 |
| StarCoreExtensionsTest | 8 |
| StarCoreTreasuryTransfersTest | 6 |
| TreasuryMoneyTest | 3 |
| LifeDefinitionsTest | 3 |
| LifePlatformTest | 6 |
| LifeServiceTest | 16 |
| LogisticsConfigTest | 2 |
| LogisticsControllerTest | 3 |
| LogisticsServiceTest | 18 |
| TransportPlanningTest | 9 |
| MarketControllerTest | 4 |
| MarketServiceTest | 11 |
| LedgerMaintenanceTest | 3 |
| WorldScaleTest | 1 |
| WorldStoreTest | 14 |
| ExpansionRuntimeTest | 7 |
| WorldRuntimeTest | 11 |
| CityMenuTest | 7 |

机器摘要：[unit-tests.json](evidence/unit-tests.json)。Surefire原始XML/文本和JaCoCo HTML/XML由证据包收录。新增验证包括：地区只读分页/转国/解散，文化衰减和政策效果同结算回滚，双方确认改道、旧运力不退、paid货物不得当易货退款，核心国库原子转账及失ACK回执恢复，金额极端指数拒绝，禁用节点付款前阻断，有界队列公平性，无损审计压缩归档，以及真实邻国/关系/资源关联的边境事件和历史事务。

## 规模基线

在真实SQLite和实际纯生产路径中，1000城市、2000设施完成3000次结算，关闭数据库后重开并重放1000城市的同周期，库存不增加。结算耗时17226毫秒，有界分页查询64毫秒，数据库4276224字节。测试JVM堆256MiB，详细证据见[scale.json](evidence/scale.json)。这项基线不代表Paper世界负载/TPS、实体或跨区域Folia压力已验证。

## 当前JAR真实服务器验证

| 套件 | 当前结果 | 机器证据 |
| --- | --- | --- |
| 地区、文化、改道与金币市场 | 20/20阶段通过 | [progression-client.json](evidence/progression-client.json) |
| 国战扩展回归 | 20/20阶段通过 | [expansion-client.json](evidence/expansion-client.json) |
| 基础三链/背包/GUI回归 | 15/15阶段通过 | [baseline-client.json](evidence/baseline-client.json) |
| 最新森罗联调 | 15/15阶段通过 | [senluo-client.json](evidence/senluo-client.json) |
| 0.2升级与重启 | 7/7阶段通过 | [upgrade-client.json](evidence/upgrade-client.json) |
| 真实边境资源争端 | 11/11阶段通过 | [border-client.json](evidence/border-client.json) |
| 缺依赖独立安装 | 两次正常启动、SQLite持久标记恢复通过 | [机器证据](evidence/no-dependency-smoke.json) |

所有套件使用真实Paper1.21.11 build132/Java21。StarCore复用审计公开服务；森罗固定本轮最新c3642ae候选、CE26.9.1及原始匹配内容包。真实核心国库资金和客户端物品的fixture投入单独标记，不计为游戏生产；所有仓储信用仍经真实物理存取或已结算生产。仅隔离测试缩短周期/文化衰减等待并降低创建费用：常规回归关闭随机事件，边境专门套件则概率设1、只保留border条件规则，持续12期/余波2期，验证自然结算而非管理员触发。配置、调优与实际JAR指纹保留。

## 限度

StarCore本身仍未声明Folia支持，公开City/Nation可变集合缺少一致权限快照与区域线程契约；StarWorld保留正确调度边界和明确启动门禁。上游MusicPlus无公共完成API，当前演奏是使用真实乐器和现场听众的StarWorld受控活动。协议客户端不证明资源包GPU画面、真人听觉或全部上游家具/作物玩法。核心抽象资源市场、国家货币、国政与科技继续由StarCore管理；本插件实物市场复用幂等国库转账和真实物流，不创建金币。

## 实服观察与失败保留

进阶套件验证地图册GUI保护、政策真实扣费及文化增量、持久衰减、双方改道完整运时、真实国库付款、撤领/战争暂停和停战单次到货。国战扩展验证维护、知识、军需、国家专长、实际army_combat计算、双向贸易、资源outbox回执、重启及核心城市解散。基础套件从无初始生产物资形成三链，并核对真实背包存取、命名物品保护、GUI、越权、失地和事件重启。边境自然事件、最新森罗和0.2存档升级各自有独立套件。

旧构建和失败/中断运行未改标通过。进阶05在会话边界被环境终止；06发现验收工具读取多个公开Future跨周期，已在客户端有限重试直到城市/发展/地图册/汇总周期一致，保留时间推进与同周期无变化断言，07完整重跑通过。扩展10在宿主提交内存不足时原生分配失败，11的较低160MiB Metaspace在玩家登录时触及上限；基础08首轮13阶段通过但第二次启动时宿主原生内存提交失败。这些失败保留，后续独立新目录完整重测，最终成功套件的实际JVM/Node参数随原始证据记录。未停止其他项目服务器、未修改系统内存设置。

SQLite规模报告是存储与生产路径基线，不是Paper在线容量；所有真实套件运行环境、测试物资/资金投入、隔离调优和最终JAR哈希单列记录。交付结果不把测试投入算作游戏生产。
