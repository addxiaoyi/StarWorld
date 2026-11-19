# StarWorld

Minecraft 动态文明与地区产业扩展。独立 Maven/Paper 插件，复用 **StarCore 国家、城市、领土、科技、政策、繁荣与资源公开 API**，不另建国家核心、不访问 StarCore 权威表。当前交付 **0.1.0，第一阶段**。

## 环境与审计

- Java 21，Paper **1.21.11**，可选 CraftEngine 26.9.1。
- StarCore 审计提交 `f769e62799e769a18fe51305b545869a17f80cd1`。
- SenLuo 审计提交 `7a676978f9c4a825275a9f905c3f860ac05a6827`。
- **本版不声明 Folia 支持**：现有 StarCore 可变模型与同步调度缺少区域线程契约。检测 Folia 时明确停用。已有调度边界与迁移方案，见[架构](docs/architecture.md)。
- 缺 StarCore 时可独立加载配置/数据库、查询降级状态；完整城市玩法需要 StarCore 国家和城市模块。不会创建替代国家。

详细审计：[StarCore](docs/audit-starcore.md) · [森罗物语](docs/audit-senluo.md)。源码：<https://github.com/addxiaoyi/StarWorld>。

## 构建与安装

```sh
./mvnw -B clean verify
# Windows，JAVA_HOME 指向 JDK 21 或以上
.\mvnw.cmd -B clean verify
```

产物：`target/starworld-0.1.0.jar`（包含 SQLite JDBC）；源码：`target/starworld-0.1.0-sources.jar`。**不要安装 original 或 sources JAR。**

1. 正常关闭测试服，备份既有 `plugins/StarWorld/`。
2. 将插件放入 `plugins/`，与经核验的 StarCore 一起启动 Paper 1.21.11。
3. 首次生成 `config.yml`、`definitions.yml`、`starworld.db`。执行 `/sw status` 查看真实集成能力。
4. 使用 StarCore 自身命令建立国家/城市，取得**国家与城市两层区块领地**。
5. 放置堆肥桶、切石机、高炉，站在城市领地内看向六格内锚点，执行 `/sw register farm|sawmill|workshop`，或 `/sw` 点击登记。

## 第一阶段实际玩法

| 内容 | 已实现行为 |
|---|---|
| 六类地区 | 山脉、河流、平原、森林、沙漠、雪原，单点群系采样、独立资源储量及恢复、农业/运输/军事属性 |
| 农业链 | 地区肥力 → 小麦 → 面包，受地形、文明、事件与国家状态影响 |
| 林业链 | 面包维护 + 地区林木 → 原木 → 木板，运输阻力影响产量 |
| 军工链 | 面包维护 + 地区矿藏 → 粗铁；粗铁+原木 → 铁锭；铁锭+木板 → 箭 |
| 三种设施 | 农场/食品作坊（堆肥桶）、林场/锯木厂（切石机）、矿业/军工作坊（高炉） |
| 登记与升级 | 每区块同类限一座，默认每城十二座；最高三级，升级消耗真实木板/铁锭库存 |
| 两种文明 | 农耕传统、工业传统；国家创始人选择，影响全国已登记城市，转型冷却六周期 |
| 三类事件 | 干旱、暴雪、丰收，确定性抽选，持续与余波分别计算，重启恢复 |
| 国家联动 | 每次重新验证国家、城市、成员与双层领土，读取真实科技/政策/繁荣修正，显示国家资源库存 |
| 实际物品 | 仓库存取、食物维护、加工投入、实体箭产出；不把国家抽象资源伪装成物品 |
| GUI | 查看国家/文明/倍率/仓储/事件/结算/设施，点击登记、清单、刷新 |

军事系数当前为公开战略属性，不修改 StarCore 战斗判定。道路/港口/商路、文化传播、人口与完整城市规划等后续设计见[路线图](docs/roadmap.md)。

默认五分钟一个生产周期，不补发停服或未加载区块期间产量。先用农场积累面包，林场/工坊随后消耗面包开工。同城市设施共享仓储，按 UUID 稳定顺序处理，下游可能在下一周期拿到原料。只放高炉不会凭空产武器。

```text
/sw menu|help|status|stock|buildings
/sw register <farm|sawmill|workshop>
/sw deposit minecraft:oak_log 16
/sw withdraw minecraft:arrow 12
/sw upgrade <设施UUID>
/sw remove <设施UUID>
/sw civilization <agrarian|industrial>
```

失去领土、城市解散/转国、锚点损坏、区块卸载时相应设施停产且不扣维护。城市转国后旧设施需新归属下重新登记；重复注销登记不能重置矿藏。数量范围 1–2304。

`starworld.use` 默认所有人；设施管理、存取、升级另需 StarCore 市长/国家创始人身份。全国文明另需国家创始人。`starworld.admin` 默认 OP，可覆盖管理权限及使用：

```text
/sw event <drought|blizzard|harvest>
/sw pending
/sw resolve <物品凭据UUID> <credit|discard>
```

`credit` 向城市库存记入该数量（存入补记/取出退回）；`discard` 仅确认。**先核对背包与账目再决定**；正在进行的交付禁止人工核对。所有决定写审计。

## 配置与可选联动

[definitions.yml](src/main/resources/definitions.yml) 定义地区、文明、建筑、配方和事件；[config.yml](src/main/resources/config.yml) 定义周期/数量上限。修改后正常重启，保留持久 ID。生产存档变更周期长度前应备份并安排迁移。

CE 支持 `minecraft:wheat` 与真实自定义 ID。缺 CE/内容未加载时相关配方停用且不扣原料，原版链继续。示例见[森罗配方](examples/senluo-recipes.yml)。内容重载后建议同步重启 StarWorld 校验定义。

Cookery/Tavern 的 CE 食材/菜肴/饮品可参与配置的生产与维护。不会从取菜前事件重复发奖。Deco/MusicPlus 缺少可靠的城市贡献完成 API，明确显示自动家具/演奏加成不可用，不因检测安装就给加成。本版不宣称完整生活职业、家具登记或节庆演奏结算已完成。

## 持久化与升级

- 独立 SQLite，WAL + `synchronous=FULL`；库存、储量、事件与周期凭据一笔事务，`last_period` 防旧周期重放。
- 国家资源仅公开 API 读取。跨库输出需 receipt/outbox，留待下一阶段。
- 背包与 SQLite 没有共同事务。物品先落 `PREPARED`，变更后保存玩家数据再确认。崩溃遗留凭据隔离、不自动重放，需人工核对。纯产业结算幂等；不承诺跨玩家文件绝对原子性。
- 配置 v1 → v2 自动备份 `.v1.bak`；DB schema v1 → v2 事务迁移；拒绝未知高版本并保留存档。
- 停服后备份整个 `plugins/StarWorld/`；卸载仅移除 JAR，数据留存。降级应恢复对应备份，不手改 schema 版本。

## 文档与验证

[Java API](docs/API.md) · [架构与线程](docs/architecture.md) · [游玩验收](docs/gameplay.md) · [测试结果](docs/test-results.md) · [完整系统后续设计](docs/roadmap.md)

`./mvnw -B verify` 运行 JUnit、真实 SQLite、并发及独立进程崩溃恢复测试，生成 `target/surefire-reports/`、`target/site/jacoco/`。GitHub Actions 执行同样检查并上传产物。Paper 真服冒烟与未验证边界见测试结果，不把契约模拟测试称为全部上游插件联服通过。
