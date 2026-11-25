# StarCore 扩展快照与国家资源出口

本扩展继续以审计提交 `f769e62799e769a18fe51305b545869a17f80cd1` 为依据。StarWorld 不直接修改 StarCore 表，不复制国家/科技/政策/战争/外交/天气/时代权威。

## 不可变边界

`CoreExtensionPort` 与 `StarCoreExtensions` 独立于现有城市 `CoreBridge`。**所有 `StarCoreExtensions` 调用必须在 Paper 主线程**。适配器每次从公开 ServicesManager/StarCoreApi 服务注册表解析服务，只调用公开方法；调用结束后仅返回 StarWorld 的不可变记录，绝不把 Nation、City、Factory 或可变服务对象送入 IO 线程。

```java
// Paper main thread
Optional<NationSnapshot> state = extensions.snapshot(nationUuid, Set.of(tradingPartnerUuid));
// Safe to pass state to the single storage worker after capture.
```

`NationSnapshot` 包括国家身份、捕获时间、国家天气与资源倍率、纪元时间线、已解锁科技及效果、当前政策及分域效果、抽象国家库存、繁荣值、活跃战争对手、指定贸易伙伴的外交关系、已有加工配方与工厂数。集合均防御复制。单次外交查询最多 100 国，加工配方快照最多 1000 条。

可选模块读取失败采用明确的 `capabilities` 原因与空值：天气 `unknown`、纪元 `null`、其他集合空。**空战争对手只有在 war capability=available 时才证明和平**；缺失外交时不能把空关系当作允许贸易。国家身份本身不存在或主线程条件不满足则整个快照 empty。

| 权威能力 | 实际公开调用 | StarWorld 语义 |
|---|---|---|
| 战争 | `WarService.activeWarsOf(NationId)`，`WarSnapshot.left/right/ended` | 复制有效对手 UUID，供补给/运输决策；不自行宣战或改战争对象 |
| 外交 | `DiplomacyRelationService.relationBetween(NationId,NationId)` | 精确保存 `NEUTRAL/FRIENDLY/ALLIED/HOSTILE/WAR/CEASE_FIRE/VASSAL`；下游自行配置贸易限制 |
| 天气 | `WeatherControlService.getNationWeather`、`getResourceModifiers(WeatherType)` | 已有国家天气与其抽象资源倍率，绝不把自定义灾害当成另一份世界天气权威 |
| 纪元 | `EpochService.snapshot()` | enabled/epochNumber/epochStart/nextEpochStart，不另建纪元轮换 |
| 科技 | `TechnologyService.unlockedTechnologies/getTotalModifier` | 复制真实已解锁 ID 与效果；未知 effect 由核心返回中性值 |
| 政策 | `PolicyService.activePolicy/activePolicyModifier` | GLOBAL + 对应 PRODUCTION/TRADE/MILITARY scope；不把生产scope误用于军事 |
| 繁荣 | `ProsperityService.getProsperityValue` | 展示/决策读数，不重复维护国家繁荣表 |
| 加工 | `ProcessingService.getAllRecipes/getFactories` | 复用已有配方定义/国家工厂，StarWorld 城市地理产业不成为第二份国家工厂权威 |

本次再次按类名和注册点核验：真实注册的是 **`module.resource.ProcessingService`**，没有已注册的 `ProductionService` 或 `ResourceProcessingService`。不能依据设计文档里不精确的类名生成虚假集成。

## 国家资源事务端口

`NationResourceTransactionService` 由 ResourceModule 注册，其真实公开请求为：

```java
TransactionRequest(String transactionId, NationId nationId,
                   Map<String,Long> resources, Category category, String description)
```

审计基线 `ResourceModule` 实际注册七个资源 ID：`mineral/agricultural/energy/luxury/industrial/chemical/strategic`。它们表示国家资源大类，不是实体物品 ID，也不是legacy配置里的具体商品名。`Category.OTHER` 则是事务用途分类，与资源 ID 分开。默认出口将真实小麦/铁锭/武器物品按显式配方兑换为 `agricultural/industrial/strategic`；每次执行仍由当前 `ResourceService.availableResourceTypes()` 验证，适应自定义部署。`StarCoreExtensions.deliver`：

1. 先查询相同 `transactionId` 的 receipt。已有匹配回执直接确认，不再 grant。
2. 无回执时验证目标国家仍存在、所有分类仍被 `ResourceService.availableResourceTypes()` 支持。
3. 用 `Category.OTHER` 调用幂等 grant，始终传入相同事务 ID、国家与资源数量。
4. 再查 receipt，核对事务 ID、国家 UUID、完整资源 map、operation=`GRANT`、category=`OTHER`。仅匹配的持久回执可 ACK。
5. `APPLIED` 但查不到 receipt 仍是 RETRY，不能仅凭方法返回成功标记本地完成。回执冲突是 CONFLICT，缺 API/异常是 RETRY，明确拒绝是 REJECTED。

`receipt(id)` 在服务不可用/错误时抛异常，不把“不知道”伪装成“无回执”。grant 抛错后可能已经提交，下一次从 receipt 重新核对即可。核心实现将库存与回执一起 flushState，并在持久化失败时回滚内存变更；不使用无事务 ID 的旧 `ResourceService.grant`。

## StarWorld 本地 outbox

`ResourceOutbox` 只访问 StarWorld 自有数据库。构造器通过事务创建 `extension_meta`（持久安装 namespace）与 `resource_outbox` 表/索引，不触碰 StarCore 存储。每安装持久 namespace + 稳定 businessKey 形成 `starworld:<namespace>:<nameUUID>`，避免两个独立服务器因 city/period 业务键碰撞。

```java
// Serialized IO worker. Authorization/core ownership must have been captured on main thread.
Entry e = outbox.enqueue("city/period/recipe", cityUuid, nationUuid,
    Map.of("minecraft:iron_ingot", 4L), Map.of("strategic", 2L), actorUuid.toString());

// IO: claim bounded work; no core calls here.
List<Dispatch> work = outbox.leaseDue(20, System.currentTimeMillis());
// Main: for each immutable dispatch:
Delivery result = extensions.deliver(dispatch.entry().request());
// IO: only a matching lease/result may change the local state.
outbox.finish(dispatch, result, System.currentTimeMillis());
```

`enqueue` 在**一个 SQLite 事务中**核对扩展城市所属国家、检查并预扣实体仓储成本、写 pending 与审计。重复相同 businessKey 与 payload 返回原行，不再扣库存；相同业务键不同 payload 拒绝。成本/资源 map 不得为空，数量为正且有界；未完成队列最多 10000 项，每轮租约最多 100 项。

状态为 `PENDING → IN_FLIGHT → ACKED / RETRY / REJECTED / REVIEW`。30 秒持久租约到期后可重取，沿用同 core transactionId；旧租约回调不能 ACK 或覆盖新租约。RETRY 指数退避至最多 300 秒。ACKED 永不再次发送。REJECTED/REVIEW 隔离，不自动退还本地成本。

管理恢复：`retry(id,actor)` 重新排队 RETRY/REJECTED/REVIEW 行，并审计；仍沿用原事务 ID 与 payload。`cancelUnsent(id,actor)` **只允许从未发送（attempts=0）的 PENDING 行**退款一次并 CANCELLED。任何曾发送行可能已经在核心提交，不能通过“本地失败”盲目退款，必须先恢复服务、核对同一回执后 ACK。接口没有绕过回执的强制 ACK/强制退款后门。

单线程 IO 对本地方法提供调用顺序；主线程只调用核心并接收不可变结果。不要在 IO 里调用 snapshot/deliver，也不要主线程 join JDBC future。外层 runtime 需要在传送/城市转国/领土变化时重新验证授权；已预扣 outbox 固定原国家，不能把重试请求改给新国家。

## 崩溃窗口

| 断点 | 重启后的结果 |
|---|---|
| 本地扣成本前/事务中 | 本地事务回滚，既无扣成本也无 outbox |
| 本地提交后、核心调用前 | 到期租约用原请求重发 |
| 核心提交后、本地 ACK 前 | 原 transactionId 命中核心 receipt，不重复 grant |
| 本地 ACK 后 | 不再进入 due 队列 |
| 旧调用迟到，新租约已存在 | 旧 token 被忽略，新调用继续核对同一回执 |
| 核心 API 不可用/receipt不可知 | 保留预扣与 RETRY，不发免费资源、不盲目退款 |
| 相同 ID 不同 receipt | REVIEW，暂停并审计 |

这依赖上游回执和库存共同持久化的真实契约；不会把模拟端口测试当作全部真服完成。测试既验证真实 SQLite 事务/重启/失 ACK，也用审计签名 fixture 验证反射端口。最终实际核心 JAR 真服回执测试由联合验收报告记录。

## 繁荣贡献与 Folia 仍需守住的边界

`modifyProsperity`、`recordEvent`、`addChunkContribution` 是公开 mutation，但没有业务 ID 与幂等 receipt。尤其 addChunkContribution 除累加区块贡献外，每调用一次还会 modifyProsperity；用“当前区块值相同”无法证明国家繁荣未重复加成。**本适配器不自动跨库重放繁荣贡献**，capabilities 明示原因。需要可靠完成回执后再增加对应 outbox；不能把资源事务的安全性推广到这些非幂等接口。

当前 StarCore 返回的 Nation/City/科技/政策模型仍是可变对象，其 scheduler 仍有 BukkitScheduler。捕获后的 `NationSnapshot` 安全跨线程，不代表捕获动作可在 Folia 全局/异步线程执行。本实现拒绝非 Paper 主线程调用，没有改 `folia-supported` 声明；正式 Folia 兼容需上游先公开安全快照/权限版本号，随后分区域验证建筑、实体调度背包和 GUI。
