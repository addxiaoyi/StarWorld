# 城市生活需求（1.0）

生活需求把城市的真实物资和已完成生活活动连接到本地城市发展。它复用 `LifeService` 的已提交 `life_action` 与本地 `evolution_city`，不创建国家核心、货币或 StarCore 资源结算，不扫描区块和实体。

| 需求 | 默认仓库成本 | 请求后必须完成的生活活动 | 默认有效期/冷却窗口 | 本地奖励 |
| --- | --- | --- | --- | --- |
| `community_meals` 社区聚餐 | 小麦 16、面包 4 | 1 次已提交宴会 | 12 / 24 周期 | 稳定 +2、繁荣 +1 |
| `public_spaces` 公共空间 | 橡木板 16 | 2 件不同真实家具的已维护贡献 | 24 / 48 周期 | 稳定 +1、繁荣 +3 |
| `festival` 城市节庆 | 纸 8、面包 8 | 1 次已完成现场演奏 | 18 / 36 周期 | 稳定 +2、繁荣 +2 |

上述物资在领取需求奖励时另行扣除；宴会食材、家具登记/维护和演奏补给仍由原有 LifeService 扣除。需求创建不扣库存。奖励只修改 StarWorld 本地稳定与繁荣，单次分别不超过 2 / 3，最终不超过 100；回执记录实际增加量，达到上限时可以为 0。

## API 与调用顺序

```java
LifeService life = new LifeService(store, lifeDefinitions);
life.initialize();
EvolutionService evolution = new EvolutionService(store, definitions, evolutionDefinitions);
CivicNeedsDefinitions.writeDefaults(new File(dataDirectory, "civic-needs.yml"));
CivicNeedsService civic = new CivicNeedsService(store, CivicNeedsDefinitions.load(configFile));
civic.initialize();

// Existing owning-thread StarCore adapter must first authorize the player and refresh city authority.
// Only immutable CoreCity and actor values cross to the serialized storage worker.
Request request = civic.request(requestId, currentCoreCity, "community_meals", period, actor);
Receipt result = civic.complete(request.id(), currentCoreCity, currentPeriod, actor);
List<Request> page = civic.requests(cityId, 16, 0);
List<History> history = civic.history(cityId, 16, 0);
Optional<Request> saved = civic.request(requestId);
```

`Request`、`Receipt` 和 `History` 位于 `CivicNeedsState`。`Receipt.claimedActionUUIDs()`、`paidItems()` 为不可变集合，`actualStability()` / `actualProsperity()` 记录首次完成时实际奖励，`applied()` 只有首次提交为 true。重试同一已完成需求返回原支付与奖励记录，不再扣料、不再增加城市数值，但仍要求有效的当前城市/国家授权和不倒退的当前周期。

`request(UUID, CoreCity, type, period, actor)` 对请求 UUID 和原始 city / nation / type / period / actor 参数严格幂等。重复 UUID 改参数会失败。新请求/完成周期不得落后于已提交的城市、生活或 civic 时钟，不能倒填旧周期来挪用预做活动。每个城市最多一个开放需求。定义、物资、活动种类、数量、奖励和有效期全部保存快照，重载配置不会改变已发出的需求。读取接口严格检查页大小与偏移上限。

冷却按绝对的半开时间窗口判断。一个周期 `p`、冷却长度 `c` 的请求保存原始 `bucket=floor(p/c)` 与 `c`，其冻结窗口为 `[bucket*c,(bucket+1)*c)`。新请求必须与同城、同国、同种需求的所有既有冻结窗口不重叠；同时使用新配置所决定的窗口，不能通过缩短或延长 `cooldown-periods` 在旧窗口内再次请求。相邻窗口端点相等可通过。例如旧请求在周期24、冷却24，窗口为 `[24,48)`；改成冷却48后，周期48的 `[48,96)` 可以正常发起，虽然两次 bucket 都是1。相反，旧 `[0,48)` 还未结束时改成冷却12，不会在 `[36,48)` 内多发一单；已有 `[60,72)` 时，新配置48的 `[48,96)` 也会拒绝，直到周期96进入下个不相交窗口。

重叠查询使用 `(city,nation,type,requested)` 索引。配置冷却上限为240周期，候选只需读取 `newWindowStart-240 < requested < newWindowEnd` 内的同类记录，再比较各自冻结窗口；不会因历史增长扫描全服请求。并发检查和请求插入处于同一个串行数据库事务，城市开放请求仍有唯一索引兜底。

## 真实凭据、事务与恢复

完成只查询数据库内已提交的 `LifeService.life_action`；没有接受调用方 `ContributionReceipt` 的 API。候选必须 city / nation / kind 匹配、`eligible=1`、`culture>0`，且 `requestedPeriod+1 <= action.period <= min(expiryPeriod,currentPeriod)`。旧活动、同周期预做、未来活动、转国后失效贡献都不能领取需求奖励。演奏还要求对应 `life_concert` 已为 `completed`，未完成、取消或仅开始的演奏不合格。

公共空间按 furniture UUID 作为 source 去重，重复维护同一家具不能冒充两件家具。登记家具没有文化凭据，不能满足需求。运行时在新请求分配前检查 CraftEngine 家具 API、`life.yml` 至少一项允许的真实家具定义以及登记/维护材料是否已加载；仅安装 CraftEngine 或同名物品不能替代家具定义。社区聚餐要求至少一条配方的食材和职业可用，城市节庆要求演奏补给、可选职业和至少一种乐器可用。缺少某条生产链时明确阻止新需求占位，已有请求、凭据和可读取的完成回执仍保留；原版聚餐和音符盒路径保持可用。家具的实体身份、位置和领土验证继续由既有 `LifePlatform` 执行。

候选通过新增索引 `life_action(city,nation,kind,period,id)` 限定城市、国家、种类及最多 24 周期区间，SQL `LIMIT` 只返回需求所需的 1–4 条。公共空间使用同一区间内的关联索引查询选择每件家具的首条未领取记录，不遍历全部历史。完成在 WorldStore 的同一 SQLite 事务内检查快照物资、扣减实际库存、插入全局 action UUID 唯一领取墓碑、更新小额本地奖励、完成需求与历史。任一步失败，所有变化回滚；也可加入调用方已有的 WorldStore 事务。

模块在 WorldStore 事务内加独立 SQLite SAVEPOINT，即使外层调用方捕获完成失败并继续提交其他操作，也不会保留这次失败的部分扣料或奖励。外层整体回滚也会回滚已完成的需求。

`civic_action_claim.action` 是全局主键，且故意不外键依赖可归档的 life_action：重启、重试，甚至历史记录被清理后，原动作 UUID 都保持已花费。不要删除这些墓碑，也不要把其当成可重建缓存。`limits.history-limit` 只保留每城最近 200 条展示历史，可配 10–2,000；裁剪与写入同事务，永久请求与领取墓碑不会被删，历史裁剪不能重新领取。新表由独立 `civic_schema` 版本2控制，遇到更高版本直接失败并保留数据。已有 WorldStore、LifeService、EvolutionService 表结构和版本不变，仅添加候选索引。

旧版 civic schema 1 的 `(city,nation,type,bucket)` 唯一约束只比较相对桶编号，无法处理冷却长度变更。schema 1→2 迁移只重建请求表和引用它的三张子表，移除这一个旧唯一约束；原请求的 bucket、冷却、定义快照、状态、奖励、成本、已领取动作及历史 ID 均按原值复制，展示历史的 AUTOINCREMENT 高水位也保留。迁移从不关闭外键检查，始终在同一 SQLite 事务及 SAVEPOINT 内执行；复制完成后检查外键，任一错误回滚表结构、数据和版本，不留下半迁移。`civic-needs-version` 配置版本仍为1，无需覆盖已有管理员文件。

系统没有离线补进度任务。超过到期周期不能完成；下一次创建需求时把旧开放需求记为 EXPIRED。换国后的旧需求不允许完成，新的有效国家请求会把旧开放需求记为 INVALIDATED。调用方仍负责按现有 StarCore 公开 API 持续刷新有效城市与国家归属；本模块不把传入 CoreCity 自动写回权威快照，不绕过上层权限检查。

## 配置与验证范围

`src/main/resources/civic-needs.yml` 是独立、只在缺失时安装的版本 1 配置。严格拒绝未知/缺失字段、未知需求、错误活动类型、非布尔去重、分数数量、非法命名空间物品、无成本需求、超过 4 个活动、少于两件的公共空间、12–24 之外的有效期和超额奖励。自定义 CraftEngine 材料可直接使用合法 namespaced ID；上层应把 `itemIds()` 加入已有可用物品检查。

`src/test/java/dev/starworld/civic` 覆盖真实 LifeService 宴会、通过 LifePlatform 身份检查产生的家具维护证据、通过实际声音/同城市听众检查产生的演奏音符、重复凭据、未来/同周期/错误国家/城市/种类/失效记录、库存不足、事务后段与外层回滚、重启、配置快照、单城市开放上限、冷却窗口、真实奖励封顶、并发领取、领取墓碑及有界索引读取。正向生活票据不由 SQL 伪造；SQL 修改仅用于构造负面损坏/回滚和初始数值边界。

`CivicCooldownMigrationTest` 专门覆盖冷却改短/改长后的重启、相同桶编号但不相交的绝对窗口、长窗口包住既有短窗口的拒绝、真实已完成与开放 v1 请求迁移、成本/领取墓碑/历史 ID/序列高水位保留、中途复制失败后的完整回滚以及索引查询计划。新增用例通过状态以最终同 JAR 的 [测试报告](test-results.md) 为准。

模块已接入主插件启动、公开只读 API、`/sw civic` 命令和城市治理 GUI。新请求在预览页显示实际成本与所需活动，明确确认后才分配；完成仍须再次确认并由服务端选择真实凭据。最终构建的自动化与实服验证结果见[测试报告](test-results.md)和[森罗联调](senluo-acceptance.md)；源码变更后的构建验证由串行集成流程执行。
