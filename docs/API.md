# Java API 与事件

消费插件将 StarWorld JAR 作为 provided，声明 depend/softdepend 后判空：

```java
StarWorldApi world = Bukkit.getServicesManager().load(StarWorldApi.class);
if (world == null) return;
Definitions definitions = world.definitions(); // immutable
world.city(starCoreCityId).thenAccept(result -> {
    // Storage worker: schedule Bukkit/player operations separately.
    result.ifPresent(city -> logger.info(city.stock().toString()));
});
```

接口：`dev.starworld.api.StarWorldApi`。

| 方法 | 契约 |
|---|---|
| version() | 插件版本 |
| definitions() | 不可变配置快照，可跨线程读取 |
| city(UUID) | Future<Optional<CityView>>，仅查询已登记扩展状态，不证明实时领土有效；IO 错误 future 异常 |
| integrationStatus() | 集成能力诊断，涉及 StarCore 读取，须在 Paper 主线程调用 |

CityView 包含核心城市/国家 UUID、名称、文明、库存/设施、事件时间线、最近周期/报告。未登记返回 empty。API 不公开绕过权限的库存 mutation 入口。WorldStore 和直接 SQL 不属于稳定扩展 API。

## 事件

```java
@EventHandler
public void settled(IndustrySettledEvent e) {
    var receipt = e.settlement();
    // Already committed; do not grant produced() again!
    logger.info(receipt.cityId() + ": " + receipt.report());
}
@EventHandler
public void evolution(WorldEvolutionEvent e) {
    // previous()/current() immutable; current()==null means aftermath ended.
}
```

包 `dev.starworld.api.event`。事件在 **Paper 主线程、事务提交后** 通知，不可取消。重复结算不发 IndustrySettledEvent；WorldEvolutionEvent 表示事件开始、替换和余波结束，余波开始直接按 endPeriod 判断。

提交后通知前进程可能崩溃，事件不是可靠队列。消费者以 `(cityId,period)` 去重并通过快照对账，不得把监听器调用当跨系统发放凭据。

## 国家状态

真实只读调用：TechnologyService.getTotalModifier(nationId,"production_speed")，PolicyService.activePolicyModifier(nationId,"production_bonus",GLOBAL/PRODUCTION)，ProsperityService.getResourceBonus(nationId)。

国家倍率为 `clamp((1+speed)*(1+policyGlobal+policyProduction)*prosperity,0,10)`，再乘本地文明/地形/事件并裁剪。缺可选模块用中性值且标记原因；核心国家/城市失效则停产。stockpile 用于展示国家七类抽象资源，不假装与实体小麦/铁锭等价。

StarCore 的 NationResourceTransactionService 已有 request 与 receipt。后续国家输出必须稳定交易 ID + 本地 outbox + 回执确认；当前版本不写国家资源，不调用 legacy boolean grant 自动发放。
