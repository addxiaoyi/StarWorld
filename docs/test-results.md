# StarWorld 0.4.0 测试结果

Java21、Maven3.9.11执行 `./mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify`，**245项测试，0失败、0错误、0跳过**。最终产品JAR SHA-256：

```text
68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba
```

当前机器证据见[unit-tests.json](evidence/unit-tests.json)。原始Surefire/JaCoCo由证据包核对收录。0.3.0完整结果见[历史报告](history/test-results-0.3.0.md)，不代替当前产物验证。

## 新增自动化范围

路线规划按有限候选与端点节点批量读取，核对动态等级维护成本、稳定排序、不可变映射、配置缩限拒绝及只读不授权运输。市场统计仅按持久物流完成sequence有界归集，收据/全服/双城聚合/游标同事务提交，覆盖重启回填、大于单笔上限的精确累计、无关易货和失败回滚。在线备份验证真实SQLite快照、完整性/schema/hash、原子替换、活动事务拒绝、主库/WAL/SHM和硬链接/符号链接别名保护、目录逃逸和失败保留旧快照。

千城真实SQLite基线：1000城市、2000设施、3000结算，重启后重放1000城市同周期不增库存；结算17413ms、分页68ms、数据库4247552bytes。详见[scale.json](evidence/scale.json)，不是Paper在线TPS证明。

## 同一JAR真实服务器验证

| 套件 | 当前结果 | 机器证据 |
| --- | --- | --- |
| 经营、规划与在线备份 | 29/29通过 | [operations-client.json](evidence/operations-client.json) |
| 0.3→0.4升级 | 7/7通过 | [upgrade-client.json](evidence/upgrade-client.json) |
| 进阶回归 | 20/20通过 | [progression-client.json](evidence/progression-client.json) |
| 国战扩展 | 20/20通过 | [expansion-client.json](evidence/expansion-client.json) |
| 基础三链/背包/GUI | 15/15通过 | [baseline-client.json](evidence/baseline-client.json) |
| 真实边境资源 | 11/11通过 | [border-client.json](evidence/border-client.json) |
| 最新森罗联调 | 15/15通过 | [senluo-client.json](evidence/senluo-client.json) |
| 缺依赖独立安装 | 两次正常启动/SQLite恢复通过 | [证据](evidence/no-dependency-smoke.json) |

经营套件保留原20阶段及九项新增检查，三个真实Paper进程、两个真实协议客户端：路线维护材料、未到货和易货不计统计、普通玩家拒备份、OP真实命令在线快照、完成1单11木材/22金币精确统计、同名快照替换、第三次启动后不重复。测试物品和国库充值单列为投入，不计作生产。只读Python探针打开备份或正常停服数据库，不SQL修改玩法数据。

实服固定Paper1.21.11 build132/Java21与审计StarCore，候选森罗及匹配资产指纹单列。Folia仍受上游线程契约限制并明确门禁；受控演奏不等于上游/kmusic完成。资源包GPU画面/真人听觉/Paper在线容量不在协议测试证明范围。下一阶段按[开发计划](development-plan.md)在独立工作区进行，未将未验收功能算入本版。
