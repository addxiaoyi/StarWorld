# StarCore 技术审计与复用边界

实现版本补记：本文保留首次审计的源码结论与第一阶段只读桥接范围。0.2.0 已在相同公开服务基线上增加不可变扩展快照、持久国家资源 outbox/receipt 和公开 army_combat 贡献者；没有直接修改核心表。当前接口、作用边界和验证记录见 [core-extension.md](core-extension.md)、[combat.md](combat.md) 与 [test-results.md](test-results.md)。

审计日期：2026-11-19（Asia/Shanghai）。仓库：<https://github.com/addxiaoyi/starcore>。本地检出 `main`，`git log -1` 和 `git ls-remote origin HEAD` 均为 `f769e62799e769a18fe51305b545869a17f80cd1`，提交时间 `2026-11-18T23:34:47Z`，消息 `docs: record final shared budget Paper acceptance`。本次只读审计未修改 StarCore。审计范围包含 README（中文/英文）、ARCHITECTURE、Maven、plugin.yml、公共入口及实际注册、国家/城市/领土/资源/科技/政策/繁荣/天气/纪元实现。

## 0.3.0 国库与线程边界复审

2026-11-20 再次核对指定远端，HEAD 仍为上述 `f769e627…`，未改变核心生产源码。新增实物金币市场前复查以下公开服务：

| 公开边界 | 已确认合同 |
| --- | --- |
| `module.treasury.NationTransferService.transfer(TransferRequest)` | 请求包含稳定 String 事务 ID、付款/收款 `NationId`、`BigDecimal`、类别和描述，返回 `APPLIED/ALREADY_APPLIED/INSUFFICIENT_FUNDS/REJECTED` |
| `NationTransferService.transferReceipt(String)` | 可查询持久 `TransferReceipt` 的双方国家、金额、类别、描述和应用时间；消费端应逐项核实 |
| `NationCreditService.credit/creditReceipt` | 单国幂等收入，不应与独立 spend 拼成双国原子转账 |
| `NationSpendingService.spend/spendingReceipt` | 单国幂等支出；核心预算接口另可负责预算优先扣款 |

`TreasuryModule` 在服务元数据中公开注册上述三种事务接口。当前 `transfer` 实现为同步锁内同时修改双国余额与事务回执，统一同步持久化；持久化失败回滚两边余额和本次回执。证据见固定源码 [NationTransferService](https://github.com/addxiaoyi/starcore/blob/f769e62799e769a18fe51305b545869a17f80cd1/src/main/java/dev/starcore/starcore/module/treasury/NationTransferService.java) 和 [TreasuryModule.transfer](https://github.com/addxiaoyi/starcore/blob/f769e62799e769a18fe51305b545869a17f80cd1/src/main/java/dev/starcore/starcore/module/treasury/TreasuryModule.java#L743)。StarWorld 0.3.0 使用该原子接口及 `OTHER` 类别，先后查询原事务回执；不会直接读写核心余额，也不把个人 `EconomyService` 的无业务幂等 ID 转账或 TransactionFabric 流水当作原子国家支付完成证明。

核心已经存在抽象国家资源的 `MarketOrderBookService`；StarWorld 的新市场对象是本插件城市仓库托管的真实商品，复用核心国库货币和实际商路，没有复制这个抽象资源订单簿。国家资源出口继续走已审计的 `NationResourceTransactionService`，两种用途和凭据分开。

边境资源事件另复用 `NationService.claimAt(String world,int chunkX,int chunkZ)`、`nationById(NationId)`，以及现有公开 `WarService.activeWarsOf` 和 `DiplomacyRelationService.relationBetween` 的真实对端状态。读取限定有效登记设施地区的四个正交邻块，并在本国原区块双领土成立后确认邻接外国仍存在；不枚举全体 claims/nations，不读取邻块方块、不强制加载。最多 64 地区和 100 不同外交对端，后续 IO 只携带不可变国家/区块/关系证据并关联自己的地区正资源储量。核心领土、外交和战争仍为权威，StarWorld 不推断或写入另一套状态。`border_dispute` 不再仅凭全国家战争布尔值触发；事件概率、权重、冷却与余波保留，开始历史在结算事务内记录具体边境和资源。

繁荣接口仍无稳定业务 ID/完成回执，`modifyProsperity` 的读取再写入和异步保存不能被当作可安全跨库重放的事务。当前保持只读核心繁荣，城市繁荣存在本地。国家、城市仍返回可变成员/领土模型，调度器仍有直接 Bukkit scheduler 调用，plugin.yml 仍无 Folia 声明；同步国库锁不能让其他核心 API 自动具有区域线程安全契约。StarWorld 的国家读取、治理确认、国库操作都限定 Paper 主线程，Folia 计划仍按下文边界执行。

## 实际基线与依赖

- Maven 坐标 `dev.starcore:starcore:1.0.0`，Java 21，`paper-api:1.21.11-R0.1-SNAPSHOT`（provided），Bukkit 主类 `dev.starcore.starcore.StarCorePlugin`，`api-version: '1.21'`。
- Java 21 是插件编译目标；上游 `tools/player-runtime-smoke/README.md` 对实际 Paper 1.21.11 验收进程要求 Java 25+。不要把 Maven 可由 JDK 21 编译与服务器启动 JVM 的要求混淆；最终启动要求应随所下载 Paper 构建的实际检查验证。
- 数据/缓存依赖包含 HikariCP、SQLite JDBC、MySQL Connector、Flyway/MySQL、Caffeine、Jedis、Gson。GUI/文本含 Triumph GUI、Adventure。Vault、PlaceholderAPI、Citizens、PacketEvents、ProtocolLib 是 provided 集成。
- `plugin.yml` 的 softdepend 为 Vault、PlaceholderAPI、LuckPerms、Slimefun、MMOItems、ItemsAdder、Oraxen、NightCore、ProtocolLib、packetevents、Citizens。没有声明 CraftEngine；不能假设 StarCore 已提供 CraftEngine API。
- 仓库存在旧模型与新模块并存，必须使用 `module.*` 中实际注册的服务。特别不能把 `dev.starcore.starcore.city.City` / `CityManager` 当作正式城市模块。README 中旧交接日期和 ARCHITECTURE 的“46 modules”“Folia compatible”等概述不能替代当前源码检查。
- 国家模块、城市模块、科技模块等是可配置模块；安装了 StarCore 不等于所有服务已启用。所有 Optional 均需处理为空。

## 公开入口

StarCorePlugin 的 `registerApi()` 通过 Bukkit ServicesManager 注册 `dev.starcore.starcore.api.StarCoreApi`。正常有编译依赖的用法：

```java
StarCoreApi api = Bukkit.getServicesManager().load(StarCoreApi.class);
Optional<NationService> nations = api.nationService();
Optional<CityService> cities = api.service(CityService.class);
```

入口还有 `version()`、`modules()`、`<T> Optional<T> service(Class<T>)`；`StarCoreApiProvider.service` 只查询 StarCore 内部 ServiceRegistry。城市虽无 `cityService()` 快捷方法，`CityModule.enable` 明确注册了 `CityService.class`。其他快捷入口包含 `technologyService()`、`resourceService()`、`policyService()`、`epochService()`、`treasuryService()` 等。

StarWorld 的 `StarCoreBridge` 从 StarCore 插件自己的 ClassLoader 加载这些公开类型，再通过 ServicesManager 取入口并调用公开方法；不读取字段、不使用 `setAccessible`、不访问 context 或存储实现、不打包 StarCore 类。这样 Maven 构建无需复制整个大型 StarCore 依赖树。API 类型/签名不兼容时关闭对应能力，并在状态/说明中报告；没有成功连接时不能放行领土管理或生产。

## 可复用的权威数据和精确签名

下表中的方法均为公开方法，包名前缀为 `dev.starcore.starcore.`。

| 服务/模型 | 查询签名及语义 | StarWorld 的使用 |
|---|---|---|
| `module.nation.NationService` | `Optional<Nation> nationById(NationId)`、`nationOf(UUID playerId)`、`nationByName(String)`、`Collection<Nation> nations()` | 国家存在性、玩家归属，绝不另建国家/成员表 |
| 同上 | `Optional<TerritoryClaim> claimAt(String world,int x,int z)`、`Collection<TerritoryClaim> claimsOf(NationId)` | `x/z` 是区块坐标，用于实时验证国家领土 |
| 同上 | `boolean isFounder(UUID,NationId)`、`int levelOf(NationId)`、`long experienceOf(NationId)` | 可查询国家地位；不由 StarWorld 重写升级/战争规则 |
| `module.nation.model.NationId` | `record NationId(UUID value)`，`static of(UUID)`、`value()`、`uuid()`、`toString()` | StarWorld 存 UUID；与 StarCore 交互保留真实 NationId 对象 |
| `module.nation.model.Nation` | `id():NationId`、`name():String`、`founderId():UUID`、`hasMember(UUID):boolean`、`isFounder(UUID):boolean` | 查询权威归属；不调用其 setter |
| `module.city.CityService` | `Optional<City> getCity(UUID)`、`getPlayerCity(UUID)`、`getCityByName(String)`、`Collection<City> getNationCities(NationId)`、`getAllCities()` | 城市 UUID 是已有城市身份；产业记录只引用它 |
| `module.city.model.City` | `id():UUID`、`nationId():NationId`、`name():String`、`level():int`、`residents():Map<UUID,CityRank>` | 城市名称、国家、等级都归 StarCore 所有 |
| 同上 | `isResident(UUID)`、`canManageCity(UUID)`、`isMayor(UUID)`、`isOfficer(UUID)` | 实际 `canManageCity` 只返回 `isMayor`；桥接按这一语义验证 |
| 同上 | `hasClaim(String world,int x,int z):boolean`、`claims():Set<String>` | 城市领土独立记录，格式 `world,x,z`；必须与国家 claim 同时满足 |
| `foundation.territory.model.TerritoryClaim` | `record TerritoryClaim(String ownerId,ChunkCoordinate coordinate)` | `ownerId` 与 NationId 字符串比较，不能误作玩家 UUID |
| `foundation.territory.model.ChunkCoordinate` | `record ChunkCoordinate(String world,int x,int z)` | 世界名+区块坐标，不能传方块坐标 |
| `module.technology.TechnologyService` | `unlockedTechnologies(NationId)`、`hasTechnology(NationId,String)`、`getTotalModifier(NationId,String):double` | 读取已研究科技效果，不另建科技树 |
| `module.policy.PolicyService` | `activePolicy(NationId)`、`activePolicyDefinition(NationId)`、`activePolicyEffects(NationId)`、`activePolicyModifier(NationId,String,PolicyEffectScope):double` | 读取现行政策效果，不重复政策投票/付费/冷却 |
| `module.resource.ResourceService` | `Map<String,Long> stockpile(NationId)`、`long amount(NationId,String)`、`availableResourceTypes()` | 读取真实国家库存；城市物品仓库是另一种状态，不冒充国家库存 |
| `module.prosperity.ProsperityService` | `getProsperityValue(NationId)`、`getResourceBonus(NationId)`、`getBonusMultiplier(NationId)` | 已通过 api.service 注册；使用真正资源倍率 |
| `module.weather.WeatherControlService` | `getNationWeather(NationId):WeatherType`、`getWorldWeatherState(String)`、`getResourceModifiers(WeatherType):Map<String,Double>` | 后续按资源类型联动现有天气；不改变世界天气归属 |
| `module.weather.WeatherForecastService` | `getForecast(NationId)`、`getTodayForecast(NationId)`、`getWeatherProbabilities(NationId,int)` | 现有国家预报；不另建天气权威 |
| `foundation.epoch.EpochService` | `snapshot()`、`snapshot(Instant):EpochSnapshot` | 已有纪元；快照含 enabled、epochNumber、epochStart、nextEpochStart、epochDuration、remaining |

城市删除、国家解散、成员离开、领土丢失后，StarWorld 保留历史扩展数据但实时查询权威状态，相关生产/注册/管理必须立即停止。桥接不长期缓存 API provider、City 或 Nation；每次动作重新验证，并校验城市 nationId 与实际 Nation.id 一致。玩家城市索引还需核对 `City.isResident` 和 `Nation.hasMember`，防止陈旧索引放行。

## 权限与国家行为

`Nation.hasPermission(UUID,String)` 当前实现除创始人外，对任何国家成员返回 true，不能据此把“国家成员”提升成管理者。

StarWorld 城市产业管理允许实际市长或该国创始人，且必须仍是国家成员。文明发展方向是全国行为，只允许国家创始人（管理员另由 StarWorld 权限控制），不允许普通市长代表全国更改。建筑登记/产出还需同时验证城市领土与国家领土；只核对玩家所属国家或只核对城市旧 claim 均不足。Site 的世界 UUID 与当前世界名也需一致；不加载未知世界、不扫描全部区块。

## 生产倍率与资源分类

第一阶段桥接明确采用：

```text
max(0,1+TechnologyService.getTotalModifier(nationId,"production_speed"))
  * max(0,1+政策 GLOBAL production_bonus + 政策 PRODUCTION production_bonus)
  * max(0,ProsperityService.getResourceBonus(nationId))
```

倍率最终受 StarWorld 上限 10 限制。科技/政策/繁荣模块未启用或数值无效时，对该项使用中性值，并写明详情。没有从国家等级、领土数量或库存数量臆造加成。

`activePolicyModifier` 实现按 scope 精确过滤，并不会自动把 GLOBAL 包入 PRODUCTION；需分别读取相加。`technologies.yml` 的 `production_speed`（如蒸汽动力 0.3）是实际效果；`production_multiplier`（工厂制度 1.5）与其他 effect 混合聚合语义存在歧义，第一阶段不额外解释这个值以免把 x1.5 当成 +150%。`resource_yield`/`crop_growth` 等有产业专属语义，不把金属产量科技盲目加到食品和木材。后续可通过分链 effect 映射扩展，保持可配置且可验证。

国家 ResourceModule 实际支持七类抽象资源：`mineral`、`agricultural`、`energy`、`luxury`、`industrial`、`chemical`、`strategic`。它们不是 Minecraft `iron_ingot`/`wheat` 物品 ID。桥接展示真实 `stockpile`，不把 StarWorld 物品直接写进该库存，也不宣称 CraftEngine 自定义物品已成为 StarCore 原生资源。

## 资源写入与幂等边界

必须区分两个实际接口：

1. `ResourceService.grant(NationId,String,long):boolean` 和 `consume(...)` 是旧的单资源入口；其中 grant 还会应用 StarCore 国策加成。没有业务事务 ID，不能把本地“已发放”标记与其调用简单拼接后声称跨库 exactly-once。
2. 最新代码已公开并注册 **`module.resource.NationResourceTransactionService`**，这是可复用的原子幂等入口，并非缺失功能。它提供 `grant(TransactionRequest)`、`consume(TransactionRequest)`、`Optional<TransactionReceipt> transactionReceipt(String txId)`；request 是 `TransactionRequest(String transactionId,NationId nationId,Map<String,Long> resources,Category category,String description)`。Category 有 RESEARCH、COMPENSATION_REFUND、OTHER。结果 status 为 APPLIED、ALREADY_APPLIED、INSUFFICIENT_RESOURCES、REJECTED；`applied()` 对前两种为 true。

ResourceModule 把 receipt 与库存一起持久化，失败时回滚；相同 ID/相同参数重试返回 ALREADY_APPLIED，不同参数返回 REJECTED。此事务 grant 直接应用请求数额，未走旧 grant 的国策加成。后续国家抽象资源出口应使用固定事务 ID、持久化本地 outbox、receipt 检查及可重放请求，不应再造另一套国家交易引擎。

第一阶段桥是完全只读的；自动生产、原料消耗、城市本地仓库、期间结算凭证由 StarWorld 自有 SQLite 事务负责。玩家物品转移横跨 Bukkit 背包和数据库，不具有共享事务；应保留转移凭证和不确定结果处理，不能靠异常后无条件补发来掩盖崩溃窗口。

## 已有加工/资源地区能力与避免重复

StarCore 已有 `ProcessingService`、`Factory`、`ProcessingRecipe`、国家工厂加工、贸易协议、贸易路线、价格/储备/禁运/配额；`NationResourceDistrictService` 也已存在国家资源区能力。StarWorld 仅新增“实地注册建筑 + 地理可恢复储量 + 城市物品生产链 + 文明及地区历史”扩展层，不复制国家、城市成员、工厂的原有行政所有权，不把不同系统仓库混成同一存储。未来接入 ProcessingService 时应把城市设施映射为已有加工能力或适配提供者，保留唯一结算权威。

## 持久化与线程边界

StarCore 采用模块生命周期 + StarCoreContext + ServiceRegistry 架构，国家领土、城市、资源、天气等各有自己的存储实现；城市当前使用 JsonCityStorage，资源使用 ResourceStateStorage（可数据库/持久化实现）。StarWorld 必须用自己的数据目录和 schema；升级 StarWorld 不执行 StarCore 表迁移。

**不能把当前基线宣告为 Folia 可用。** 虽然 ARCHITECTURE 写“Folia compatible”，实际 `core.scheduler.StarCoreScheduler.runSync/runSyncLater/runSyncTimer` 仍调用 `Bukkit.getScheduler().runTask/runTaskLater/runTaskTimer`；plugin.yml 无 `folia-supported: true`。Nation 的 members 是 LinkedHashMap，City 的 residents/claims 是 HashMap/HashSet，服务外层 ConcurrentHashMap 不能让返回的可变模型自动线程安全。

第一阶段 StarCore 读取限定在 Paper 主线程，桥对异步调用 fail closed；异步 I/O 只处理不可变 StarWorld 快照，不拿 City/Nation 模型跨线程。Folia 后续应先得到 StarCore 明确提供的线程安全只读快照/所有权查询契约，再将 GUI/背包放到玩家 entity scheduler、建筑方块检查放到正确 region scheduler，数据库任务放到 async scheduler。Global region scheduler 不能代替任意位置/实体的所属区域，也不能凭当前只读反射绕过上游线程限制。

## 验证边界

StarWorld 的 `StarCoreBridgeTest` 使用仅位于 `src/test` 的真实包名/公开签名 fixture，覆盖服务缺失、已关闭 provider、城市/国家删除、成员索引失效、国家治理授权、负坐标区块、双重领土、世界身份、真实政策/科技/繁荣数值、可选服务降级和主线程限制。这些 fixture 不随插件发货；它们验证适配协议与权限逻辑，不冒充完整 StarCore 服务器实测。最终 Maven 测试及 Paper 运行结果以本项目测试报告为准。

对 StarCore 本身的完整 Maven 套件、本次新组合的 Folia、CraftEngine 及森罗各插件实服联测，不因“源码审计通过”而视作通过。目标 API/版本改变时应重新核对本文件记录的 commit 与签名。
