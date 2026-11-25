# StarWorld 1.4 交付与复验入口

更新：2026-11-25（UTC）。开发范围截至1.4，功能和本轮发行验收均已完成。当前Linux构建通过475项Java、73项Python和20项Node测试，以及12套真实Paper玩法/迁移的217阶段与独立安装双启动；证据和发行ZIP已生成并独立核验。详细结果见[测试记录](test-results.md)。

## 仓库与交付

- 仓库：[addxiaoyi/StarWorld](https://github.com/addxiaoyi/StarWorld)。使用分支 [`codex/complete-development-stages`](https://github.com/addxiaoyi/StarWorld/tree/codex/complete-development-stages)。
- 审阅入口：[草稿PR #2](https://github.com/addxiaoyi/StarWorld/pull/2)，目标分支 `main`。PR #1记录历史1.0交付。
- 验收工具提交 `1ed45f3` 已通过[CI #47](https://github.com/addxiaoyi/StarWorld/actions/runs/45595260611)，Java475、Python73、Node20全部通过；后续报告提交的CI以GitHub最新分支状态为准。
- 阶段表：[开发计划](development-plan.md)；逐项实现：[需求映射](requirements.md)；交付核对：[完成审计](completion-audit.md)。
- 自有交付件位于 `target/`：主JAR、源码JAR、`StarWorld-1.4.0-evidence.zip` 和 `StarWorld-1.4.0.zip`，均已完成校验。最终ZIP哈希见同目录 `StarWorld-1.4.0.sha256`，逐项审计见 `release-audit.json`；Git不携带这些二进制文件。

```sh
git clone --branch codex/complete-development-stages https://github.com/addxiaoyi/StarWorld.git
cd StarWorld
git log -1 --oneline
```

当前冻结主JAR SHA-256为 `8e7da9179ae5b8b4d3d524df6989611cafdf9881000798b36d09a279480e936f`；源码JAR为 `045c5d9e8d08101502ea060b3fb6e8aa161ca0024ee8e4b3e034eafe08548e33`。构建和规模记录在 `docs/evidence/unit-tests.json`、`scale.json`。重建若产生不同JAR指纹，需要绑定新证据，不能重用本次实服通过记录。

## 已实现的阶段

| 阶段 | 实现 |
| --- | --- |
| 0.1–0.3 | 六系统、实物产业、文明、地区文化、有限商路、职业、国库市场和审计归档 |
| 0.4–0.5 | 路线成本、真正到货统计、在线备份、六种付费救灾与有限恢复 |
| 0.6–1.0 | 公共工程、城市需求、真实活动凭据、治理GUI/API及连续世界 |
| 1.1 | 生活文化分期结转、交易公平派发、商路配置恢复、停服任务终态 |
| 1.2 | 逐世界天气对农场/矿井的产量影响与付费灌溉韧性 |
| 1.3 | 有限地区勘探、纸张成本、每城128地区和schema3 |
| 1.4 | 256项IO队列、64城提交批次、转国设施隔离、登记与勘探回调归属复核 |

Folia运行、第三国过境、全图自动道路和缺公开完成回执的上游生产归因保留在[后续范围](roadmap.md)。不需要重复开发已有功能来推进本次阶段。

## 新设备复验

环境需要JDK21、Python3、Node22以上、PowerShell7和Git；Maven由Wrapper提供。本轮Linux和以前Windows均有构建记录。

```sh
./mvnw -B --no-transfer-progress clean verify
python3 -m unittest discover -s tools -p 'test_*.py' -v
npm --prefix tools/paper-client test
./mvnw -B --no-transfer-progress -f tools/paper-fixture/pom.xml package
./mvnw -B --no-transfer-progress -f tools/senluo-fixture/pom.xml package
```

Windows将 `./mvnw` 换成 `.\mvnw.cmd`。保存构建原始日志后，用 `tools/record-verification.py` 记录实际命令和参数；同版本旧指纹自动归档。本轮Maven堆192 MiB、独立测试堆160 MiB。夹具和第三方依赖不打入产品。

用户确认旧设备 `starworld-tools` 未迁移。本轮从历史0.5提交 `68cb543`、1.0提交 `4cd594e` 重新构建并运行完整旧版玩法，生成真实迁移来源；原Windows绝对路径清单 [acceptance-plan-20261125.json](handoff/acceptance-plan-20261125.json)仅是历史索引。不能用空数据库代替旧存档。

本机 `runtime/` 包含固定依赖、隔离输入、完整存档、来源指纹、日志及失败尝试。它被Git忽略，交接需另行携带，或按以下入口重建：

1. 阅读[客户端说明](../tools/paper-client/README.md)、[Paper夹具](../tools/paper-fixture/README.md)、[森罗夹具](../tools/senluo-fixture/README.md)与[依赖审计](audit-senluo.md)。使用固定Paper、StarCore、CE和森罗版本。
2. 普通套件用 `tools/prepare-paper-acceptance.py` 生成全新隔离目录；升级用 `tools/paper-client/prepare-upgrade-10.py`。源服正常停服、证据完整后才复制，不修改原世界。
3. 生活源先通过旧1.0森罗30阶段，再运行源4阶段；之后用 `tools/prepare-life-candidate.py` 创建当前候选，预检并完成7阶段。候选只调整声明的插件、端口和两项贡献上限。
4. 当前JAR的12套玩法与迁移验收及独立安装双启动按串行执行。每次启动仍执行资源门槛；用户EULA授权仅限本项目localhost隔离服。
5. 按 `tools/package-evidence.py --help` 传入实际证据目录，先 `--check-only`，再生成证据ZIP，最后 `pwsh -NoProfile -File tools/package-release.ps1`。除 `--smoke-dir` 使用服务端根目录，其余参数指向含 `evidence.json` 的运行文件夹。

发行ZIP内有全部源码、构建/验收工具、配置示例、插件及允许的原始证据。第三方JAR、世界和玩家数据库不进入源码交付；证据包有日志与停服只读快照，可审计本次运行但不能替代完整复跑输入。
