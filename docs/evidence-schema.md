# 真实 Paper 验收证据

`tools/paper-client/expansion-acceptance.cjs` 在隔离服务器中启动两个真实离线协议客户端，使用实际 StarCore/StarWorld JAR。它将每次观察写入 `expansion-acceptance/<UTC时间>/evidence.json`，并保存两个服务器进程日志及两名玩家分别在重启前后的聊天日志。`latest.json` 只指向最近一次结果，失败运行也保留。

此文档说明证据格式；脚本存在、编译成功或 fixture 命令已派发均不等于验收通过。只能以实际运行产生的 `status=passed` 和全部步骤的观察值为依据。

## Evidence schema 2

| 字段 | 内容 |
|---|---|
| `schema` / `suite` | `2` / `real-paper-expansion` |
| `status` | `running`、`passed` 或 `failed`；清理失败也会使结果失败 |
| `startedAt` / `finishedAt` | 实际 UTC 时间 |
| `minecraft` / `mineflayer` / `node` | 客户端协议与运行时版本 |
| `paperSha256` / `pluginJars` | 实际加载的 Paper 及每个插件 JAR 的 SHA-256 |
| `configSha256` | StarWorld 六份配置及 StarCore 配置指纹 |
| `testTuning` | 十秒周期、关闭随机事件、新国家保护为零、有限测试区及锚点平原生物群系等隔离环境设置 |
| `seeds` | 明确的测试投入，包含玩家、物品和数量；真实军团测试使用公开国库 API 投入的资金也单独记录 |
| `steps` | 每一步名称、时间、状态与 `observed` 原始观察；失败保留错误栈 |
| `firstOrder` / `blockedOrder` | 两笔真实双向实物货单 UUID |
| `exportReceipt` | 真实核心持久回执，含稳定事务 ID、国家、资源 map、操作、分类与应用时间 |

`observed` 中的城市快照来自公开 `StarWorldApi.city`，发展、文化、生活、节点、商路、货单和军需状态由公开只读服务在 StarWorld 单线程 IO 工作器读取。国家快照在 Paper 主线程捕获，保留每个模块的 capabilities。`capacity` 由当前有效锚点和已提交维护回执计算。集合、UUID、Instant 等值转换成普通 JSON，不序列化任何可变 Bukkit 实体。

fixture 输出一行 `SW_FIXTURE_EXPANSION {schema:2,action,player,observed}`。客户端同时核对 `action` 与 `player`，不会把另一名玩家的异步响应当作当前结果。`SW_FIXTURE_FAILED` 会立即终止当前观察。

## 断言范围

验收创建两国两城，分别在区块 `20..24`、`52..56` 登记实际设施和道路端点。测试 helper 显式保活这十个测试区块，记录在 `testTuning.fixtureForceLoadedChunks`；此行为不属于 StarWorld 的区块加载策略。玩家通过真实 `deposit` 将显式 seed 物品存入仓库；所有 seed 均标为测试投入，不计作生产证据。注册使用真实传送后 raycast 和玩家命令，只有随后持久状态和成功消息同时出现才通过。

学院消耗纸张产生知识；兵营消耗实际箭矢产生军需；仓库只有付过维护费才扩容。职业限制先拒绝未入职宴会，再由厨师实际消耗八面包，产生文化和临时加成，并比较三级农场实际结算的小麦产出。双城商路检查真实距离和运输周期，offer/accept 分别扣双方物资，完成后双方各仅收到一次。已提交贸易还须产生对方文明的区域文化。

国家专长验收等待真实农场累积农业倾向、学院实际消耗纸张产生足够知识，核对国家聚合与单城实际指标一致；创始人命令选择农业专长后，下一周期实际小麦产量增加，并继续服从总倍率上限。重启验证国家选择、方向、知识和倾向不丢失，不用 seed 模拟国家分数。

全部重启恢复断言完成后，测试通过公开 `CityService.deleteCity(UUID)` 删除第一座真实核心城市，核对核心城市与玩家城市映射均消失。至少一个结算周期后，公开国家发展 API 必须去掉该国唯一城市的全部知识和倾向，并停止国家专长加成；公开 `StarWorldApi.city` 仍须保留历史建筑、库存与发展。再次跨过结算周期，历史城市的结算周期、库存和发展不得继续增加。fixture 不调用 StarWorld 的内部失效方法，也不直接修改任何数据库。

国家出口必须得到真实 `NationResourceTransactionService` 的 GRANT/OTHER 回执，默认粮食契约增加 `agricultural:8`。同一事务通过公开 `StarCoreExtensions.deliver` 重放时，核心库存前后必须相同，并且本地 outbox 为 ACKED。重启后检查同一回执及货单、文明、规划、知识、职业、文化和资源存续。核心存储将 Instant 按 epoch 毫秒持久化，重启前后严格比较交易载荷，应用时间只在这个真实存储精度上比较。

军事扩展通过公开 `ArmyService.createArmy` 付费创建真实、持久军团，再由 `ExtensionRegistry.execute("army_combat", CombatContext)` 执行真实扩展点。结果必须包含 `starworld:city_readiness` 来源及军需修饰，伤害必须受默认上限约束。此项证明实际军团计算使用城市军需；不声称 StarCore 原有军队维护会消费 StarWorld 出口资源。

物流封锁先真实取消国家领土而保留城市 claim，确认货物留在托管；恢复领土后经真实 WarService 宣战，确认战争/外交阻断且进度不变；公开停战后同一货单继续并只交付一次。fixture 不直接设置 WAR 关系，不写任何插件权威数据库，不读取私有字段或私有缓存。

这组独立环境验收使用原版 community 宴会，不能替代 CraftEngine/森罗 Cookery、Tavern、Deco、MusicPlus 已安装环境的实际家具、物品与声音测试；缺少这些插件时应明确降级。Folia 仍不在当前支持声明中。

## 0.3 进阶与升级套件

`progression-acceptance.cjs` 使用 schema 3、`suite=real-paper-progression`。两名协议玩家验证地区GUI保护、真实政策成本与文化增量、周期衰减、替代商路双方改道、国库原子付款与已付款货物的撤领/战争暂停和重启恢复；保存两份服务端、四份客户端日志。`configSha256` 包含新增 `market.yml`；测试物品和国库充值仍明确记作投入。

`upgrade-acceptance.cjs` 使用 schema 1、`suite=real-paper-upgrade-02-to-03`。输入必须是已经通过0.2验收且正常停止的存档副本，`UPGRADE-PROVENANCE.json` 保存原JAR、原证据、数据库、玩家文件与六份配置的指纹和只读SQLite快照。`beforeProvenance` 将它完整嵌入结果，禁止把原服路径作为目标。通过两个真实客户端/服务端启动，检查原国家城市、设施、物品转移凭据、特殊物品和旧配置保留，新模块只加法建表，正常新周期继续且无离线补算。只读SQLite探针在正常关闭且WAL已检查点后运行，不通过SQL修改玩法数据。

`border-acceptance.cjs` 使用 schema 1、`suite=real-paper-border-resource-dispute`，保存两份服务端及四份客户端日志。它用真实公开国家claim和宣战/停战调用验证远方战争及和平邻居两个阴性条件，邻国交战时由正常生产周期自然产生事件；不调用管理员事件命令或构造假边境证据。对端撤领后，经过原事件、余波和冷却结束仍只能保留一条历史。独立配置采用概率1、只保留border规则、持续12期/余波2期及重复宣战冷却0；全部定义、JAR、配置和Node/JVM内存调优均记录。常规回归保持各自的零随机概率设置。

`package-evidence.py` 逐项比对当前脚本阶段名、全部passed状态、最终插件SHA256及完整两次启动日志；当前机器摘要不得混入其他JAR证据。森罗和升级还必须保留源内容/升级准备文件，其他套件如有 `PREPARATION.json` 也一并收录。历史0.2及早期0.1观察保存在 `docs/evidence/history/`，不作为0.3通过证明。

1.10 `real-paper-third-country-transit`套件绑定三名普通客户端、三次正常启停、完整Core候选出处与准备目录哈希。阶段覆盖精确许可、旧版本拒绝、三方原子付款、撤销/旧批准重放、单一收款方余额不足时整笔退款冻结、恢复/重启、战争/失地/道路破坏、免费替代路线和货款统计。每次金融回放保存全部受益国的`balancesBefore/balancesAfter`。物资种子、国库注入/撤出及地形修改分别标记为测试干预，不当作生产收入或天然地理证据。
