# StarWorld 1.9.0 关税报价验收

2026-11-25，出口关税报价、明确付款确认、冻结税费与含税退款已通过增量验收。同一JAR通过34个真实Paper阶段、八次正常启动/停服。第三国过境等[后续扩展](development-plan.md)继续实施，本轮未生成完整发行ZIP。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Maven完整构建 | 548项Java / 62套件，0失败、错误、跳过 | [单测索引](evidence/unit-tests.json)，runtime/logs/tax19-verify.log |
| 工具回归 | Python77、Node25全部通过 | runtime/logs/tax19-python.log、tax19-node.log |
| Paper关税与退款 | 15/15阶段，三次正常启动/停服 | [关税证据](evidence/market-tax-acceptance.json) |
| Paper零税退款回归 | 13/13阶段，三次正常启动/停服 | [退款证据](evidence/market-refund-acceptance.json) |
| 真实1.4→1.9市场迁移 | 6/6阶段，两次正常启动/停服 | [迁移证据](evidence/market-upgrade-acceptance.json) |
| 千城SQLite | 1,000城 / 2,000设施 / 3,000结算，重启重放1,000城无重复 | [规模报告](scale.md) |
| 上游结算候选 | 固定候选已通过7,008项构建测试（21项环境跳过）；本轮再次验证实际退款 | [补丁和验证](../upstream/starcore/README.md) |

主JAR SHA-256：1381e9f938bfe0eb97f0377dde920c47e885d7365df03d7e183d2559994eb527。源码JAR SHA-256：e95f7116712bdcd80d77d69cc176c921b1b3551b070effeb3429d77f88b6296b。构建使用OpenJDK21.0.10、Maven堆192 MiB和测试堆160 MiB。产物及索引副本在runtime/releases/1.9.0，[验收索引](evidence/acceptance.json)绑定JAR、运行器和日志。

## 已验证行为

SQLite回归覆盖报价不占库存、60秒到期、每城20份预览上限、有界清理、竞争库存、防篡改、事务失败整体回滚，以及接受后跨重启保留税额。控制器验证正税额必须明确确认，零税直接购买，报价/确认前后复核当前创始人、使用权限及城市国家；已确认付款重试不重新计税。公共核心适配器拒绝身份不符、分以下金额和缺失API，不调用独立税收存款或逆转接口。

关税实服使用两名普通客户端、真实Core国家/城市/双层claim及物理道路。显式夹具投放物资后，玩家通过普通存入命令建立库存。卖方挂牌20个铁锭、单价2.50，公开Core税率设为3%；买方请求购买7个，只得到17.50货款、0.52关税、18.02总额的报价，商品和国库均未变动。税率随后设为10%，确认旧报价仍只支付18.02。另一份14个商品的竞争报价因余量不足被拒绝，没有新增付款。

未交货前双方确认退款，买方恢复1000、卖方恢复0，七个铁锭一次性退回原仓。重启后接受原报价及重放付款/退款均使用原身份；60秒到期的未接受报价不能预留或付款。新报价按10%收取1.75税费，真实支付19.25并交付七个铁锭。统计仅累计一笔、七个商品、17.50货款，均价2.50；原退款不计交货，已交货订单拒绝退款。关闭剩余挂牌并第三次启动后，买方980.75、卖方19.25，双方商品合计守恒，两笔付款及一笔退款重放均无重复。

关税运行：runtime/tax19/tax-acceptance/2026-11-25T12-48-40-351Z。零税退款回归：runtime/refund19/refund-acceptance/2026-11-25T12-52-52-899Z，覆盖卖方余额不足冻结、跨周期无进度/维护、重启补款及原退款重试。两者使用Core候选f540963e，SHA-256为79dbaaecf3140d377edeab93b14adf9cfd72738118e4a047738c4cff5f2db079。物资、国库种子及税率修改均明确标记为测试干预。

迁移运行：runtime/market-upgrade19/market-upgrade-acceptance/2026-11-25T12-52-16-438Z。从已通过1.4验收的停止存档独立复制，源文件哈希保持不变，继续使用原Core。市场schema升为4，新增空报价/退款表；旧交易货款等于原总额、税费为0、报价为空，原付款、货单、库存、历史、交货统计和玩家背包全部保持。两次启动重放旧付款不再扣钱。

## 范围与历史

本轮三套实服均首次通过，八次停服完整保存世界。日志保留Paper空超平坦生成器信息及Core缺少可选ProtocolLib的替代菜单提示；StarWorld没有运行错误。税费使用公开评估及一个原子国家转账核对，未写入Core独立税务收入账，详见[接口审计](audit-starcore.md)。进口税、第三国通行费、CE视觉/音频、Folia和长期在线容量仍在后续范围。

[1.8退款](history/test-results-1.8.0.md)、[1.7通道](history/test-results-1.7.0.md)、[1.6地图](history/test-results-1.6.0.md)、[1.5分批市场](history/test-results-1.5.0.md)与[1.4完整发行](history/test-results-1.4.0.md)属于各自历史JAR。提交b070256的[CI #57](https://github.com/addxiaoyi/StarWorld/actions/runs/45614762889)成功，已核对532项Java、76项Python和24项Node日志，见[1.8 CI索引](evidence/ci/1.8.0-20261125.json)。完整发行打包从1.9起还要求本版关税套件、三次启动日志及精确上游补丁绑定。
