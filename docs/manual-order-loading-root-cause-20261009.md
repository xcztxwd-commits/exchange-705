# 模拟订单生成持续提示后台补齐：根因验证

验证日期：2026-10-09，Asia/Singapore。源码版本：`abb29ed379014f936d60912b1bfadafc69d68f63`。

## 已确认的根因

生成读事务遇到缺失分钟行情或历史汇率时，返回业务码 425 的异常使事务回滚；同一事务里登记的 `afterCommit` 补齐通知不执行。请求已把状态标记为 pending，但新任务没有真正进入后台队列。反复点击生成会重复这个过程。

完整调用关系：

1. `ManualOrderService.generateSimple` 调用带 Spring 事务代理的 `ManualOrderPrices.selectedCandles`，自动搜索则调用 `simpleCandles`。
2. 两个方法均使用只读、REPEATABLE_READ 事务。
3. `ForexQuoteMarketService.cachedKline` 在此事务内将新加载工作登记到 `afterCommit`，并用 `requestedAfterCommit=true` 提前报告 pending。
4. `ManualOrderPrices.exact` 或 `generationCandles` 因数据缺失抛出 `BusinessException(425, ...)`。`BusinessException` 继承 `RuntimeException`，事务注解没有配置不回滚规则。
5. Spring 回滚事务，补齐回调被丢弃。新任务队列仍为空。

`repairHistoricalKline` 的分钟行情补齐通知同样依赖提交回调，真实 H2 历史读取也复现了任务丢失。

该生成接口的 HTTP 状态由异常处理器设为 400；425 是响应体里的业务码。前端根据业务码显示截图中的通用提示，同时覆盖服务端原本包含行情代码和 UTC 分钟的具体信息。

## 修复前实际测试结果

新增 `exchange-backend/src/test/java/com/gtcfesk/exchange/market/ManualOrderHistoryLoadingTransactionTest.java`，使用真实 Spring 事务代理、H2 事务管理器和实际行情队列。汇率测试固定已有主行情；另一个用真实 H2 历史读取验证主行情缺失。没有连接外部行情源或写入业务资金。

| 情景 | 结果 |
| --- | --- |
| 固定分钟，缺 JPYUSD=X，连续生成三次 | 每次业务码 425，队列均为 0 |
| 自动搜索，缺历史汇率 | 业务码 425，队列为 0 |
| 相同读取绕过事务 | 业务码 425，队列为 1 |
| 在读事务内捕获加载状态，使读事务正常提交 | 业务码 425，队列为 1 |
| 成功读取图表并预热汇率，再生成 | 图表 available，生成 425，已入队任务仍为 1 |
| 汇率缓存已经齐全 | 两个所选分钟正常返回 |
| 真实 H2 主行情缺失，使用固定 USD 换算 | 业务码 425，队列为 0，行情源调用为 0 |

上述 7 项加现有 `SimpleManualOrderPricesTest` 8 项、`ManualOrderPricesLoadingTest` 2 项，共 17 项通过，失败和跳过均为 0。原有汇率加载测试直接构造对象、模拟行情服务，未经过真实事务和队列，无法发现这个提交回调缺陷。

复现命令：

```powershell
mvn -B '-Dtest=ManualOrderHistoryLoadingTransactionTest,SimpleManualOrderPricesTest,ManualOrderPricesLoadingTest' test
```

本机 protoc 无法处理当前中文用户目录，因此将同一 `pom.xml` 和 `src` 复制到 ASCII 路径执行。最终日志：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/manual-loading-test.log`。

## 版本与发布记录

- `71ba9236`（2026-10-05）将只读事务中的缓存加载通知延迟到 `afterCommit`。
- `b71f3542`（2026-10-06）为简版生成行情读取增加只读事务，两项规则组合后形成上述问题。
- 2026-10-09 发布记录的版本与当前源码一致；留档的正式、模拟 `*.after.jar` 中，`ManualOrderPrices.selectedCandles` 都具有该事务注解，没有不回滚规则。两份 class SHA-256 均为 `03866dec198368872bd129b6f871c5397a438205b759120515d283ee310f9aeb`。
- 发布留档中的 JAR 注解检查证据：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/deployed-class-evidence.json`。

## 结论边界与修复方向

代码缺陷已稳定复现。截图那次请求究竟缺 `JPY=X` 主行情还是 `JPYUSD=X` 汇率，仍未取得原始响应证明。远程 SSH 连接失败，无法读取该次请求的线上日志；本地另一套后端的 Forex 网络失败日志不作为该截图的证据。检查发布留档也不等同于读取当前运行进程的内存状态。

图表读取成功时可能已提前入队，因此并非每一个 425 都表示任务丢失。新加载任务首次由失败的生成读事务登记时，会稳定触发已验证缺陷。

## 本地修复与回归结果

在 `ManualOrderPrices` 内增加仅用于业务码 425 的 `HistoryLoadingException`，继承 `BusinessException`，保持响应业务码和提示兼容。`simpleCandles`、`selectedCandles`、`rangeQuote` 三个只读事务仅为此异常配置 `noRollbackFor`，使加载通知执行提交回调。`rangeQuote` 必须独立配置，因为其内部调用 `selectedCandles` 不会再次经过 Spring 代理。

没有扩大 `BusinessException` 的全局提交规则。无效 OHLC 返回 400 和意外运行异常仍回滚；订单创建和资金写事务使用原有处理规则。

诊断测试已改为防止复发的回归断言。修复前四项断言失败：固定分钟、自动搜索、最终预览均期望队列 1 但实际为 0；真实 H2 两个缺失分钟期望补齐队列 2 但实际为 0。修复后四项均通过，重复加载不会重复入队。

最终运行七个测试类，共 39 项通过，失败、错误和跳过均为 0：`ManualOrderHistoryLoadingTransactionTest`（10）、`SimpleManualOrderPricesTest`（8）、`ManualOrderPricesLoadingTest`（2）、`SimpleManualOrderGenerationDatabaseTest`（1）、`ManualOrderChartDatabaseTest`（1）、`SimpleManualOrderAdminTest`（2）、`SimpleManualOrderGeneratorTest`（15）。其中 H2 生成预览用例验证账户与已有行情记录保持不变。

```powershell
mvn -B '-Dtest=ManualOrderHistoryLoadingTransactionTest,SimpleManualOrderPricesTest,ManualOrderPricesLoadingTest,SimpleManualOrderGenerationDatabaseTest,ManualOrderChartDatabaseTest,SimpleManualOrderAdminTest,SimpleManualOrderGeneratorTest' test
```

修复前断言日志：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/regression-before-fix.log`。修复后日志：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/regression-after-fix.log`。本地源码修复已完成，尚未发布线上后端。

## 进一步评估：只读事务是否可以去除

根据用户要求先完成事务分析，不在这一轮扩大生产改动。结论是保留一次生成所需的数据库快照事务，缩小职责和范围；不能以删除外层事务解决任务可靠性。

单条独立查询、纯内存缓存读取、纯价格计算通常没有必要额外开启应用事务。当前行情读取则由多条 SQL 组成，包括归档响应及封存策略、主行情、控制分钟、任务发布范围、恢复覆盖和投影进度。`REPEATABLE_READ` 为同一次数据库读取提供一致快照，避免在并发补齐、恢复或控制发布时拼出不同版本。`readOnly` 本身不是快照机制，也不等于所有写入必然被框架拒绝；它是传给事务管理器的只读提示。

移除 `ManualOrderPrices` 外层事务后，`ControlledKlineMerger.merge` 仍会通过 `ControlHistoryStore.readSnapshot` 逐次开启事务，开平仓两次读取不再共享一个快照。因此移除注解不等于整个链路没有事务，也没有依据断言一定更快。另外 `projectedSourceKline` 检查线程上是否已有只读事务；直接删除外层注解会使相应 SOURCE 投影分支无法命中，转向其他读取路径。启用该功能时必须同时处理此语义变化。

数据库事务只覆盖同一事务连接上的数据库读取，不能冻结 `Group.klines` 内存汇率缓存。当前 `windowEnd` 也会逐次读取系统时间。因此完整订单快照还需要固定请求截止时刻，并对读取的价格、汇率及相关版本保留同一份数据；不能声称 RR 事务已经提供数据库与内存之间的原子快照。

### 三项实际对照实验

在独立 ASCII 构建目录新增分析专用 `ManualOrderReadTransactionAnalysisTest.java`，不加入生产回归代码。实验使用真实 Spring 事务、真实 H2 历史表和实际行情合并读取，无外部行情或业务资金操作。

1. 初始两个分钟的开盘价均为 90。读取开仓分钟后，另一个线程在单次写事务内把两个分钟都更新为 190 并提交。绕过外层读事务，结果为开仓 90、平仓 190；这是将两个数据库版本拼在一起。
2. 相同并发更新，保留外层只读 RR 事务，结果为开仓 90、平仓 90；读完后确认数据库两行均已提交为 190，写入没有被此次快照读取阻止。
3. 在上一版本地修复基础上，模拟其他请求于提交回调执行前占满队列的 32 个位置。生成请求仍返回业务码 425，但 `JPYUSD=X` 任务没有被接收。`noRollbackFor` 修复覆盖了回滚丢任务，未覆盖先报告 pending、后实际入队的竞争条件。

三项实验均通过。日志：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/transaction-analysis.log`。H2 实验验证具体读取链路和竞争条件；没有把它称为实际线上 MySQL 并发复现，也未进行性能基准测试。

### 完整修复应满足的边界

- 在短只读 RR 事务内读取并复制数据库事实；加载不足作为读取结果返回，不在这一步依赖异常来决定后台任务是否存在。
- 事务结束后，按现有租户、源代码、分钟窗口和版本规则去重并接收补齐任务。以实际入队或运行状态作为响应依据，明确区分队列满、失败、休市/无覆盖和已排队。
- 所需数据齐全后，在事务外用固定的价格、汇率和截止时间计算预览；读取事务不等待外部行情请求。最终创建继续保留原有权限、参数和资金版本校验。
- 对外只有真实 queued/running 才报告补齐中。有限重试后必须返回失败或无覆盖原因，不能无限保留泛化提示；前端对当前输入对应的请求检查状态，完成后继续预览。
- 若要求进程重启后已接收任务仍能恢复，需要持久化任务意图或启动补偿。目前内存队列不能提供这个保证。

上述边界可复用现有行情读取、缺口检查和有界队列，不需要增加另一套价格规则。上一版异常白名单可以保留为已验证的局部修复，但不作为整个加载流程已彻底可靠的结论。

参考：[Spring 5.3.31 Transactional](https://docs.spring.io/spring-framework/docs/5.3.31/javadoc-api/org/springframework/transaction/annotation/Transactional.html)、[MySQL 一致性非锁定读取](https://dev.mysql.com/doc/refman/9.7/en/innodb-consistent-read.html)、[MySQL 只读事务优化](https://dev.mysql.com/doc/refman/9.7/en/innodb-performance-ro-txn.html)。原 MySQL 5.7 在线手册链接现重定向到新版本；这里引用通用 MVCC 及只读语义，不将其当作线上版本验证。

## 最终最小修复：当前源码

按用户最终要求限制实现范围。当前方案替换了前面第一阶段的异常白名单，不新增任务系统、依赖或数据库表。生产代码仅调整三个文件。

- `ControlHistoryStore` 复用已有 `consumerReads`，为订单数据读取提供独立的只读 RR 快照；快照返回前正常提交，调用方的业务判断和资金写事务与它分开。
- `ForexQuoteMarketService` 提供该读取边界，并在原有补齐提交回调完成后，以实际排队或运行状态校正 `pending`。队列一开始已满、或提交前被其他请求占满时，都会报告 `queue_full`，不再把尚未被接收的工作标成正在补齐。
- `ManualOrderPrices` 的固定分钟和自动搜索先取得正常完成的读取结果，再做 OHLC、汇率及数据就绪校验。最终预览复用该边界，原三个覆盖整个流程的事务注解已去除。仅在没有可用生成候选时报告队列满，已有完整候选仍正常使用。

行情窗口、来源与保护规则、数量和收益算法、权限、幂等创建、资金处理及队列容量保持原有实现。前端代码不变，继续保留已有输入；实际已排队时使用原业务码 425，排队失败时展示明确的繁忙错误。

最终后端回归：17 个测试类、135 项通过，失败、错误和跳过均为 0。覆盖加载入队与去重、两类队列满、主行情补齐、缺汇率、最终预览、只读快照、调用方写事务回滚，以及现有图表、队列调度、休市、历史保护和行情发布规则。新增的事务测试共 15 项。

并发用例验证读取结果仍为 `90 / 90`，同时另一个写事务已将两分钟都提交为 190；调用方写事务用例验证其测试资金记录仍回滚为 0，而独立行情补齐任务保留。读取失败的意外异常仍回滚读取阶段的通知。

后台四个现有测试脚本均通过：`simpleManualOrder.test.mjs`、`simpleManualOrderQuantity.test.mjs`、`manualOrderChart.test.mjs`、`manualOrderGeneration.test.mjs`。复用了本机已有 Node 依赖，没有安装新包。

最终证据：`C:/workspace/fx/new/manual-loading-diagnosis-20261009-781a/minimal-fix-final.log`、`minimal-fix-admin-tests.log`。另一次扩大检查中的两项 `MarketIsolationTest` 因未提供专用 MySQL/Redis 集成环境跳过，未计入最终 135 项通过数；`SimpleManualOrderMySqlIT` 的模拟行情适配已更新并编译，未执行其真实 MySQL 用例。

代码和测试已保存在当前工作树，未发布线上服务。外部数据源缺失或失败仍会按现有规则拒绝生成；本次修复针对补齐任务被异常丢弃，以及任务没有接收却提示加载中的问题。
