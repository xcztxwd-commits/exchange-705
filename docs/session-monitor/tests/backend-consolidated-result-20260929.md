# 后端合并独立复验报告

日期：2026-09-29，Asia/Singapore；执行约 10:32–10:47。

## 结论

**平仓三行修复独立复验通过；本轮指定后端交叉回归局部通过，整项目发布闸门仍未通过。**

- 新建文件复制快照，重新编译并执行 MySQL 5.7 / Redis 7 平仓并发 11 项，全部通过，exit 0。没有沿用修复负责人的成功日志。
- 允许范围的广覆盖套件：2134 项，2110 通过、24 条条件跳过，0 失败、0 错误，exit 0。显式排除了模拟/客服/活动专项和贷款规则专项，**不是默认全仓完整验收**。
- MySQL 充值 22 项全部通过；合成旧表连续迁移两次，金额、状态、余额和结构计数不变。
- MySQL 手工单 41 项及计算 5 项通过；随后独立 MySQL 普通交易/权限/KYC/充值矩阵 47 项通过。运行器 exit 0。
- 新增真实 TCP HTTP/JWT/MySQL 充值流程 1 项通过；同流程 H2 通过；新增真实汇率有效期判断与充值事务组合 1 项通过。
- 各组有交集，不能加总成独立覆盖量。未发现本轮可归因为生产代码的新缺陷，**没有新增生产修复补丁**。只交独立测试补丁；共享业务源码未修改。
- 历史 round2 模拟账户 HTTP503、跨账户隔离、客服执行拦截仍是未验证门槛；未在本会话恢复或重试。贷款规则冲突仍待业务决定。

## 版本与隔离

证据根目录 R：`C:/workspace/fx/new/backend-consolidated-20260929-103229`。
构建目录：`C:/workspace/fx/new/backend-consolidated-20260929-103229/source/exchange-backend`。

已读取调度计划及所列诊断、修复、权限、MT02、第一轮、充值、KYC、数量报告。磁盘项目及上级未找到 AGENTS.md，使用本会话提供的指令；已读取 ponytail/full 与 caveman/full。

- 最初复制 415 个 src/pom 白名单文件，每文件复制后校验 SHA-256；补入两个必需的内置图标资源 ZIP。没有复制 .env、密码文件、业务 dump、uploads、旧 target、dist 或 Git 目录。首次漏掉图标资源造成夹具启动错误，详见失败记录。
- application.yml 仅在快照内去除默认数据库凭据、禁用默认 DB 地址、随机化 JWT，并将默认外部 HTTP/WS/邮件目标改到 loopback。保留原配置的哈希而不保留凭据副本。它是隔离配置差异，不是产品修复，不在交付补丁中。
- `R/source-before.json` SHA-256：`5E42AB3861A9E3D3791E3090AA643CDEF023A04D959C903C7814518BE9F983AE`。
- 最终源清单 `R/tested-source.json` SHA-256：`4C8DCF21D9A17BDA299186DC7D3D50B4404FA4ACC2D21F0BD39305C52584CE6B`。覆盖实际快照 src/pom，包括新夹具；快照变更见 `R/snapshot-changes.json`，新增夹具见 `R/new-fixtures.json`。
- 原415文件与共享源最终对比漂移 0，见 `R/shared-source-drift.json`。只代表本次核对，不冻结其他会话后续修改。
- 本机 Maven 离线 `-o -B`，JDK 21；Maven 堆256MB，测试JVM768MB。没有下载依赖、部署、浏览器、Git索引/提交/推送操作。
- 专用临时 MySQL/Redis 容器，合成数据、tmpfs、随机127.0.0.1端口；只清理本次名称且标签核对的容器。未触碰现有业务容器或数据库。最终自有标签残留为空。
- 真实网络部分仅本机 TCP HTTP、MySQL、Redis；测试中的行情、邮件等按夹具替换。本轮未证明生产供应商可用性，也没有真实资金业务。

## 平仓修复：独立检查与实测

`ContractOrderService.java` SHA-256：`284016B3B9A4B1F4E469597917F272001ED369585D9AC22F387AB58973DC3B29`，与修复交付完全一致。

核对 `closeOrder`、`adminCloseOrder` 两个直接入口和后台自动平仓调用链。`settleOrder` 保留先取新鲜行情/换算、再 `TrialFunds.lock`（user、assets），之后 `EntityManager.refresh(order, PESSIMISTIC_WRITE)`，再次要求 OPEN，才结算。没有删除 @Version，没有在回滚事务内重试，没有提前锁订单改变现有锁序。此为代码检查及有限交错实测，不是所有死锁排列证明。

命令：`pwsh -NoProfile -File R/run-close.ps1 -Report R`。实际 Maven 选择 `ContractCloseConcurrencyTest`。
日志：`C:/workspace/fx/new/backend-consolidated-20260929-103229/close-race.log`。

| 用例/步骤 | 预期与实测 |
|---|---|
| quoteRefreshRace，3次 | 用户读OPEN后暂停；后台真实显示价格刷新并推进版本；恢复用户请求，HTTP200。不是把原HTTP400缺陷改为可接受。 |
| forceCheckRefreshRace | 强平检查只刷新显示价格后，用户HTTP200。 |
| mixedFundsDisplayRefresh | 混合资金经历同交错，正常一次结算，现金/试用本金/利润及账本断言通过。 |
| adminCloseAfterDisplayRefresh | 管理员服务入口经历价格刷新仍结算成功。该入口本项不是管理员TCP HTTP。 |
| duplicateManualClose | 双HTTP竞争同单，一200一400；只结算一次，非吞错成功。 |
| autoCloseRace | 自动止盈抢先结算，用户重复请求被拒；资金/活动账本只有一次效果。 |
| duplicateWithUnrelatedReserve | 另有OPEN订单冻结资金；目标只结算一次，最终available=946/frozen=102，另一单不变。 |
| wrongOwnerDoesNotSettle | 非订单所有者拒绝，订单/资金不变。 |
| missingFreshQuoteDoesNotSettle | 无新鲜报价拒绝，订单/资金不变。 |

11个调用全部通过，0跳过。全套中的9个“条件跳过方法”与这里11个实际调用来自同一类（含3次重复方法），不可相加。

## 权限、软删除、数量和手工单映射

最新快照执行结果见 `R/allowed-suite-summary.json`、`R/allowed-suite-cases.json`；后者逐条保留JUnit方法、结果、耗时与跳过信息。断言源码与XML在快照/证据目录，不仅是计数。

| 范围 | 本轮步骤与不变量 | 实测/边界 |
|---|---|---|
| C1/C2授权输入/停用清理 | 原字符串、小数、缺字段、混合有效无效输入；旧授权精确集合不变；显式空清空；停用旧授权可移除但不得重新授予；两张授权表写入后故障回滚 | PermissionGrantRepairTest 14、独立5、权限基线7及登录审计11通过。H2；未穷举MySQL授权竞态。 |
| 软删除/恢复/权限 | TCP管理员登录、无菜单拒绝、读/删/恢复分开授予、原token撤权立即拒绝；OPEN/PENDING保护；删除/旧路径/恢复；active/deleted筛选；资金与数量快照不变 | Round1IntegrationTest 2、OrderSoftDeleteTest 5通过。后台单位字段严格断言通过；未浏览器验证隐藏。 |
| 数量生命周期 | BASE_ASSET规则、步长/名义额/精度、历史兼容、真实KYC通过和拒绝、挂单不足资金不成交、并发平仓只结算一次 | CryptoQuantityRulesTest 423、Compatibility 2、Lifecycle 4、Persistence 2通过；不是所有单位×资金来源×界面的笛卡尔积。 |
| MT02及生成 | 原独立ManualHashRecheckTest原样重跑；现有生成、容差、分钟、杠杆、费用与截图规则矩阵 | 广覆盖套件全部所选Manual*Test通过。原MT02严格反例1/1通过，不改断言。 |
| 手工单MySQL | 实际生产服务/事务及专用schema；创建、幂等、失败回滚、历史与钱包标志、限制检查 | ManualOrderMySqlIT 41/41，Calculation 5/5；行情/规格repository为夹具替代。无浏览器，不能替代MT02浏览器全集。 |
| 普通交易、KYC与权限交叉 | 最新MinimalFixRegressionTest，保持余额/冻结/订单/拒绝断言；H2后再在独立MySQL运行 | 47/47；KYC及非模拟路径局部有效，不声称round2隔离已验收。 |

手工单日志：`C:/workspace/fx/new/backend-consolidated-20260929-103229/manual-mysql.log`。
执行：`pwsh -NoProfile -File R/source/scripts/manual-order/Test-MySql.ps1 -NormalRules`。

## 充值覆盖

1. `DepositOrderMySqlIT`在全新MySQL5.7执行22项：重复审批、审批/拒绝竞争、同键幂等/参数冲突、并发充值/划转、账户新建、订单/账户/凭据/flush/beforeCommit故障回滚、旧单不重复入账、币种与账户、DTO、CSV、代理范围及统计。每项方法见 `R/deposit-cases.json`。22/22，0跳过，exit0。
2. 合成旧表迁移执行两次；旧金额/状态/可用/冻结逐值相同，结构菜单计数稳定为32/18/4/3/1。未使用业务备份。
3. 新增 `ConsolidatedDepositHttpTest#independentDepositHttp`：真实TCP管理员登录；无权限写入403且资金不变；手工100入账、同键重试200但仅1订单/凭据；改额409；提交服务先生成PENDING但不入账；无审核权403、审批200、重复审批/终态拒绝409；最后可用135、冻结3。H2通过，随后同一断言继承到MySQL真实HTTP再通过。
4. HTTP过期报价路径：行情依赖明确抛expired异常，HTTP503，订单、账本、资金不变。这里的503是**预期且注入的充值汇率拒绝**，与历史模拟账户入口503完全不同。
5. 新增 `ConsolidatedDepositExpiryTest`：真实ForexQuoteMarketService与FiatCurrencyService、真实充值事务，缓存传入实际过期expiresAt，503且零写入；改为有效期限入账一次；重新过期，同键成功单重试只返回旧单，不重新定价或入账。1/1通过。Redis存储用mock返回合成缓存，未连接外部报价供应商。

绝对证据路径：
- `C:/workspace/fx/new/backend-consolidated-20260929-103229/source/reports/deposit-orders/mysql-5e227fe7398a45989ee3a69d3baf55ee/maven.log`
- 同目录 `migration-result.txt`、`TEST-com.gtcfesk.exchange.user.DepositOrderMySqlIT.xml`。
- `C:/workspace/fx/new/backend-consolidated-20260929-103229/deposit-http-mysql.log`（1/1，exit0）。
- `C:/workspace/fx/new/backend-consolidated-20260929-103229/evidence/allowed-suite/TEST-com.gtcfesk.exchange.user.ConsolidatedDepositExpiryTest.xml`。

仍未覆盖：浏览器真实充值端到端、用户提交HTTP入口在新MySQL HTTP夹具中的完整链路（本夹具提交走真实service）、充值与所有真实交易入口的压力组合、真实供应商断源+Redis过期+HTTP充值整条链路。

## 广覆盖套件与跳过分类

命令：`pwsh -NoProfile -File R/run-allowed.ps1 -Report R`；实际 `mvn.cmd -o -B -f R/source/exchange-backend/pom.xml -Dtest=<allowed-test-classes及独立方法> -DargLine=-Xmx768m test`。
日志：`C:/workspace/fx/new/backend-consolidated-20260929-103229/allowed-suite.log`。
结果2134项，2110通过、24跳过，exit0。

24跳过逐项类别：
- ContractCloseConcurrencyTest 9方法：专用环境开关未开；本轮另在专用MySQL/Redis实际11调用通过。
- BalancedControlPlanMySqlTest 1：未提供V3_MYSQL_URL，未验证。
- AssetEquityMySqlTest 8：未提供专用EQUITY_TEST_JDBC，未验证该专项。
- MarketIsolationTest 2：未启用整市场隔离环境；未恢复历史catalog/全链路任务。
- CatalogLiveTest 1、ExchangeLiveTest 1：生产网络禁止，未执行。
- RealRegistrationFlowTest 1、RegistrationSecurityTest中Redis专项1：未提供security.test.redis.port，未验证真实Redis验证码全集。

另外显式未选的类见 `R/excluded-test-classes.txt`：activity 2、demo 3、simulation 4、support 3、IdentityLoanFlowTest 1。这些不在2134中，不称“跳过通过”。本轮不创建新会话或换入口重试历史round2。

## 失败记录、修正与补丁

保留全部中间失败，未弱化生产断言：
- 首次平仓run：漏复制内置图标ZIP导致MarketIconController初始化NPE，9个错误、exit1；补齐原样资源，再重编译11调用通过。`R/evidence/close-initial-missing-resource.log`。
- 第一轮交叉套件1254项中1失败：本轮新HTTP夹具漏传必填remark，期待200实际400。完善合法请求和USD汇率夹具；过期汇率依据生产服务明确503契约写严格断言。原失败日志 `R/cross-regression.log` 与原夹具 `R/evidence/deposit-http-initial.java` 保留。后续同HTTP全部断言H2/MySQL均通过。
- 首次广覆盖2133项，94错误、24跳过、exit1：运行器用SetEnvironmentVariable(null)造成该环境中空字符串变量仍被Java识别为已设置，误触专用DB配置/安全拒绝。改为PowerShell Remove-Item Env:精确移除变量，不改生产守卫或测试断言。保留 `R/evidence/allowed-suite-initial-empty-env.log`、原脚本、XML；修正后广覆盖成功，新增expiry单项使总数2134。
- `R/exits.txt`完整保留每轮退出码。本轮没有出现新的平台拦截。

交付：`C:/workspace/fx/new/backend-consolidated-20260929-103229/independent-tests.patch`，仅六个新增测试文件（含历史独立夹具依赖），**未应用共享树**。修改/新增指纹见 `R/delivery-hashes.json`、`R/tested-source.json`。隔离脚本在R，不要把隔离application.yml合入产品。

文件所有权：共享生产文件仍归原负责人；此线只拥有R内快照、运行器、新测试及本报告。没有需要回滚的生产写入。调度审核补丁后应逐文件合并，并在最终整合树重跑；不能整目录覆盖。

## 贷款冲突：仅证据，不决定规则

- `C:/workspace/fx/705/exchange-frontend/src/store/locale.ts:16885` 的 repaymentMethodDescription 明确写日语“返済金を口座残高から直接差し引くことはできません”。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/user/LoanService.java:183–244` earlyRepayment 读取FUND账户；229–235检查可用余额后以 available.subtract(actualRepaymentAmount) 扣款。
- 文案与实现冲突存在。未修改合同、债务义务或还款逻辑，未把现有通用权限回归解释为贷款产品规则获批。需业务明确正式规则后另行验收。

## 未关闭总闸门

历史模拟账户503与跨账户隔离、客服完整验收、活动预算并发及真实资金闭环、贷款规则、完整权限MySQL竞态、所有订单状态×软删除竞争、各类资金/数量全部组合、性能/长期稳定性、三端浏览器、最终合并源码全量与迁移回退仍未全部闭环。本报告不授权提交、推送、部署或业务库变更。