# 城市军需与真实核心战斗

`StarCoreCombatContributor` 通过 StarCore 公开的 `ExtensionRegistry` 和 `ArmyCombatExtensionPoint` 注册 `army_combat` 贡献者。StarCore 的 `BattleCalculator.calculateDamage` 在实际军队交战时调用此扩展点，因此已提交的城市军需与工业区地形可以实际改变核心军队的伤害值，不需要修改战争、军队或国家权威数据表。

适配器只在 Paper 主线程读取公开的 `StarCoreApi.service(ArmyService.class).getArmy(UUID)`，取得军队国家与坐标，再按区块查本地不可变城市军需缓存。城市与国家必须仍存在，军队所属国家必须匹配，且战斗位置仍同时属于该城市领地和国家领土、世界和区块已加载。它不会扫描全服城市、区块或实体。

缓存来自城市周期事务提交后的 `Readiness` 与该周期校验通过的设施坐标。只有当前周期或前一周期、确有实际军需供给的快照生效。未来周期、超过一周期、缺少军队、丢失领土、归属变化、未加载区块和非主线程回调均不给予贡献；核心使用自己的默认伤害或其他插件结果。军需作用范围是有已验证设施的工业区块，不假称整个国家所有战场都被覆盖。重启后没有缓存，须等下一次真实结算恢复。

默认伤害公式为：

```
attack = clamp(attackerReadiness, 0, 1)
defense = clamp(defenderTerrainDefense - 1, 0, 1) * clamp(defenderReadiness, 0, 1)
damage = coreBaseDamage * (1 + maxAttack * attack) * (1 - maxDefense * defense)
```

`maxAttack` 默认 `0.30`，完整供给最多增加30%攻击伤害；`maxDefense` 默认 `0.20`，额外地形防御最多减少20%伤害。它使用配置文明、产业供给与地区地形产生的真实军需值，不固定某个文明 ID。没有有效军需时不凭空提供加成。

StarCore 的该扩展点在多个贡献者之间选择**最高伤害**，不会自动相乘。因此其他插件更高的结果可以覆盖 StarWorld 的地形减伤，也可能覆盖较小的增伤。StarWorld 保留该公开聚合规则，不能声称拥有最终伤害控制权。适配器不修改真人玩家 PVP 伤害或核心军队补给值；`ArmyService.resupplyArmy` 本身消费的是核心国库，国家 `weapons` 资源库存不应被描述成该方法自动消费的军粮。

配置位于根协调器读取的 `evolution.yml`：

```yaml
combat:
  enabled: true
  max-attack: 0.30
  max-defense: 0.20
```

攻击上限允许0至1，防御减伤上限允许0至0.8；非有限数值拒绝加载。设置 `enabled: false` 明确关闭作战扩展。

Java 合同：

```java
var settings = StarCoreCombatContributor.Settings.load(evolutionYaml);
var combat = new StarCoreCombatContributor(plugin, coreBridge, runtime::currentPeriod, settings);
combat.register(); // Paper main thread; false means explicit degradation via status()

// After a committed city cycle, and after main-thread site validation:
combat.update(readiness, validSites);
combat.remove(deletedCityId);
combat.prune(runtime.currentPeriod()); // Remove snapshots older than one period.
combat.unregister(); // Paper main thread at disable; remove only our contributor.
```

注册适配器时若核心尚未创建该公开扩展点，调用其公开构造器完成注册。卸载时只注销 StarWorld 自身贡献者；仅当扩展点由 StarWorld 创建、仍是同一个实例且已经没有其他贡献者时，才移除空扩展点。不能调用 `unregisterAllContributors` 或清空其他插件的注册。

自动化测试使用与审计源码一致的测试专用公开接口替身，验证反射注册/卸载、主线程与实时领土复核、默认增伤/地形减伤、过期与未来周期中性、无供给中性、多个贡献者的真实最高值聚合、其他插件注册保留、禁用与配置边界。替身只在测试目录中，不打入发布插件。
