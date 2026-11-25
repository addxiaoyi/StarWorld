# Java API 与事件

消费插件将StarWorld作为provided依赖，通过Bukkit ServicesManager查询 `dev.starworld.api.StarWorldApi`。公开查询不授予管理权限，不承诺实时领土有效；返回值是不可变快照，Future失败表示IO/状态错误。

```java
StarWorldApi world = Bukkit.getServicesManager().load(StarWorldApi.class);
if (world == null) return;
world.city(starCoreCityId).thenAccept(result -> {
    // Storage worker callback: schedule all Bukkit/player work separately.
    result.ifPresent(city -> logger.info(city.stock().toString()));
});
```

| 方法 | 结果与语义 |
| --- | --- |
| `version()` | 当前插件版本 |
| `definitions()` | 不可变地区/文明/设施/产业/事件配置 |
| `city(UUID)` | `Future<Optional<CityView>>`；已登记扩展城市、库存、设施、事件和最后报告 |
| `climate(UUID)` | `Future<Optional<ClimateReport>>`；按真实验证过的设施世界读取StarCore逐世界天气控制权及农业/矿井配方倍率；灌溉水网运行状态来自最近结算周期；查询不推进生产 |
| `development(UUID)` | `Future<Optional<Profile>>`；规划/专长、城市繁荣、稳定、知识、军需、文化、倾向 |
| `nationDevelopment(UUID)` | `Future<Optional<NationalEvolution.NationDevelopment>>`；最近核心复核的有效归属城市聚合的国家倾向、知识/文化、主导方向和专长；入参为StarCore国家UUID |
| `culture(UUID)` | `Future<List<Culture>>`；已登记地区各文明传统与影响值 |
| `regions(UUID,String,int)` | `Future<Optional<RegionAtlas.Page>>`；本城设施或勘探地区、资源/文化/设施的 cursor 分页，空字符串开始，limit 为 1–64 |
| `geography(UUID)` | `Future<Optional<RegionAtlas.Summary>>`；最多128个不同地区的地形、剩余量/初始容量/恢复汇总 |
| `districts(UUID)` | `Future<Optional<RegionalMap.DistrictReport>>`；同世界、同地形、四向相邻的已登记地区分组，保留成员ID及各资源总和；最多128个地区，不改变原地区储量 |
| `regionMap(UUID,UUID,int,int)` | `Future<Optional<RegionalMap.View>>`；城市、世界、中心区块X/Z的9×5只读地图，上北下南，未知位置不返回单元；坐标范围±1,875,000，不读取或加载世界区块 |
| `life(UUID)` | `Future<Optional<LifeView>>`；累计生活文化、当前有限倍率、家具和活动会话 |
| `lifeEvolutionCredit(UUID)` | `Future<Optional<LifeState.EvolutionCredit>>`；按真实生活动作 UUID 只读诊断原文化、已归集文化、最近归集周期和是否待处理；不存在返回空，不推进归集、不暴露玩家身份 |
| `routes(UUID,int,int)` | `Future<List<Route>>`；有限分页查询城市相关商路 |
| `routePlans(UUID,UUID,Mode,int,int)` | `Future<List<RoutePlan>>`；同方向已声明路线的只读成本排序（周期/距离/容量/节点数/启用状态、按城市每期维护及独立货单全程维护基线），limit为1–100，不授予通行或外交许可 |
| `orders(UUID,int,int)` | `Future<List<Order>>`；有限分页查询城市相关托管货单 |
| `transport(UUID)` | `Future<Optional<TransportStatus>>`；当前周期持久运力、共享节点剩余量、维护、待运/暂停及瓶颈；不证明现场领土/锚点或外交有效 |
| `marketListings(int,int)` | `Future<List<Listing>>`；公开仍有剩余可买数量的实物挂牌，limit 为 1–100，offset 非负 |
| `marketPurchases(UUID,int,int)` | `Future<List<Purchase>>`；城市作为买方或卖方的国家付款/实物交接状态，limit 为 1–100，offset 非负 |
| `marketDeliveryStats(UUID,String,int,int)` | `Future<List<DeliveryStats>>`；仅后台已核对物流 `COMPLETED` 收据的实际市场交货，按物品统计订单、数量、精确总额、加权均价和完成周期；城市可为空，物品可为空；有界回填可能短暂落后于最新交货 |
| `disasterResponse(UUID)` | `Future<Optional<DisasterState.Response>>`；按稳定请求 UUID 读取救灾成本、生效/到期、原事件身份、恢复步骤与预算快照 |
| `disasterResponses(UUID,int,int)` | `Future<List<DisasterState.Response>>`；按城市分页读取救灾回执，含已完成/到期记录，不重新扣料或赋予权限；limit 为 1 至配置 `limits.max-page`（最多128），offset 为 0–1,000,000 |
| `disasterHistory(UUID,int,int)` | `Future<List<DisasterState.History>>`；按城市分页读取请求与已提交恢复历史，分页边界同上 |
| `publicWorks(UUID,int,int)` | `Future<List<PublicWorksState.Project>>`；城市各原国家的工程参数快照、施工进度、最近维护水位；limit不超过配置 `max-page`，offset为0–1,000,000 |
| `publicWorksHistory(UUID,int,int)` | `Future<List<PublicWorksState.History>>`；有界工程施工、完成、运行和暂停历史；同工程分页边界 |
| `civicNeeds(UUID,int,int)` | `Future<List<CivicNeedsState.Request>>`；需求参数快照、请求/到期/完成周期和保存状态；limit与offset遵从独立需求配置 |
| `civicHistory(UUID,int,int)` | `Future<List<CivicNeedsState.History>>`；已提交的需求请求和完成历史；同需求分页边界 |
| `civicReceipt(UUID)` | `Future<Optional<CivicNeedsState.Receipt>>`；只返回已完成需求的实际动作UUID、实付材料和封顶奖励，`applied=false`；不存在或未完成返回空，不执行完成或认领 |
| `readiness(UUID)` | `Future<Optional<Readiness>>`；城市/国家/周期、provided/required军需、terrainDefense及readiness |
| `integrationStatus()` | 真实能力/降级原因；涉及StarCore读取，须在Paper主线程调用 |

未登记或模块不可用返回empty/空列表；API扩展查询有默认空实现以保留旧消费端源码兼容。城市、成员和领土的权威仍来自StarCore。`WorldStore`、直接SQL以及模块内部mutation入口不是稳定的无权限公开写API，不应让不受信玩家参数直接调用。

所有表中的 `Future` 实际类型为 `CompletableFuture`，结果不包含可变 Bukkit 世界/玩家对象。完成回调可运行于 IO worker，消费端必须自行安排 Bukkit 操作线程。数据库快照只承诺提交时一致性：地区和国家聚合采用最近一次有限核心归属复核，cursor 不是权限令牌，读取不会自动扫描未登记世界。`regions` 的空 `nextCursor` 表示末页，失效城市保留身份/周期并返回空地区。详见 [地区地图册](geography.md)。

由存储队列产生的 Future 由插件完成，调用方的 `complete`、`completeExceptionally` 和 `obtrude` 不会替代数据库结果。`cancel` 只会取消尚未开始的排队任务，任务开始后返回 `false`，因为 SQLite 写入无法安全回滚为“已取消”。`orTimeout` 与 `completeOnTimeout` 返回只影响调用方的独立 Future 视图；它们不会取消或伪造底层读写结果。

## 提交后通知

事件包为 `dev.starworld.api.event`，在Paper主线程、事务提交后通知，不可取消：

| 事件 | 不可变载荷 |
| --- | --- |
| `IndustrySettledEvent` | `settlement()`：城市/周期、produced、报告；重复周期不再次发送 |
| `WorldEvolutionEvent` | `previous()` / `current()`；开始、替换和余波结束，current可为null |
| `CityDevelopmentEvent` | `profile()`；已提交城市发展快照 |

```java
@EventHandler
public void onIndustry(IndustrySettledEvent event) {
    var receipt = event.settlement();
    // Output already exists in city storage. Never grant produced() again.
    logger.info(receipt.cityId() + " / " + receipt.period());
}
```

提交后、通知前进程可能退出。事件不是可靠队列，不保证跨重启重放；消费端按城市/周期去重并查询快照对账。它们不能代替国家幂等资源回执。

## 现有核心复用

`CoreBridge` 在Paper主线程验证真实国家、城市、成员、管理/统治权限和双层领土，读取实际科技、政策和国家繁荣修正。`StarCoreExtensions` 读取天气、纪元、战争/外交、已解锁科技、当前政策、国家资源以及真实 `ProcessingService` 的配方和工厂。每次返回防御复制的不可变记录，缺模块带capability原因；未知战争或外交不能当作和平。

1.2 气候采样只读取已验证设施所在的有限世界名，通过公开 `getWorldWeatherState(String)` 获取天气及控制国家；本国实际控制该世界时，才把StarCore `agricultural` 直接倍率投影到农场配方、`mineral` 投影到矿井原矿配方。未控制、未知或服务缺失时天气倍率为1.00。已完工且本期实际支付维护的 `irrigation_network` 仅缓解负面农业天气，不影响正常天气或矿产。快照和区块现场验证均在Paper主线程完成，再把不可变值传入既有SQLite结算；不扫描世界、不读取未登记区块、不改核心权威数据。

国家生产倍率为 `clamp((1+speed)*(1+policyGlobal+policyProduction)*prosperity,0,10)`，再组合本地地理/文明/规划/文化/事件/生活效果并有限裁剪。军事/贸易使用对应scope，实体物品不被当作抽象国家资源的别名。

`CoreBridge.foreignNeighbors(coreCity,site)` 只读该有效产地区块的四邻国家claim，默认空实现保留适配器兼容。`ExpansionRuntime.captureBorderEvidence(coreCity,registered,verified)` 是仅Paper主线程使用的内部取证入口：按有效设施地区去重，最多64地区/256邻接记录和100个明确对端，返回防御复制的国家快照与 `BorderWitness`。IO结算仅接受同城、同国、同周期、仍有效产地证据，并读取当前正资源储量形成 `ResourceDispute`；只有明确战争/敌对的实际邻国满足事件条件。其公开Java可见性用于可信适配器和验收，不授予玩家写权限或允许伪造事件证据，详见[地区文档](geography.md)。

`NationalEvolution` 只保存国家专长选择与转型周期，农业/工业/贸易/军需/文化和知识来自本国最近核心复核为有效的归属城市已提交演化状态。`city_authority` 排除核心已确认缺失或无法证明存在的城市，重启保留失效标记；有效核心快照可恢复。归属变化最多需要一次城市分页轮转及IO排队后反映，不在API查询时扫描全服。`view(nation)` 不创建StarCore国家；`choose(nation,id,period,actor)` 要求调用方先在Paper线程证明国家创始人/管理员权限；`multipliers(nation)` 在行为/知识条件不再满足时返回空效果。专长倍率受0.85–1.15界限限制，并和城市规划等共同裁剪。

审计基线真实资源ID为 `mineral/agricultural/energy/luxury/industrial/chemical/strategic`，由 `ResourceModule` 注册；默认显式兑换配方输出 `agricultural/industrial/strategic`，执行前仍通过 `ResourceService.availableResourceTypes()` 验证，不读取legacy具体商品名。`Category.OTHER` 是事务用途分类。`ResourceOutbox` 按稳定business key产生持久事务ID，成本和队列同事务写入；主线程调用 `NationResourceTransactionService` 后核实匹配receipt，本地再ACK。详见[国家扩展文档](core-extension.md)。

`StarCoreCombatContributor` 将已提交readiness和主线程验证过的设施区块缓存接入真实 `army_combat` 公共扩展点；仅当前/前一周期、真实供给、实时归属有效的军队参与。它不修改玩家PVP或ArmyService补给，核心仍按最高伤害聚合多个贡献者。详见[战斗接口](combat.md)。

## 生活与交通模块接口

`LifeService` 提供带request UUID的宴会、家具登记/维护、会话/音符/完成回执、职业与文化查询。`drainEvolutionCulture(city,period)` 必须在模拟事务内调用，使用已消费回执ledger防重复并承接迟到活动。`LifePlatform` 只在Paper主线程验证真实实体/乐器/听众；证据类型不能由包外构造。详见[生活接口](life-integrations.md)。

`LogisticsService` 与WorldStore共用连接，节点/路线、托管、运输、改道提议与完成收据均为不可变值，世界和权限操作由 `LogisticsController` 主线程摘取证据。`alternativeRoutes(order,auth,limit)` 只返回已登记的同方向有限候选；`proposeReroute/acceptReroute/cancelReroute` 要求双方城市管理者确认新路径与维护，接受后完整新行程从下一周期重新开始，不退已耗运力。`transportStatus(route,period)` 是持久计数查询，不能用于免复核运输授权。详见[交通贸易](logistics.md)。

1.7 `Route.corridorRequired` 表示该路线已持久绑定物理通道约定，不表示当前畅通。旧构造重载默认为false。`AuthoritySnapshot.corridors` 保存仅本次捕获的不可变 `Corridor.Proof`；旧构造重载没有通道证据，因此不能授权绑定路线。`Corridor.plan` 只计算最多1024个显式中心点，`CorridorCapture` 在Paper主线程验证已加载路面/水体和沿途双层claim。`LogisticsService.bindCorridor` 与其他mutation同属可信内部存储入口，公开可见性不授予玩家写权限。生产绑定入口为 `/sw route bind`。

1.8 `PurchaseStatus` 增加 `REFUNDING`、`REFUNDED`，物流 `OrderStatus` 增加 `REFUNDING`。查询消费者不能将非 `IN_TRANSIT` 状态一概视作完成；退款货单不会产生交货完成凭据。`MarketRefundService` 是使用串行存储事务的内部服务，`ExpansionRuntime.marketRefunds()`供受控集成读取，写操作仍须经过创始人权限捕获和双边确认。`TreasuryRefundPort`承载原付款与逆向回执；生产实现只通过公开核心`NationSettlementService`，必须在Paper主线程调用。

## 国家金币市场与维护接口

`TreasuryTransferPort` 是国家金币适配边界，调用属于 Paper 主线程。`Request(transactionId,payer,receiver,BigDecimal,description)` 与 `Receipt` 是不可变值；金额精确到两位小数，双方国家不同。`Receipt.matches` 核对国家、金额、ID 和 `OTHER` 分类。`StarCoreTreasuryTransfers` 仅使用公开 `NationTransferService.transfer/transferReceipt`，缺服务时明确降级；不组合两次独立的支出/收入操作，不访问核心账表。

内部 `MarketService` 在串行 IO 保存实物挂牌、锁定、付款 outbox 和物流交接。`list/buy` 接受稳定请求 UUID，并要求调用方在主线程证明城市管理和当前国家治理权限；构造 `AuthoritySnapshot` 不能代替真实授权。派发前仍重新核实双方国家、战争/外交和实际启用节点。`MarketController` 已提供这一调度/授权边界。付款确认后，`createPaidShipment` 的内部 `ReservedCargoClaim` 在同一个 `WorldStore` 事务消费已支付的原托管，不再次扣仓库；这个信任入口不是第三方插件随意发货 API。

1.5中 `Listing.units` 保留原始数量，`availableUnits/reservedUnits/shippedUnits/returnedUnits` 的和始终等于原始数量。`Purchase.units` 为该次不可变份额，`PriceHistory.purchaseId` 唯一标识该次成交；同一挂牌允许多份历史。记录构造签名已扩展，直接构造或反射解析这些记录的扩展需适配新字段。

1.9 `Purchase`增加`goodsTotal/taxAmount/taxQuote`，`total`为货款、税费以及1.10过境费之和，`taxQuote`在旧版或内部零税购买中可以为空。旧构造签名仍按零税解释。价格历史与`DeliveryStats.gross`只含货款。`MarketTaxPort`是公开核心税费评估的只读端口，生产实现`StarCoreMarketTaxes`只能在Paper主线程调用；`MarketQuoteService`在串行IO内保存及接受60秒报价，`ExpansionRuntime.marketQuotes()`供受控集成读取。它不是免权限的玩家写接口，接受前须捕获当前国家创始人、城市管理、领土及贸易权限。

`Purchase.SHIPPED` 只代表该份额转入物流，`PriceHistory` 记录该时点成交价格；真实到货由原物流单 `COMPLETED` 证明。挂牌关闭不撤销其他已预留份额。消费 API 查询不得再次发物品或支付。恢复、撤单与配置合同见 [市场文档](market.md)。

`CultureDynamics` 为已提交城市结算提供持久地区衰减时钟及公开核心政策对新影响的有限修正，最多处理 64 个地区，不离线多步追赶。`LedgerMaintenance.sweep` 在同库事务有限压缩旧审计、裁剪过期报告；`archives/readArchive` 分页与校验读取原审计，保留所有业务幂等凭据。以上为内部仓储服务，须使用现有串行 IO；详见 [存档维护](storage-maintenance.md) 与 [规模基线](scale.md)。

## 灾害治理边界

玩家入口为 `/sw disaster options`、`/sw disaster respond <策略ID> <requestUUID>`、`/sw disaster status [页]` 和 `/sw disaster history [页]`。响应命令先检查城市管理权限，异步读取扩展状态后，在 Paper 主线程重新核实成员、StarCore 城市与国家、管理权限和实际设施锚点；SQL 请求还核对捕获的原事件，防止排队期间更换事件后误扣仓储。请求 UUID 是幂等键，重复相同业务返回原回执，不再扣成本；换策略或城市/国家复用 UUID 会拒绝。

`/sw disaster-menu` 提供同一授权路径的策略/成本预览、响应确认、状态及历史界面。`ExpansionRuntime.respondToDisaster` → `DisasterController.respondFromPreview` 保留确认页的原城市/国家、原事件与稳定请求 UUID，在重新读取后再次证明权限/设施；预览不是写入授权。界面只读查询和确认交互见[灾害治理文档](disaster-governance.md)。

`DisasterResponseService` 是可信内部仓储服务，并非免权限写 API。`DisasterResponseDefinitions` 从独立 `disaster-response.yml` 读取有界成本、所需设施、缓解、可再生资源恢复和城市稳定/繁荣恢复。请求只扣真实城市实物，最早在请求周期的下一周期生效；请求本身不立即修改生产或城市发展属性。公开查询返回持久化的响应参数快照，重载配置不改写已付款响应。

幂等重试先核对持久城市的当前 nation 与 active 状态，再匹配原请求的 city/nation/strategy；调用边界仍须证明当前权限。同权限历史重试允许旧事件、旧请求周期并返回原回执，当前归属变化或城市失效会拒绝，不会借原 UUID 修改新事件。`remainingBudget` 同时覆盖稳定、繁荣与自然资源，按该顺序消耗；属性按实际封顶增量 `ceil` 收费，已满100不消耗预算，资源按实际恢复量收费。上限或余额不足时返回实际恢复量，history 记录实际变化。

`Simulation.Hooks.eventFactor(building,event,period,base)` 在实际事件推进后、地理和其他生产倍率组合前调用。默认原样返回；对于 `base < 1` 只能返回 `[base,1]`，对于 `base >= 1` 必须原样返回。无效值使整期回滚，不能以救灾制造正向事件增益。`ExpansionRuntime` 将有效响应接入该钩子，并在正常城市演化完成后、最终结算回执之前调用响应恢复。库存、地区储量、稳定/繁荣与救灾历史/步骤在同一个 `WorldStore` 事务提交；失败回滚，重复周期不执行恢复。跨期恢复每个实际结算至多一步，不补算离线周期；失去原国家归属或必需设施时暂停，资源只恢复配置允许的可再生容量，不恢复矿石。

## 公共工程与城市需求

`/sw governance` 打开独立注册的 `GovernanceMenu`；`/sw works-menu`、`/sw civic-menu` 分别打开工程与需求状态，城市主界面和发展界面的空槽也提供治理入口。原城市、市场、地区和灾害菜单保留。治理菜单展示当前库存缺口、工程门槛/起建成本/逐期施工与运行维护、已提交进度和暂停原因，以及真实 `LifeView` 累计文化/家具登记/演奏会话。生活快照只是参考，不能宣称这些动作仍可被某项需求认领。

`PublicWorksController.startFromPreview` 和 `CivicNeedsController.requestFromPreview/completeFromPreview` 在 Paper 主线程核实在线、使用/管理权限、当前成员、实时核心城市与国家，并在只读城市查询后重新捕获实际有效锚点。控制器不调用 `ensureCity` 或 `runtime.open` 重新激活权限。`ExpansionRuntime.startPublicWorks/requestCivicNeed/completeCivicNeed` 是上述受控入口的转发；GUI 确认页始终保留同一个请求 UUID，旧国家或旧界面不能回放操作。Civic 请求幂等重试先查询原请求周期，再让服务核对原 actor/type/city/nation；请求本身不扣材料、不发奖励，完成才选取真实已提交且未认领的请求后生活凭据并同事务扣料。公共空间需要不同真实家具的维护，缺少 CE 家具 API 时暂停，登记不能代替维护。

`PublicWorksService` 在演化服务之后初始化，`CivicNeedsService` 在生活服务建表之后初始化，各自配置和物品ID独立加入扩展存入白名单。正常城市结算由 `works.atomicSettlement` 包住整个 `ensureCity`、重复周期检查、扩展周期和 `Simulation.settle`。工程运行维护在 Simulation 读取库存之前扣除，工程倍率与既有城市/国家倍率组合并继续受总上限约束；建设推进在正常演化和救灾恢复之后、最终结算回执之前执行，完成当期不享受运行倍率。即使外层调用方捕获异常并继续提交其他工作，工程 savepoint 也会回滚该失败结算的库存、储量、演化、生活消费、救灾与工程水位。

工程/需求公开 Future 方法仅调用相应仓储的只读列表、历史和完成回执查询，不推进施工、收维护费、完成需求或生成生活凭据。Civic 完成更新已提交 profile，后续正常结算从新 profile 捕获，不用旧快照覆盖有限奖励。内部 `cycle`/`completed` 必须遵守上述完整结算事务，不是查询 API。详情见[公共工程](public-works.md)和[城市需求](civic-needs.md)。

1.10 `Route`增加`transitKey`，`Purchase`和`Quote`增加不可变`transit`许可映射，原构造签名仍以无过境费解释。`Corridor.Proof`包含实际核对的公开许可。`TransitPermitPort`及适配器负责主线程权威操作；`TransitProposalService`在串行IO内保存草案和核对回执，不自行授予权利。`TreasuryTransferPort.Request`保存完整受益国金额映射，单收款方仍可使用旧构造方法及`receiver()`；多收款方请求调用`receiver()`会拒绝，应使用`credits()`。回执保留总额`amount`，多方回执的旧`receiver`为null。查询回执必须传入完整Request以选用原转账或多方结算契约；缺少服务不能解释为未付款。
