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
