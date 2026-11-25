# StarWorld 测试与验收记录

## 2026-11-25 生活候选迁移准备补齐

新增 `tools/prepare-life-candidate.py`，只从已通过当前四阶段、正常停服且资源日志绑定当前运行器的真实1.0生活源准备候选副本。它核对未消费的Cookery25凭据、插件/配置指纹、两个数据库的完整性和SHA-256；保留世界、玩家及数据库文件的原始字节，只替换已通过单测的候选JAR、端口和两项文化上限。复制前后复核源库与WAL，发现源服重新启动或库文件改变时拒绝输出准备成功记录。使用方式见[生活夹具说明](../tools/senluo-fixture/README.md)。

Python工具回归 **71/71通过，0失败、错误或跳过**，其中候选迁移准备覆盖11项，包括库存字节保留、只读检查、未完成源拒绝、源库变化、WAL拒绝、旧启动器指纹拒绝、旧尝试保留、独立配置/数据库副本、磁盘门槛及复制期间源库变化。实际历史生活源因缺少当前资源预检标记被拒绝，未创建候选目录。13个既有续跑目录、命令可执行文件及预期数据库初态复核通过；物流恢复的配置与仓库默认模板指纹相同。原产品JAR保持 `9d120795…`，不需重新冻结或重复Java测试。机读记录见[候选准备工具验证](evidence/preparation/life-candidate-tool-20261125.json)。

15:43（UTC+8）主机可用物理/提交内存为 **912/937 MiB**，仍低于标准套件的2,520 MiB与森罗套件的2,712 MiB要求。当前JAR的实服、生活源/候选迁移及最终证据ZIP仍未完成；候选目录须等新的源阶段实际通过后才创建。

## 2026-11-25 实服续跑准备

本轮补齐 `tools/prepare-paper-acceptance.py`，从已拥有的隔离模板准备当前候选的全新静态输入，保留套件调优配置和既有 EULA 选择，排除世界、玩家、数据库、旧日志和重映射插件。已有目录拒绝覆盖，输入/输出路径拒绝符号链接或 junction，写入前预留 1 GiB 磁盘空间；可在同卷硬链接 Paper 引导 JAR 以减少重复占用。森罗准备额外核对旧依赖指纹、厨房包解压与活动资源副本，并绑定当前单测记录。当前 Python 工具回归 **60/60**，其中准备工具 **16** 项；Paper 和森罗夹具分别重新构建成功。产品源码与 `1.4.0` 源码 JAR 的字节一致性通过，主 JAR 仍为 `9d120795…`，此前 **475 项 Java 测试**继续对应同一构建。

已准备 **13 个隔离续跑目录**：基础、扩展、进阶、边境、经营、救灾、公共工程、勘探/存储压力、物流恢复、森罗/城市需求、独立安装、0.5→1.4 升级以及真实 1.0 生活迁移源。完整绝对路径和命令数组保存在 `target/release-acceptance-plan.json`；可保留的机读摘要见[准备证据](evidence/preparation/1.4.0-20261125.json)。这些目录都尚未启动 Paper。生活候选目录必须在新源阶段完成真实已提交活动并正常停服后再复制、安装候选 JAR、将玩家/城市贡献上限改为20。

- 森罗只读预检通过，核对 **2,399** 个厨房文件、**5,159** 个依赖文件和 **11** 份配置。首次准备遗漏活动厨房资源的目录保留为预检失败，修正后使用全新 `release14-civic-20261125-r2`，不覆盖失败记录。
- 0.5→1.4 升级副本只读预检通过，保留59个存档文件指纹、8份原配置、8笔仓储转移、3笔已付款救灾回执及玩家命名物品。
- 真实 1.0 生活源副本只读预检通过，两个数据库与已通过30阶段的原始停服存档相同；只调整声明的60秒生产窗口及生活测试夹具。

**准备及预检不计为实服通过，发行尚未完成。** 2026-11-25 14:14（UTC+8），标准客户端预检实测可用物理/提交内存 **1,367/1,322 MiB**，对应启动要求均为 **2,520 MiB**；森罗实测 **1,357/1,316 MiB**，要求均为 **2,712 MiB**。未降低门槛、未启动本项目 Paper，也未生成发行 ZIP。原始资源记录、夹具构建日志、预检日志和 Python 测试日志的指纹均保存在准备证据中。待资源满足条件后，按清单串行执行全部套件，完成生活候选迁移，再运行证据收集器和发行打包脚本。

## 2026-11-25 当前 1.4.0 构建验证

设施登记与勘探在玩家回调中重新核对城市及国家归属后才更新本地城市行；勘探地形也改为在位置、区块及领土复核后读取。归属变化回归测试核对旧快照不会覆盖现国家、扣除纸张或读取地形。崩溃恢复测试改用 Surefire 的测试 classpath，兼容非 fork 的 Maven 执行，并在超时后回收该测试子进程。

Java 21 下采用分阶段 Maven 验证，**475/475 项测试通过，53 个套件，0 失败、0 错误、0 跳过**。第一阶段在单个最大堆 192 MiB 的 Maven JVM 内执行全部测试，同时附加 JaCoCo 0.8.14 和 Mockito 5.20.0 javaagent；JaCoCo 写入 `target/jacoco.exec`。第二阶段不重复测试，生成主 JAR、源码 JAR 和覆盖率报告，两个阶段均为 `BUILD SUCCESS`：

```powershell
.\mvnw.cmd -B --no-transfer-progress -DforkCount=0 -Dstarworld.testHeap=192m clean test
.\mvnw.cmd -B --no-transfer-progress -DskipTests verify
```

上述命令运行于受限 JVM 配置：Serial GC、单活动处理器、`TieredStopAtLevel=1`、32 MiB code cache 和 160 MiB metaspace 上限。测试阶段的两个 agent 通过 `MAVEN_OPTS` 指定，打包阶段移除 agent 并保留相同 JVM 约束；不能仅复制非 fork 命令而省略 agent，再声称覆盖率已生成。实际命令记录在[单测证据](evidence/unit-tests.json)。千城场景完成 1,000 城、2,000 设施、3,000 次结算与 1,000 次重启重放；本轮结算用时 20,011 ms，详见[规模基线](scale.md)。构建监测最低可用物理内存 1,177 MiB、最低可用提交内存 1,061 MiB，未触发保护停止。

当前主 JAR SHA-256 为 `9d1207953c4883a407a2f284332d0e19e12c71b8b72dee3547931319fdd70dfd`，源码 JAR SHA-256 为 `7ecd28437433c1c9a8d22e27ad22f831b2f69cbd7f944e36cd110cd76ae5d394`。`record-verification.py` 已核对 JAR 完整性、资源版本、源码包与当前主源码的字节一致性、Surefire XML、JaCoCo 和规模结果。原始成功日志位于 `target/verify-1.4.0.log`，日志 SHA-256 记录在单测证据中。2026-11-22 同版本旧构建的[单测](evidence/history/1.4.0/d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7/unit-tests.json)和[规模证据](evidence/history/1.4.0/d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7/scale.json)按旧 JAR 哈希归档，未当作新构建结果。

Python 证据工具 **44/44**、Node 验收工具 **18/18** 通过。CI 新增独立工具回归任务；这是本地验证结果，未声称远程 CI 已运行。新增记录器测试覆盖同版本旧证据归档、冲突拒绝、Windows 混合编码日志、失败标记以及缺少规模文件时保留原单测证据。

早期双 JVM 构建曾因内存余量保护中断，不能计为通过；首轮单 JVM 测试执行 475 项但因崩溃子进程 classpath 错误失败 1 项。其失败日志保留在外部测试工具目录中，最终成功日志为 `starworld-tools/verify-staged-1.4.0-20261125-125915.log`。

**新构建的发行验收尚未完成。** 2026-11-25 13:02（UTC+8）的实服预检记录可用物理内存 649 MiB、可用提交余量 484 MiB；512 MiB Paper 堆和实际 216 MiB Node 堆上限对应的动态物理/提交门槛均为 2,520 MiB，因此预检拒绝，未启动 Paper。原始记录在 `target/release-resource-preflight.json`。下一步须用同一新 JAR 重跑全部 Paper 功能、独立安装、生活文化及旧存档迁移套件，再通过证据收集器生成发行 ZIP。下节旧 JAR 的通过记录不能代替这些步骤。

## 2026-11-22 的 1.4.0 历史冻结构建

本节记录均对应旧指纹 `d575832f…`；其中“当前 JAR”等时点描述指当时冻结版本，不指上节 2026-11-25 的新构建。

存储 IO 执行器改用容量 256 的固定队列。队列已满时，提交的任务 Future 以 `RejectedExecutionException` 失败，调用方可观察到过载；已接受任务仍按串行顺序运行。`StorageWorkerTest` 在工作线程阻塞时填满队列，验证额外任务被拒绝、已排队任务按顺序完成且拒绝任务未执行。

Java 21、Maven 最大堆 192 MiB、Surefire 最大堆 160 MiB 执行 `mvnw.cmd -B --no-transfer-progress '-Dstarworld.testHeap=160m' verify`：**473 项测试全部通过，53 个测试套件，0 失败、0 错误、0 跳过**。千城 SQLite 场景包含 1,000 城、2,000 设施、3,000 次结算和 1,000 次重启重放。当前主 JAR SHA-256 为 `d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7`，sources JAR SHA-256 为 `fddc54130b635c4c8862ab208695d31a6ac1a0c8ec2466dbf0f2098c66d62ff6`。完整机读报告见[旧单测证据](evidence/history/1.4.0/d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7/unit-tests.json)及[旧千城基线](evidence/history/1.4.0/d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7/scale.json)。

当前 JAR 的 Paper 1.21.11 build 132 基础验收 `baseline14-server09` 完成 15/15 阶段，两次启动均通过资源预检并正常退出，证据位于 `starworld-tools/baseline14-server09/client-acceptance/2026-11-22T08-55-34-555Z`。进阶 `progression14-server03` 完成 20/20 阶段，位于 `starworld-tools/progression14-server03/progression-acceptance/2026-11-22T07-43-35-458Z`；扩展 `expansion14-server05` 完成 23/23 阶段，位于 `starworld-tools/expansion14-server05/expansion-acceptance/2026-11-22T07-10-37-004Z`；边境 `border14-server01` 完成 11/11 阶段，位于 `starworld-tools/border14-server01/border-acceptance/2026-11-22T09-33-30-881Z`；勘探与60秒存储压力 `survey14-server05` 完成 8/8 阶段，位于 `starworld-tools/survey14-server05/survey-acceptance/2026-11-22T06-54-12-864Z`。五套客户端验收和独立安装双启动的 StarWorld JAR SHA-256 均为 `d575832ff0f78d1f7f3ed77dfa3516a0d5762845b82835b846fe6c641daa0fa7`。边境运行使用 192 MiB Node 堆限制，V8 heap limit 为 216 MiB；两次 Paper 启动时分别有 2920/3021 MiB 可用物理内存，最低观测余量为1887/1941 MiB。独立安装通过记录位于 `starworld-tools/smoke14-server02/smoke-evidence.json`。

此前的基础运行均保留原始状态：`baseline14-server06` 因动态预检需要2736 MiB而当时仅有1565 MiB，未启动 Paper；`baseline14-server07` 运行至9/10阶段时可用物理内存降至742 MiB，触发768 MiB保护并停止；`baseline14-server08` 因隔离干旱时长配置错误而失败；`baseline14-server09` 前两次分别在1926 MiB和2589 MiB时被预检拒绝，随后在同一隔离配置修正后通过。各失败记录均保留且无 OOM。用户授权结束其他 Paper 后，Folia PID 29140 通过本机控制接口正常停服，世界和玩家数据保存完成，端口25729/25739释放。此后另一 Paper 任务也已退出；最近检查时没有其他 Paper 服务，可用物理内存仍随系统负载波动，边境套件此前四次使用384 MiB Node堆时均在预检阶段拒绝，Node内实际读数为1911、1911、1927和2439 MiB，未启动 Paper；改用192 MiB Node堆后通过11/11阶段。

启动预检要求至少1.5 GiB可用物理内存、2 GiB可用提交余量，并按JVM/Node堆、1 GiB非堆储备及768 MiB运行余量动态提高门槛；当前标准客户端启动需要2736 MiB。运行中任一余量低于768 MiB时只停止本次 Paper。物流生命周期专项另要求至少1 GiB可用磁盘空间，并在服务器无响应15秒后只终止本专项子进程。启动失败清理、运行期保护、优雅停服及强制退出回退由 Node 自动化测试覆盖；Node 测试18/18、Python证据测试36/36通过。60秒存储探针覆盖有界突发队列压力和查询恢复，不代表代表性在线世界负载或数小时级持续运行。经营29/29、救灾22/22、公共工程31/31、0.5→1.4升级7/7及森罗/城市需求30/30已通过。当前 JAR 的商路生命周期验收已通过14/14阶段，证据见[物流生命周期专项](lifecycle-logistics-acceptance.md)。

完整 1.4 发行证据包的只读预检尚未通过。发行收集器要求每份 Paper 日志绑定本次实际运行器和共享资源保护启动器的 SHA-256；当前所选的主要功能套件历史日志包含旧共享启动器哈希，基础套件日志也记录了旧运行器哈希。商路生命周期专项仍绑定现用启动器，14/14 结果有效。`package-evidence.py --check-only` 目前首先因扩展套件日志指纹不匹配而拒绝收录；收集器没有放宽指纹检查。生活迁移收集器现已允许 1.2 及以上候选版新增与最终 JAR 默认字节一致的 `climate.yml`，并拒绝其他未声明的配置新增；对应全套 Python 证据测试 36/36 通过。现有生活文化源证据来自真实 1.0.0 存档，但候选验收使用的是 1.1.0 JAR，不能作为 1.4 发行证据。完整收尾仍需以当前启动器刷新指纹过期的 Paper 实服日志、用 1.4.0 JAR 重跑生活文化源/候选迁移，再通过证据校验器并生成发行 ZIP。

## 1.3.0 有限地区勘探开发验收

2026-11-21，Java 21、Maven Wrapper 3.9.11 执行 `mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`，**464 项测试全部通过，53 个测试套件，0 失败、0 错误、0 跳过**。主 JAR SHA-256 为 `1e36a0ea2f57096d3dc46a44938b970c7c5950d2b23acee15591394b304c3b21`，sources JAR SHA-256 为 `17ca54c73255078c8eb5c94428478e15e219317ffbce1f39f8c124d0e299efeb`。独立机读记录见[1.3 勘探开发验收](evidence/history/1.3.0/survey-acceptance.json)。

- 千城 SQLite 测试通过，覆盖 1,000 城、2,000 设施、3,000 次结算和 1,000 次重启重放。
- 同一最终 JAR 的 Paper 勘探验收 7/7 阶段通过，覆盖真实双层领土、玩家存入纸张、勘探扣除 8 张纸、重复勘探免费、失去国家领土后的拒绝以及正常停服。原始证据位于 `../starworld-tools/survey13-server03/survey-acceptance/2026-11-21T10-37-38-130Z`，`evidence.json` SHA-256 为 `06d7cfc86c60281aa716070df1d95472ad837da4dfe2675d0a452376de8deef4`。
- 本次验证使用 Maven 192 MiB、Surefire 256 MiB、Paper 512 MiB 堆及 Node `--max-old-space-size=384`，Paper 套件串行执行，未观察到 OOM。千城结果覆盖 SQLite 与纯生产路径。
- 每城勘探上限 128 个地区，schema 3 从旧设施记录回填地区索引，勘探、仓库扣费及审计在同一事务提交。地图册使用有界游标分页，展示无设施的勘探地区。

本轮完成勘探开发验收。完整 1.3 发行级验收及发行证据 ZIP 打包仍待完成。

## 1.2.0 验收记录

本版把逐世界 StarCore 天气投影到农场和矿井产量，并让已缴费灌溉水网缓冲农田负面天气。2026-11-21 气候修订构建通过 461 项 Maven 测试和 120 项 Paper 实服阶段，覆盖基础、进阶、边境、经营、灾害和扩展玩法。真实 StarCore 降雨把每周期小麦产量从 91 提高到 137，把原铁产量从 5 降至 4。各套件的构建指纹和实服记录见[1.2 验收状态](evidence/1.2-acceptance-status.md)。

## 1.2.0 气候修订构建

Java 21、Maven Wrapper 3.9.11 执行 `mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`：**461 项测试、0 失败、0 错误、0 跳过，53 个测试套件**。主 JAR SHA-256 为 `534d8b93cfcc4d1499cdf33681ef2e13524b5a53a04a3b94d40fef152bd62c6f`，sources JAR SHA-256 为 `6b6a191eeb07586e6ca294ee875a69a67abf99dfff15d083ff53bdf5b2da5a96`。历史构建说明见[1.2 验收状态](evidence/1.2-acceptance-status.md)，当前根目录的单测和规模 JSON 已对应 1.4.0，不能用于复核本历史构建。1.2.0 JAR 的独立安装和两份完整日志摘要见[no-dependency-smoke.json](evidence/history/1.2.0/no-dependency-smoke.json)，初次冻结构建保存在[历史记录](evidence/history/1.2.0-initial-freeze/no-dependency-smoke.json)。

1,000城、2,000设施、3,000次SQLite结算与1,000次重启重放通过；这只表示存储和纯生产路径基准，不代表Paper tick或世界加载容量。

| 验收范围 | 结果 | 证明内容 |
| --- | --- | --- |
| 气候规则与生产接线 | 聚焦场景通过，计入461项全量测试 | 逐世界归属校验、agricultural农场映射、mineral仅矿井映射、异世界/未知国家中性、倍率边界、灌溉逆境缓解、精确科技映射、同周期防重复 |
| StarCore公开逐世界天气适配 | 公开签名测试通过 | 使用模拟公开 API 签名验证天气 ID、`NationId` 控制国及无控制或未登记世界 |
| Paper独立安装与重启 | 2 次正常启动 | Paper 1.21.11 build132 仅安装 StarWorld 1.2.0，配置生成、ready/status、schema2 及 SQLite 审计标记重启恢复均通过 |
| 补充迁移与公共工程 | 0.5→1.2 存档升级7阶段、连续公共工程世界31阶段通过 | 付款回执、物资守恒、战争货运恢复、三次启停；不计入六套120阶段 |
| 森罗及生命周期发行验收 | 尚未完整重绑1.2 JAR | 旧版1.0/1.1实服记录保留作历史参考，不计入1.2通过数；当时收集器仅接受至1.1版本，后续扩展为支持1.2+候选版新增默认 climate.yml |

缺 StarCore 时，气候按中性倍率降级，不能建立替代国家。Folia 仍明确停用。正式发行证据 ZIP 尚未生成，待森罗/城市需求与兼容 1.2 的生命周期验收完成。
