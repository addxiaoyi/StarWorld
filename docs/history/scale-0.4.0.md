# 千城 SQLite 规模基线

0.4.0 已运行真实 SQLite、实际产业结算与重启去重路径的千城测试。此次结果为存储/模拟基线，不能换算为 Paper 每 tick 耗时、在线玩家容量或加载世界吞吐。

## 固定工作量

`src/test/java/dev/starworld/persistence/WorldScaleTest.java` 创建一个临时数据库，使用实际 `WorldStore`、`Simulation`、`EvolutionService` 和 `RegionAtlas`：

| 工作项 | 数量与断言 |
| --- | --- |
| 城市/国家 | 1,000 城市，分属 50 国家 |
| 登记设施 | 每城两个不同区块农场，共 2,000 座 |
| 初始投入 | 每城显式设置 100 面包用于维护与生活；不是待测产出 |
| 实际结算 | 三个周期、3,000 次城市事务；有地区肥力、恢复、真实产物和城市演化 |
| 城市分页 | 每页最多 20 城，遍历后恰好取得 1,000 个不同城市 |
| 地图册 | 每十城抽一城，第一页仅取一个地区并核对继续游标、汇总为两个地区 |
| 重启重放 | 关闭并重开数据库后重放全部 1,000 城市的第三周期，均不得再次结算，累计小麦库存保持相同 |

设施有效证据在测试中显式传入；测试不启动 Minecraft、不扫描区块、不创建实体，也不调用真实核心领土/外交服务。基线使用单一农场配方，未模拟全套市场、玩家背包、Furniture 或并发 Bukkit 调度。

## 已测结果

当前证据为 [evidence/scale.json](../evidence/history/0.4.0/scale.json)，由同一候选的完整 `clean verify` 产生：

| 指标 | 实测 |
| --- | ---: |
| 建库与初始投入 | 534 ms |
| 3,000 次实际结算 | 17,413 ms |
| 分页与抽样地图册查询 | 68 ms |
| 最终数据库文件 | 4,247,552 bytes |
| 重启去重失败 | 0 / 1,000 |

版本为 `0.4.0`，JAR SHA-256 为 `68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba`。环境为 Java `21.0.13.1+2`，测试 JVM 最大堆 256 MiB、Maven 最大堆 192 MiB。一次运行的总时间受本机磁盘、缓存、其他进程和防病毒等影响；测试不以这个机器上的毫秒数作为通用性能保证。

## 复现

使用 Java 21，在仓库根目录运行：

```powershell
.\mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m -Dtest=WorldScaleTest test
```

完整发布校验使用：

```powershell
.\mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify
```

测试将本次机器结果写入 `target/scale-results.json`，包含工作量、准备/结算/查询毫秒、数据库字节和重启重放数量。当前 Maven 完整测试包含此用例，未提供单独的 `scale` profile。只有整个用例所有守恒、数量和分页断言成功才产生最终报告；失败和不同版本的结果应单独保留。

生产调优仍应测量真实 Paper 主线程摘取权限/锚点、IO 排队延迟、完整产业分工、持续市场积压和备份。现有城市、设施、节点、货单、付款与归档均有批量上限，不能通过一次处理所有城市掩盖积压。Folia 仍需要上游线程契约与跨区真服验证，纯 SQLite 千城通过不构成 Folia 支持。
