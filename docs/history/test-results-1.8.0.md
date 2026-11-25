# StarWorld 1.8.0 双边退款验收

提交`b070256`的[CI #57](https://github.com/addxiaoyi/StarWorld/actions/runs/45614762889)成功，已核对日志中的532项Java、76项Python及24项Node测试全部通过，见[CI索引](evidence/ci/1.8.0-20261125.json)。

2026-11-25，未交货退款、余额不足冻结、三次重启重放及真实1.4市场迁移通过本轮增量验收。税费、第三国过境和其他[后续扩展](development-plan.md)继续实施。本轮未生成完整发行ZIP。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Maven完整构建 | 532项Java / 60套件，0失败、错误、跳过 | [单测索引](evidence/unit-tests.json)，`runtime/logs/refund18-verify-r3.log` |
| 工具回归 | Python76、Node24全部通过 | `runtime/logs/refund18-python-r2.log`、`refund18-node.log` |
| Paper双边退款 | 13/13阶段，三次正常启动/停服 | [退款证据](evidence/market-refund-acceptance.json) |
| 真实1.4→1.8市场迁移 | 6/6阶段，两次正常启动/停服 | [迁移证据](evidence/market-upgrade-acceptance.json) |
| 千城SQLite | 1,000城 / 2,000设施 / 3,000结算，重启重放1,000城无重复 | [规模报告](scale.md) |
| 上游结算候选 | 7,008项测试，0失败/错误、21项环境相关跳过；真实退款及重启通过 | [补丁和验证](../upstream/starcore/README.md) |

主JAR SHA-256：`c5f2b3f302200580625f032dcf4e1efc5ea8299c8da02cfd44184edc648b2091`。源码JAR SHA-256：`ed11519d7050c009422d96903a521dff2fc67b24f0bb056712e29c3b96449704`。构建使用OpenJDK21.0.10、Maven堆192 MiB和测试堆160 MiB。产物与索引副本保存在`runtime/releases/1.8.0`，[验收索引](evidence/acceptance.json)绑定准确源码、JAR、运行器与日志。

## 已验证行为

真实SQLite回归覆盖买方申请/卖方确认、未发货和部分份额退款、丢失回调与重启、过期租约不落账、错误/缺失回执保持冻结、仓库满/转国暂停退货、退货事务注入失败整体回滚、已交货拒绝退款及待确认申请撤销。控制器验证权限失效、归属变化仅核对历史回执、接口异常保留原凭据并完成本批回调、退休/关闭调度不发起新逆转。物流汇总将普通暂停和退款冻结一起计数。

实服从全新存档和两名普通客户端开始，国家/城市与双层claim由真实核心公共服务建立；真实背包物品通过普通命令存入城市。卖方挂牌20个铁锭，买方购买7个，国库真实支付17.50金币后进入四周期物流。夹具通过公开TreasuryService明确撤出卖方余额，买方不能自行代替卖方确认；双方同意后货物冻结、余额不足时重试，跨运输周期不前进、不消耗维护。重启保留原申请、付款ID、物流单和买方982.50/卖方0余额。

补回卖方17.50金币后，原退款凭据将买方余额恢复到1000、卖方回到0；七个铁锭一次性退回原卖仓。剩余13个挂牌商品可单独撤销，双方最终铁锭库存恢复到各自初值。第三次启动重放原付款与退款回执，余额、商品和退款身份均不重复变化。已付款发货历史仍保留，退款不计入完成交货。资金/物品种子和撤资均为明确测试干预，不计为真实生产收入。

退款运行：`runtime/refund18-r2/refund-acceptance/2026-11-25T12-29-11-988Z`。使用StarCore候选`f540963e`、SHA-256 `79dbaaecf3140d377edeab93b14adf9cfd72738118e4a047738c4cff5f2db079`，公开NationSettlementService完成原子逆转，未拼接非原子国库收付。原Core基线未替换；上游候选只提交在项目内独立工作树，补丁随本仓库保留。

迁移运行：`runtime/market-upgrade18-r2/market-upgrade-acceptance/2026-11-25T12-30-41-316Z`。基于已通过1.4验收的停止存档独立副本，旧Core继续实际运行；所有源文件哈希不变。市场schema推进到3并新增空退款表/调度队列，原付款、货单、商品份额、价格历史、交货统计与玩家背包保持一致。两次启动重放旧付款不再扣钱。

## 尝试记录与范围

`runtime/refund18`与`runtime/market-upgrade18`通过早期1.8构建，产物保存在`runtime/releases/1.8.0-candidate1`。复核修正混合暂停数量汇总和退款回执查询异常隔离后重新完整构建；最终仅引用上述修订JAR及-r2实服记录。`refund18-verify-r2.log`保留新增测试使用过旧授权周期导致的失败；修正测试时序后-r3全量通过。最初定向测试中的旧schema预期和玩家权限mock失败也保留。

实服日志保留Paper空超平坦生成器的`No key layers in MapLike`信息及Core缺少可选ProtocolLib后使用替代菜单的提示；StarWorld无运行错误，所有五次停服均完整保存世界。此验证未覆盖MySQL/Redis联机、CE视觉/音效、Folia和长期在线容量，后续按计划单独验证。

[1.7通道](history/test-results-1.7.0.md)、[1.6地图](history/test-results-1.6.0.md)、[1.5分批市场](history/test-results-1.5.0.md)与[1.4完整发行](history/test-results-1.4.0.md)属于各自历史JAR。上一提交`84cc5e7`的[CI #55](https://github.com/addxiaoyi/StarWorld/actions/runs/45609546965)成功，记录见[CI索引](evidence/ci/1.7.0-20261125.json)。完整发行打包仍要求同一最终JAR的全部套件，1.8新增退款套件和精确上游补丁绑定。
