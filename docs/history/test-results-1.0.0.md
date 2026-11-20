# StarWorld 1.0.0 发布验证

当前冻结 JAR SHA-256：`da87d1af73067a87a3fea04acb2a198ce6a1a4e1f32463f15a375ed824fe9ac6`。2026-11-20 使用 Java 21 / Maven 3.9.11 执行 `./mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`：**398 测试、0 失败、0 错误、0 跳过**，见[机器证据](evidence/unit-tests.json)。主 JAR、源码 JAR、JaCoCo 和规模报告来自同一次 clean verify；源码包与当前 `src/main` 已逐文件核对。

| 同一最终 JAR 的实服范围 | 当前结果 | 证据 |
| --- | --- | --- |
| 原始三产业链、登记/仓储/事件/国家状态与重启 | 15/15 阶段通过 | [基础证据](evidence/baseline-client.json) |
| 国家发展、政策文化、替代路线、国库付款与重启 | 20/20 阶段通过 | [进阶证据](evidence/progression-client.json) |
| 九设施、城市分工、生活、军队扩展与核心资源出口 | 20/20 阶段通过 | [扩展证据](evidence/expansion-client.json) |
| 真实边境资源争端的阴性与阳性条件 | 11/11 阶段通过 | [边境证据](evidence/border-client.json) |
| 路线成本、真实到货统计和在线备份 | 29/29 阶段通过，3 次正常启停 | [经营证据](evidence/operations-client.json) |
| 救灾 GUI、成本、损坏/失地暂停和恢复预算 | 22/22 阶段通过，2 次正常启停 | [救灾证据](evidence/disaster-client.json) |
| 三城公共工程、战时封锁、和平交付和进口救灾 | 31/31 阶段通过，3 次正常启停 | [连续世界证据](evidence/works-client.json) |
| CE、Cookery、Tavern、Deco、MusicPlus 与三类生活需求 | 30/30 阶段通过，2 次正常启停 | [森罗证据](evidence/civic-client.json)、[报告](senluo-acceptance.md) |
| 真实 0.5 存档的城市、物品、配置和业务回执升级 | 7/7 阶段通过，2 次正常启停 | [升级证据](evidence/upgrade-client.json)、[报告](upgrade-1.0.md) |
| 缺 StarCore 和全部森罗模块的独立安装与重启 | 2 次正常启停，SQLite 标记恢复 | [独立安装证据](evidence/no-dependency-smoke.json) |

自动化覆盖周期与请求去重、真实 SQLite 回滚、强制中断恢复、离线不补发、有限恢复预算、当前国家归属、家具身份和演奏完成证明。新增冷却配置改长/改短、旧 civic schema 迁移与失败回滚、缺实际内容时禁止分配需求的测试。千城、2,000 设施、3,000 结算与 1,000 次重启重放的工作量和时间见[规模基线](scale.md)。

协议验收只记录实际产出；测试物资从明确记录的玩家背包经 deposit 入库，不能计作生产奖励。核心测试资金和权限仅用于隔离服。原始日志、输入准备记录、配置及 JAR 指纹将由打包器逐项核对。第一轮 1.0 候选虽通过 387 自动化、30 森罗和 7 升级阶段，但因后续独立审计修复而被替换；记录保存在[候选历史](evidence/history/1.0.0-candidate1/README.txt)，不作为当前冻结 JAR 的通过证据。

九套实服合计 **185/185 阶段**，含 20 次正常 Paper 启停、38 份客户端日志；另有缺依赖环境的 2 次正常启停。经营套件保留进阶路径，森罗/需求套件保留原森罗 15 阶段，阶段数不等于独立功能数。证据包通过当前脚本逐阶段名称、完整日志、实际安装 JAR、配置和 PREP 指纹核对。主 JAR、源码 JAR、证据 ZIP 及完整交付 ZIP 均有独立 SHA-256 清单。

失败和中断的隔离世界及原始运行记录保留，包括早期测试库存手册、集合顺序、周期同步、临时权限和旧 smoke 版本匹配假设。只将完整通过且对应当前产物的记录放入顶层证据；未把失败运行改标为通过。

历史 0.5 结果见[0.5 报告](history/test-results-0.5.0.md)，开发边界见[计划](development-plan.md)。Folia 保持明确启用门禁；该版未声称支持跨区运行、森罗热重载、任意玩家建筑识别或自动 `/kmusic` 完成归因。
