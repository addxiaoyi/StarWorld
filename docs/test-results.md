# StarWorld 1.4.0 测试与验收记录

2026-11-25 在 Linux 新设备重新构建并验证。当前主 JAR SHA-256 为 `8e7da9179ae5b8b4d3d524df6989611cafdf9881000798b36d09a279480e936f`，源码 JAR 为 `045c5d9e8d08101502ea060b3fb6e8aa161ca0024ee8e4b3e034eafe08548e33`。以下当前结果均绑定这份产物；旧设备的 `9d120795…` 和更早同版本构建只作历史记录。

## 构建与工具

| 验证 | 结果 |
| --- | --- |
| Java 21 / Maven | 475项、53套件，0失败、错误或跳过；主JAR、源码JAR及JaCoCo生成成功 |
| Python证据与准备工具 | 73项通过 |
| Node验收工具 | 20项通过 |
| 千城SQLite | 1,000城、2,000设施、3,000次结算、1,000次重启重放；结算13,810毫秒，分页12毫秒，数据库5,095,424字节 |
| Paper与森罗夹具 | 两个独立Maven构建成功，夹具不进入产品JAR |

构建命令为 `./mvnw -B --no-transfer-progress clean verify`，使用 OpenJDK `21.0.10+7-Ubuntu-124.04`、Maven堆192 MiB、独立测试堆160 MiB、Serial GC及两个活动处理器。原始日志为 `runtime/logs/build-1.4.0.log`、`python-tests-final.log` 和 `node-tests-final.log`。单测与源码指纹见[单测证据](evidence/unit-tests.json)，规模结果与适用范围见[千城基线](scale.md)。本轮收尾未修改产品Java源码。

## 当前JAR的真实Paper验收

当前JAR的 **12套玩法与迁移验收共217/217阶段通过**，另有独立安装双启动通过。使用 Paper 1.21.11 build132、真实StarCore及Mineflayer协议客户端，逐套串行运行。各隔离目录绑定localhost；EULA接受仅限本项目测试。依赖、准备记录、世界和原始日志保存在忽略的 `runtime/`，原始输入和失败尝试保留，未写入源码仓库。准确时间、原始证据哈希及完整收集命令见[本轮验收索引](evidence/acceptance.json)。

| 套件 | 阶段 | 本轮目录 |
| --- | --- | --- |
| 基础产业、GUI与重启 | 15/15 | `runtime/baseline` |
| 扩展、真实气候与生产 | 23/23 | `runtime/expansion-r2` |
| 地区文化、商路与国库市场 | 20/20 | `runtime/progression-r3` |
| 经营反馈、备份与战后到货 | 29/29 | `runtime/operations-r2` |
| 边境资源冲突 | 11/11 | `runtime/border-r3` |
| 六种付费救灾与恢复 | 22/22 | `runtime/disaster-r2` |
| 公共工程与连续世界 | 31/31 | `runtime/works-r4` |
| 有限勘探与存储压力 | 8/8 | `runtime/storage` |
| 森罗与真实城市生活需求 | 30/30 | `runtime/civic` |
| 0.5→1.4旧存档迁移 | 7/7 | `runtime/upgrade` |
| 1.0→1.4生活文化结转 | 7/7 | `runtime/life-candidate-r2` |
| 商路配置恢复与四次启停 | 14/14 | `runtime/lifecycle-logistics-r2` |
| 仅安装StarWorld、schema3及持久标记恢复 | 两次启动通过 | `runtime/smoke-r2` |

森罗30阶段包括真实CE物品、家具、两场受控演奏及三类城市需求；准确依赖组合见[森罗联调](senluo-acceptance.md)和[上游审计](audit-senluo.md)。本轮使用固定森罗生产提交 `3e774d0…`，不把最新源码观察当作最新二进制实测。

存储探针持续60,395毫秒，135轮共51,840请求，其中37,133接受、14,707显式拒绝，所有恢复查询成功。679次堆采样的峰值为490,536,752字节，占实际最大堆518,979,584字节的94.519%，低于95%停止线。该结果证明这一分钟有界突发与恢复；千城SQLite结果也只覆盖存储及纯生产路径，两者均不能推定长期在线人数或数小时世界负载。

通用客户端在Linux记录真实物理可用内存；提交余量字段为不可用哨兵，不能当作实际Linux commit测量。独立安装脚本使用 `/proc/meminfo`，严格overcommit模式下还检查内核提交预算。保留运行期768 MiB余量门槛，套件仅管理自己启动的Paper进程。

## 真实迁移来源与失败重跑

旧设备的存档和依赖没有迁移。本轮从历史源码重新生成可审计来源：0.5提交 `68cb543` 构建通过291项测试并完成22阶段救灾；1.0提交 `4cd594e` 构建通过398项测试并完成30阶段森罗。随后从正常停服的1.0完整副本生成4阶段生活源 `runtime/life-source-r2`。这些旧JAR结果单独计数，不加入当前1.4阶段数。

0.5升级准备保留59个存档文件指纹与8份旧配置；生活候选保留世界、玩家及两个数据库的原始字节，只替换候选插件、端口和两项文化上限。1.2及以上候选允许插件生成与JAR默认字节完全一致的 `climate.yml`，其余旧配置必须保持原指纹。生活源和候选均绑定本次同一运行器，旧源数据库保留不动。

本轮保留的失败及修正：

- 首轮进阶与经营因隔离StarCore未显式配置 `diplomacy.war.declaration-cost: 0` 而无法支付默认宣战费。补齐测试配置，在全新目录重跑；边境首次因事件duration/aftermath不符而被启动前预检拒绝。
- `progression-r2` 的异步快照读到交货前库存和交货后状态，终态断言失败。停服只读核验确认货物只到一次；运行器改为同时等待完成状态和精确库存，后续守恒及重放断言保留，`progression-r3` 20阶段通过。
- 首次生活候选7个玩法阶段完成后，被过严的配置终态检查拒绝新生成的默认气候文件。修正为只接受JAR中同字节默认项，增加缺失/篡改/额外配置回归，并重新运行来源和候选；原失败证据未改标。
- 发行预校验发现首次StarCore启动自动补写配置，扩展、救灾、公共工程、边境及物流恢复的最终配置指纹与启动前不同。原玩法结果保留；以初始化后的静态配置创建全新空世界重跑，收集器继续要求精确指纹一致。
- 完整收集器拒绝公共工程旧运行器遗漏的气候配置指纹。补充启动前 `climate.yml` 记录后，在全新目录重跑31阶段；既有运行记录不做事后补写。
- `works-r3` 在交付守恒阶段发现井锚点失效。只读解析停服区块确认该方块为 `water_cauldron[level=1]` 且世界正在下雨；运行器在三个空炼药锅上方放置真实石顶并由客户端确认，保留九设施、物资守恒和全部31阶段断言后重新建服验证。
- Linux归档探针发现 `Compress-Archive` 丢失点号文件，改用.NET ZIP API，保留逐文件校验，并补齐发行源码中的准备、记录和测试工具。

## 发行与远程验证

**本轮证据收集与发行打包已完成。** 完整收集器的 `--check-only`、实际证据打包及 `package-release.ps1` 均通过。`target/StarWorld-1.4.0-evidence.zip` 包含709个条目；`target/StarWorld-1.4.0.zip` 包含494个条目，其中351个源码文件与工作区逐字节一致。独立校验覆盖ZIP CRC、唯一安全路径、证据清单的708份载荷及外层493份SHA-256清单；Maven/CI点号文件和可执行Wrapper完整，内嵌证据ZIP字节一致。

最终ZIP哈希与独立校验结果分别保存在 `target/StarWorld-1.4.0.sha256` 和 `target/release-audit.json`，不写回ZIP自身，避免自引用。报告定稿后重新打包并核对最终提交源码。发行门禁还用真实重建来源验证了一个合法输入及四种重新计算关联哈希后的篡改输入：阶段名、遗留错误、错误版本和错误JAR均被拒绝。发行包只收录自有主JAR、源码JAR、完整源码、配置示例及允许的证据日志，不包含第三方JAR、世界或玩家数据库；Git不携带二进制发行文件。

开发分支为 `codex/complete-development-stages`，交付入口是[草稿PR #2](https://github.com/addxiaoyi/StarWorld/pull/2)。远程CI只验证源码构建及工具回归，不代替本地Paper和迁移验收，也不默认宣称远程JAR与本地冻结产物字节相同。验收工具提交 `1ed45f3` 的[CI #47](https://github.com/addxiaoyi/StarWorld/actions/runs/45595260611)已验证Java475、Python73和Node20项全部通过；准确提交与artifact见[CI索引](evidence/ci/1.4.0-20261125.json)。

## 历史结果与范围

历史报告分别保存在[0.3](history/test-results-0.3.0.md)、[0.4](history/test-results-0.4.0.md)、[0.5](history/test-results-0.5.0.md)、[1.0](history/test-results-1.0.0.md)、[1.1](history/test-results-1.1.0.md)、[1.2](evidence/1.2-acceptance-status.md)和[1.3](evidence/history/1.3.0/survey-acceptance.json)。同版本旧构建按JAR哈希存放在 `docs/evidence/history/1.4.0/`；旧设备准备清单只作历史索引，不能直接在新设备运行。此前逐次交接文本可从Git历史查阅。

本次范围截至1.4。Folia运行、第三国过境、全图自动道路识别、缺公开完成回执的上游生产归因及资源包视听体验按[后续边界](roadmap.md)处理，不计为本次支持能力。
