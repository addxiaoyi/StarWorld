# StarWorld 1.7.0 有限通道验收

2026-11-25，绑定道路/航道、受阻暂停与恢复、重启及真实旧市场迁移已通过本轮验证。第三国过境、税费退款、上游内容接口和Folia等扩展继续按[开发计划](development-plan.md)实施。本轮为增量验收，未生成完整发行ZIP。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Maven完整构建 | 515项Java / 58套件，0失败、错误、跳过 | [单测索引](evidence/unit-tests.json)，`runtime/logs/build-1.7.0-r2.log` |
| 工具回归 | Python75、Node23全部通过 | `runtime/logs/python-tests-1.7.0-r2.log`、`node-tests-1.7.0-r3.log` |
| Paper道路与水路 | 12/12阶段，两次正常启动/停服 | [通道证据](evidence/physical-corridors-acceptance.json) |
| 真实1.4→1.7市场迁移 | 6/6阶段，两次正常启动/停服 | [迁移证据](evidence/market-upgrade-acceptance.json) |
| 千城SQLite | 1,000城 / 2,000设施 / 3,000结算，重启重放1,000城无重复 | [规模报告](scale.md) |

主JAR SHA-256：`dba8fdb3da8d06feae42001ad74dad1585f33087767f4b4864a67ea4bdda7659`。源码JAR SHA-256：`2f5c366130c9ea4a8a2dd77fb87bdf8ae1811d921f191caef2c19b3e124507f9`。构建使用OpenJDK 21.0.10、Maven堆192 MiB和测试堆160 MiB。产物和索引副本保存在`runtime/releases/1.7.0`；[验收索引](evidence/acceptance.json)绑定准确JAR、源码、测试、运行器与日志。

## 通道和资金边界

规划测试覆盖有限直角折线、坡度与反向对称、负坐标、跨世界/对角/垂直路径拒绝、1024中心点和水路港口约束。现场捕获测试验证路面缺口、头顶障碍、双层领土、未加载区块、水深/水源/宽度/净空、16,384次读取预算及下一批恢复。50条候选路线超过单次32条上限时，其余路线按缺证据暂停，按周期轮换进入后续核验，不使整批中止。

真实SQLite测试验证绑定不能追溯改变已有货单或市场购买的条件，不能解除；绑定、几何指纹、库存和货物跨重启保留。错误/缺失证据阻止报价、预留和发货；真实控制器回归验证道路损坏时不发起新国库付款，恢复后才允许付款。

实服从空存档开始，通过两名客户端与公开StarCore城市/国家claim准备有限道路和航道。玩家以实际背包物品投入仓库并用真实视线登记锚点。断路拒绝报价且不扣货；接受后断路暂停货物且不扣维护，停服后保持原绑定与托管。恢复道路但撤销中途国家claim时仍暂停，恢复claim后双方精确收到原报价。水路缺少第二层水源时暂停，补齐后只交付一次。两次运行均正常保存全部世界。

最终通道记录：`runtime/corridor17-r5/corridor-acceptance/2026-11-25T11-53-15-625Z`。迁移记录：`runtime/market-upgrade17-r2/market-upgrade-acceptance/2026-11-25T11-56-14-662Z`。迁移使用已通过1.4进阶验收的停止存档独立副本，原存档逐文件哈希未改变；仅增加空的通道绑定表，旧路线不自动绑定。已完成货运、价格历史、交货统计、原始付款凭据和玩家背包保持一致，两次启动重放旧付款均不重新收款。

## 保留的尝试与范围

`corridor17`、`corridor17-r2`、`corridor17-r3`保留夹具视线遮挡、命令频率触发客户端踢出、非原子查询跨提交导致断言过早的失败记录；分别调整锚点瞄准、命令间隔及库存/货单共同收敛条件。`corridor17-r4`和`market-upgrade17`通过的是早期1.7构建；发现50条候选超过32条核验上限后修正并重新完整构建，最终验收仅使用上述修订JAR。早期Java失败日志与同版本旧构建索引也保留。

[1.6地图/重启/压力](history/test-results-1.6.0.md)、[1.5分批市场](history/test-results-1.5.0.md)和[1.4完整发行](history/test-results-1.4.0.md)属于各自历史JAR，不计入当前18个实服阶段。1.6提交`ddc5f5d`的[CI #53](https://github.com/addxiaoyi/StarWorld/actions/runs/45605158315)已核对成功，记录见[CI索引](evidence/ci/1.6.0-20261125.json)。完整发行打包仍要求同一最终JAR的全部套件。

提交`84cc5e7`的[CI #55](https://github.com/addxiaoyi/StarWorld/actions/runs/45609546965)成功，任务日志核对Java515、Python75和Node23均通过；[CI索引](evidence/ci/1.7.0-20261125.json)记录准确提交与任务ID。
