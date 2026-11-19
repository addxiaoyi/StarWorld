# Java API 与事件

消费插件将StarWorld作为provided依赖，通过Bukkit ServicesManager查询 `dev.starworld.api.StarWorldApi`。公开查询不授予管理权限，不承诺实时领土有效；返回值是不可变快照，Future失败表示IO/状态错误。

```java
StarWorldApi world = Bukkit.getServicesManager().load(StarWorldApi.class);
if (world == null) return;
world.city(starCoreCityId).thenAccept(result -> {
    // Storage worker callback: schedule all Bukkit/player work separately.
    result.ifPresent(city -> logger.info(city.stock().toString()));
});
```

| 方法 | 结果与语义 |
| --- | --- |
| `version()` | 当前插件版本 |
| `definitions()` | 不可变地区/文明/设施/产业/事件配置 |
| `city(UUID)` | `Future<Optional<CityView>>`；已登记扩展城市、库存、设施、事件和最后报告 |
| `development(UUID)` | `Future<Optional<Profile>>`；规划/专长、城市繁荣、稳定、知识、军需、文化、倾向 |
| `nationDevelopment(UUID)` | `Future<Optional<NationalEvolution.NationDevelopment>>`；最近核心复核的有效归属城市聚合的国家倾向、知识/文化、主导方向和专长；入参为StarCore国家UUID |
| `culture(UUID)` | `Future<List<Culture>>`；已登记地区各文明传统与影响值 |
| `life(UUID)` | `Future<Optional<LifeView>>`；累计生活文化、当前有限倍率、家具和活动会话 |
| `routes(UUID,int,int)` | `Future<List<Route>>`；有限分页查询城市相关商路 |
| `orders(UUID,int,int)` | `Future<List<Order>>`；有限分页查询城市相关托管货单 |
| `readiness(UUID)` | `Future<Optional<Readiness>>`；城市/国家/周期、provided/required军需、terrainDefense及readiness |
| `integrationStatus()` | 真实能力/降级原因；涉及StarCore读取，须在Paper主线程调用 |

未登记或模块不可用返回empty/空列表；API扩展查询有默认空实现以保留旧消费端源码兼容。城市、成员和领土的权威仍来自StarCore。`WorldStore`、直接SQL以及模块内部mutation入口不是稳定的无权限公开写API，不应让不受信玩家参数直接调用。

## 提交后通知

事件包为 `dev.starworld.api.event`，在Paper主线程、事务提交后通知，不可取消：

| 事件 | 不可变载荷 |
| --- | --- |
| `IndustrySettledEvent` | `settlement()`：城市/周期、produced、报告；重复周期不再次发送 |
| `WorldEvolutionEvent` | `previous()` / `current()`；开始、替换和余波结束，current可为null |
| `CityDevelopmentEvent` | `profile()`；已提交城市发展快照 |

```java
@EventHandler
public void onIndustry(IndustrySettledEvent event) {
    var receipt = event.settlement();
    // Output already exists in city storage. Never grant produced() again.
    logger.info(receipt.cityId() + " / " + receipt.period());
}
```

提交后、通知前进程可能退出。事件不是可靠队列，不保证跨重启重放；消费端按城市/周期去重并查询快照对账。它们不能代替国家幂等资源回执。

## 现有核心复用

`CoreBridge` 在Paper主线程验证真实国家、城市、成员、管理/统治权限和双层领土，读取实际科技、政策和国家繁荣修正。`StarCoreExtensions` 读取天气、纪元、战争/外交、已解锁科技、当前政策、国家资源以及真实 `ProcessingService` 的配方和工厂。每次返回防御复制的不可变记录，缺模块带capability原因；未知战争或外交不能当作和平。

国家生产倍率为 `clamp((1+speed)*(1+policyGlobal+policyProduction)*prosperity,0,10)`，再组合本地地理/文明/规划/文化/事件/生活效果并有限裁剪。军事/贸易使用对应scope，实体物品不被当作抽象国家资源的别名。

`NationalEvolution` 只保存国家专长选择与转型周期，农业/工业/贸易/军需/文化和知识来自本国最近核心复核为有效的归属城市已提交演化状态。`city_authority` 排除核心已确认缺失或无法证明存在的城市，重启保留失效标记；有效核心快照可恢复。归属变化最多需要一次城市分页轮转及IO排队后反映，不在API查询时扫描全服。`view(nation)` 不创建StarCore国家；`choose(nation,id,period,actor)` 要求调用方先在Paper线程证明国家创始人/管理员权限；`multipliers(nation)` 在行为/知识条件不再满足时返回空效果。专长倍率受0.85–1.15界限限制，并和城市规划等共同裁剪。

审计基线真实资源ID为 `mineral/agricultural/energy/luxury/industrial/chemical/strategic`，由 `ResourceModule` 注册；默认显式兑换配方输出 `agricultural/industrial/strategic`，执行前仍通过 `ResourceService.availableResourceTypes()` 验证，不读取legacy具体商品名。`Category.OTHER` 是事务用途分类。`ResourceOutbox` 按稳定business key产生持久事务ID，成本和队列同事务写入；主线程调用 `NationResourceTransactionService` 后核实匹配receipt，本地再ACK。详见[国家扩展文档](core-extension.md)。

`StarCoreCombatContributor` 将已提交readiness和主线程验证过的设施区块缓存接入真实 `army_combat` 公共扩展点；仅当前/前一周期、真实供给、实时归属有效的军队参与。它不修改玩家PVP或ArmyService补给，核心仍按最高伤害聚合多个贡献者。详见[战斗接口](combat.md)。

## 生活与交通模块接口

`LifeService` 提供带request UUID的宴会、家具登记/维护、会话/音符/完成回执、职业与文化查询。`drainEvolutionCulture(city,period)` 必须在模拟事务内调用，使用已消费回执ledger防重复并承接迟到活动。`LifePlatform` 只在Paper主线程验证真实实体/乐器/听众；证据类型不能由包外构造。详见[生活接口](life-integrations.md)。

`LogisticsService` 与WorldStore共用连接，节点/路线、托管、运输与完成收据均为不可变值，世界和权限操作由 `LogisticsController` 主线程摘取证据。没有金币增发或绕过国家权限的路径，详见[交通贸易](logistics.md)。
