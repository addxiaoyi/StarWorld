# StarWorld

持续演化的 Minecraft 文明世界。独立 Maven/Paper 插件，复用 **StarCore 的国家、城市、领土、科技、政策、繁荣、天气、时代和资源服务**，保存自己的地理产业、城市发展、交通、文化与生活状态，不创建第二套国家核心。当前源代码版本为 **0.2.0**。

代码仓库：[addxiaoyi/StarWorld](https://github.com/addxiaoyi/StarWorld)。技术基线：Java 21、Paper **1.21.11**，CraftEngine **26.9.1** 为可选内容依赖。审计固定 StarCore `f769e62799e769a18fe51305b545869a17f80cd1` 与 SenLuo `7a676978f9c4a825275a9f905c3f860ac05a6827`，见 [StarCore 审计](docs/audit-starcore.md)、[森罗审计](docs/audit-senluo.md)。

**Folia 仍为兼容规划。** 当前 StarCore 可变服务缺少区域线程安全契约，本版检测 Folia 后明确停用。缺 StarCore 时可独立安装、生成配置和数据库并显示降级原因；城市玩法需要真实 StarCore 国家/城市模块，不会自动建立替代国家。森罗模块或 CE 缺失时保留原版产业、贸易和音符盒演奏；缺失内容或 API 明确停用。

## 构建、安装和升级

```sh
./mvnw -B --no-transfer-progress clean verify
```

Windows 使用 `.\mvnw.cmd -B --no-transfer-progress clean verify`，`JAVA_HOME` 指向 JDK 21。Maven Wrapper 使用 Maven 3.9.11；插件包含 SQLite JDBC，不打包外部核心或森罗实现。

产物为 `target/starworld-0.2.0.jar` 和 `target/starworld-0.2.0-sources.jar`；发布脚本生成 `target/StarWorld-0.2.0.zip`，包含插件、源码、配置、文档与校验和。只安装主插件 JAR，源码包和 `original-*` 不是运行插件。

1. 正常关服，备份整个 `plugins/StarWorld/`。升级时保留数据库和持久配置 ID。
2. 放置插件 JAR，使用 Paper 1.21.11 和审计兼容的 StarCore 启动。
3. 首次生成 `config.yml`、`definitions.yml`、`evolution.yml`、`logistics.yml`、`exports.yml`、`life.yml` 及 `starworld.db`。
4. `/sw status` 查看真实能力；用 StarCore 自身命令建立国家、城市和国家/城市**两层区块领土**。
5. 放置堆肥桶、切石机、高炉，看向六格内本城锚点，执行 `/sw register farm|sawmill|workshop`。先积累粮食，再供应林业和军工。

原有配置 v1 自动备份后迁移 v2；扩展定义单独按版本加载，只补充旧定义里不存在的 ID，管理员已有覆盖优先。数据库做加法式事务迁移，拒绝未知高版本。卸载仅删除 JAR，数据保留；降级恢复对应备份。正式存档不要随意更改生产周期长度。

## 已实现的世界玩法

| 系统 | 0.2.0 行为 |
| --- | --- |
| 六类地理 | 山脉、河流、平原、森林、沙漠、雪原；农业/运输/军事属性；地区矿藏/林木/肥力及恢复率 |
| 基础产业 | 肥力→小麦→面包；面包维护+林木→原木→木板；面包维护+矿藏→粗铁→铁锭→箭；新增造纸、食品加工与盾牌 |
| 九类设施 | 农场、林场、工坊、仓库、市场、学院、兵营、水井、厨房；显式锚点登记、三级升级、真实维护成本 |
| 五种文明 | 农耕、工业、商业、尚武、文化；国家统一选择，六周期转型冷却，各产业有取舍 |
| 城市发展 | 六种规划、四种专长；知识、稳定、城市繁荣、军需、发展倾向和有限历史；仓库容量依赖有效且维护过的仓库 |
| 国家发展 | 按当前归属城市的真实结算聚合农业、工业、贸易、军需和文化倾向；五类国家专长有知识/行为门槛与六周期冷却，失去贡献城市会暂停加成 |
| 区域文化 | 有效设施积累本国传统；完成贸易后凭唯一回执交流；地域文化有限影响产业 |
| 十一种事件 | 干旱、暴雪、丰收、洪水、饥荒、资源危机、商业繁荣、文化节庆、边境争端、衰落、复兴；条件/权重/冷却/余波 |
| 交通贸易 | 道路、桥梁、港口、关隘；显式商路、地形运时、共享容量、维护、双向实物托管、供需与战争封锁 |
| 生活职业 | 厨师、酿酒师、装饰工匠、乐师；仓库消耗、个人/城市限额、临时产业效果和重启恢复 |
| 国家资源出口 | 配置实物成本兑换已有国家抽象资源分类；outbox、稳定事务 ID、核心持久回执、退避重试 |
| 核心军队战斗 | 已提交真实军需与地形通过公开army_combat贡献点影响有效设施区块的军队伤害 |
| GUI/API | 城市仓储/设施与发展GUI，生活/贸易命令入口；不可变 Future 查询及提交后事件 |

运输路径是管理者声明的有限节点路线，不证明沿途铺满道路。兵营实际消耗箭形成军需；当前或前一周期的有效供给通过核心公开扩展点提供最多30%军需增伤、20%额外地形减伤，只限有效设施区块。核心取贡献者最高伤害，其他插件可覆盖减伤；不修改真人PVP或核心军队补给。见[战斗联动](docs/combat.md)。城市繁荣为本地属性，不重复写无幂等回执的国家繁荣。

默认五分钟一周期，停服和未加载区块不补发。产业需要维护和投入，注销登记不刷新矿藏。设施损坏、区块卸载、城市/国家丢领或转国会停产。失去资源地会切断原料；战争可暂停商路，军工城市需要粮食与材料补给。

## 常用命令

```text
/sw menu|develop|status|stock|buildings|development|history|culture|nation
/sw register <设施ID>
/sw deposit|withdraw <物品ID> <1–2304>
/sw upgrade|remove <设施UUID>
/sw civilization <agrarian|industrial|mercantile|martial|scholarly>
/sw plan <balanced|agriculture|industry|commerce|culture|military>
/sw specialty <granary|arsenal|woodworking|gastronomy>
/sw nation-development
/sw national-specialty <agriculture|industry|commerce|military|culture>
/sw node|route|trade|demand help
/sw profession <chef|brewer|artisan|musician>
/sw feast <community|cookery|tavern>
/sw decoration <register|list|remove UUID>
/sw concert <start|list|note UUID 音高|finish UUID|cancel UUID>
/sw export <契约ID> <批数>
/sw outbox
```

`starworld.use` 默认所有玩家。设施、公共仓库、规划、贸易及有成本的生活活动需要城市管理权限；全国文明和国家专长还需 StarCore 国家创始人。`starworld.admin` 默认 OP，可覆盖管理权限，但不能证明无效领土/锚点有效。管理员可用：

```text
/sw event <事件ID>
/sw pending
/sw resolve <物品凭据UUID> <credit|discard>
/sw outbox <retry|cancel> <事务ID>
```

背包存档与 SQLite 没有共同事务，异常遗留凭据必须核对。`resolve credit` 只向城市库存记账一次，`discard` 只确认；活跃操作不能人工处理。已发送国家 outbox 可能在核心提交，不能直接退款；按原事务回执恢复。见[架构](docs/architecture.md)和[国家事务](docs/core-extension.md)。

## 配置与森罗内容

| 文件 | 内容 |
| --- | --- |
| [config.yml](src/main/resources/config.yml) | 周期、批量、设施和库存上限 |
| [definitions.yml](src/main/resources/definitions.yml) | 六地形、基础文明、产业、事件 |
| [evolution.yml](src/main/resources/evolution.yml) | 加法式设施/配方/文明/事件，规划、专长和条件 |
| [logistics.yml](src/main/resources/logistics.yml) | 节点、成本、容量、运输和批量限制 |
| [exports.yml](src/main/resources/exports.yml) | 国家资源出口契约及有效设施要求 |
| [life.yml示例](examples/life.yml) | 首次由完整默认值生成；职业、宴会、家具、乐器、限额及效果 |

自定义物品须为真实 CE ID；未知/未加载 ID 不静默替换。Cookery 菜肴和 Tavern 饮品以实际入库商品参与宴会，不用提交前事件刷奖励。Deco 通过公共 CE 家具 API 对登记 UUID 逐期复核。MusicPlus 没有公开完成 API；`/sw concert` 是 StarWorld 自己控制的现场演奏，可用真实森罗乐器，不自动奖励 `/kmusic`。见[生活联动](docs/life-integrations.md)、[森罗配方示例](examples/senluo-recipes.yml)。

## 文档与验证证据

[需求映射](docs/requirements.md) · [架构](docs/architecture.md) · [Java API](docs/API.md) · [玩法验收](docs/gameplay.md) · [交通贸易](docs/logistics.md) · [国家服务](docs/core-extension.md) · [战斗联动](docs/combat.md) · [生活联动](docs/life-integrations.md) · [测试结果](docs/test-results.md) · [后续边界](docs/roadmap.md)

`clean verify` 运行 JUnit、真实 SQLite、并发和独立进程崩溃恢复测试，生成 `target/surefire-reports/` 与 `target/site/jacoco/`。实际通过数、JAR SHA256、Paper 客户端步骤和限度以[测试结果](docs/test-results.md)及机器证据为准。旧版原版产业有真服验证基础；新增模块的模拟契约测试不等于整套 CE/森罗多人联服或 Folia 已通过。
