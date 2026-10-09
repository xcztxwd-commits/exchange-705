# K 线缺口回填：本轮本地回归验收

执行时间：2026-10-09 16:32:55—16:38:47（Asia/Singapore）。工作树：`C:/Users/徐乾妖/.codex/worktrees/72e5/705`。

本轮指定脚本退出码 **0**，13 组检查最终均为 **PASS**。所有结论使用本轮新建目录中的实际报告，不使用旧报告替代执行。

```powershell
python "C:\Users\徐乾妖\.codex\worktrees\72e5\705\scripts\market\test_kline_gap_local.py"
```

## 本轮证据

- [执行汇总与逐组日志](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/README.md)、[机器可读汇总](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/local-summary.json)。
- [顶层实际命令、起止时间与退出码](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/runner.command.json)、[顶层输出](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/runner.log)。
- [额外证据核验](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/independent-verification.json)：JUnit、前端测试数量、浏览器报告、14 张截图、保护事实、匿名卷清理、源码哈希。
- 后端 JUnit 在 `C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/backend-junit/`；真实 MySQL JUnit 在 `C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/physical-junit/`。各 `*.command.json` 和 `*.log` 保存实际命令、cwd、时间、退出码及输出。
- [基线与原有修改清单](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/baseline.json)、`C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/baseline.diff`、`C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/baseline-files/` 保留开始时的修改与文件副本。

## 功能与关联模块结果

| 范围 | 结果 | 本轮覆盖及证据 |
|---|---|---|
| 后端定向回归及打包 | PASS | 31 类、176 项；失败、错误、跳过均为 0。真实 Maven package 成功。 |
| 缺口检测、异步回填、重复请求 | PASS | KlineGapBackfillTest 7 项、SourceHistoryGapRepairTest 34 项；逐槽稀疏检测、去重、串行拆窗、限流、错误与重试、insert-only、审计和缓存失败。 |
| 1m/5m/15m/30m/1h | PASS | 原生周期独立，秒/毫秒规范化、周期边界、未收盘排除；不据 5m 完整推断 1m 完整。 |
| 真实事务、锁和幂等 | PASS | 新建 MySQL 5.7.44，2 类、2 项测试实际执行，无跳过。8 次并发只新增 2 行、唯一源修订；插入失败、修订失败、lease/generation 失效均回滚；缓存失败后重试不重写。 |
| 控盘、模拟、快照、封存及已发布历史 | PASS | 控盘任务保持 RUNNING；同周同月、hold、mixed minute、SOURCE 资格、模拟前缀、恢复账本、封存精确响应保护。运行中与已发布区间的 1m/5m/15m/30m/1h/1w/1M 非空 OHLCV、权威报价、启动依据和保护事实前后字节相等。恢复/撤销只在隔离数据库和模拟接口执行。 |
| SOURCE 与模拟路径 | PASS | SourceHistoryDirtyRevisionJointTest、SourceHistoryProjectorJointTest、SourceHistoryWindowTest、SimulationControlPathTest、SimulationControlTenantTest，共 34 项；另有 MySQL writer 内保护重查。 |
| 报价、控盘任务、换汇与资金权威 | PASS | LatestSourceLookupTest、ExecutionQuoteTest、FundingQuoteAuthorityJointTest、FundingConversionsJointTest、ControlFlowMarketIntegrationTest。当前可执行报价及控盘启动依据未被历史补采改变。 |
| 首页小图、行情/深度推送、缓存与采集 | PASS | HomeSparklineCacheTest、MarketKlinePushTest、MarketPushTest、DepthPushTest、KlineSchedulingTest、KlineRetentionRegressionTest 的两个定向方法、SourceCandlesBatchRegressionTest 的四个非 performance 方法。 |
| 权限及租户隔离 | PASS | AdminPermissionIntegrationTest 12 项、TenantMarketIsolationTest 3 项，以及后台提交权限、跨租户 writer 拒绝和浏览器迟到响应。 |
| 手工订单及资金写入 | PASS | ManualOrderChartDatabaseTest、SimpleManualOrderGenerationDatabaseTest、ManualOrderCalculationTest、ManualOrderPricesLoadingTest、ManualOrderChartPageTest、MoneyLockOrderRegressionTest、MoneyWriteCheckpointRegressionTest。 |
| PC/移动端单测与构建 | PASS | PC 83 项、移动端 11 项，失败、取消、跳过、todo 均为 0；两个真实 build 成功。PC 组也运行两个图表组件的工具回归。 |
| PC/移动端真实浏览器 | PASS | 26 项：最新中间插入、自动 pending 重读、历史翻页补洞、HTTP/WS 交错、来源/品种/周期/租户/模拟/发布/恢复/撤销变化。两个时间戳视口偏移均 0 px；旧历史、画线、指标、缩放保持，排序去重，加载次数有界。另实跑受保护稀疏历史、封存、终止重试与继续更早分页回归。 |
| 后台单测、构建与浏览器 | PASS | 4 项单测、真实 build；1280/390px 各有/无提交权限共 4 组安全回填验收；既有“定位最新”、选区保持、框选、5m 展开、丢失回执查询和撤销共 2 组回归。 |

[PC/移动端浏览器结果](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/client-backfill-results.json)、[后台安全回填结果](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/admin-backfill-results.json)、[后台原功能结果](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/browser-results.json)。[PC 截图](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/pc-middle-backfill.png)、[移动端截图](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/mobile-middle-backfill.png)、[后台 390px 截图](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/admin-gap-390-safe.png)。其余截图与受保护历史日志均保留在同一目录。

## FAIL、修复与影响

功能、模块回归和构建没有 FAIL，没有删除断言、跳过失败测试或降低标准。构建输出的 bundle 大小警告保留在日志中；本轮没有据此得出性能结论。

额外清理核验脚本第一次退出码为 1：Docker 29.6.1 返回 `error: no such object`，新写核验脚本按大写 `No such object` 匹配，发生文本大小写误判。修正 `C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/verify_evidence.py`，改为大小写规范化匹配；仍要求非零退出码及明确“不存在”错误。重跑退出码 0，容器与匿名卷不存在的检查通过。[首次失败日志](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/verify-evidence-attempt1.log)、[首次退出码](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/verify-evidence-attempt1.command.json)、[最终命令及退出码](C:/workspace/fx/new/kline-backfill-qa-recheck-20261009-163241-bce252/verify-evidence.command.json) 全部保留。

**业务源码、既有测试和依赖文件新增修复：无。** 核验开始时的 2629 个非忽略文件 SHA-256 全部未变，HEAD 未变。仓库仅新增本验收摘要；额外核验脚本与证据存于 QA 目录。覆盖范围内没有发现本次改动造成的其他模块回归。

## NOT_RUN 与结论边界

以下均为 **NOT_RUN**，不计为通过，也不以本轮 0 跳过代替这些范围的验证：

- 性能/压力测试，`SourceCandlesBatchRegressionTest.mysqlStatementFailureAfterFirstChunkIsAtomic` 的 performance 专用 MySQL 方法。
- 长时间运行、synthetic retention soak、持续行情/控盘负载及长期缓存增长。
- 旧 S2 固定身份 MySQL/Redis 全套；本轮使用新建、独立的 owned MySQL 验证写入边界。
- 真实供应商历史完整性、当前业务数据库缺口、最初漏采根因、真实订单结算及生产端到端验收。
- 图表浏览器具体周期竞态使用 5m 与切换到 1m；15m/30m/1h 的独立处理由后端测试验证，未逐一重演全部浏览器竞态矩阵。

浏览器使用真实 Vue、klinecharts、Chrome，HTTP/WS 为隔离模拟接口；数据库事务使用真实新建 MySQL。两个层面的通过不能视为生产端到端通过，也不能宣称所有功能绝对无影响。

## 资源清理与执行边界

[MySQL 清理记录](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/mysql-cleanup.json)、[服务清理记录](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/local-cleanup.json)、[额外核验](C:/workspace/fx/new/kline-local-qa-20261009-163255-51bd5c/independent-verification.json) 证明本轮容器、1 个匿名卷、3 个 Vite 服务及临时 ASCII junction 已清理，MySQL 与 Vite 端口关闭。容器删除前校验完整 ID 和 owner label；匿名卷名称由运行时保存的自有容器 Mounts 核对。

没有访问或写入业务数据库、重启业务容器、执行真实业务恢复/撤销、reset、clean、stash、提交、推送或部署。没有业务数据需要回滚；如需删除本次交付，仅按新增报告与 QA 目录逐项处理，不回退原有源码修改。
