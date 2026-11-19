# 实际客户端联合验收

这是开发测试工具，不进入 StarWorld 插件 JAR。使用实际 Mineflayer 协议玩家连接 localhost 的 Paper 1.21.11，与真实 StarCore、StarWorld、[测试 helper](../paper-fixture/README.md) 共同验证。脚本不模拟核心服务、不写数据库、不下载文件、不更改配置、不替换已经运行的服务器。

在本目录安装 `package.json` 固定的 Mineflayer，或通过 `--client-deps` 指定外部已安装依赖的目录。要求 Node.js 22 以上。

```sh
npm install
node acceptance.cjs --help
node acceptance.cjs --java C:/jdk21/bin/java.exe --runtime C:/starworld-joint-test --paper C:/paper.jar
```

预先准备独立测试目录，含有效 Paper bootstrap 缓存、三个本地插件 JAR、已显式接受的 eula.txt，server.properties 绑定 `127.0.0.1`、offline模式、指定空闲端口。目录 `.starworld-smoke-runtime` 内容必须为 `StarWorld isolated Paper smoke runtime v1`，防误用现有生产目录。

使用新世界/新扩展数据库。StarWorld周期10秒、事件概率0；为验证重启后仍生效，测试定义将drought设duration12、aftermath2。仅隔离服把 StarCore 国家/认领费用设0或为玩家充值。配置与JAR哈希将记入evidence，测试调优不是默认生产配置。

脚本启动自己的 Java 子进程，等待真实启用，再创建 `StarWorldQA` 离线玩家。15阶段包括公共API造国/城/双领地、实际视线登记、无初始生产物资的三链、真实背包箭存取、GUI Shift、防越权、命名物品保护、撤领停产/恢复、事件与两次重启恢复。任何断言失败则保留证据并正常关闭自己创建的服务器；不操作其他进程。

结果在 `runtime/client-acceptance/<UTC timestamp>/evidence.json`，同目录有完整服务端和客户端日志。只有所有阶段状态均为passed才算成功。`latest.json` 指向最近一次结果，失败不会被重新标为成功。不要把 helper 派发命令或仅日志里出现插件名称当作玩法通过。

完整扩展使用独立的 `node expansion-acceptance.cjs --help`，参数相同。它使用 `StarWorldEA` 和 `StarWorldEB` 两个真实玩家、两个国家和两个城市，包含19阶段的设施维护/知识/军需/容量、国家实际发展倾向与创始人专长及生产倍率、职业与实际宴会扣费及农场产量、真实持久军团的公共作战扩展、双向实物托管运输、区域文化、核心国家资源回执、撤领和战争封锁、停战恢复与重启。该套件明确记录玩家物品和国库测试投入；未把 seed 当作产出。测试 helper 明确保活十个测试区块，StarWorld 本身不会加载区块。仅隔离配置关闭 `playability.new-nation-protection-days` 并把 `diplomacy.war.declaration-cost` 设零或公开充值后运行。结果位于 `runtime/expansion-acceptance/<UTC timestamp>`，格式与限制见 [evidence-schema.md](../../docs/evidence-schema.md)。原 `acceptance.cjs` 仍可直接执行原15阶段，亦向扩展套件提供公用服务器和客户端 harness。
