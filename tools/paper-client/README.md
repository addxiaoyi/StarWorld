# 实际客户端联合验收

这是开发测试工具，不进入 StarWorld 插件 JAR。使用实际 Mineflayer 协议玩家连接 localhost 的 Paper 1.21.11，与真实 StarCore、StarWorld、[测试 helper](../paper-fixture/README.md) 共同验证。脚本不模拟核心服务、不写数据库、不下载文件、不更改配置、不替换已经运行的服务器。

在本目录安装 `package.json` 固定的 Mineflayer，或通过 `--client-deps` 指定外部已安装依赖的目录。要求 Node.js 22 以上。

```sh
npm install
node acceptance.cjs --help
node acceptance.cjs --java C:/jdk21/bin/java.exe --runtime C:/starworld-joint-test --paper C:/paper.jar
```

预先准备独立测试目录，含有效 Paper bootstrap 缓存、三个本地插件 JAR、已显式接受的 eula.txt，server.properties 绑定 `127.0.0.1`、offline模式、指定空闲端口。目录 `.starworld-smoke-runtime` 内容必须为 `StarWorld isolated Paper smoke runtime v1`，防误用现有生产目录。

使用新世界/新扩展数据库。StarWorld周期10秒、事件概率0；为验证重启后仍生效，测试定义将drought设duration12、aftermath2。仅隔离服把 StarCore 国家/认领费用设0或为玩家充值。配置与JAR哈希将记入evidence，测试调优不是默认生产配置。

脚本启动自己的 Java 子进程，等待真实启用，再创建 `StarWorldQA` 离线玩家。15阶段包括公共API造国/城/双领地、实际视线登记、无初始生产物资的三链、真实背包箭存取、GUI Shift、防越权、命名物品保护、撤领停产/恢复、事件与两次重启恢复。任何断言失败则保留证据并正常关闭自己创建的服务器；不操作其他进程。

结果在 `runtime/client-acceptance/<UTC timestamp>/evidence.json`，同目录有完整服务端和客户端日志。只有所有阶段状态均为passed才算成功。`latest.json` 指向最近一次结果，失败不会被重新标为成功。不要把 helper 派发命令或仅日志里出现插件名称当作玩法通过。

完整扩展使用独立的 `node expansion-acceptance.cjs --help`，参数相同。它使用 `StarWorldEA` 和 `StarWorldEB` 两个真实玩家、两个国家和两个城市，包含20阶段的设施维护/知识/军需/容量、国家实际发展倾向与创始人专长及生产倍率、职业与实际宴会扣费及农场产量、真实持久军团的公共作战扩展、双向实物托管运输、区域文化、核心国家资源回执、撤领和战争封锁、停战恢复、重启及公共核心城市删除后的聚合失效。该套件明确记录玩家物品和国库测试投入；未把 seed 当作产出。测试 helper 明确保活十个测试区块，StarWorld 本身不会加载区块。仅隔离配置关闭 `playability.new-nation-protection-days` 并把 `diplomacy.war.declaration-cost` 设零或公开充值后运行。结果位于 `runtime/expansion-acceptance/<UTC timestamp>`，格式与限制见 [evidence-schema.md](../../docs/evidence-schema.md)。原 `acceptance.cjs` 仍可直接执行原15阶段，亦向扩展套件提供公用服务器和客户端 harness。

0.3 进阶验收使用 `node progression-acceptance.cjs --help`，参数与上述套件相同。它要求新的隔离目录和最新 helper，使用 `StarWorldPA`、`StarWorldPB` 两位真实玩家，完成20阶段：已登记地区地图册及GUI保护、核心政策真实扣费与文化增长、持久文化衰减、三处道路节点的双商路、共享运力查询、双方接受改道、完成贸易的文化传播、实物金币挂牌与GUI保护、核心国库转账和回执重放、已付款货物撤领/战争暂停、重启恢复、停战交货一次和国家金币守恒。

仅在该隔离目录把 `evolution.yml` 的 `culture.decay-interval` 设为2，并保留默认500基点衰减与政策倍率；生产周期10秒、随机事件关闭、新国家保护关闭，认领/宣战费用按脚本帮助配置。玩家物品由显式helper种子投入并通过真实 `/sw deposit` 入库，国库测试充值通过公共核心服务完成；两者在证据中明确标记为测试投入。不得把已有世界或前一轮套件状态当作新输入。套件只关闭自己创建的两个服务端进程，完整20阶段、四份客户端和两份服务端日志保存在 `runtime/progression-acceptance/<UTC timestamp>`；证据包要求与最终JAR哈希一致且每阶段全部通过。

0.2 → 0.3 存档升级使用独立的 `upgrade-acceptance.cjs`，需要 Python 3 标准库的只读 SQLite 探针：

```sh
node upgrade-acceptance.cjs --help
node upgrade-acceptance.cjs --java C:/jdk21/bin/java.exe --paper C:/paper.jar --runtime C:/starworld-upgrade-copy --python C:/python/python.exe --client-deps C:/mineflayer-deps
```

输入必须是已通过原15阶段、正常停服并完成SQLite checkpoint的0.2隔离服完整副本，包含原世界、玩家数据、StarCore权威数据库、StarWorld数据库、六份已有配置及原helper。只在副本中换入0.3候选JAR并指定空闲localhost端口，不能指向原始验收目录或生产服。`UPGRADE-PROVENANCE.json` 必须记录 `sourceVersion=0.2.0`、`targetVersion=0.3.0`、两个版本的JAR哈希、原15阶段通过证据及哈希、复制前只读数据库快照、关键世界/玩家/数据库文件哈希和旧配置哈希。脚本在启动前逐项验证；不接受已升级数据库作为新一轮初始输入。

7阶段验证原国家/城市/三处设施及真实玩家背包恢复、六份配置保留与新地图册/市场可用、实际新生产周期、旧存取及支付回执保留、新增表初始化，以及再次正常重启后的状态与物品一致性。数据库仅通过 `mode=ro&immutable=1` 读取已停服且无非空WAL的文件；套件不建国、不seed、不调用仓库存取、不修改SQL值。新增库存必须落在实际新结算回执与配方输出上界内，以检测离线追补或重复结算。两次服务端与客户端完整日志和 `evidence.json` 保存在 `runtime/upgrade-acceptance/<UTC timestamp>`。

版本字段断言按数据来源区分：`sourceVersion` 与源证据JAR为0.2.0，而复制目录已经安装候选JAR，启动前读取其 `plugin.yml` 必须为0.3.0，旧数据库仍不含新增模块表。真实旧helper的 `inventory.items[].type` 使用 `minecraft:wheat` 等命名空间ID。首次运行误以枚举名 `WHEAT` 读取该字段而失败，日志实际显示命名小麦仍为7；工具已按原协议修正。失败证据保留为失败，重跑重新复制原停服0.2输入。

边境资源事件使用独立的 `npm run border -- --help` 或：

```sh
node border-acceptance.cjs --java C:/jdk21/bin/java.exe --paper C:/paper.jar --runtime C:/starworld-border-fresh --client-deps C:/mineflayer-deps
```

必须新建隔离目录、世界、数据库和 `StarWorldBA` / `StarWorldBB` 玩家。测试配置将周期设10秒、`event-chance=1`，保持完整 `definitions.yml`；`evolution.yml` 保留全部事件定义，仅 `event-rules.border_dispute: {condition: border, weight: 2, cooldown: 10}` 参与随机选择，并将该事件duration设12、aftermath设2，以跨过真实服务器重启的等待时间。国家保护关闭；由于同一对端需要停战后再次宣战，隔离核心 `war.declaration_cooldown_hours=0`。建国/认领/宣战费用设零或显式公开充值。全部调优、JAR和配置指纹写入证据；它们不是生产推荐值。

11阶段使用两个实际国家、城市及总计四处真实农场/林场，物品由明确标记的玩家背包投入并经 `/sw deposit` 入库。先验证远方战争连续结算无争端，再和平通过 `NationService.claimCurrentChunk` 在A资源地区四邻认领B国家领土，验证和平邻接仍不触发；随后通过真实 `WarService` 宣战，等待正常生产周期自然产生 `border_dispute`。历史必须含资源、正剩余量、对端、地区和相邻区块证据。所有候选均读取产品同一个 `ExpansionRuntime.captureBorderEvidence` 入口，不合成见证、不调用事件触发命令、不写SQL、不强加载区块。撤销相邻国家领土后，新见证消失，原事件与唯一历史在重启后保留；超过余波和冷却后继续远方战争仍不新增争端。

结果在 `runtime/border-acceptance/<UTC timestamp>`，包括两份服务端、`client-run-1-a/1-b/2-a/2-b.log` 四份客户端及全部阶段原始观察。每次失败完整保留；重跑必须重新准备干净输入，不能复用失败后的国家或数据库。

0.3 → 0.4 存档升级使用 `upgrade-04-acceptance.cjs`（`npm run upgrade04 -- ...`）。输入必须是已经通过0.3最终基础15阶段并正常停止的 `baseline-final-09` 副本；`UPGRADE-PROVENANCE.json` 保存源15阶段证据、0.3 JAR、世界/玩家/Core/StarWorld 数据库及七配置指纹。脚本要求候选 `plugin.yml` 为0.4.0，保留原国家、城市、三设施、四笔转移和命名物品，并用两次真实启动/关闭验证无离线补算。

升级只加法建立 `market_delivery_schema`、`market_delivery_clock`、`market_delivery_receipt`、`market_delivery_stat` 四张交付统计表；源0.3已有的市场 namespace/schema、文化表和所有空业务表必须逐字保留。新库不能虚构挂牌、付款、交付、统计或金币，数据库探针只读已停服 checkpoint 文件。

首次边境运行在玩家连接前遇到主机内存提交不足，Node原生退出，保存为失败并保留驱动stderr；后续测试限制Node堆128MiB、半空间4MiB和Paper堆512MiB。第二次运行发现旧helper的Gson省略null字段，工具对所有无事件字段统一按 `event ?? null` 读取；该次仍保留失败结果并从新目录重跑。未将这些失败重标为通过，也未降低玩法断言。

0.4 运维与经济反馈使用独立 `npm run operations -- --help`：

```sh
node operations-acceptance.cjs --java C:/jdk21/bin/java.exe --paper C:/paper.jar --runtime C:/starworld-operations-fresh --python C:/python/python.exe --client-deps C:/mineflayer-deps
```

它保留原进阶20阶段及严格断言，再加九阶段实际路线维护材料规划、只读查询不扣库存、付款未到货/易货不计市场到货、普通玩家拒绝备份、管理员在线一致快照、完成到货精确统计、同名快照替换和第三次真实启动后统计去重。`exp-operations` 只读取公开 `StarWorldApi.routePlans` 与 `marketDeliveryStats` Future，不主动结算或调用归集器；`exp-permissions` 只查询当前玩家实际权限。管理员权限只由隔离服控制台临时op，备份命令仍由真实玩家执行，随后deop。

`PREPARATION.json` 必须绑定0.4.0最终JAR与重建helper；新世界/数据库、localhost、七份配置、10秒周期/随机事件0/文化衰减间隔2以及明确测试投入规则与进阶套件相同。Python只以 `mode=ro&immutable=1` 打开在线备份完成后的独立快照，核对完整性、schema、库存、付款和到货凭据；不读写活跃数据库，不用SQL注入玩法状态。三个服务端与 `client-run-1-a/1-b/2-a/2-b/3-a/3-b.log` 六份客户端、逐阶段证据及快照指纹位于 `runtime/operations-acceptance/<UTC timestamp>`。第三次启动用于证明已完成到货统计不会因重启重复归集；仅两次启动时原流程只能证明暂停货物的零统计恢复。

0.5 → 1.0 升级以已经通过救灾套件全部22阶段且正常关闭的0.5世界作为输入。准备工具只复制数据并在副本安装1.0 JAR，验收工具也提供 `--check-only` 预检，不启动服务端：

```sh
python prepare-upgrade-10.py --source C:/accepted-disaster05 --runtime C:/upgrade10-fresh --jar C:/starworld-1.0.0.jar --fixture C:/StarWorldFixture.jar --port 25633
node upgrade-10-acceptance.cjs --java C:/jdk21/bin/java.exe --paper C:/paper.jar --runtime C:/upgrade10-fresh --python C:/python/python.exe --client-deps C:/mineflayer-deps
```

七个阶段覆盖两次正常启停。通过真实客户端读取原城市、设施和命名物品，打开新治理界面与旧市场，以原 UUID 重试已付款的救灾策略并核对同周期库存与历史。两次停服后只读核对 SQLite：两国两城、十设施、八笔仓储转移和三笔已付款救灾回执全部保留；工程和需求只加法创建表，不自动发起项目或认领凭据；新库存增量不超过实际在线结算产出。八份旧配置按 SHA-256 保持不变，两份自动生成的配置与 JAR 内文件一致。证据与两份服务端、两份客户端日志保存在 `runtime/upgrade-10-acceptance/<UTC timestamp>`。重跑请从同一个未改动的0.5原件准备新目录。
