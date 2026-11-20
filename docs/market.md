# 国家金币与城市实物市场

0.3.0 的市场出售城市仓库内的真实物品，使用 StarCore 现有国库金币结算，并通过已登记商路交货。它复用核心 `NationTransferService` 的原子国家转账和持久回执。核心自己的抽象国家资源订单簿仍由 StarCore 管理；小麦、面包和 CE 菜肴不被当作国家资源 ID。

## 游玩流程

1. 卖方城市管理者用 `/sw deposit minecraft:bread 64` 存入真实物品，再执行 `/sw market sell minecraft:bread 20 1.25`。20 个面包立即进入挂牌托管，整批价格为 25.00 国家金币；当前仅整批购买。
2. 两城建立一条从卖城正向通往买城的有效商路，参考 [logistics.md](logistics.md)。跨国战争或未知外交关系、停用路线/节点、失地、未加载或损坏的节点会阻止新购买或付款。商品批量不得超过商路单周期容量，应先拆分挂牌。
3. 买方国家创始人或管理员同时具有本城管理权限时，执行 `/sw market buy <挂牌UUID> <卖城至本城商路UUID>`。普通市长不能支出国家金币；同国城市间使用实物贸易。
4. 系统锁定挂牌，持久保存购买请求和国家付款 ID。后台核对双方当前归属、外交和商路后，调用核心原子转账。付款确认后，原托管物进入一张单向运输货单。
5. `/sw market orders` 查看付款状态、原国家凭据与物流单 UUID；`/sw trade list` 查看实际运输。买城只有在物流单 `COMPLETED` 时才收到物品，战争、维护不足或仓储满等会暂停，货物继续托管。

`/sw market-menu` 或发展面板的市场入口可分页查看挂牌；点击商品提供带挂牌 UUID 的购买命令，需要玩家明确填写正向商路。展示物品不能被取出。

| 命令 | 用途 |
| --- | --- |
| `/sw market list [页码]` | 查看可用挂牌，每页十项 |
| `/sw market mine [页码]` | 查看本城所有状态的挂牌 |
| `/sw market sell <物品ID> <数量> <单价>` | 城市管理者托管整批实物；单价为普通十进制、最多两位小数 |
| `/sw market buy <挂牌UUID> <正向商路UUID>` | 国家创始人/管理员确认国库支付 |
| `/sw market orders [页码]` | 查看本城作为买方或卖方的购买单 |
| `/sw market cancel <挂牌UUID>` | 撤销仍 `AVAILABLE` 的本城挂牌，原仓有空间才退还 |
| `/sw market cancel-buy <购买UUID>` | 仅撤销从未派发过的 `PENDING` 付款准备，重新开放原挂牌 |
| `/sw market retry <购买UUID>` | 原付款国创始人/管理员用同一国家凭据恢复对账 |
| `/sw market history [物品ID]` | 最近十份已付款且已转入物流的价格记录 |

`SHIPPED` 和价格历史表示货物已经转入运输托管，不能据此认定买城已经收货。实际到货以物流完成收据为准。

## 付款与恢复

| 购买状态 | 持久语义 |
| --- | --- |
| `PENDING` | 已锁定实物，等待首次派发 |
| `IN_FLIGHT` | 已取得派发租约，核心可能已经提交；崩溃后等待租约到期再核对同一 ID |
| `RETRY` | 接口暂不可用、路线/归属阻塞或尚无可核实回执，按原 ID 重试 |
| `REJECTED` | 核心明确拒绝，例如金币不足；原物仍锁定，治理者可修正条件后重试 |
| `REVIEW` | ID 冲突或回执内容不符，保留托管并要求核对；不会发货 |
| `PAID` | 核实核心持久回执，尚待成功创建物流单 |
| `SHIPPED` | 托管原物已在同一 SQLite 事务内交给单向物流 |
| `CANCELLED` | 首次派发前已取消，挂牌恢复可用 |

每个数据库实例保存自己的随机命名空间，购买 UUID 对应稳定 `starworld:market:<namespace>:<purchase>` 国家事务 ID 和物流 ID。核心回执必须逐项匹配 ID、付款国家、收款国家、精确金额和 `OTHER` 类别；不以“调用未抛异常”当作到账。相同请求 ID 搭配不同参数会被拒绝。

核心已支付、本地确认前退出时，重启先查询原回执；原子国库服务不会再次扣款。路线或归属在付款后变化时仍查询历史回执，但不会在无效路线下发起新付款。`PAID` 发货失败保留原托管，有限批处理与持久退避让后续可处理的购买继续前进。

付款与物流不在一个跨插件数据库事务中；持久 outbox、核心原子回执和本地幂等交接连接这两个边界。只有完全未派发的购买可直接取消。已经尝试付款的订单不能因为本地异常自动退款；已支付物流不能使用普通易货撤销或未付款报价过期退款。当前没有自动跨国退款/战利品裁决入口，恢复条件后继续交货，避免把同一付款和商品退给双方。

卖方可撤销未锁定挂牌，到期的未锁定挂牌自动尝试退回原仓。满仓、国家改属或无法证明原国归属时，原物继续托管，既不丢弃也不给新所有者；轮转检查不会让这一条永久阻塞其他到期挂牌。

## 配置与能力降级

首次启动生成独立 `market.yml`，已有文件不覆盖。默认值如下：

```yaml
market-version: 1
max-listings-per-city: 20
listing-lifetime-periods: 48
dispatch-batch: 10
max-units: 2304
maximum-unit-price: '1000000.00'
items:
  - minecraft:wheat
  - minecraft:bread
  - minecraft:oak_log
  - minecraft:oak_planks
  - minecraft:iron_ingot
  - minecraft:arrow
  - minecraft:shield
  - minecraft:paper
  - kaleidoscopecookery:baozi
  - kaleidoscope_tavern:wine
```

只允许白名单实物，CE ID 必须真实可解析，不能用配方 ID、显示名或临时石头替代。每城未结束挂牌、数量、单价、金额与派发批量有硬上限。金额使用 `BigDecimal`，最多两位小数，必须在 0.01 至 1,000,000,000,000.00 之间；不会舍入玩家价格。

缺少 `NationTransferService` 或核心未启用时，状态页明确显示国家金币市场待恢复，不使用无稳定业务 ID 的个人 Economy 转账，也不将独立 debit/credit 拼成国家原子支付。现有实物产业和易货链可继续使用。高于当前的市场配置/数据库版本拒绝加载并保留原文件。

## Java 与测试边界

公开只读查询为 `StarWorldApi.marketListings(limit,offset)` 与 `marketPurchases(city,limit,offset)`，见 [API.md](API.md)。内部 `MarketService` 与 `LogisticsService` 共用 `WorldStore` 串行 IO 和事务；写入调用方必须先由 Paper 主线程捕获当前城市、治理、领土、节点与外交证据，内部方法不构成免权限玩家 API。

真实 SQLite 回归覆盖挂牌扣货一次、唯一买方、付款丢 ACK/重启、回执冲突不发货、发货事务失败回滚、未派发撤单、满仓到期与公平轮转；控制器另验证创始人权限和异步回调再次授权、停用节点后不调用新转账。核心适配器测试按审计的公开接口精确签名执行。实际服务器、客户端与最终通过数量另见 [test-results.md](test-results.md)。
