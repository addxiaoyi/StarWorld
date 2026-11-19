# 测试结果（2026-11-19）

## 最终构建

命令：`./mvnw -B --no-transfer-progress clean verify`，Windows 实际执行 `mvnw.cmd`。Microsoft OpenJDK **21.0.13.1+2**、Maven **3.9.11**，最终构建完成于 **2026-11-19 20:51:32 Asia/Shanghai**，BUILD SUCCESS。

`target/starworld-0.1.0.jar` SHA256：

```text
7e962b132c474519fea4f7d2d215ae07766baf6e3e2b1a48b0f16ccec9da6040
```

最终真服验收使用的 JAR 与这个哈希相同。ZIP 全条目 CRC 检查通过，包含 plugin.yml/config.yml/definitions.yml、插件类、SQLite JDBC（含平台原生库）；**不包含** StarCore 类、验收 helper、测试替身或测试类。

## 自动化测试

| 测试组 | 数量 | 范围 |
|---|---:|---|
| ConfigLoaderTest | 9 | 默认定义、版本迁移备份、非法版本/引用/数量/倍率/锚点、配置自定义数据 |
| SimulationTest | 13 | 三条完整链、六地形、两文明、三个事件与余波、重启/同周期/回拨、同/双连接并发、失地/转国停产、矿藏耗尽、维护与容量、失败全回滚、共享地区恢复一次、缺内容停用 |
| CustomItemsTest | 6 | 可选依赖降级、CE 精确身份、特殊原版元数据保护、失败关闭、返回副本 |
| StarCoreBridgeTest | 12 | 经审计公开方法签名、核心存在性、成员/市长/国家创始人授权、双领土、真实科技/政策/繁荣倍率、缺模块/线程边界 |
| WorldStoreTest | 10 | SQLite事务、Error回滚、独立子进程 Runtime.halt(23) 后 WAL 恢复、真实缺v2表/索引的v1迁移、高版本拒绝、物品凭据重启隔离与单次核对、重复登记不刷新矿藏、全国文明冷却、升级扣费原子性 |
| WorldRuntimeTest | 11 | 活跃转移拒绝人工核对、权限/归属变化、库存/背包不足、成功存取与分堆、一次身份快照、保存失败隔离 |
| CityMenuTest | 7 | 底栏/Shift/拖动/非拥有者取消，下一tick操作，换城市/换界面避免旧动作 |
| **合计** | **68** | **0 失败、0 错误、0 跳过** |

机器摘要：[unit-tests.json](evidence/unit-tests.json)。完整 XML/文本：`target/surefire-reports/`。JaCoCo：`target/site/jacoco/`。核心计算/SQLite 为真实实现；平台服务/物品边界有 Mockito 模拟，不能把这些契约测试视作真实核心已运行。以下另有独立真服证据。

## 独立安装与恢复冒烟

实际 Paper **1.21.11 build132** + Java21，无 StarCore/CE/森罗：成功加载 StarWorld、明确报告各依赖降级、执行控制台 `starworld status`、正常关闭、检查 SQLite schema2，第二次启动后验证持久化审计标记仍存在。两次均正常退出。

脚本：[smoke-paper.ps1](../tools/smoke-paper.ps1)。示例：

```powershell
.\tools\smoke-paper.ps1 -JavaPath C:/jdk21/bin/java.exe `
  -ServerJar C:/paper-1.21.11.jar -PluginJar ./target/starworld-0.1.0.jar `
  -RuntimeDirectory C:/starworld-test -BootstrapDirectory C:/prepared-paper -AgreeEula
```

仅使用指定本地文件与已核验的 Paper bootstrap 缓存，绑定 localhost；已有非空目录必须有工具自己的 sentinel。脚本不下载依赖，`-AgreeEula` 为显式测试参数。原始日志位于本机 `C:/Users/Administrator/Documents/starworld-tools/smoke-server/smoke-run-1.log` 与 `smoke-run-2.log`，联合验收另提供完整可归档日志。

## 真实 StarCore 与客户端联合验收

**15 个阶段全部通过**，实际起止 **2026-11-19 20:52–20:55 Asia/Shanghai**。使用真实 Paper 1.21.11-132、真实 StarCore `f769e627` 产物、最终 StarWorld JAR、Mineflayer **4.39.0** 的实际在线协议玩家；没有将测试替身注入 Bukkit 服务。

StarCore JAR SHA256：`953f926f12384e0cfafae6f55d266a10c7156468e7e28be75518c3f7b413884b`。来源核验与真实运行记录：[provenance](../tools/paper-fixture/starcore-build-provenance.json)。本机相同提交无源码变更的完整产物，在构建进程结束后核验复制，随后由联合启动/玩法验证证明可用。上游源码和权威表未被 StarWorld 修改。

1. 三个真实插件启用，StarCore 公共 API 连接。
2. 实际离线协议玩家登录。
3. 公共 API 建国家、城市及国家/城市双层区块领土。
4. 实际玩家视线/命令登记三种设施，收到三条成功反馈，查询持久化恰好三座。
5. 未注入生产物资，经过实际周期，粮食、木板、军工箭真实产出。
6. 从仓库取出4箭，真实客户端背包看见4箭，存回后箭为0。
7. 实际 GUI Shift 点击不能拿走展示信标。
8. 普通国家创始人触发管理员事件被拒绝。
9. 16普通小麦+7命名小麦，存17失败且不吞道具；存16成功，7命名小麦保留。
10. 撤销真实国家工坊区块认领后，停产报告出现且连续两个实际结算周期箭数不增加。
11. 重新认领后，军工箭数再次增长。
12. 管理员触发干旱，API 快照确认实际持久化。
13. 第一次正常关服并提交状态。
14. 重启与客户端重新登录，核心城市ID、3设施、库存/账本恢复，干旱完整时间线不变且仍生效；背包箭0、普通小麦0、命名小麦7，未重放已存物品。
15. 第二次正常关闭。

机器证据含逐阶段观察、快照、JAR哈希：[paper-client.json](evidence/paper-client.json)。工具：[fixture](../tools/paper-fixture/README.md) 与 [客户端脚本](../tools/paper-client/acceptance.cjs)。完整服务端/客户端日志可随交付归档：`target/StarWorld-0.1.0-evidence.zip`。

隔离测试配置明确区别于默认生产配置：周期10秒、随机事件概率0、干旱12周期+2周期余波（保证重启仍生效）；国家/认领费用0；上游无关 dungeon 模块因 Windows 目录同步错误在此测试禁用、地图 Web 服务关闭。其余国家/城市/科技/政策/繁荣/资源服务使用真实实现。配置/定义哈希已写入机器证据，不把这些调优混入默认资源配置。

首个联合尝试因测试 helper 同tick连续传送导致前两次登记读到最终视线而失败，保留失败日志；修正 helper 每座登记分tick后，从全新隔离世界重新进行上述完整验收。没有以 command dispatched 代替成功观察，也没有为通过测试放宽生产权限。

## 验证限度

第一阶段默认原版产业链已真服验证。CE/Cookery/Tavern 公共物品签名已源码审计并做边界契约测试，但**没有安装整套森罗内容做多人真服验收**；Deco/Music 自动贡献明确不可用。道路、市场、港口、文化传播、完整生活职业与更大规模文明系统属于后续设计。Folia 仅完成调度规划与明确停用边界，未声明可运行兼容。

JAR shade 对标准 META-INF/MANIFEST.MF 有一次无害重叠提示；无编译/测试错误。未把上游独立模块问题或测试工具初始失败隐去并宣传为全系统无风险。生产服上线前应使用自己的配置/核心版本在测试服复核。
