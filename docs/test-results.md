# StarWorld 1.2.0 验收状态

本版把逐世界StarCore天气投影到农场/矿井产量，并让已缴费灌溉水网缓冲农田负面天气。当前测试和独立Paper启动使用同一个最终1.2.0 JAR。旧版本正式运行结果分别保存在[1.1验收归档](evidence/history/1.1.0/acceptance-status.md)和[1.0历史报告](history/test-results-1.0.0.md)。

## 1.2.0 自动化与产物

Java 21、Maven Wrapper 3.9.11 执行 `mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`：**455 项测试、0 失败、0 错误、0 跳过，53 个测试套件**。主JAR SHA-256 为 `da942bffa5771f5cf1c231f51347e5bf247fe6f27ac14a9c8eabc445562534d9`；sources JAR SHA-256 为 `3f774d048ccd546eb6bc5b0edcf17641d44237895e448ea9c5b43298aeedd3fa`。机读记录为[unit-tests.json](evidence/unit-tests.json)、[scale.json](evidence/scale.json)；实际启动使用的Jar与两个完整日志摘要见[no-dependency-smoke.json](evidence/no-dependency-smoke.json)。

1,000城、2,000设施、3,000次SQLite结算与1,000次重启重放通过；这只表示存储和纯生产路径基准，不代表Paper tick或世界加载容量。

| 验收范围 | 结果 | 证明内容 |
| --- | --- | --- |
| 气候规则与生产接线 | 聚焦场景通过，计入455项全量测试 | 逐世界归属校验、agricultural农场映射、mineral仅矿井映射、异世界/未知国家中性、倍率边界、灌溉逆境缓解、精确科技映射、同周期防重复 |
| StarCore公开逐世界天气适配 | 公开签名测试通过 | 使用模拟公开API签名，验证天气ID、`NationId`控制国及无控制/未登记世界；尚未声称真实StarCore天气下的产业端到端已通过 |
| Paper独立安装与重启 | 2次正常启动 | Paper 1.21.11 build132仅安装StarWorld 1.2.0；配置生成、ready/status、schema2及SQLite审计标记重启恢复均通过 |
| 历史业务回归 | 本轮未全部重跑到1.2 JAR | 1.0完整、1.1生命周期实服旧结果保留作历史参考，不计入1.2同一JAR通过数 |

缺StarCore时，气候按中性倍率降级，不能建立替代国家；本版的独立安装smoke不验证StarCore真实气候玩法。Folia仍明确停用。完整1.2交付ZIP与所有历史实服流程的同JAR重绑尚未完成；不要把历史版本证据改称为1.2通过。
