# 1.1 商路配置收紧与恢复：真实 Paper 专项验收

本专项对应 tools/paper-client/lifecycle-logistics-acceptance.cjs。它使用真实 Paper 1.21.11、StarCore、StarWorld 1.1 和两个 Mineflayer 玩家，验证收紧配置不会破坏历史商路、实物托管或已付款货物。当前记录尚未形成完整通过运行：一次尝试在启动 Java 前因主机可用提交内存低于 1.5 GiB 被安全拒绝，另一次仍停留在 running 状态；这些记录不构成 14 阶段通过证据。

工具包含 **4 次正常启动/关闭、14 个阶段**。它只扩展专项回归，不代替已有国战、生产、生活、救灾和公共设施验收。脚本文件和静态校验本身不等于真实服务器通过；最终须检查本次运行的 `evidence.json`、停止快照及全部服务端/客户端日志。

## 运行前提

使用单独、可丢弃的本地运行目录。不能使用生产服、已验收交付目录、前一轮失败后的世界，或运行中的任意现有 Java 进程。

- Java 21、预先准备的 Paper 1.21.11 JAR 及其有效 bootstrap 缓存；工具不下载运行依赖。
- Node.js 22 以上，以及 `tools/paper-client/package.json` 固定的 Mineflayer 4.39.0。可用 `--client-deps` 指定已有依赖目录，工具不执行安装。
- Python 3，须有标准库 `sqlite3`；本工具只用它读取正常停服后的 StarWorld 扩展数据库。
- 本地 JAR：真实 StarCore、StarWorld 1.1 候选及当前 `tools/paper-fixture` 构建的 `StarWorldFixture.jar`。无需新增 `LifecycleFixture` 或更改 fixture 注册入口。
- 新世界、新玩家、新 StarCore/StarWorld 数据库；目标 `plugins/StarWorld/starworld.db` 和 `server.properties` 指定的主世界目录启动前必须不存在。
- `.starworld-smoke-runtime` 内容为 `StarWorld isolated Paper smoke runtime v1`。
- `eula.txt` 已由运行者明确配置为 `eula=true`；脚本不接受或修改 EULA。
- `server.properties` 显式配置 `server-ip=127.0.0.1`、`online-mode=false`、独立空闲端口，至少允许 2 位玩家。建议平坦世界、视距及模拟距离为 2，避免无关世界生成开销。
- 隔离配置 `config.yml`：`period-seconds: 10`、`event-chance: 0`。本专项不制造或依赖随机灾害。
- 隔离 StarCore 建国、建城及领土认领费用须为零或已由准备流程提供实际可支付的余额。脚本中的国库测试充值发生在国家/城市建立之后，不能用它补救最初的建国/认领失败。本专项不宣战，不需要调整战争保护或战争冷却。

### 必须复制本仓库的默认物流模板

运行前，**正常停服状态下**将当前仓库 `src/main/resources/logistics.yml` 按字节复制到隔离目录的 `plugins/StarWorld/logistics.yml`。脚本比较两者 SHA-256，不接受之前 operations 套件的自定义物流文件，也不接受仅数值相同、但包含额外节点成本配置的文件。

默认值包括：

```yaml
limits:
  waypoints: 16
  travel-periods: 1000
blocks-per-period: 128
```

正常道路节点使用默认 `LODESTONE` 锚点、256 容量、0.8 时间因子、16 木板建造费和每节点每运输周期 1 木板维护费。专项不降低这些真实成本。

例如，准备者在确认该隔离服已停止后执行：

```powershell
Copy-Item -LiteralPath 'C:/path/to/StarWorld/src/main/resources/logistics.yml' `
  -Destination 'C:/starworld-lifecycle-fresh/plugins/StarWorld/logistics.yml'
```

这只是测试输入模板操作，不应覆盖任何生产服配置。之后由准备流程重新计算配置指纹。

### 准备来源与 JAR 绑定

`runtime/PREPARATION.json` 须绑定本次输入，至少包含以下真实字段：

- `schema: 1`；`productVersion` 为 `1.1.x`，允许明确的候选后缀。
- `artifactsBound: true`。
- `targetPluginSha256` 为运行目录 `plugins/StarWorld.jar` 的实际 SHA-256。
- `fixtureSha256` 为运行目录 `plugins/StarWorldFixture.jar` 的实际 SHA-256。
- `serverPort` 与 `server.properties` 一致。
- `worldsCopied`、`databasesCopied`、`playersCopied` 均为 `0`。

不能将旧版本或前一次通过证据的 SHA、世界或玩家状态直接标为本次新输入。脚本保存准备清单的原文指纹，并再次保存所有插件 JAR 和已有 YAML 配置的指纹。

## 命令

从仓库根目录运行，替换为本机已存在的路径：

```powershell
node tools/paper-client/lifecycle-logistics-acceptance.cjs --help
node tools/paper-client/lifecycle-logistics-acceptance.cjs `
  --java C:/jdk21/bin/java.exe `
  --paper C:/paper/paper-1.21.11.jar `
  --runtime C:/starworld-lifecycle-fresh `
  --python C:/python/python.exe `
  --client-deps C:/mineflayer-deps
```

脚本使用限定的 Java 堆、单处理器和 Serial GC，只管理自己创建的四个子服务器。不要同时在相同运行目录启动其他 Java 进程。主机资源紧张时应串行运行专项；预计约 5–8 分钟，实际启动时间取决于已准备缓存与主机负载。每次启动之前，Python 通过 Windows `GlobalMemoryStatusEx` 读取可用提交额度，要求至少 **1.5 GiB（1610612736 字节）**；检查结果保存为 `memory-before-run-N.json` 并写入 `evidence.startPreflight`。不足时在启动 Java 之前失败并保留证据，不等待、停止或调整任何外部 Java 进程。非 Windows 平台明确记录该门禁不适用，不伪造 Windows 数值。

两个协议玩家固定为 `StarWorldLA`、`StarWorldLB`。每次连接后由控制台执行 `deop`，并查询真实 `starworld.use` 与 `starworld.admin` 权限。重启后还会明确核对原城市 ID 与国家 ID。实际交易、付款、撤单和改道均由普通国家创始人/城市管理者的 `/sw` 命令完成，不通过 fixture 注入运输授权。

## 路线、投入和实际货单

复用现有 fixture：`exp-setup`、`exp-progress-seed`、`exp-node`、`exp-alt-node`、`exp-fund`、`exp-permissions`、`exp-progress`、`exp-operations`、`exp-market-receipt`、`exp-market-replay`、`exp-reroute-proof`。

`exp-setup` 通过真实 StarCore 公共服务建立两个国家、两个城市及国家/城市双重领地。每城在现有已认领区块登记两个真实道路锚点；登记仍执行传送、实际六格视线和生产 `/sw node register road` 命令。复用 fixture 显式保活的十个测试区块，StarWorld 本身不因此获得自动加载区块的行为。

四个不同节点组成两条从 A 到 B 的已声明路线：

- 短路线：`[A0, B0]`，默认约 512 格、4 个运输周期。
- 长路线：`[A0, B1, A1, B0]`，明确绕行两座端点城市，默认约 1536 格、10 个运输周期。路径节点不重复，全部归属交易双方，没有第三城市维护费。

这是当前公开物流模型支持的**玩家声明的有序节点路径**，不把它描述为世界扫描、连续道路寻路或对沿途区块的额外通行许可。脚本以实际 API 返回的周期数为准，要求长路线至少为短路线的两倍。B1 和 A1 仅用于长路线，适合独立核对受限期间的维护。

两位玩家各有明确标记的背包测试投入：面包 256、木板 512、铁锭 128、原木 64，以及登记/测试流程的其他原版材料，全部通过实际 `/sw deposit` 入库。不登记任何生产设施，因此不能将运输中的库存变化伪装成自然生产。每个国家另通过真实公共 `TreasuryService.deposit` 显式充值 1000 测试金币；这些投入不是玩家赚取的收入。

创建的实际货单如下：

| 货单 | 原路线 | 实际货物 | 用途 |
| --- | --- | --- | --- |
| 已付款长单 | 长 | A 出售 11 原木，B 国库支付 22 金币 | 保留原付款及原运输进度，最后恢复交货 |
| 保留的长易货 | 长 | A 的 13 面包交换 B 的 3 铁锭 | 不改道，恢复后按原冻结行程继续 |
| 改道长易货 | 长后改短 | A 的 7 面包交换 B 的 2 铁锭 | 受限状态下经双方确认改道 |
| 未接受长报价 | 长 | A 托管 5 面包，索要 1 铁锭 | 时长收紧后由原报价城市合法撤销 |
| 短易货 | 短 | A 的 3 面包交换 B 的 1 铁锭 | 证明受限长路线不阻断其他已接受货单 |
| 已付款短单 | 短 | A 出售 6 原木，B 国库支付 12 金币 | 证明其他实际国家付款和发货可继续 |

另挂牌 1 原木，尝试通过受限长路线购买；必须在准备付款之前拒绝，购买行数和国库余额不变。随后卖方合法撤销该未成交挂牌，归还同一份实物。

## 四次启停和十四阶段

每次改物流配置都先等待自己创建的 Paper 正常停止，再读取只读快照，最后修改隔离目录 YAML。不会使用 `/reload`、插件热重载或活跃数据库编辑。

| 启动 | 运行时物流限制 | 阶段 |
| --- | --- | --- |
| 第 1 次 | 原模板：travel 1000、waypoints 16 | 1–4 |
| 第 2 次 | travel 为短路线实际周期数（通常 4）、waypoints 16 | 5–8 |
| 第 3 次 | travel 恢复 1000、waypoints 降为 2 | 9–11 |
| 第 4 次 | 原模板：travel 1000、waypoints 16；长路线仍保持已停用 | 12–14 |

1. 真实服务器启用，建立普通创始人、国家、城市与四个道路锚点；实际命令创建长短两商路。
2. 显式公共国库充值；买方创始人购买长路线商品，取得一份真实国家转账收据及已付款物流单；观察至少一次真实运输推进。
3. 由实际玩家创建并接受两份长易货，保留一份未接受的长报价；最后创建并接受短易货。
4. 第一次正常关闭。只读数据库确认以上已接受货单仍未完成，长付款单已有实际进度；将**停服后的确切数据**设为冻结基线，再收紧最大运输时长。
5. 第二次启动。长货单必须因当前最大运输时长暂停，原未接受报价仍能实际撤销；已付款货物不能当作易货撤销退款；`route status` 明确显示暂停原因和零可用运力。原国家付款收据仍一致。
6. 同一条受限长路线不能准备新的付款或易货；原短易货正常完成。国库不因失败操作额外扣款。
7. 新短路线挂牌、真实国家付款和实际交货均成功；买方只能收到新增短单的 6 原木，不能提前收到长单的 11 原木。
8. 第二次正常关闭。独立核对三个长单的原身份、路径、国家、货物、行程和进度；长路线独有节点没有新维护，长单没有完成收据。恢复时长上限，单独收紧节点数。
9. 第三次启动。长单因节点数限制暂停，原声明路线依然可查询；路线规划只返回有效短路线。原付款收据再次一致。
10. 原管理者实际停用长路线，受限时重新启用必须拒绝。原长易货提出改走短路线；同一方不能代替对方确认，另一方确认后实际推进并交货。重放已应用提议不得重置进度；另两个原长单继续冻结。
11. 第三次正常关闭。保留的一份易货与一份已付款长单仍保持原货物、原付款交接与进度；改道仅一份 APPLIED 记录及一份完成收据。恢复原模板配置，保留数据库中真实停用状态。
12. 第四次启动。恢复配置不能自动启用旧路线；启用之前仍须冻结。原管理者实际执行 `route enable` 后继续原行程，不能按离线时间追补运输。
13. 原已付款长单与保留的长易货完成；核对双方最终实物及国库余额。重放原国家转账凭据仍为 CONFIRMED 且不再扣款；再等待一个实际提交的城市周期确认无重复到货。
14. 最后正常关闭。只读核对五份唯一完成收据、两份已付款物流交接、两份市场成交历史、一份已应用改道，及全部库存/托管/维护守恒。

### 重启时序为何不会抢先推进长单

第 2、3 次启动前，新 YAML 已写入；StarWorld 在构造 `LogisticsService` 时加载 `LogisticsConfig`，随后才注册正常 runtime 定时结算。因此最早一批历史订单也使用收紧后的限制，而不是先按旧上限运输一次。限制检查在维护和推进之前执行。

第 4 次虽然恢复原 YAML，长路线在第 3 次已经由真实玩家停用并持久保存。它不会在客户端连接或公开查询前抢先运输；脚本先核对冻结状态，再执行管理者启用命令，并按照启用后的实际周期差约束恢复进度。

第一次关服之前的 API 观察只用于确认真实玩法，**不作为精确冻结基线**。`exp-progress` 含多个独立 Future，不应假定跨字段原子性；精确基线取自 `Paper.stop()` 完成后读取的扩展库。后续货物守恒及唯一收据也以停服数据作独立复核。

## 守恒及成功条件

每个停止快照均核对：

- 面包、铁锭、原木：城市库存，加尚未完成的物流实物托管，加 AVAILABLE/LOCKED 市场挂牌托管，必须等于实际玩家背包投入总数。
- 不计算已完成、已取消或已到期订单留存的历史 cargo 行，避免把已归还/已到货物重复计入；SHIPPED 挂牌的商品由其实际物流托管承担，不再重复计算挂牌。
- 不登记生产设施，四个节点都是未升级的默认 ROAD。停服快照前通过真实只读 `transportStatus` 快速采样每个已付维护的 `(node, period)`，按城市与每节点 1 木板费用去重，再与该城市此票面基线后的木板库存严格对账；节点持久 `last_maintenance` 水位还要与采样周期吻合。测试不把会定期清理旧期记录的 `logistics_node_usage` 当成终身维护总账。受限期间长路线独有节点的水位及双方库存差额都必须保持不变。
- 长路线两个独有节点在受限两次运行中保持原 `last_maintenance`/`maintained`；不能仅凭仓库共享节点费用变化推断长路线已收费。
- 冻结的接受订单还比较原 route、双方城市/国家、创建/到期周期、progress、travel、未完成标记、逐项 cargo，以及已付款物流的原 settlement key/initial route 交接。

全部完成后，B 应有 81 原木、279 面包、122 铁锭；A 应有 47 原木、233 面包、134 铁锭。相对两笔付款前的已记录余额，卖方国家增加 34 金币、买方减少 34 金币，双方金币总额不变。原长单的付款凭据、货物和冻结行程没有被换成新订单。

只有 `evidence.status == "passed"`、十四阶段全部 passed、四次正常关闭及原配置恢复成功，才可纳入该候选 JAR 的真实验收结果。构建、静态语法或 `--help` 通过不能代替这些条件。更换 JAR 后必须重新准备输入与证据绑定。

## 只读 SQL 边界与证据

Python 探针只在 `server.child === null`、`Paper.stop()` 已验证自身子进程正常退出后调用。它仅打开 `plugins/StarWorld/starworld.db`，URI 使用 `mode=ro`，立即设置 `PRAGMA query_only=ON`，执行 `quick_check`、表结构读取和有界 SELECT（每表最多 501，达到上限即失败）。不打开 StarCore 数据库，不执行任何 INSERT、UPDATE、DELETE、迁移或资金写入。

观察表仅限 StarWorld 扩展的城市/库存、物流节点/路线/运力/订单/货物/改道/交接/完成凭据、市场挂牌/购买/历史/统计，以及新的双队列游标元数据。正常游戏内的造国、领地、充值及转账通过真实 StarCore 公共服务完成；这些正常业务写入不能误写为“核心完全未变化”。

本专项不调用在线备份命令，不读取活跃数据库，也不通过 SQL 制造进度、付款、库存或完成收据。

输出目录：

```text
runtime/lifecycle-logistics-acceptance/<UTC timestamp>/
  evidence.json
  logistics-original.yml
  logistics-travel-reduced.yml
  logistics-waypoints-reduced.yml
  logistics-restored.yml
  stopped-default.json
  stopped-travel-reduced.json
  stopped-waypoints-reduced.json
  stopped-restored.json
  memory-before-run-1.json ... memory-before-run-4.json
  paper-run-1.log ... paper-run-4.log
  client-run-1-a.log / client-run-1-b.log ... client-run-4-a.log / client-run-4-b.log
```

停止检查还要求正常世界保存结束，运行期间不得出现 StarWorld tick/settlement/extension 批处理失败，整个日志不得出现 OOM 或存储刷新/关闭失败。关闭过程中由 scheduler 正常取消在途 Future 的日志与运行期错误分开判断，不能代替停服数据库和实际收据验证。

父目录 `latest.json` 仅指向最近一次结果。失败保留失败状态、当时阶段、异常和已有全部日志；重跑必须新建干净运行目录。`finally` 只关闭自己创建的服务器，成功停止后恢复原始 `logistics.yml` 字节，并记录 `originalConfigurationRestored`。若无法停止，不会趁运行中改配置。工具不替换任何原有服务端进程。

本专项不声明 Folia 实机兼容、CraftEngine 自定义物品/模型、森罗生活插件玩法、客户端资源包或声音效果已经验收；这些范围由各自专项独立提供证据。
