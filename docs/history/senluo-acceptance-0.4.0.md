# 森罗物语真实联调验收

**0.4.0 当前候选15/15阶段全部通过**，实际时间 **2026-11-20 15:03:05–2026-11-20 15:07:24 Asia/Shanghai**。两次Paper正常退出，三份客户端日志完整，运行器stderr为零字节。机器证据见[senluo-client.json](../evidence/history/0.4.0/senluo-client.json)，原始日志、准备来源与文件指纹进入证据包。0.3结果保存在[历史报告](senluo-acceptance-0.3.0.md)。

StarWorld JAR SHA-256：`68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba`。固定SenLuo源码审计提交`3e774d00567deb9cb65eee8da6de8a65254e5b8e`，使用Cookery候选和同源`kitchen-crops-3.zip`；来源与生产源码匹配边界见[审计](../audit-senluo.md)。Paper1.21.11 build132、Java21、CraftEngine26.9.1。

| 当前输入 | SHA-256 |
| --- | --- |
| craftengine-26.9.1.jar | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |
| KaleidoscopeCookeryPlugin-1.3.0.jar | `26a37d7187766b4990a53ca7c01622e098fd6606d1c763eb479586051f952457` |
| KaleidoscopeDeco-0.1.0-dev.jar | `1db18976692f5de6b284b31cfc5ef40e14d60f86925f75e87fe7dc3cf807f263` |
| KaleidoscopeMusic-0.1.0-dev.jar | `983fb4b88b39bb5211e12ead23d3202222624e114e2ee4137f48d49d434b1078` |
| KaleidoscopeTavern-0.1.0-dev.jar | `205b36165b519106cda696f0d48ab91e031b6b90ba6bef8ee0d2202155948033` |
| StarCore.jar | `953f926f12384e0cfafae6f55d266a10c7156468e7e28be75518c3f7b413884b` |
| StarWorld.jar | `68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba` |
| StarWorldFixture.jar | `ce2aaaeeb8c34b870eefa2572444db2cf60b0d804d31fcf24b19a72b41e3e22b` |
| StarWorldSenLuoFixture.jar | `6d0b557758d4eede2729a8cc90490ed34936675444e3c5002f76ce07a69fee7d` |
| 厨房资源ZIP | `a3f68c026c7440153444cd306374ce043dc7825dc7ab74df98c3fb1efd1d6c45` |

本次独立目录`senluo04-server01`、localhost25619，创建新世界与新StarCore/StarWorld数据库，未导入国家、城市、国库、玩家或旧数据库。测试开始前核对9个JAR、7个配置、同源厨房包2,399个文件及55个CE internal文件，读取的静态依赖和资源指纹都保存于PREPARATION。准备记录SHA-256：`05aec2c6149b64fb34301d01c3b1325e880018d7fa3612643c26da2ee187c35f`；实际证据SHA-256：`35cf2ce37ec2ab63207b1a396e75b1d9070c0f76893262dba5b703fffaf471f8`。Cookery未内嵌源码提交，来源审计未声称全JAR可重复构建。

## 实际观察

| 阶段 | 真实通过依据 |
| --- | --- |
| 1–3 载入与入库 | CE和四个森罗模块启用，包子、油、成品酒和乐器可精确构造；公开核心API建立国家、城市、双层领土和两名真实协议玩家；测试投入经过真实玩家背包和deposit命令入库 |
| 4–5 餐饮 | 厨师消耗4包子与1油，酿酒师消耗成品wine与4面包；实际文化与临时产业倍率按定义改变 |
| 6–7 家具 | 公共CE place放置椅子，玩家rayTrace登记同一UUID/ID；登记扣木板且不奖励，下一有效维护周期扣面包并获得文化和装饰效果 |
| 8–10 失地与拆除 | 公开核心撤领后连续两个周期停止家具收益；复领只恢复一次维护贡献；CE实际remove后再次停止消耗与收益 |
| 11–12 演奏 | 真实森罗乐器与同城现场听众，至少60秒、多音高和有效音符；实际发声后完成受控演奏，消耗并奖励一次，重复finish不重复 |
| 13–15 重启 | 第一次正常关服；第二次启动恢复原城市、职业、文化与库存，原演奏再完成仍幂等；第二次正常关服 |

测试投入明确标记，不计作产业产量。GUI、无投入基础三链、战争物流及国库市场由其他同JAR套件单独验收，详见[测试结果](test-results-0.4.0.md)。

## 隔离设置与边界

仅隔离测试服职业冷却设1周期、结算10秒、随机事件概率0；演奏默认60秒、音符/现场听众约束保持。JVM768MiB堆、384MiB metaspace、单逻辑CPU、SerialGC/C1；Node128MiB。CE关闭资源内存缓存并预备internal资源，只在此隔离服关闭Spark。启动前可用提交量3168460800bytes，通过1.5GiB门槛。没有停止无关服务器或改变全局系统配置。

家具身份使用CE公开rayTrace及实体回查，每期复核原位置、已加载区块、当前成员与国家/城市领土；无实体全图扫描或区块强制加载。受控演奏由StarWorld自己的声音和凭据流程证明，未自动监听上游/kmusic。资源包GPU画面、真人听觉、椅子碰撞/乘坐、全部上游作物/家具、热重载与Folia不在协议测试的证明范围。

此前0.2/0.3的启动和工具失败保留在历史报告，本轮没有使用旧JAR通过结果。完整自动化为245项，15阶段实服使用上表同一JAR；可复现前置条件见[验收工具](../../tools/senluo-fixture/README.md)。
