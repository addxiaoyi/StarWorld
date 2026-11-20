# StarWorld 0.5.0 里程碑验证

当前JAR SHA-256：`096013edfdb8a3ca56654b15966cc0e319f336043ff8ed9ef0f8ef6d825bc5e7`。Java21/Maven3.9.11执行`./mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m verify`：**291测试、0失败、0错误、0跳过**，见[机器证据](../evidence/history/0.5.0/unit-tests.json)。此次运行未执行clean；全量测试、重新编译后的主/源码JAR及JaCoCo均由同一次verify产生，配置与源码JAR逐文件核对后才绑定实服。

| 本版实服范围 | 结果 | 证据 |
| --- | --- | --- |
| 救灾GUI、实际成本、三种灾害响应、损坏/失地暂停、恢复预算与重启 | 22/22阶段，2次正常启动/关服、4份客户端日志 | [disaster-client.json](../evidence/history/0.5.0/disaster-client.json) |
| 真实0.4存档升级、原城市/背包/领土/7配置/业务凭据、新表与再次重启 | 7/7阶段，2次正常启动/关服 | [upgrade-client.json](../evidence/history/0.5.0/upgrade-client.json) |

精确事件倍率、次期生效、同事务回滚、不补离线步、仅恢复可再生资源、共享预算以及历史UUID现属nation/active边界由自动化测试覆盖。协议验收记录实际输出，不从单个产量倒推精确倍率。测试物资从明确标记的玩家背包经deposit入库，非SQL灌库、非生产奖励。

本版为继续深化过程中的可运行里程碑。继承的其他六系统最近完整验收为[0.4历史报告](test-results-0.4.0.md)（245测试、117实服阶段和无依赖双启动），不包装成当前JAR的全套通过结果。最终1.0将对最终JAR执行完整回归和交付打包；后续开发状态见[开发计划](../development-plan.md)。
