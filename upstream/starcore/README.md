# StarCore 原子多方结算与退款候选

本目录保存 StarWorld 退款/税费前置契约的可审阅补丁。基线为 `f769e62799e769a18fe51305b545869a17f80cd1`，候选提交为 `f540963ea48a9bde9e8a67aff0e08eacca84d50c`。开发只发生在项目内的独立工作树 `runtime/upstream/starcore-settlement`；未替换外部服务器或发布上游版本。

从可访问的 StarCore 仓库建立独立工作树，再应用补丁：

```sh
git worktree add -b review/starworld-settlement /path/to/new-worktree f769e62799e769a18fe51305b545869a17f80cd1
git -C /path/to/new-worktree am /path/to/StarWorld/upstream/starcore/0001-atomic-settlements-and-refunds.patch
cd /path/to/new-worktree
./mvnw -B --no-transfer-progress verify
```

构建需要 Java 21。产物仍使用 StarCore 原版本号 `1.0.0`，应以提交和 SHA-256 区分；核对 [verification.json](verification.json) 中的基线、补丁、JAR与日志哈希。完整构建运行7,008项测试，0失败/错误；21项环境相关用例跳过，具体套件保存在索引中。SQLite冲突写入与恢复测试实际运行。此记录不代表已通过MySQL或Redis联机验收。

公开 `NationSettlementService` 支持一个付款国向1–16个受益国同事务分账，所有金额精确到分；完整凭据与全部余额通过原国库持久化事务提交。稳定ID不能跨转账、支出、收入、结算或退款复用。原子退款兼容新的分账和既有 `NationTransferService` 付款；每笔原付款最多一次完整逆转，任一收款国余额不足则全部拒绝。退款持久回执包含原付款ID、付款国、全部受益国及金额。没有持久存储时拒绝新结算与退款。

StarWorld通过公共接口反射调用，不读取核心私有状态。旧Core继续支持原市场付款；缺少新契约时，已确认退款保持冻结并等待恢复。隔离实服使用 `prepare-paper-acceptance.py --suite refund --starcore <candidate.jar> --starcore-provenance <verification.json>` 将候选JAR绑定到独立目录；验收状态见项目 [测试记录](../../docs/test-results.md)。

## 带版本第三国许可候选

在结算补丁之后应用 `0002-versioned-transit-permissions.patch`，得到独立候选 `bc720cda1c9b113667fc33bc521a7a900b1e5d8d`。它在项目内 `runtime/upstream/starcore-transit` 构建，未推送或替换上游生产版本；具体JAR、补丁、测试及真实运行状态见 [transit-verification.json](transit-verification.json)。完整构建7,020项测试，0失败/错误，21项环境跳过。

公开 `TransitPermitService` 以精确路线键、通道指纹、国家和区块集合保存许可。当前创始人与成员身份、CAS版本、期限、双方和平及实时双层claim共同约束批准/评估。持久请求ID可重放，但不能使撤销失效；保存结果不确定时关闭读写，直到验证重载。最多15个第三国、每份128区块、每国64个有效许可、最长30天。许可服务本身不转移金币，付款通过上一个补丁的原子结算服务完成。

```sh
git -C /path/to/new-worktree am /path/to/StarWorld/upstream/starcore/0002-versioned-transit-permissions.patch
```
