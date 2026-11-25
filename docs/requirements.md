# 原始需求与实现、验证映射

此表按开发任务逐项对应当前 1.4.0 源码，保留各增量版本的实现边界。自动化组名指 `src/test/java/dev/starworld/` 下测试；通过数、真实服务端流程、JAR 哈希及未验证项以 [test-results.md](test-results.md) 为准。

## 项目、环境和复用

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 先审计两仓库最新代码/README/API/数据模型/依赖 | [audit-starcore.md](audit-starcore.md)、[audit-senluo.md](audit-senluo.md) 固定远端HEAD与源码证据 | 固定提交不是未来任意上游版本兼容承诺 |
| 扩展现有国家核心，不重复开发 | `integration/CoreBridge`、`StarCoreBridge`、`CoreExtensionPort`、`StarCoreExtensions`；只读国家/城市/领地等，出口走公开事务 | `StarCoreBridgeTest`、`StarCoreExtensionsTest`；不访问核心权威表 |
| 1.21.11/Paper/Java21/CE | pom、plugin.yml、CustomItems；CE26.9.1可选公共API隔离 | 自动化及各套实服证据均按最终JAR分别记录，见测试结果；没有打包上游实现 |
| 规划Folia | `runtime/SchedulerGate`、启动门禁、[architecture.md](architecture.md) | 当前拒绝Folia启用，上游快照/权限区域契约与真服验证尚需完成 |
| 绑定指定仓库并交付源码/Maven/JAR/配置/API/README | pom URL、README、Maven Wrapper、package-release.ps1 | 最终推送/产物状态与哈希见交付记录；不会把本地文件当作已远端发布 |

## 地理战略系统

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 地区划分与地形战略属性 | `WorldState.Site.regionKey`、`TerrainClassifier`、Definitions.Terrain | 登记区块增量分区，单点群系采样；RegionAtlas分页地图册与汇总显示储量、恢复、设施和文化；不全图扫描 |
| 山/河/平原/林/沙/雪六类地区 | definitions.yml六项农业/运输/军事系数 | `SimulationTest`、配置测试 |
| 区域资源丰度和恢复 | region/reserve/last_regen；肥力、林木和矿藏有限储量 | 共享地区每期只恢复一次，矿藏默认不可再生；重复登记不刷新 |
| 地形影响农业/运输/贸易/军事 | Simulation农业与运输阻力；Logistics地形运时；readiness与terrainDefense通过公开army_combat影响真实军队伤害 | `SimulationTest`、`LogisticsServiceTest`、`StarCoreCombatContributorTest`及真实ArmyService/army_combat验收；有限设施区块/当前或前一周期，核心最高值聚合，不影响玩家PVP |
| 要道/天然关隘/港口/交通节点 | `logistics`道路/桥梁/港口/关隘显式登记、路径/容量/维护 | 节点锚点真实验证；同方向已登记替代商路、双边确认改道、运力与瓶颈报告；天然关隘和道路连续性不全图推断 |
| 发展差异与稀缺 | 不同储量/恢复/倍率、矿竭、食物维护、贸易供需、仓储容量 | 原料不足不能生产，失地停产；可进口替代 |

## 动态文明系统

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 国家文明特性和可自定义文明 | nation_state、civilization命令；基础+扩展共五文明，配置可增 | 全国统一选择与六周期冷却；不重建国家科技树 |
| 农/商/军/文化倾向、国家专长 | `EvolutionService`城市倾向、六规划/四专长；`NationalEvolution`按最近核心复核的有效归属城市真实结算聚合五国家倾向与五专长 | `NationalEvolutionTest`：真实生产/教育积累、五方向行为/知识门槛、六周期冷却、转国/解散暂停、重启、恢复和失败回滚；国家专长选择需创始人权限；归属变化最多需一次城市分页轮转及IO排队收敛 |
| 区域文化传播/交流 | region_culture；有效设施积累；真实贸易完成收据触发exchange | `EvolutionServiceTest`、重启/重复文化收据测试；只作用已登记有效地区；新文化按实际公开政策修正，持久周期衰减不离线补算 |
| 科技与产业联动 | 核心production_speed/policy/prosperity；1.2按逐世界控制天气映射农业/矿业，已维护灌溉水网缓冲农场负面天气；科技效果仅精确配置到具体配方 | 缺可选天气接口时中性值；不复制天气/科研权威；科技倍率有边界且默认不映射 |
| 文明优势与制约 | 五文明、规划和专长含正负产业倍率；地理/粮食/维护约束 | 地域混合文化给有限影响，不能无限叠加收益 |

## 城市建设系统

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 城市规划 | balanced/agriculture/industry/commerce/culture/military | `EvolutionServiceTest`；规划/专长共用冷却 |
| 功能建筑识别登记 | 九种允许锚点、视线6格、成员/管理/双领土验证 | 原版锚点识别；不声称自动理解任意玩家建筑结构 |
| 仓库/市场/农场/工坊/兵营/学院 | definitions+evolution九设施、维护、产能/容量/知识/军需 | 有效并维护的设施才贡献；水井和厨房额外提供公共/食品功能 |
| 公共设施/繁荣/维护成本 | Evolution本地繁荣、稳定；真实面包、纸、木板与箭消费 | 本地繁荣不重复写核心国家繁荣，后者缺幂等回执 |
| 升级/特色/产业分工 | 三级实物升级、granary/arsenal/woodworking/gastronomy | `WorldStoreTest`、`EvolutionServiceTest`；条件失效专长作用停止 |
| Deco家具/装饰 | LifePlatform公共CE家具UUID/ID/位置验证，登记/维护贡献 | `LifePlatformTest`、`LifeServiceTest`；真实CE套件结果见森罗验收，未将热重载列为已通过 |

## 生产与产业系统

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 农业、矿业/加工、木材、食品、战略物资 | wheat/bread、raw_iron/iron_ingot、oak_log/planks、kitchen食品、arrow/shield/paper | 三条完整基础链+扩展配方；真实投入、维护、储量和库存 |
| 地区供需变化 | Logistics demand目标/真实库存短缺过剩、双向实物报价与实体限价挂牌，国库原子幂等转账后由实际商路交货 | 复用NationTransferService，不新增货币或核心抽象资源市场；托管预扣、同付款ID恢复、已付款不可当易货退款 |
| 国家政策/科技影响生产 | 核心快照倍率与对应scope，战争/天气/时代影响上下文 | 核心API契约与真实基线审计；可选模块缺失明确降级 |
| 新增CE物品 | `CustomItems`精确ID/构造/存在性、配置配方输入输出、生活消耗 | 未加载ID停用配方不扣料；原版特殊元数据物品不误吞 |

## 动态事件

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 干旱/暴雪/洪水/丰收 | 基础3+flood，环境/地理条件及权重 | 不更改大片方块、不替换核心天气权威 |
| 饥荒/资源危机 | 食物不足、矿竭或战时箭不足条件 | 持久冷却/倍率/繁荣稳定影响 |
| 商业繁荣/文化节庆 | 成功贸易、实际生活文化/文化积累条件 | 不因安装插件直接触发收益 |
| 边境资源争端 | 有效设施地区的四邻外国领土、对应国家的战争/明确敌对关系、同周期本地正资源储量 | 有界读取公开claim/外交；远方战争、和平邻居、失效/矿竭证据拒绝；历史记录资源/对端/区块，不自动宣战 |
| 衰落/复兴、长期影响 | 繁荣/稳定条件、持续期+余波、有限历史 | `EvolutionServiceTest`、`SimulationTest`；重启保留时间线 |

## 城市生活

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| Cookery食材/菜肴 | 实际已入库baozi/oil用于配置宴会 | 不奖励提交前取菜事件；真实ID仍需CE内容加载 |
| Tavern酒馆 | 实际wine商品+食物消费、酿酒师贡献 | DrinkCreatedEvent缺玩家/位置/交易ID，不猜归属 |
| Deco家具装饰 | 显式登记允许家具，有限UUID周期复核、维护品消费 | 移动/替换/卸载/失地无贡献；登记本身不奖励 |
| MusicPlus节庆演奏 | 真实musicplus:handheld_instrument或原版音符盒参与StarWorld受控演奏 | 上游无公开完成API，不自动监听 `/kmusic`；实际声音/听众/时长/音高/间隔检验 |
| 生活职业影响产出 | 四职业、冷却、实际成本、文化与短期有限产业效果 | 同请求UUID幂等；个人/城市限额；迟到文化消费ledger承接未来结算 |

## 技术和第一阶段交付

| 原始要求 | 实现路径与行为 | 验证和边界 |
| --- | --- | --- |
| 独立Maven插件 | pom、Wrapper、shade SQLite、plugin.yml | `clean verify`、产物内容检查、源码JAR |
| 通过公开API读国家/城市/科技/资源 | CoreBridge、CoreExtensionPort、公开ServicesManager适配 | 主线程契约，immutable复制，缺模块原因 |
| 不直接修改核心权威表 | WorldStore只保存插件目录starworld.db；资源出口走公开TransactionService | 源码审计、outbox端口/回执测试 |
| 独立持久化、重启恢复 | WAL+FULL、事务、last_period、settlement、logistics/life/evolution/outbox表 | 真SQLite、并发、独立进程中断、重开与迁移测试；有界无损审计归档保留关键业务去重凭据 |
| 公开JavaAPI和事件 | StarWorldApi查询、IndustrySettled/WorldEvolution/CityDevelopment事件 | Future值不可变，提交后通知；事件非可靠支付队列 |
| 可配置数据和GUI | 十一份配置（含逐世界气候）；设施、文明、规划、地区、市场、灾害、公共工程及生活需求界面与命令入口 | `ConfigLoaderTest`、Life/Logistics 配置测试、`CityMenuTest`、`GovernanceMenuTest` |
| 防重复产出 | 城市/地区周期账本；货单、文化、生活、国家事务、工程与城市需求均有稳定 ID | 同周期、同请求、并发、崩溃、失去 ACK；城市需求同时全局去重已认领动作 |
| 不持续扫区块/大量实体 | 城市分页、有限登记锚点/节点/家具、空间听众查询 | 不加载区块、有限批量和不重叠任务；已有千城/两千设施真实SQLite基线，Paper世界负载/TPS仍需实际测量 |
| Folia区域调度正确 | SchedulerGate预留Global/Entity/Region边界，当前检测停用 | 上游不满足前不虚报兼容，正式Folia真服未验收 |
| 森罗可选依赖、明确降级 | 正确插件名softdepend、CustomItems/平台能力诊断 | 缺CE或内容停相关动作，原版链继续；不伪造完成API |
| 安装/升级/迁移 | 首次配置、旧ID优先、版本拒绝、备份与加法DDL | 配置/基础schema迁移、各模块识别的未来版本拒绝测试；降级用备份 |
| 第一阶段6地理/3链/3设施/2文明/3事件 | 已包含于当前6/完整基础3链/9设施/5文明/11事件 | 各版本真实服务端验收与自动化报告见测试结果 |
| 国家状态、持久化、配置、GUI、自动测试 | 上述实际模块与测试、客户端工具、GitHub CI | 最终通过数和JAR哈希不在此硬编码，避免旧结果冒充新版本 |

## 0.4 交通规划增量

| 增量 | 实现与验证 |
| --- | --- |
| 已声明路线成本比较 | `RoutePlan` 不可变查询、`StarWorldApi.routePlans`、`/sw route compare`；按周期/距离/容量/节点数/UUID稳定排序，停用路线仍可比较；`TransportPlanningTest` 覆盖10项测试 |
| 第三国过境 | 明确保持拒绝；StarCore公开API未提供通道许可、过境回执或第三国外交授权，现有服务拒绝第三城市节点，未复制核心权威 |

## 0.5 至 1.0 深化

| 增量 | 实现与验证 |
| --- | --- |
| 救灾与长期恢复 | [灾害治理](disaster-governance.md)：3 类灾害各 2 种策略，收取真实材料，次期缓解；稳定、繁荣、可再生资源共享有限恢复预算 |
| 公共工程和分工 | [公共工程](public-works.md)：3 类投资依赖有效设施、文明和城市发展；每期单独付费，失地或缺料暂停，完成后持续维护才加成 |
| 生活需求 | [城市生活需求](civic-needs.md)：事务同时扣库存、认领请求后真实宴会/家具维护/演奏凭据并记录奖励；重放返还同回执 |
| 连续世界与升级 | 独立隔离服覆盖三城、三次启停、战时暂停、和平交付和进口救灾；真实 0.5 备份用于验证加法式迁移。通过状态见[测试结果](test-results.md) |

## 1.1 至 1.4 深化与最终验收

| 增量 | 实现与验证 |
| --- | --- |
| 旧生活贡献分期结转 | `LifeEvolutionCarryTest` 验证降低上限后的分期额度、持久预算与重启去重；真实1.0源和当前候选分别运行4/7阶段。当前候选实服结果仍待完成 |
| 商路配置收紧与恢复 | `LogisticsConfigurationRecoveryTest` 验证受限路线、托管货物及恢复；真实四次启停14阶段须绑定当前JAR，不复用旧版本结果 |
| 逐世界气候与灌溉韧性 | `ClimateRulesTest`、`StarCoreBridgeTest` 和生产结算接线覆盖国家/世界归属、倍率边界与精确配方映射；扩展实服套件验证真实核心天气对产量的影响 |
| 有限地区勘探 | `RegionAtlasTest`、`WorldRuntimeTest`、`WorldStoreTest` 覆盖纸张成本、128地区上限、归属复核、幂等发现和schema3回填；当前JAR的勘探/存储套件待实服运行 |
| 存储过载和批次边界 | `StorageWorkerTest` 覆盖256项队列、拒绝与顺序、关闭终态；城市生产批次最多64个。当前JAR还需60秒真实Paper压力及恢复验证 |
| 完整发行交付 | 当前JAR的单测、规模、源码包、全部功能、独立安装、旧存档和生活迁移证据共同通过收集器后，才由 `package-release.ps1` 生成发行ZIP。准备目录、静态预检和历史通过记录不证明本次发行完成 |

## 玩家决策如何落实

河流/平原和农业文明适合粮食城；矿藏与军工专长支撑武器城；道路/市场/仓库需要实际投资和维护换取运输、容量与发展；学院和核心科技影响专长与产能；贸易国家通过实物互补和文化交流发展，战争使商路封锁、军需需求增加。失去重要矿区/林场/粮食地会停止对应产出，库存和进口路线决定后续供给。

国家资源出口将真实成本兑换为当前 `ResourceService.availableResourceTypes()` 支持的国家资源。审计基线七个真实ID为 `mineral/agricultural/energy/luxury/industrial/chemical/strategic`，默认输出 `agricultural/industrial/strategic`，不是legacy具体商品名；`Category.OTHER` 单独表示事务用途。战斗扩展经公开贡献点接入，具体准备度/地形与真实战斗验收以源码、[战斗文档](combat.md)和测试结果为准，不声称核心军队自动消费StarWorld仓库。
