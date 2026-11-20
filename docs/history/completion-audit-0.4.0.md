# 原始任务完成核对

此核对以原始六系统、技术要求和交付项为范围，版本0.4.0；详细源码映射见[requirements.md](../requirements.md)。以下实服证据与245项全量Maven测试均对应同一安装JAR：`68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba`。

| 原始范围 | 已交付与核对依据 |
| --- | --- |
| 两仓库最新代码、README、API、模型与依赖审计 | [StarCore审计](../audit-starcore.md)、[森罗审计](../audit-senluo.md)记录固定上游提交与公开签名；桥接测试与实际核心服务验收，未复制国家/城市/领土权威 |
| 地理战略 | 六地形、有限储量与恢复、农业/运输/军队影响、道路/桥梁/港口/关隘及地区地图册；[地理](../geography.md)、[物流](../logistics.md)、[战斗](../combat.md)及进阶/扩展实服 |
| 动态文明 | 五个可配置文明，城市规划/专长、国家倾向/专长、文化积累/贸易传播/政策与衰减、核心科技联动；Evolution/Culture/National测试及进阶/扩展实服 |
| 城市建设 | 九类锚点登记设施、维护/三级升级/知识/本地繁荣/特色分工，真实Deco家具联动；GUI/事务测试及扩展/森罗实服 |
| 生产与产业 | 三条完整粮食、木材和矿业加工链，食品/战略物资配方、真实仓储供需、易货与国库实物市场、精确CE ID、政策科技修正；基础/扩展/进阶实服 |
| 动态事件 | 十一种条件事件、持久冷却/持续期/余波/历史；边境事件有相邻外国领土、对应战争或敌对和正资源证据；自然事件与合法自定义资源键测试、边境实服 |
| 城市生活 | Cookery食材菜肴、Tavern酒品、Deco家具、MusicPlus真实乐器参与受控节庆；四生活职业有实际消耗与产出影响；森罗15阶段实服 |
| 持久化和防重复 | 独立SQLite事务、周期/货单/付款/生活凭据、国家outbox回执；并发/崩溃/失ACK/重开测试，所有实服两次启停；千城重放库存不增加 |
| 配置、GUI、Java API/事件 | 七配置与示例、受保护的城市/发展/地区/市场GUI、不可变异步查询和提交后事件；[API](../API.md)、Config/CityMenu测试及真实GUI操作 |
| 性能与区域线程 | 分页/有界登记对象处理、不持续全图扫描或加载区块；[千城SQLite基线](scale-0.4.0.md)。Folia按原任务完成调度迁移规划并明确拒绝当前不安全上游启用，未宣称运行兼容 |
| 可选依赖与独立安装 | 缺模块带明确能力原因、停相关动作；无依赖两次正常Paper启动、SQLite标记恢复，[机器证据](../evidence/history/0.4.0/no-dependency-smoke.json) |
| 升级与迁移 | 真实已验收0.3存档副本升级、七旧配置与原状态保留，新表加法建立，两个真实启动；未来版本拒绝/配置迁移测试；降级恢复对应备份 |
| 源码、Maven、JAR、示例、API、README和测试结果 | 完整源码、Maven Wrapper、主/源码JAR、七配置、中文文档与原始报告；打包脚本核对JAR/源码和每项证据SHA256，独立ZIP审计核CRC、完整源码与允许内容 |

## 同一最终JAR实服记录

| 套件 | 通过阶段 | 机器证据 |
| --- | ---: | --- |
| 经营规划、到货与在线备份 | 29/29 | [operations-client.json](../evidence/history/0.4.0/operations-client.json) |
| 地区与市场 | 20/20 | [progression-client.json](../evidence/history/0.4.0/progression-client.json) |
| 国战扩展 | 20/20 | [expansion-client.json](../evidence/history/0.4.0/expansion-client.json) |
| 基础玩法 | 15/15 | [baseline-client.json](../evidence/history/0.4.0/baseline-client.json) |
| 森罗联动 | 15/15 | [senluo-client.json](../evidence/history/0.4.0/senluo-client.json) |
| 旧存档升级 | 7/7 | [upgrade-client.json](../evidence/history/0.4.0/upgrade-client.json) |
| 边境资源 | 11/11 | [border-client.json](../evidence/history/0.4.0/border-client.json) |

共117个实服阶段（经营套件保留20阶段进阶路径，再增加9项经营检查），另有缺依赖两次正常启动。245项自动化测试0失败、0错误、0跳过；千城SQLite基线完成1000城市/2000设施/3000结算及重启重放1000城市。原始日志、Surefire、JaCoCo、准备来源和SHA256清单保存在证据包。

原始任务没有要求自动合并全图地貌、连续道路全段识别、独立人口/选举、另一套国政科技、全境围城补给或自动市场退款。这些可扩展能力不作为本次完成项。Folia正式运行、资源包GPU画面、真人听觉和Paper在线容量的验证边界见[后续边界](../roadmap.md)；当前结果不替代这些验证。

0.4 新增有限商路成本规划、真正完成交货的精确统计和原子在线备份，具体新断言见[测试结果](test-results-0.4.0.md)。0.3 完成记录独立保存在[历史报告](completion-audit-0.3.0.md)。本页核对当前版本；用户授权的后续六系统深化仍按[开发计划](../development-plan.md)继续，未将后续版本算作已完成。
