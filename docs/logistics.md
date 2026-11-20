# 交通、供需与托管贸易

交通模块直接使用 StarWorld 的城市实物仓储，在同一个 `WorldStore` SQLite 连接和事务中保存路网、维护、货单与完成回执。它不创建国家、金币或 StarCore 资源表；小麦、铁锭等实物也不会被暗中换算成 StarCore 抽象资源。

## 实际玩法

1. 用 `/sw deposit minecraft:oak_planks 64` 等现有仓储命令准备建设物资。看向本城市与本国双重领地里的正确锚点，执行 `/sw node register road`。可选节点为 `road` 道路驿站（磁石）、`bridge` 桥梁（錾制石砖）、`port` 港口（木桶）、`pass` 关隘（磨制安山岩）。登记消耗真实木板/铁锭，节点可以升级至三级。
2. 双方用 `/sw node list` 获取节点 UUID。起点城市管理者执行 `/sw route create <目标城市UUID> land <起点UUID,途经UUID,终点UUID>`。海运使用 `sea`，且路径只能包含港口。只支持同世界的显式节点路径；跨维度运输被拒绝。
3. 起点管理者执行 `/sw trade offer <商路UUID> minecraft:bread=16 minecraft:iron_ingot=4`。面包立即从起点实物仓储转入数据库托管。多种物品用逗号分隔，例如 `minecraft:bread=16,minecraft:oak_planks=8`。
4. 目标城市管理者执行 `/sw trade accept <货单UUID>` 后，四枚铁锭也转入托管。接受动作同时同意路径中本城节点的维护。双方城市库存只有完成运输时才同时收到对方物品；一方无法单独提取托管货物。
5. `/sw trade list [页码]` 显示状态、运送进度、双方报价与暂停原因。`/sw route list [页码]` 显示容量与时长。`/sw trade cancel <货单UUID>` 在未接受时只允许报价方；接受后任一方管理者可以取消整笔交易，双方原物在同一事务退还。仓储满会拒绝整笔退还，托管保持原样，可腾出空间后重试。

`/sw node upgrade <UUID>` 消耗建设物资，提升容量与速度。`/sw node enable|disable <UUID>` 和 `/sw route enable|disable <UUID>` 可停用或恢复设施/商路。停用不销毁托管、不返还建设费用。命令均复核当前城市管理权限，管理员权限也不能绕过领土与国家归属、实体锚点或跨国战争检查。

`/sw demand <物品ID> <目标库存>` 设置本城需求目标，`/sw demand list [页码]` 展示可用仓储、过剩和短缺。托管中的物资已扣除，因此不会重复出现在供给里。它提供真实库存的供需信息，价格由双方选择实物报价确定，不凭空生成经济余额。

## 运力观察与双方改道

0.3.0 的 `/sw route status <商路UUID>` 向双方城市显示当前周期的持久运力、已占用/剩余、待运/暂停货单、瓶颈节点、节点维护和暂停原因。它只查询已登记路径的持久计数，不检查所有世界节点；当前领土、加载状态、锚点和外交仍在实际结算现场复核。观察到剩余容量不保证下一次运输一定成功。

货单被原路维护、容量或停用阻塞时，可以先建立另一条相同起点和终点方向的显式路线，再使用：

| 命令 | 行为 |
| --- | --- |
| `/sw trade alternatives <货单UUID>` | 最多十六条已登记且当前复核有效的同方向候选，按运时和容量排序；不探索世界 |
| `/sw trade reroute <货单UUID> <新商路UUID>` | 货单任一方管理者提议，须保持原双方城市、国家、货物与容量约束 |
| `/sw trade reroute-accept <请求UUID>` | 另一城市管理者再次核对路线并确认新的维护义务 |
| `/sw trade reroute-cancel <请求UUID>` | 任一端管理者撤销尚未生效的提议，不撤销货单本身 |
| `/sw trade reroutes [页码]` | 查看本城待确认改道和到期周期 |

提议不移动货物、不收费、不预占容量；每张货单最多一个未过期的待确认提议，到期时长沿用 `offer-lifetime-periods`。接受时再次检查原国归属、战争/外交、路线方向、启用节点与容量，任何失效均拒绝。成功接受后原托管不变，运输进度清零，从下一周期重新走完整新行程；旧路本周期已花掉的维护和共享运力不退还。稳定请求 UUID 和已生效状态阻止重复接受再次重置行程。

国家金币市场会创建带已支付标记的单向物流，仍使用相同维护、容量、改道和完成收据；不能用 `/sw trade cancel` 或未接受报价的自动过期把它按易货退款。其国家付款回执与恢复规则见 [market.md](market.md)。

## 运输结算与安全边界

- 路径由有限个登记节点组成，不扫描道路方块或沿途区块。距离为节点间水平直线段的总长；路径是管理者声明的交通线，不证明沿线已铺满道路。节点必须位于双方城市，禁止向第三方仓储收取维护费。
- 地形、节点类型与等级共同决定运时，瓶颈节点决定每周期容量。运输按货单的双向总件数同时占用商路与每个经过节点的容量；复制多条商路不能绕过共享关隘或港口的容量。超出单周期容量的报价必须拆单；不同物品当前均按一件一个容量单位计。
- 只对实际尝试运输的有效节点收费；共享节点每周期最多支付一次维护，材料从节点所属城市仓储扣除。维护不足、失去城市/国家领土、未加载区块、锚点损坏、商路停用和战争/敌对关系都会暂停运输。没有强制加载区块。
- 只在当前周期推进一步。重启或离线跨过很多周期不会补发移动、维护或产物。同周期重复调用不会再次移动、扣费或交付。
- 创建报价、接受、双向交付、取消、过期退还及成功回执各自使用同库事务。稳定 UUID 可供 API 调用者重试，已完成交易不会再次发货。容量不足时保留托管，不丢弃物品。
- 一方城市改属其他国家时，旧国家货单冻结，无法被新所有者领取或通过取消获得旧托管；恢复原归属后才可继续。尚无跨国征收/战利品的管理员裁决入口。
- 跨国贸易要求双方 StarCore `war` 与 `diplomacy` 能力都可读取，双方均没有战争对手且关系属于 `NEUTRAL/FRIENDLY/ALLIED/CEASE_FIRE/VASSAL`。缺失模块或未知关系会拒绝跨国贸易。同国城市间贸易不依赖可选外交模块。

## 配置

`LogisticsConfig.load(ConfigurationSection)` 在主线程读取独立的 `logistics.yml`。缺失键使用默认值，非法数值、版本或未知节点类型在加载时拒绝；嵌套映射、数量、容量与批处理均有上限。配置对象与货单记录不可变。重载配置应重建服务，现有货单保留接受时的运时；当前节点维护、容量使用新配置。容量变小的易货货单可以取消并拆单；已支付市场货物继续托管，需要恢复容量或双方改走足够容量的路线。

```yaml
schema-version: 1
blocks-per-period: 128
offer-lifetime-periods: 24
limits:
  nodes-per-city: 24
  routes-per-city: 12
  waypoints: 16
  open-orders-per-city: 100
  batch: 50
  travel-periods: 1000
  items-per-order: 16
terrain-time:
  plains: 1.0
  mountains: 2.2
  forest: 1.4
  desert: 1.6
  snow: 2.0
  river: 1.8
nodes:
  road:
    name: 道路驿站
    anchor: LODESTONE
    capacity: 256
    time-factor: 0.8
    construction:
      minecraft:oak_planks: 16
    maintenance:
      minecraft:oak_planks: 1
```

## Java API 与调度

`new LogisticsService(store, definitions)` 自动 `CREATE TABLE IF NOT EXISTS logistics_*`，不改变共享数据库的 `PRAGMA user_version`。其方法为同步存储操作，放在 StarWorld 串行 IO worker 中调用：

| API | 合同 |
|---|---|
| `registerNode(id,city,type,site,terrain,auth)` | 验证主线程授权，原子扣建设物资；相同请求 ID 不重复收费 |
| `upgradeNode / setNodeEnabled` | 权限、归属和实体校验；停用不删除记录 |
| `createRoute(id,origin,target,path,mode,auth)` | 有界显式路径；仅起点管理者发起，交易时目标方接受 |
| `createOffer(id,routeId,offered,requested,auth)` | 起点仓储原物托管，稳定 ID 幂等 |
| `accept(id,auth) / cancel(id,auth)` | 目标方接受/原子双向撤单，均重新检查当前国家归属 |
| `dueOrders(period,limit)` | 仅返回尚未处理当前周期的有界批次 |
| `settleCaptured(auth,ids)` | 只处理已捕获授权的确定货单 ID，事务内维护、容量、交付与回执 |
| `nodes / routes / pendingOrders / cityOrders` | 有界分页只读快照；节点 ID、所属城/国、Site、类型可供主线程校验 |
| `setDemand / supplyDemand` | 城市经理目标与实物仓储的供需快照 |
| `completedAfter(cursor,limit)` | 单调 sequence 的持久成功回执，供文化传播消费 |
| `alternativeRoutes(orderId,auth,limit)` | 最多十六条已登记同方向且当前证据有效、容量足够的替代路线 |
| `proposeReroute / acceptReroute / cancelReroute` | 持久、双方确认改道，不动原物品，不退过去周期的运力 |
| `pendingReroutes(city,limit,offset)` | 有界待确认提议查询；返回到期周期供调用者展示 |
| `transportStatus(routeId,period)` | 只读持久商路/节点剩余运力、维护、待运/暂停与瓶颈快照 |

`AuthoritySnapshot` 的 `cityNations`、`managedCities`、`controlledAnchors`、`validNodes`、`permittedTrades` 是调用者在当前周期捕获的授权证据。`TradePair.of(cityA,cityB)` 对称规范化。节点缺失、城市缺失或外交许可缺失均不会被视为有效。数据库维护最高已用周期，旧周期快照被拒绝；同周期内调用方仍须在每次操作前重新捕获证据，不能缓存一份快照长期授予权限。

`LogisticsController(JavaPlugin,WorldRuntime,CoreBridge,CoreExtensionPort,LogisticsService)` 是现成 Paper 调度边界。`execute(player,args)` 接受完整的子命令参数数组，处理 `node/route/trade/demand`，无关命令返回 false。异步读取持久节点/商路后切回主线程复核核心城市、权限、节点锚点与外交；再提交纯存储操作。`tickFuture()` 每次最多处理 `min(batch,20)` 笔货单，仅读取关联路径而非枚举所有城市，返回 `CompletableFuture<BatchResult>`。`capture(...)` 可供其他主线程协调器获取同样的不可变证据。

文化模块将 `completedAfter` 的 `sequence` 游标和自身文化变化写入同一个 `WorldStore` 事务，即可在重启后继续消费成功商路。每个成功回执包含源/目标城市与国家、交付周期、双向物品，取消或暂停不产生成功回执。跨库消费者仍须自行持久化去重游标；不得根据回执再次发放贸易物品。

## 自动化验证范围

测试使用真实 SQLite，覆盖四类节点建设升级、权限与领土拒绝、海运/地形运时、跨维度拒绝、报价与接受幂等、双向实物托管、跨周期运输与无离线补发、重启恢复、唯一文化回执、战争和丢领暂停、征服冻结、共享维护/容量、批处理上限、目的仓储满、撤单回滚、到期延后退还、供需、配置与命令报价语法。测试由根工程 Maven 串行验证，具体通过数量以最终测试结果文档为准。
