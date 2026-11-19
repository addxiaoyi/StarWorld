# 森罗物语依赖技术审计

实现版本补记：本文保留审计基线与最初第一阶段建议；0.2.0 已据此实现 Cookery/Tavern 实际仓库商品消费、公共 CE 家具显式登记与逐期复核，以及 StarWorld 自己的受控现场演奏。上游 MusicPlus 无公开完成 API 的结论未变，不能把独立演奏说成自动监听 `/kmusic`。当前功能、降级和验证限度见 [life-integrations.md](life-integrations.md) 与 [test-results.md](test-results.md)。

审计日期：2026-11-19（Asia/Shanghai）。审计方式：读取 Git 远端当前代码、README、Gradle 定义、插件描述符、公开 API、事件触发点、状态与调度代码。本审计不把上游 README 的历史验收范围扩大为 StarWorld 的运行验收。

## 0.2.0 最新代码复审

2026-11-19 再次通过远端固定 `6a432fe54097a3ebf22ba5757e016b602c711f28`（2026-11-19 22:05:49 +08:00，`Verify connected gameplay recovery across normal restarts`）。在 StarWorld 自己的只读审计 checkout 中 fetch 此提交，并比较下文历史 `7a676978…` 基线：仅新增一条提交，21 个文件变化；Paper `1.21.11`、Java 21 主目标和 CraftEngine `26.9.1` 均未改变。Cookery 根工程仍为 Gradle，系列为独立 Gradle 多模块，没有 Maven POM。

此次唯一生产代码变化为包内 `MolotovController.preRemove`：异常归档失败时保留尽可能完整的原物品字节、Paper JSON 或明确标记不完整的应急状态，并允许 CE 完成家具生命周期；不得将恢复记录自动重放成物品。新增单测、双人协议三次正常重启夹具和文档占其余变化。Cookery 公共 API/取菜提交前语义、Tavern `DrinkCreatedEvent` 缺少玩家/位置/交易 ID、Deco 没有城市贡献 API、MusicPlus 没有公开演奏完成 API 的结论全部保持。

上游新报告 `docs/research/series-gameplay-connected-acceptance-2026-11-19.json` 单独记录了当前五插件候选的物品哈希、Paper 132/Folia 14 有界协议测试与失败历史。StarWorld 可依据报告核对候选二进制身份，但它们的 PASS 不替代 StarWorld 适配器验收；不据此声明 Folia 安全、GPU 画面、音频或所有家具玩法通过。StarWorld 实服联调另见 [senluo-acceptance.md](senluo-acceptance.md)。

当前报告中的四个接入候选 SHA-256：

| 插件 | SHA-256 |
| --- | --- |
| Cookery 1.3.0 | `789528ba1d585553cb31cef4a3658693f9f6bd2d52c4669471f967aeefe7ec59` |
| Tavern 0.1.0-dev | `205b36165b519106cda696f0d48ab91e031b6b90ba6bef8ee0d2202155948033` |
| Deco 0.1.0-dev | `1db18976692f5de6b284b31cfc5ef40e14d60f86925f75e87fe7dc3cf807f263` |
| Music 0.1.0-dev | `983fb4b88b39bb5211e12ead23d3202222624e114e2ee4137f48d49d434b1078` |

2026-11-20 StarWorld 实服联调补记：CE26.9.1 的 `ItemStackUtils` 同时公开 `getBukkitStack(Object)` 和 `getBukkitStack(core.item.Item)`。前者要求原生 Minecraft 栈，后者先解包 CE Item；反射时按 `isInstance` 遍历重载可能误选 Object。当前适配器按公共 `Item` 类型精确绑定后一重载，并在公共 `BuildableItem` 接口绑定 `buildItem(CE Player)`、在公共 `Key` 类绑定 `asString()`。新增3项直接运行反射边界的契约测试，不能用 Access mock 代替这一选择验证。

Tavern `catalog.json` 区分配方 `id` 与成品 `output`：`kaleidoscope_tavern:barrel/wine` 是酿造配方ID，其成品为 `kaleidoscope_tavern:wine`。默认生活配置和示例现已使用成品ID；服务端已有自定义 `life.yml` 保留管理员定义，更新时应核对自己的宴会输入，不把配方ID当物品ID。

下文为最初固定提交的详细审计，配合此复审补记阅读。

## 固定代码基线

- 仓库：`https://github.com/addxiaoyi/SenLuo_Plugin.git`
- 分支：`main`
- HEAD：`7a676978f9c4a825275a9f905c3f860ac05a6827`
- 提交时间：`2026-11-19T17:12:22+08:00`
- 提交标题：`Refresh gameplay checkpoint and complete kitchen setup docs`
- 审计时 `git ls-remote origin HEAD` 与本地 `git log -1` 相同；原仓库未修改。

## 架构和依赖

这是 **五个独立 Bukkit 插件及各自 CraftEngine 内容包**，不是单个名为 SenLuo 的插件。根工程是 Cookery；`series/` 是独立 Gradle 多模块工程，包含 `common / doll / tavern / deco / music`。`common` 在 Shadow 阶段重定位进各模块，不能把它当作跨插件共享服务 API。

| 模块 | 运行插件名（softdepend 必须使用此名） | 主类 | 工程版本 |
| --- | --- | --- | --- |
| Cookery | `KaleidoscopeCookeryPlugin` | `net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin` | `1.3.0` |
| Tavern | `KaleidoscopeTavernPlugin` | `net.kaleidoscope.tavern.TavernPlugin` | `0.1.0-dev` |
| Deco | `KaleidoscopeDecoPlugin` | `net.kaleidoscope.deco.DecoPlugin` | `0.1.0-dev` |
| MusicPlus | `KaleidoscopeMusicPlugin` | `net.kaleidoscope.music.MusicPlugin` | `0.1.0-dev` |
| Doll | `KaleidoscopeDollPlugin` | 以本模块 `plugin.yml` 为准 | `0.1.0-dev` |

真实编译基线：Paper API `1.21.11-R0.1-SNAPSHOT`、Java 21、CraftEngine `26.9.1`。根 Cookery 有九组 NMS 源集，1.21.11 对应 `v1_21_R7`；26.x NMS 编译额外要求 Java 25，但主字节码目标仍为 21。StarWorld 不应为了接入食物、家具而复制这些 NMS 适配或引入 Java 25 要求。

五个运行插件都硬依赖 CraftEngine，描述符声明 `folia-supported: true`。Tavern 软依赖 Cookery 与 Dominion，Cookery 可选 Dominion / PlaceholderAPI / UltimateAdvancementAPI。系列模块的 CraftEngine 核心和 Bukkit API 为 `compileOnly`，AntiGriefLib 被私有重定位。StarWorld 应保持森罗模块可选，不打包森罗或 CraftEngine 实现。

主要证据：根 `README.md`、`build.gradle`、`gradle.properties`、`src/main/resources/plugin.yml`；`series/README.md`、`series/build.gradle`、`series/gradle.properties` 和四个模块各自 `plugin.yml`。

## Cookery 公开 API

`net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI` 是源码明确标注的静态公开入口，不能改为反射读取控制器字段。

| 方法 | 含义 |
| --- | --- |
| `plugin()` | 当前 Bukkit Plugin |
| `choppingBoardKnives()` | 菜板刀具注册表 |
| `millstoneAnimals()` | 磨盘动物配置注册表 |
| `itemTags()` / `blockTags()` | 食材与原版方块标签 |
| `entityProperties()` | 实体属性注册表 |
| `recipeMenuStyle()` / `recipeMenuHooks()` | 食谱菜单样式与替换钩子 |
| `potCookConditions()` | 烹饪条件注册表 |
| `foodRecipes()` | `FoodRecipeRegistry`，读取、匹配或扩展真实食谱 |
| `applianceFoods()` | `ApplianceFoodRegistry`，设备原料允许表 |
| `soupBases()` | 炖锅汤底注册表 |

`FoodRecipeRegistry` 提供 `potRecipes()`、`stockpotRecipes()`、`accurateRecipes(ApplianceType)`、`flexRecipes(ApplianceType)`、`findPotById(Key)`、`findStockpotById(Key)`、`planAccurate(...)`、`planFlex(...)`、`planPot(...)`、`planStockpot(...)` 等方法。配置发布带 `readSnapshot(Supplier<T>)`、`generation()`、`isConfigurationLoading()`、`afterConfigurationLoad(Runnable)`；多步读取应使用同一快照，不与 CE 热重载争用。StarWorld 第一阶段不需要修改其食谱注册表。

### 可监听事件与真实提交语义

| 事件类（包均在 `net.kaleidoscope.cookery.api.event`） | 字段/访问器 | 边界 |
| --- | --- | --- |
| `PotExtractDishEvent` | `getPlayer()`、`location()`、`dish()`、`setDish(ItemStack)` | `PlayerEvent`、可取消；炒锅取菜提交前 |
| `StockpotExtractDishEvent` | 同上 | `PlayerEvent`、可取消；炖锅取菜提交前 |
| `MillstoneGrindCompleteEvent` | `player()`（动物驱动时为 null）、`location()`、`products()`（可变 List） | 可取消；产品掉落实体前 |
| 其他事件 | `SickleHarvestEvent`、`ShawarmaExtractEvent`、`PotStirFryEvent`、`SteamerBreakFullEvent`、`CookeryEffectChangeEvent` | 对应玩法钩子；不能推定为已提交生产回执 |

**不可将以上取菜/研磨事件直接计为城市已完成生产。** `PotBehavior` 事件返回后还要校验成品数量、容器和食物状态，`serveDish` 才逐份消费并交给玩家；`MillstoneController` 事件后还要生成掉落、校验实体并在失败时回滚。`MONITOR + ignoreCancelled` 只能证明监听时未取消，不能证明后续提交成功。这些事件没有稳定、可持久化、可幂等消费的交易 ID。

来源：`KaleidoscopeCookeryAPI.java:27–130`；`PotExtractDishEvent.java:15–74`；`StockpotExtractDishEvent.java:15–74`；`MillstoneGrindCompleteEvent.java:18–75`；`block/behavior/PotBehavior.java` 的 `handleExtractDish` / `serveDish`；`block/entity/MillstoneController.java` 的产品掉落和 `rollbackDropped`。

## Tavern 公开事件与限制

`net.kaleidoscope.tavern.api.DrinkCreatedEvent` 为不可取消通知：

```java
String drinkId();
ItemStack result();                 // 返回 clone
List<ItemStack> ingredients();      // 返回元素 clone
```

它在 `Drinks.cocktail(String, List<ItemStack>)` 构造成品时触发（`Drinks.java:50–59`）。**不包含 Player、Location、城市、家具 UUID、唯一交易 ID 或提交回执**。不能通过“最近玩家”“最近酒桶”猜测城市，也不能对该事件无限累加城市资源。它适合诊断或无权威性的配方观察，不适合第一阶段的自动产出账本。

Tavern 的酒桶、果盆、葡萄、龙头、雪克杯与饮品效果已在其内部实现。StarWorld 不应重新实现发酵或私自读写家具 NBT。可以使用现存 CE 物品 ID 作产业配方输入、酒馆补给或节庆消耗；该动作必须由 StarWorld 自己的登记设施、权限和库存扣除流程授权。

## Deco 与 MusicPlus 的公开边界

### Deco

当前没有 `net.kaleidoscope.deco.api`、Bukkit ServicesManager 城市服务或稳定的装饰贡献事件。`DecoPlugin` 的 `entries`、`styles`、`stateKey` 是私有或包级状态；公开嵌套 `Entry` / `Catalog` 仅为数据类型，并不意味着存在可访问的目录服务。

现有真实接口是 **CraftEngine 公共家具和物品 API**，而不是“Deco API”。例如系列代码使用 `CraftEngineFurniture.getLoadedFurnitureByMetaEntity(Entity)` 解析当前加载家具，取得 `uuid()`、`id()`、`isValid()`；CE 的 `FurnitureBreakEvent` 被 Deco 监听。注册功能建筑可以要求玩家显式选定少量家具锚点，由所属区域线程做查询、核对配置允许的 ID/UUID，并记录在 StarWorld。不得持续扫描实体、加载全城所有区块或写 Deco 的 `deco_state_v1`。

如果第一阶段只检查“Deco 是否安装”而尚未核实家具锚点，它只能显示集成状态或提供装饰建设指南，不能给出“家具识别/繁荣加成已完成”的结论。上游完整家具生命周期（含强制删除、独立卸载、热重载、跨区域）也不能仅凭注册一次就永久有效。

### MusicPlus

真实插件名是 `KaleidoscopeMusicPlugin`，不是 `MusicPlus`。当前只有 `/kmusic`、`PlayerInteractEvent` 打开键盘、玩家实体调度器 MIDI 播放；`keyboard`、`loadSong`、`startSong`、`playNote`、`stopSong` 和播放表均为私有。

**没有公开的演奏开始/结束/音符/节庆贡献事件，也没有公开播放服务。** StarWorld 不能读取私有 `playing` 表，不能把执行 `/kmusic` 当作一次有效演奏。第一阶段可在节庆界面提供真实乐器 ID `musicplus:handheld_instrument` 与使用说明；自动“演奏提升城市产出”必须明确标成暂不可用，等待上游新增带玩家、位置、会话 ID、完成时长的公开事件。不要伪装已实现。

## CraftEngine 物品接入

当前系列自己的 `Items` 工具证实可用的 CE 调用：

```java
Key custom = CraftEngineItems.getCustomItemId(stack);
String id = custom == null ? stack.getType().getKey().toString() : custom.asString();
var buildable = BukkitItemManager.instance().getBuildableItem(Key.of(id));
var item = buildable.orElseThrow().buildItem((net.momirealms.craftengine.core.entity.player.Player) null);
ItemStack stack = ItemStackUtils.getBukkitStack(item).clone();
```

参考：`series/common/src/main/java/net/kaleidoscope/series/Items.java:18–51`。StarWorld 应在自己的可选 CE 适配器隔离版本相关调用；Minecraft 原版物品走 Bukkit Material，CE 缺失/未知 ID 时关闭对应配方并显示错误，不能静默替换为石头或免费产品。若缓存模板，应在 CE 资源重载后失效；系列有 `SeriesResourceReloadListener` 监听 `CraftEngineReloadEvent` 调用 `Items.clearCache()`。

命名空间为 `kaleidoscopecookery:*`、`kaleidoscope_tavern:*`、`kaleidoscope_deco:*`（Deco 还包含分类来源命名空间，必须以实际 CE 加载目录为准）、`musicplus:handheld_instrument`。Cookery 的确切前缀见 `item/ItemKeys.java:9`，不能误写成 `kaleidoscope_cookery`。不能仅用命名空间推断物品已加载；应做存在性检查。

可用于 StarWorld 自定义配置的实际 ID 样例：`kaleidoscopecookery:baozi`（包子）、`kaleidoscopecookery:oil`（油）、`kaleidoscope_tavern:wine`、`kaleidoscope_tavern:honey_wine`。后两项为 Tavern `src/main/resources/catalog.json` 中配方的 `output` 物品 ID；`kaleidoscope_tavern:barrel/wine` 等带 `barrel/` 的 ID 是配方 ID，不能用于构造物品。这些仍须安装对应 CE 内容包并完成成功加载。Cookery 和 Tavern 产物均通过 CE 自定义物品表示，不是仅凭显示名区分的原版物品。

## 数据与线程规则

森罗家具状态使用 `namespace/schema_version/source_commit/payload` 信封，经 CE 控制器保存。异常/未知版本拒绝操作并原样保留；强制移除时部分控制器会写 `recovery/*.nbt` 管理员核对快照，**不是自动退款凭证**。StarWorld 应使用独立存档，不修改这些信封或恢复文件。

Cookery `FoliaUtil`、系列 `SeriesScheduler` 和 Music 的 `Player#getScheduler()` 展示了真实线程边界：

- 玩家 GUI、背包、权限涉及玩家实体操作时在其实体调度器执行，处理退休回调；
- 家具、方块查询在坐标所属区域调度器执行；不能从数据库线程或全局调度器访问世界；
- 全局/异步路径只处理纯值快照、配置和文件，不能把可变 Bukkit 对象带入长期状态；
- 事件默认同步不代表 Folia 全局单线程，必须保留触发区域语义；事件监听器只摘取不可变值，数据库事务经自己的串行入口处理；
- 插件关闭必须让已排队任务失效；重载不应重复注册监听、重复结算或继续操作旧家具实例。

## 建议的第一阶段复用与降级矩阵

| 能力 | 最小真实实现 | 缺失时的行为 |
| --- | --- | --- |
| Cookery 食材/菜肴 | CE 物品 ID 配方输入/城市补给；物理物品扣除只经 StarWorld 自己的库存流程 | 对应 CE 配方不可用，保留原版生产链；状态页说明 Cookery/CE 缺失 |
| Tavern 饮品 | 相同方式接受已存在饮品，用于节庆或商业维护；不接管酿造状态 | 关闭饮品配方；不监听无归属事件来发城市资源 |
| Deco | 可配置明确家具 ID、玩家显式登记锚点，所属线程验证后记录；未实现锚点验证前只列状态 | 原版建筑登记继续可用，装饰识别/加成显示未支持 |
| MusicPlus | 显示乐器和真实 `/kmusic` 指引；自动节庆贡献等待公开事件 | 节庆世界事件继续运行；不自动奖励演奏贡献 |
| CraftEngine | 可选适配器解析/构造 ID；未知 ID 拒绝，不静默回退 | 原版物品生产链可独立玩 |

“检测到插件”与“可用 API 能力”必须分开呈现。在无森罗模块的服务器上，StarWorld 应仍然完整执行原版第一阶段链、持久化与 GUI。上述四模块内部生产逻辑和权威数据均不由 StarWorld 复制。

## 后续上游 API 请求（非第一阶段伪实现）

1. Cookery：提交成功后带不可复用交易 ID、玩家/设备 UUID、位置、实际份数的通知。
2. Tavern：成品领取提交后带玩家、位置、家具 UUID、批次 ID、实际结果数量的事件。
3. Deco：稳定的目录只读 API 与家具放置/变更/移除提交事件，附 ID、UUID、锚点、变体。
4. Music：演奏会话开始/完成/停止事件，附会话 UUID、玩家、地点、实际时长；公共播放服务说明调用线程。
5. 各模块：版本化 API 工件发布和兼容声明，避免依赖开发 JAR 文件名或复制内部类。

## 验证范围

本文件完成源码、构建定义、API 和触发点审计；未在本子任务运行上游 912 项 Java 测试或其历史游戏测试服。上游 README 也明确当前开发版仍有真人操作、画面、声音、跨区、CE 热重载与部分家具未验收范围。StarWorld 的最终测试必须单列自己的源码单测、Paper/Folia 启动与玩家流程，不能转引上游测试数作为本工程通过数。
