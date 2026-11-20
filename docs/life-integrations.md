# 城市生活、职业与森罗联动

本模块只保存 StarWorld 的职业、公共活动、家具登记与贡献账本，不修改 StarCore 或森罗数据库。真实物品先通过 StarWorld 原有存取流程进入城市仓库；生活活动在同一个 SQLite 事务里扣除仓库物资、增加城市文化、记录临时产业效果和唯一回执。配置文件为独立的 `life.yml`，首次启动生成，已存在的管理员配置不会被覆盖。未知配置版本或未来数据库版本会拒绝载入并保留原文件。

## 可玩的默认流程

1. 加入 StarCore 城市。城市管理员准备仓库物资；普通生活职业不会自动取得支配公共仓库的权限。
2. `/sw profession chef` 选择厨师。`brewer`、`artisan`、`musician` 分别为酿酒师、装饰工匠和乐师。换职业冷却为六个生产周期。
3. `/sw feast community` 消耗八个仓库面包，获得 12 文化与三周期的 4% 产业加成。需要厨师职业及城市管理权限。
4. `/sw feast cookery` 消耗实际已入库的四个 `kaleidoscopecookery:baozi` 与一个 `kaleidoscopecookery:oil`，获得 25 文化与四周期的 8% 加成。缺少 CE 内容定义时拒绝执行，不扣仓库。`/sw feast tavern` 由酿酒师消耗一份 `kaleidoscope_tavern:wine` 和四个面包。
5. 装饰工匠看向六格内城市领土中的已加载家具，运行 `/sw decoration register`；默认允许真实 Deco ID `kaleidoscope_deco:chair/oak` 与 `kaleidoscope_deco:cook_stool/oak`。登记消耗四个橡木板，没有登记奖励。`/sw decoration list` 查看 UUID，`/sw decoration remove <家具UUID>` 注销且不退款。
6. 乐师在自己城市领土内使用 `/sw concert start`，以城市管理权限消耗八个仓库面包。记下返回的会话 UUID；手持原版音符盒或已经加载的 `musicplus:handheld_instrument`，使用 `/sw concert note <会话UUID> <0–24>` 发出真实声音。八格内至少需要一位在线同城听众。每个音符间隔须为 0.75–10 秒，演奏至少持续 60 秒、累计至少 12 个音符并使用至少五种音高，且须在五分钟内结束。`/sw concert finish <会话UUID>` 产生 20 文化及四周期的 6% 产业加成。`list` 可找回进行中的会话，`cancel <会话UUID>` 结束且不退款。

默认每玩家每周期至多贡献 80 文化，每城市每周期至多 240，城市累计文化上限十亿。相同宴会配方的临时产业效果仅取最高值，不随重复宴会叠加；全部生活产业加成合计最多 30%。所有期限和冷却以 StarWorld 生产周期计算，演奏的实际时间与音符间隔使用服务器毫秒时钟。没有离线补算或自动退款。

## 实际接入边界

**Cookery/Tavern：实际商品消费。** 公开取菜事件在物品交付前触发，Tavern 的 `DrinkCreatedEvent` 没有玩家、地点或稳定交易 ID。StarWorld 不将这些通知当作完成生产。真实 CE 物品身份由 `CustomItems` 验证，入库后才可由上述宴会消费；不存在“安装了插件就加产出”的分支。默认物品 ID 取自已审计上游的 `ItemKeys`、配方与 `catalog.json`，仍须相应 CE 内容包成功加载。

**Deco：真实公共 CE 家具 API。** 登记先通过 `CraftEngineFurniture.rayTrace(Player,6d)` 取得玩家看向的家具，再用 `getBukkitEntity()` 和 `getLoadedFurnitureByMetaEntity(Entity)` 交叉核对相同的家具 `uuid()`、`id()`、`isValid()`、元实体 UUID、世界及坐标和 StarCore 领土归属。默认家具 ID 取自上游 `WoodenSeats.java`。不访问 Deco 私有目录、控制器或 NBT。每城市最多登记八个家具，每结算周期只验证这些已知 UUID；不扫描实体、不加载区块。已卸载、被移动、强制删除、替换身份、国家归属变化或不再位于本城市领土的家具本周期没有贡献。

每个有效家具每周期消耗一个面包，贡献一文化和当周期 0.5% 产业加成。其登记工匠仍须是装饰工匠，且维护费与个人/城市额度均足够。重复结算同一周期不再收费或奖励。卸除或注销家具不能刷新登记文化奖励；注销立即清除当前装饰加成，下一周期再重新评估现存家具。家具离线期间不积累补算。

**MusicPlus：物品互通与独立演奏。** 已审计上游 `MusicPlugin.java` 的 `INSTRUMENT` 确认 `musicplus:handheld_instrument` 是真实 ID。但是其键盘、播放和停止方法是私有的，没有公共演奏完成事件。因此 `/sw concert` 是 StarWorld 自己控制的现场演奏功能，使用 Bukkit 声音 API；它既不是 `/kmusic` 播放监听器，也不声称自动接收上游 MIDI 完成通知。上游普通演奏不会凭空产生城市奖励。`LifePlatform.UpstreamMusicCompletionPort` 预留带稳定交易 UUID、表演者、位置与起止时间的适配协议；当前没有虚构的已实现上游适配器。

## 持久化和失败行为

独立表以 `life_` 开头，包含职业、城市文化、活动回执、家具登记、演奏会话、音符和听众；由 `LifeService.initialize()` 加法式初始化，不占用 StarCore 数据表。回执 UUID 是主键。相同请求重送返回 `applied=false`，参数不匹配的 UUID 重用直接拒绝。多物品扣除、额度检查、回执、文化和临时效果同事务提交；任何异常全部回滚。可恢复的会话音符、音高集合、听众 UUID 和成本均已持久化，重启不重复收费。崩溃后的演奏若已超时或停顿过久，只能取消或重新付费开始；不会为未完成演奏自动补偿。

城市易主后，旧国家活动效果永久停用，旧演奏会话失效，旧国家家具不能结算；夺回城市不会复活已经停用的临时奖励。历史文化仍是城市历史记录。`cultureInPeriod` 查询当期记录，`drainEvolutionCulture` 在下一次有效模拟事务中消费未处理的当前国家回执；本期城市已结算后举办的活动也不会遗漏。已消费ledger与模拟同事务提交、失败回滚；每次至多500条及城市文化限额。不能将累计 `LifeView.culture` 每周期重复写入世界演化。

## Java 接口与线程

`LifeService` 的构造签名为 `(WorldStore, LifeDefinitions)`。它只处理不可变值和 SQL，可通过 `WorldRuntime.async` 串行执行；需要先调用 `initialize()`。主要方法：

| 方法 | 用途 |
| --- | --- |
| `chooseProfession(player, profession, period)` / `profession(player)` | 保存职业与冷却、查询累计贡献 |
| `feast(requestId, coreCity, actor, recipe, period, availableItems)` | 原子扣仓库并生成宴会回执 |
| `registerDecoration(coreCity, actor, evidence, period)` | 只接受平台生成的真实家具证据 |
| `decorations(city)` / `removeDecoration(city, furniture)` | 查询有限登记集合、显式注销 |
| `settleDecorations(coreCity, period, verifiedFurnitureIds)` | 本期维护扣款、文化与短期效果 |
| `startConcert(...)` / `note(receipt)` / `finishConcert(...)` | 演奏成本、不可变音符凭证与唯一完成回执 |
| `concert(id)` / `concerts(city)` / `cancelConcert(id, performer)` | 重启找回、查询和取消 |
| `view(city, period)` | 累计文化、临时倍率、家具与活动会话 |
| `cultureInPeriod(city, period)` | 当前国家当期已提交文化，最多配置的城市上限 |
| `drainEvolutionCulture(city, period)` | 在模拟事务内消费有限未处理回执，承接迟到活动，失败回滚 |
| `productionMultiplier(city, period)` | 已限幅临时倍率，过期或失效时为 1 |

`LifeDefinitions.defaults/load/writeDefaults` 管理完整的可配置职业、活动、消耗、额度、允许家具、乐器与演奏要求。`LifeState.DecorationEvidence` 和 `NoteReceipt` 仅有包级构造器；受信平台验证后才能产生，不能由公共 API 提交任意 Bukkit 对象代替。

`LifePlatform(JavaPlugin, CoreBridge, CustomItems, LifeDefinitions)` 的 `inspectTargetDecoration`、`inspectDecoration`、`validateDecoration`、`playNote`、`validatePerformer` 和 `availableItems` 必须在 Paper 主线程执行。它们不在数据库工作线程读取玩家、家具或世界。`LifeController(JavaPlugin, WorldRuntime, CoreBridge, CustomItems, LifeService, LifePlatform)` 提供 `boolean execute(Player,String[])`，接受完整参数数组；存储回调通过 `WorldRuntime.reply` 回到玩家线程后，再核实城市、权限和现场位置。具体数据库动作只携带 UUID、数字、字符串和不可变值。

当前 StarCore 公共服务不保证区域线程安全，因此整个插件仍明确拒绝 Folia。生活模块也不会绕过此门禁；后续应在 StarCore 发布安全快照服务后，将家具验证放在锚点区域调度器、玩家音符和 GUI 放在玩家实体调度器，跨区域听众使用不可变快照汇聚。

## 自动验证

`LifeServiceTest` 直接使用真实临时 SQLite 库验证扣款、重复和并发请求、进程重开、上限、职业、家具维护、会话恢复、音符要求、取消、易主和配置版本拒绝。`LifeDefinitionsTest` 验证默认配置完整往返、不覆盖管理员配置及非法定义拒绝。`LifePlatformTest` 验证缺 CE、工作线程、真实声音调用边界、同城听众和家具移动/身份替换/卸载/外部领土的拒绝；其中公共家具返回值采用测试夹具，不替代真实 CE 服务器验收。真人声音体验、CE 热重载和完整上游插件套件应按实际测试服单列验收。
