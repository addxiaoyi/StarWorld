# StarWorld 1.4 开发进度与换设备交接

更新：2026-11-25（Asia/Shanghai）。当前源码版本 **1.4.0**，代码、工具和文档已推送到 GitHub。**当前版本尚未完成最终发行验收**：新 JAR 的实服、真实存档迁移、证据 ZIP 和发行 ZIP 仍待完成。

## 从哪里继续

- 仓库：[addxiaoyi/StarWorld](https://github.com/addxiaoyi/StarWorld)。
- 继续开发的分支：[`codex/complete-development-stages`](https://github.com/addxiaoyi/StarWorld/tree/codex/complete-development-stages)。`main` 仍为初始提交，请克隆这个分支。
- 当前产品代码提交：[`0a4898622cb96cf6bd6f5b5f4b99189c2463d4a3`](https://github.com/addxiaoyi/StarWorld/commit/0a4898622cb96cf6bd6f5b5f4b99189c2463d4a3)。后续交接文档提交不改变产品源码。
- 草稿交付：[PR #2](https://github.com/addxiaoyi/StarWorld/pull/2)，目标分支 `main`，尚未合并。PR #1 是历史 1.0 交付。
- 已完成的远程验证：[GitHub Actions #39](https://github.com/addxiaoyi/StarWorld/actions/runs/45571366911)，两个任务均为 `success`。

```sh
git clone --branch codex/complete-development-stages https://github.com/addxiaoyi/StarWorld.git
cd StarWorld
git log -1 --oneline
```

## 开发阶段

| 阶段 | 已实现内容 | 已有验证与当前状态 |
| --- | --- | --- |
| 0.1–0.3 基础世界 | 六系统、实物产业、文明发展、地区文化、有限商路、生活职业、国库市场和审计归档 | 已有对应历史版本记录，见[需求映射](requirements.md)和[测试记录](test-results.md) |
| 0.4–0.5 经营与灾害 | 路线成本、到货统计、在线备份、六种付费救灾方案与有限恢复 | 0.4 的245项自动化/117实服阶段，0.5 的291项自动化/22救灾阶段及旧存档升级已记录 |
| 0.6–1.0 建设与治理 | 三类公共工程、城市生活需求、真实凭据认领、GUI/API | 随1.0实现；同一历史1.0 JAR通过398项自动化、9套185实服阶段及独立安装双启动 |
| 1.1 长期世界恢复 | 生活文化分期结转、交易公平派发、商路配置恢复、停服任务终态 | 444项自动化；生活源/候选4+7、物流14、升级7阶段专题通过 |
| 1.2 气候产业 | 逐世界天气、农场/矿井产量、付费灌溉韧性 | 气候修订构建通过461项自动化、六套120实服阶段 |
| 1.3 有限勘探 | 当前归属与领土校验、纸张成本、每城128地区、schema 3 | 464项自动化及7阶段勘探实服通过 |
| 1.4 存储边界与归属修复 | 256项有界IO队列、最多64城分批提交、转国设施隔离、登记/勘探回调复核 | 当前代码通过本地及远程475项Java测试，工具71项Python/18项Node通过；完整新JAR实服和发行打包待完成 |

以上历史通过数只对应当时的 JAR，不能累计成当前 1.4 的实服通过数。详细版本边界在[开发计划](development-plan.md)。Folia、第三国过境、自动识别全图道路和缺公开完成回执的上游生产归因仍在[后续边界](roadmap.md)，不能声明已支持。

## 当前构建与远程 CI

| 验证 | 结果 |
| --- | --- |
| 本地Java | 475项、53套件，失败/错误/跳过均为0 |
| 远程Java 21 | 475项，失败/错误/跳过均为0；Maven `BUILD SUCCESS` |
| Python证据/准备工具 | 本地及远程71项通过 |
| Node验收工具 | 本地及远程18项通过，失败/跳过均为0 |
| 存储基线 | 1,000城、2,000设施、3,000次SQLite结算和1,000城重启重放已验证 |
| 覆盖率与产物 | 本地JaCoCo、主JAR、源码JAR已生成；远程插件与测试报告artifact上传成功 |
| 当前冻结JAR的Paper验证 | 尚未开始；13个目录仅完成准备，部分只读预检已通过 |

本地冻结产物：

```text
target/starworld-1.4.0.jar
SHA-256 9d1207953c4883a407a2f284332d0e19e12c71b8b72dee3547931319fdd70dfd

target/starworld-1.4.0-sources.jar
SHA-256 7ecd28437433c1c9a8d22e27ad22f831b2f69cbd7f944e36cd110cd76ae5d394
```

远程产物可在[插件artifact](https://github.com/addxiaoyi/StarWorld/actions/runs/45571366911/artifacts/15002745412)与[测试/覆盖率报告](https://github.com/addxiaoyi/StarWorld/actions/runs/45571366911/artifacts/15002421256)下载，截至本次读取到期时间为2027-02-23。远程构建验证的是同一源码；尚未比对远程JAR与本地冻结JAR的哈希。GitHub artifact摘要是外层ZIP摘要，不是插件JAR摘要。机读结果见[CI记录](evidence/ci/1.4.0-20261125.json)。

## 换设备要带什么

GitHub包含源码、Maven Wrapper、配置模板、测试、验收/打包工具和已提交的证据摘要。以下内容位于旧设备本地，Git克隆不会携带：

| 本机位置 | 用途与处理 |
| --- | --- |
| `C:/Users/Administrator/Documents/starworld/target/` | 冻结JAR、Surefire/JaCoCo、构建/工具日志、本机运行计划和准备脚本。继续沿用原冻结构建时保留这些原始文件；在新设备重新构建则生成并绑定新证据 |
| `starworld/tools/paper-fixture/target/`、`starworld/tools/senluo-fixture/target/` | 当前测试夹具，可迁移或从源码重新构建；夹具变化后需要重新准备与记录指纹 |
| `C:/Users/Administrator/Documents/starworld-tools/release14-*` | 13个尚未启动的隔离输入。森罗使用 `release14-civic-20261125-r2`；首次 `release14-civic-20261125` 保留为失败准备记录 |
| `starworld-tools/disaster05-server01/` | 正常停服且通过的真实0.5源存档与完整证据，用于0.5→1.4升级；保留世界、玩家、两个数据库和原配置 |
| `starworld-tools/senluo10-server02/` | 通过30阶段的真实1.0森罗源存档及完整证据，新的生活源4阶段从此准备 |
| `starworld-tools/` 中被 `PREPARATION.json` 引用的依赖、审计记录和厨房资源 | Paper 1.21.11 build132、固定StarCore/森罗/CraftEngine依赖、厨房ZIP及活动资源；保持来源和哈希。第三方JAR、资源和玩家数据库没有推到公共仓库 |

如果继续迁移现有实服输入，复制停服目录及其清单引用的依赖，保留原文件字节与旧记录。跨设备的Java、Paper、Node、Python、PowerShell路径、端口、准备记录和证据源路径需要在新环境逐项复核；不能只批量替换文本后把旧预检视为有效。重新准备到新目录并运行只读预检，旧失败目录保留。原始机器计划已另存为[续跑输入清单](handoff/acceptance-plan-20261125.json)，它含旧设备绝对路径，作为交接索引使用，不能直接在任意设备执行。

如果只通过GitHub继续开发，可以重新构建和执行全部单元/工具测试。完整存档升级验收还需要迁移上述真实旧存档，或重新生成并验证真实0.5/1.0来源；空数据库不能代替旧存档。当前独立安装脚本使用Windows CIM，完整实服自动化优先使用Windows；Linux CI通过仅证明构建和工具回归。

## 新设备操作顺序

1. 安装或使用已有的JDK21、Python3.12、Node22、PowerShell7和Git。确认处于上面的开发分支，先阅读本文件、[测试记录](test-results.md)与[需求映射](requirements.md)。Maven由仓库Wrapper提供。
2. 选择继续原冻结构建还是新构建。沿用时先核对上面的两个JAR哈希及源码一致性；新构建时运行下面命令，保存完整日志，再用 `tools/record-verification.py` 按真实命令、Java版本和堆参数生成当前构建证据。记录器会归档同版本旧哈希；任何新JAR都需要独立实服验收。

   ```sh
   ./mvnw -B --no-transfer-progress clean verify
   python -m unittest discover -s tools -p "test_*.py" -v
   npm --prefix tools/paper-client test
   ./mvnw -B --no-transfer-progress -f tools/paper-fixture/pom.xml package
   ./mvnw -B --no-transfer-progress -f tools/senluo-fixture/pom.xml package
   ```

   Windows将 `./mvnw` 换成 `.\mvnw.cmd`。仓库当前默认Maven堆192 MiB、测试堆160 MiB；记录参数以实际运行值为准。需要Mineflayer实服客户端时执行 `npm --prefix tools/paper-client install`，或复用已验证的外部 `--client-deps` 目录。

3. 准备真实Paper输入。审计依赖基线、测试配置和命令见[客户端说明](../tools/paper-client/README.md)、[Paper夹具](../tools/paper-fixture/README.md)与[森罗夹具](../tools/senluo-fixture/README.md)。普通套件用 `tools/prepare-paper-acceptance.py`，升级用 `tools/paper-client/prepare-upgrade-10.py`。所有运行器都要绑定当前JAR和夹具。
4. 满足资源门槛后串行运行：基础、扩展、进阶、边境、经营、救灾、公共工程、勘探/存储压力、物流恢复、森罗/城市需求、独立安装、0.5→1.4升级。标准客户端当前需物理/提交余量均至少2520 MiB，森罗/生活需2712 MiB，实际值由JVM和Node堆共同计算；运行低于768 MiB会停止本次Paper。准备至少保留1 GiB磁盘并为世界/日志留额外空间。旧设备曾只有约0.8 GiB可用内存，所以未启动本轮实服。
5. 单独运行真实1.0生活源4阶段并正常停服。随后才用 `tools/prepare-life-candidate.py` 复制该新源、安装当前1.4 JAR、将玩家/城市文化上限改为20；先做独立 `--preflight`，再完成候选7阶段。历史1.1候选结果不能替代本次迁移。
6. 全部结果通过后，运行 `python tools/package-evidence.py --help`，传入各套件实际结果目录，先 `--check-only` 再生成 `target/StarWorld-1.4.0-evidence.zip`。随后运行 `pwsh -File tools/package-release.ps1`，检查发行ZIP、manifest和哈希。更新测试记录与PR后才完成最终发行。

不需要重做已实现功能来“推进阶段”。下一项实际工作是补齐当前构建的真实运行证据；如果验收发现产品问题，修复、重新构建并绑定新哈希后继续。

## 可粘贴给下一台设备的Codex

> 继续开发StarWorld，仓库 https://github.com/addxiaoyi/StarWorld ，分支 codex/complete-development-stages ，PR #2。先读 docs/handoff-1.4.0.md、docs/test-results.md、docs/development-plan.md 和 docs/requirements.md。产品代码到1.4.0，源码提交0a4898622cb96cf6bd6f5b5f4b99189c2463d4a3已通过远程CI：Java475、Python71、Node18。本地冻结JAR哈希9d1207953c4883a407a2f284332d0e19e12c71b8b72dee3547931319fdd70dfd。旧设备内存不足，13个实服目录仅准备完，新JAR完整Paper、独立安装、旧存档及生活源/候选迁移、证据ZIP和发行ZIP尚未完成。核实新设备状态和依赖后继续这些步骤，保留失败记录，历史版本和不同哈希的通过结果不能算成当前发行通过。若旧设备运行目录未迁移，先确认真实旧存档与依赖来源，不能以空库代替。
