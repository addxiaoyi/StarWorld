# 历史验证对象

当前版本的单测、规模及运行摘要位于 `docs/evidence/`，必须和[测试结果](../test-results.md)中的最终JAR哈希一致。此目录及 `docs/evidence/history/` 保留各历史版本与同版本旧构建的验证对象，不能合计为当前版本的通过数。

- [0.2.0交付报告](test-results-0.2.0.md)及[森罗报告](senluo-acceptance-0.2.0.md)对应 `3940fdf9…654f6`，机器记录位于 `../evidence/history/0.2.0/`。其中早期 `paper-client.json` 本身仍标识0.1.0，不作为0.2的最终证明。
- `../evidence/history/0.3.0-pre-border/` 保留 `dabe9c4f…8d193` 构建的217项测试、规模、进阶和森罗观察。随后补齐真实边境资源条件，最终构建重新执行整套验证；这些旧观察不作为新JAR通过证明。
- `../evidence/history/0.3.0-border-before-custom/` 保留 `45159ecc…d3494` 构建的230项测试、规模、边境、森罗和升级观察。随后修正合法命名空间/路径资源键的兼容校验，最终构建为232项；此目录同样只作历史记录。

失败或宿主机中断的原始服务器目录保留在隔离测试工作目录，不会改标为passed或混入当前交付的通过证据。
