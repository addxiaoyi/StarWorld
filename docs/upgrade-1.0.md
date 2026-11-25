# 0.5 → 1.0 存档升级验收

> 本文记录历史1.0发行的升级结果。当前1.4使用本机重建的真实0.5来源，准确产物、阶段与证据见[当前测试记录](test-results.md)。

2026-11-20 的隔离 Paper 1.21.11-132 / Java 21 验收通过 **7/7 阶段**。使用通过398项自动化测试的同一个 StarWorld 1.0.0 JAR，先后正常启动和关闭两次；每次都由真实离线协议客户端登录。原服未启动，也未修改原存档。

- 原始证据：[upgrade-client.json](evidence/history/1.0.0/upgrade-client.json)，SHA-256 `857076149b79e9c31fa3043d368a583d81e1d2bcac5692cc75c9735106948416`。
- 测试时间：2026-11-20 13:11:25.397–13:12:58.000 UTC（北京时间21:11:25–21:12:58）。
- 最终 JAR SHA-256：`da87d1af73067a87a3fea04acb2a198ce6a1a4e1f32463f15a375ed824fe9ac6`。
- 旧版 JAR SHA-256：`096013edfdb8a3ca56654b15966cc0e319f336043ff8ed9ef0f8ef6d825bc5e7`。
- 实服 helper SHA-256：`e9696c3e03072aee7a841fecbc241d98eda562ba21e1cc4f7e90286683cf1ab6`。

## 原始存档与边界

输入来自已通过22阶段救灾验收且正常停止的0.5存档 `disaster05-server01`。该存档有两个独立国家与城市、十个登记设施、八笔已完成仓储转移、三笔真实付费救灾回执及已存入仓库的物品。测试玩家原背包保留一个带名字及完整组件的 StarCore 指南；已入库的测试物资没有重新出现在玩家背包。

准备工具在原数据停止且 WAL 为空时复制存档，记录59个世界、玩家、数据库及依赖文件指纹，以及8份旧版 StarWorld 配置。`UPGRADE-PROVENANCE.json` 包含源验收证据 SHA、两个正常关服日志 SHA、源与目标 JAR SHA、专用端口、世界和玩家来源。该来源文件完整嵌入机器证据的 `beforeProvenance`。

测试仅使用隔离副本，端口为 localhost:25638；原始 EULA 已接受，offline-mode 仅用于隔离协议客户端。生产调优不应照抄：周期10秒、随机事件概率0、JVM堆512MiB、单活跃处理器、Node堆128MiB。脚本没有写入 SQL，没有调用 seed、创建国家城市、登记新设施、提取/存入仓库或新救灾扣费命令。

## 验证结果

1. 停服输入与原始0.5验收、59个存档文件和8份旧配置指纹一致，两个数据库完整性检查通过。
2. 第一次启动恢复原城市与国家 UUID、五个本城设施和原玩家 UUID；真实客户端库存与原始 NBT 对应。
3. 新治理 GUI 可以打开，公共工程、需求和生活贡献入口齐备，旧市场命令可用；8份旧配置保持原 SHA，自动生成的 `public-works.yml`、`civic-needs.yml` 与候选 JAR 内文件逐字一致。
4. 以原 UUID 重试已付款、已达到最大恢复步数的干旱救灾策略，返回原回执；同周期库存、响应数和历史均不变，随后继续出现真实在线生产。
5. 第一次正常关服只加法创建9张工程/需求表，没有虚构项目、需求、完成凭据或奖励。原国家权威、国库、核心资源、转移、救灾请求和已存在回执保持不变。
6. 第二次启动恢复同一城市与命名指南，再次重试原救灾 UUID 仍不扣物品、不增加请求历史。
7. 第二次正常关服再次通过数据库完整性、外键、原始国家/城市/付款/转移凭据、命名物品完整 NBT 与10份配置指纹检查。原始0.5世界和配置仍与原指纹一致。

当前数据库 `user_version` 保持2，新增模块使用独立 schema：`public_works_schema`、`public_works`、`public_works_history`、`civic_schema`、`civic_clock`、`civic_request`、`civic_request_cost`、`civic_action_claim`、`civic_history`。公共工程 schema 元数据版本为1，城市需求为2。允许正常查询推进需求时钟；其他新增业务表保持空。

需求 schema 2 按每个请求保存的绝对冷却窗口判断重叠，管理员修改冷却周期也不会绕过原窗口或误拒绝相邻窗口。已有需求 schema 1 的行、成本、动作领取凭据和历史 ID 迁移由本次398项自动化中的6项 `CivicCooldownMigrationTest` 验证；本实服样本源为没有城市需求表的0.5存档，验证的是正确初始化至 schema 2，不冒充已有需求凭据的跨版本实服迁移。详细合同见[城市需求](civic-needs.md)。

每城第一次启动发生2笔实际新结算，第二次启动发生1笔，均落在真实在线时钟范围内。每城三期合计报出小麦84、面包33；库存变化落在这些新产出和设施维护/配方消耗上界内。没有重算离线期间的约1990个周期。旧版每城 `last_period` 水印保留并向前推进，永久仓储转移、付款和恢复凭据保留。生产报告遵循既有每城1000周期保留窗口，超窗旧报告会正常清理，不将其误作丢失付款状态。

## 复现

先正常停止已经通过0.5救灾套件的隔离服，保存其 `disaster-acceptance/latest.json`、原始证据和两个正常关服日志。准备工具拒绝覆盖现有目标目录；失败重跑使用新的副本路径。

```sh
python tools/paper-client/prepare-upgrade-10.py --source C:/accepted-disaster05 --runtime C:/upgrade10-fresh --jar target/starworld-1.0.0.jar --fixture tools/paper-fixture/target/starworld-paper-fixture-0.1.0-test.jar --port 25638
node --max-old-space-size=128 --max-semi-space-size=4 tools/paper-client/upgrade-10-acceptance.cjs --check-only --java C:/jdk21/bin/java.exe --paper C:/paper-1.21.11-132.jar --runtime C:/upgrade10-fresh --python C:/python/python.exe --client-deps C:/mineflayer-deps
node --max-old-space-size=128 --max-semi-space-size=4 tools/paper-client/upgrade-10-acceptance.cjs --java C:/jdk21/bin/java.exe --paper C:/paper-1.21.11-132.jar --runtime C:/upgrade10-fresh --python C:/python/python.exe --client-deps C:/mineflayer-deps
```

准备过程只复制、校验和安装候选 JAR，不启动 Java；`--check-only` 校验来源和物品，不启动服务端。正式运行把7阶段 JSON、两份服务端和两份客户端日志写入 `runtime/upgrade-10-acceptance/<UTC timestamp>`；旧尝试不会被覆盖。

本次实服转录指纹：

| 文件 | SHA-256 |
| --- | --- |
| `paper-run-1.log` | `b839c9d6aadc07cf6cc7a6790cf43a5bd0e55fb32663056bde75fda0a81833d9` |
| `paper-run-2.log` | `b0decd025d7fa7a70b40bc40c99265ec03800d0d85664313f85f1ba83dd3c8aa` |
| `client-run-1.log` | `cd32d6196d63afed8cd2f29f06fe32894b44454dd1fa34e9dd0d219130892ac2` |
| `client-run-2.log` | `5fc7e02b30068b4cba9f3374ef113195223543d4cd4cfddf823637fea1c9aeed` |

这项验收证明上述0.5实存档升级样本及本版配置的行为；其他版本应遵循 [备份与存储维护说明](storage-maintenance.md)先备份，并在隔离副本上验证。森罗/CraftEngine需求完成与受控演奏由独立联动套件验证；本套件只验证新治理入口、加法初始化及既有存档恢复。
