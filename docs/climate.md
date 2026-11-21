# 逐世界气候与产业韧性

StarCore继续拥有天气权威。StarWorld仅在城市结算前，对已登记且当前现场验证的设施采样其所在世界公开 `WorldWeatherState`，读取当前天气与控制国家。只有控制国家等于设施所属国家时，才应用对应天气倍率；世界未控制、控制权不符或服务不可用时，天气效果为1.00。采样只遍历单城最多64个有效设施涉及的世界，不访问相邻区块、不扫描全部世界，也不加载区块。

内置映射只使用StarCore公开资源语义：`agricultural` 影响 `farm` 的农业配方，`mineral` 仅影响使用 `ore` 储量的 `workshop/mine` 原矿产出。熔炼、锻造、木材、箭矢和其他抽象资源不套用相近但不同的天气键。StarCore数值是直接倍率；例如1.5代表产量乘1.5。`climate.yml` 把农业和矿业天气倍率都限制在0.5至1.5。

完成并本周期已支付运行维护的 `irrigation_network` 可缓冲农场的不利农业天气：默认恢复一半天气惩罚，上限仍受农业倍率范围限制。它不抵消丰收等正面天气，不影响矿井，也不绕过工程施工、运行或设施有效性。

科技倍率按精确的 effect/building/recipe 配置映射，并默认不映射。`production` 聚合键、`crop_growth` 的方块生长机率、未映射效果和未知效果都不直接改变周期产量；管理员如需把 `resource_yield` 解释成工坊矿井额外比例，须在 `climate.yml` 明确填写 `technology-mappings`，额外幅度受 `technology.adjustment-minimum/maximum` 限制。StarCore科技效果聚合的来源语义可能随上游变化，映射前须重新审计。

```yaml
schema-version: 1
input:
  multiplier-minimum: 0.0
  multiplier-maximum: 10.0
weather:
  agriculture: {minimum: 0.5, maximum: 1.5}
  mineral: {minimum: 0.5, maximum: 1.5}
resilience:
  adverse-agriculture-mitigation: 0.5
technology:
  adjustment-minimum: -0.5
  adjustment-maximum: 0.5
technology-mappings: {}
```

`/sw climate` 与城市 GUI 可查看设施世界、天气归属、逐配方当前倍率和最近结算的灌溉运行状态。公开 `StarWorldApi.climate(cityId)` 返回不可变异步投影；它不推进周期、扣除维护或授权写入。查询状态未登记或现场没有有效设施时返回空结果。

天气快照和产业出产在同一现有城市结算事务内使用；结算去重、库存/资源守恒及重启规则不变。天气API不可用时保留原有产业结果。此版本仍只支持Paper；StarCore可变服务尚无Folia区域线程安全契约。
