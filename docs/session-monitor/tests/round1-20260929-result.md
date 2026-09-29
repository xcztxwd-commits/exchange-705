# 第一轮独立补充验收结果

日期：2026-09-29（Asia/Singapore），快照约 03:28，执行及核对约 03:29–03:37。

## 结论

**未通过发布闸门。新增确认一个数量单位接口缺陷；历史前端 6 个失败入口、日语检查、catalogTradingChain 400 均在本次快照复现。** 本轮不修共享源码、不放宽断言，不把隔离夹具通过当作当前项目全部通过。

- 新增真实 TCP HTTP / Spring Boot / 隔离 H2 验收通过：使用合成管理员真实登录，逐步授予订单菜单、delete_order、restore_order，验证直接请求拒绝、软删除/恢复、旧路径、活动订单保护、撤权立即生效及资金/数量快照不变。
- 本次原有后端定向入口：489 项，488 通过、1 失败、0 错误、0 跳过。失败定位为新增 canUseTradingFunds 调用后旧 Mockito 夹具没有配套更新，独立诊断夹具保留全部原断言后通过；原失败证据仍保留，不将原入口改记通过。
- 新增严格接口断言失败：后台订单列表不返回 quantityUnitType / quantityAsset，新币数量被后台模板显示为“手”。数据库中的快照仍存在，属于响应映射缺陷。
- 合成历史数据隔离 MySQL 迁移通过：幂等、原订单/余额不变、不可精确转换拒绝、无部分配置切换、新规格订单回滚保护。
- 复制前后 1139 个文件哈希完全一致；验收结束共享源中 13 个已有文件发生变化，新增文件 0。独立快照的原始 1139 个文件未修改。**通过结果仅适用于该快照。**

## 版本与安全边界

- 工作区只读来源：C:\workspace\fx\705。
- HEAD：a3818cb44d854dff76e1a66770d9772d8c0f5d5b。HEAD 不代表未提交工作树。
- 独立目录：C:\workspace\fx\new\round1-20260929-032814。
- 测试源码：C:\workspace\fx\new\round1-20260929-032814\source。复制 backend/admin/pc/frontend/scripts，排除 node_modules、target、dist、.git；没有复制业务 dump、.env、客户数据或 uploads。
- 初始源码清单及逐文件 SHA-256：C:\workspace\fx\new\round1-20260929-032814\source-before.json。该清单自身 SHA-256：2AF6B3CACBEA5AE4105B4CCC4AD9B4A48C0E574E723D56B11A263368E6F4596C。
- 三方复制核对：C:\workspace\fx\new\round1-20260929-032814\copy-verification.json，复制时漂移 0。
- 原始快照结束核对：C:\workspace\fx\new\round1-20260929-032814\snapshot-after.json，原始文件变化 0。
- 共享源结束清单、漂移与新增：C:\workspace\fx\new\round1-20260929-032814\source-after.json、C:\workspace\fx\new\round1-20260929-032814\source-drift.json、C:\workspace\fx\new\round1-20260929-032814\new-files-after.json。
- 新增测试夹具指纹：C:\workspace\fx\new\round1-20260929-032814\fixture-manifest.json。只新增 Round1IntegrationTest 与 Round1LifecycleDiagnosticTest，没有改原有公共测试或快照生产代码。
- 环境：Windows PowerShell，Temurin Java 21.0.11，Maven 3.9.9，Node 24.18.0，Docker Server 29.6.1。Java 8 全量不重跑，复用历史证据。
- 前端只执行脚本，不构建；node_modules 通过独立目录 Junction 只读复用现有安装。依赖目录未逐文件冻结，属于复现限制；package manifests 已纳入源码指纹。未安装依赖。
- HTTP 补充使用随机本地端口与 H2 内存库，真实 AuthService、JWT filter、权限拦截器、Controller、Repository；行情、RedisMarketService、邮件、注册安全、默认管理员初始化被 mock。订单/权限/数据库没有 mock。既不是浏览器 API 拦截，也不是生产部署验收。
- 市场历史失败复核使用新建 MySQL 5.7 / Redis 7 容器、随机 127.0.0.1 端口、合成账户和本地行情 HTTP 服务；没有接业务库。迁移使用合成 SQL，不使用此前业务备份。
- Maven 重任务串行执行，堆 256 MB、测试 JVM 768 MB；市场临时容器限制 2 CPU / 2 GB。未部署、重启现有服务、写业务库、操作 Git 索引、调用支付/邮件/外部下单。测试容器已清理，两个 remaining-*-containers.txt 为空。

## 历史证据复用

已阅读四份指定文档，并核对以下历史日志尾部，不重新跑全量：

- C:\workspace\fx\705\reports\full-test-20260929-024522\java8-verified.log：1576 项、0 failure/error、15 skipped。
- C:\workspace\fx\705\reports\full-test-20260929-024522\frontend-frozen-final.log：79/79。
- C:\workspace\fx\705\reports\full-test-20260929-024522\current-workspace-final.log：保存了后续工作树失败。
- 其他三端构建、MySQL 业务和缓存专项结果仅引用原 REPORT.md 的历史说明，不声明本轮执行或对最新树生效。各集合交叉，不汇总成独立覆盖数。

## 每个编号的结论

以下全部继承上述源码指纹和环境；“未验证（部分通过）”表示该编号要求未全部完成，而不是失败项被跳过。

| 编号 | 结论 | 本轮步骤、实测与剩余边界 | 证据与退出码 |
|---|---|---|---|
| S01 | 未验证（后端核心通过） | TCP 登录后删除 CLOSED 新规格合约、CLOSED/CANCELLED 期货，行及主键保留，订单状态、quantity、lot_size、fee、spec_version、quantity_unit_type、余额123.0003/冻结7.0003前后相同；用户 repository 隐藏，后台响应 deleted=true。原5项测试补合约CANCELLED。没有启动PC/移动真实页面，置灰效果与两端用户HTTP未本轮验证。 | C:\workspace\fx\new\round1-20260929-032814\backend-http.log exit0；C:\workspace\fx\new\round1-20260929-032814\backend-focused.log 内 OrderSoftDeleteTest 5/5，整体exit1因C02 |
| S02 | 未验证（部分通过） | HTTP验证全部/active/deleted+用户+状态+size1总数；重复删除、旧abnormal-delete、重复恢复不改变资金；原持久化测试补分页。未穷举代理归属×多页×全部状态笛卡尔积。 | C:\workspace\fx\new\round1-20260929-032814\backend-http.log exit0；C:\workspace\fx\new\round1-20260929-032814\backend-focused.log |
| S03 | 未验证（部分通过） | 真实HTTP无delete/restore权限403；OPEN/PENDING删除400；旧路径软删除。原测试代理拒绝、陈旧rowVersion更新0行通过。尚未进行真正同时删除/恢复压力、其他用户详情完整HTTP矩阵；陈旧版本测试不冒充并发调度测试。 | C:\workspace\fx\new\round1-20260929-032814\backend-http.log exit0；C:\workspace\fx\new\round1-20260929-032814\backend-focused.log |
| P01 | 通过（订单代表入口） | 同一合成角色真实登录，从无菜单403、只读查询200但删除403、仅delete授权可删而restore403，到单独授restore成功。请求附带X-Is-Super-Admin:true仍不能越权；拒绝后订单未写入。原权限7项覆盖其他操作及无菜单入口。不是逐个业务按钮全量验收。 | C:\workspace\fx\new\round1-20260929-032814\backend-http.log exit0；C:\workspace\fx\new\round1-20260929-032814\backend-focused.log 中AdminPermissionIntegrationTest 7/7 |
| P02 | 未验证（服务端撤权通过） | 保持原登录token，移除delete授权后下一DELETE立刻403，行仍未删除。客户端伪造header不能提权。导航刷新、权限服务故障时浏览器关闭访问仅有历史mock证据，本轮未重新验证真实页面。 | C:\workspace\fx\new\round1-20260929-032814\backend-http.log exit0；历史权限报告 |
| P03 | 未验证（原定向场景通过） | 原7项真实MVC/JWT/H2测试通过：委派超授、超级管理员入口、代理范围、混合配置批次无部分写入、目录数量幂等。该套token直接生成，非全部用例真实登录；未补完整“已有授权及禁用状态跨多次初始化”数据库矩阵。 | C:\workspace\fx\new\round1-20260929-032814\backend-focused.log，原权限7/7，整体exit1与此无关 |
| C01 | 通过（规则/服务层） | CryptoQuantityRulesTest 423/423，Compatibility 2/2：版本、单位、门槛、步长、资金前拒绝及通用规则；脚本2214 checks通过。实际HTTP开仓的全部边界未穷举，不能称全部端到端通过。 | C:\workspace\fx\new\round1-20260929-032814\backend-focused.log；C:\workspace\fx\new\round1-20260929-032814\scripts_crypto-quantity_test.mjs.log exit0 |
| C02 | 失败（原夹具失配已定位） | Persistence 2/2含真实H2并发平仓只结算一次与规格锁；Lifecycle 2/3，pendingUsesSnapshotAndUnfundedSellRemainsPending期待1实际0。新增诊断复制只补mock canUseTradingFunds=true，原资金/快照断言全部保留，该单项通过。共享原测试未改；活动资金交叉仍未验收。 | C:\workspace\fx\new\round1-20260929-032814\backend-focused.log exit1；C:\workspace\fx\new\round1-20260929-032814\diagnostics.log exit1，其中诊断单项通过、另一接口单项失败 |
| C03 | 失败 | 新增后台原生币数量投影断言确认quantityUnitType/quantityAsset缺失；后台会回退显示“手”。前端小额手续费/预算的2214 checks通过，不代表实际三端页面和活动资金交叉通过；活动资金及模拟账户仍开发，交叉项未验证。 | C:\workspace\fx\new\round1-20260929-032814\diagnostics.log exit1；C:\workspace\fx\new\round1-20260929-032814\scripts_crypto-quantity_test.mjs.log exit0 |
| C04 | 通过（合成隔离迁移） | 合成4品种/2旧订单/1账户；schema和迁移各执行两次；旧订单/余额、非加密配置逐值不变；lot3/fee1不可精确转换拒绝且无部分切换；恢复可回滚；模拟新规格订单后回滚被拒。外汇业务环境依赖仍沿用原报告阻断，本轮未查改运行库。 | C:\workspace\fx\new\round1-20260929-032814\migration.log，脚本成功、执行exit0；C:\workspace\fx\new\round1-20260929-032814\migration\result.json |
| R01 | 失败 | 原6个前端失败入口全部复现；日语首个缺失仍复现；当前快照catalogTradingChain真实MySQL/Redis再现400。模拟账户相关13文件随后漂移，最新共享树这些文案是否已修未复验。 | C:\workspace\fx\new\round1-20260929-032814\frontend-results.json 各exit1；C:\workspace\fx\new\round1-20260929-032814\market-final.log Maven exit1 |

## 缺陷与最小复现

### D01 — 新增确认，后台币数量单位丢失（建议高优先级）

1. 合成订单保存 quantity=0.001、quantityUnitType=BASE_ASSET、quantityAsset=BTC、specVersion=1。
2. 有 orders 菜单的管理员请求 POST /api/admin/orders/contract/query。
3. 实测HTTP200，仅返回quantity，两个单位字段缺失；严格断言期待BASE_ASSET/BTC，实际均为空。
4. C:\workspace\fx\705\exchange-admin\src\views\Orders.vue:400 根据这两个字段决定币/股/手；缺字段必走“手”，把0.001 BTC标成0.001手。

责任文件：C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\admin\AdminOrderController.java:134–157（convertContractOrderToMap）。建议数量负责人和软删除负责人合并响应映射，保留删除字段并补数量快照字段；同时核对后台分享/详情消费者。不修改数据库数值来掩盖显示问题。

可运行复现：在C:\workspace\fx\new\round1-20260929-032814\source执行 `mvn.cmd -B -f exchange-backend/pom.xml "-Dtest=Round1IntegrationTest#nativeQuantityProjection" "-DargLine=-Xmx768m" test`。该夹具只在独立快照存在。

### D02 — 加密挂单原测试夹具过时，原入口仍红

责任位置：C:\workspace\fx\705\exchange-backend\src\test\java\com\gtcfesk\exchange\trade\CryptoQuantityLifecycleTest.java:29–32。

原夹具仅stub isApproved=true；当前 C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\trade\ContractOrderService.java:184 改调用canUseTradingFunds。Mockito默认false，不执行真实委托，提前返回0。真实 KycIdentityService.canUseTradingFunds 是 simulationExempt || isApproved。

诊断仅新建 Round1LifecycleDiagnosticTest，增加对应stub，不更改期待成交1、margin8.01、fee0.0003、available0等断言；通过。这支持“夹具失配”，不等于新活动资金逻辑已验证。交数量与模拟账户负责人协调适配，同时补拒绝与非模拟批准用户路径。

### D03 — 原前端6失败与日语

- manualOrderLifecycle：C:\workspace\fx\705\exchange-admin\tests\manualOrderLifecycle.test.mjs:85–86 固定匹配aria-label="手数"，组件 C:\workspace\fx\705\exchange-admin\src\components\ManualContractOrder.vue:162 已改动态“数量（单位）”。属断言语义过时；应测试具体币/股/手输出，不删断言。
- PC chartData、controlRecovery、marketSimulation、marketTransport，移动marketTransport：ERR_MODULE_NOT_FOUND，Cannot find package '@/utils'。责任入口为PC/移动 src\utils\marketWebSocket.ts:1新引入 `@/utils/accountMode`，原生Node没有Vite别名解析。属于测试加载兼容问题，不能据此断言浏览器运行坏了；协调模拟账户负责人统一解析边界。
- 日语：scripts/check-i18n.cjs:84首次停止，缺 `Demo service unavailable. Account unchanged.`，来自移动AccountModeSwitch.vue。此处之后未检查；不可宣称只缺这一条。对应uiMessages/AccountModeSwitch测试后均漂移，旧失败只对应快照。

### D04 — catalogTradingChain 400仍在，新增规则与夹具前提冲突

原场景导入BTCUSDT后只设置杠杆、lotSize、feeMultiplier，没有配完整新数量规格，也未合法启用。C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\admin\AdminSymbolService.java:175–179明确让新加密品种默认停用；:204–206要求完整规格后才可启用。C:\workspace\fx\705\exchange-backend\src\test\java\com\gtcfesk\exchange\market\CatalogTradingScenario.java:60–69仍按旧前提直接开仓。

本轮真实隔离MySQL/Redis、当前快照重现HTTP400“交易品种已停用”。静态原因明确为新规格启用前置条件未满足；不应删除服务端停用保护。建议原场景先通过管理API配置合规规格，再合法启用、请求携带版本/单位，并重算保持严格等价的预期金额。后续交易链还未走到，不能宣称只修此处即可全绿。

## 命令、日志与退出码

执行器路径：Java C:\Environment\Java\jdk-21.0.11；Maven C:\Environment\Maven\3.9.9\bin\mvn.cmd；Node C:\Environment\Node.js\24.18.0\node.exe；Docker C:\Environment\Docker\4.81.0\resources\bin\docker.exe。Maven使用JAVA_HOME/PATH及MAVEN_OPTS=-Xmx256m。H2入口禁用EXCHANGE_STREAM_ENABLED、YAHOO_STREAM_MODE=http_only，Redis/mail端口置1，邮件host127.0.0.1；核心测试annotation覆盖数据源，默认业务application.yml不得直接独立启动。

工作目录C:\workspace\fx\new\round1-20260929-032814\source：

1. `mvn.cmd -B -f exchange-backend/pom.xml "-Dtest=AdminPermissionIntegrationTest,OrderSoftDeleteTest,CryptoQuantity*Test,MinimalFixRegressionTest" "-DargLine=-Xmx768m" test`，exit1。C:\workspace\fx\new\round1-20260929-032814\backend-focused.log；C:\workspace\fx\new\round1-20260929-032814\backend-command.txt。MinimalFixRegressionTest 47/47，之前403回归本次通过。
2. `mvn.cmd -B -f exchange-backend/pom.xml "-Dtest=Round1IntegrationTest" "-DargLine=-Xmx768m -Dfile.encoding=UTF-8" test`，当时仅round1Cross，1/1，exit0。C:\workspace\fx\new\round1-20260929-032814\backend-http.log。该时点夹具另存C:\workspace\fx\new\round1-20260929-032814\Round1IntegrationTest.http-passed.java。后来追加独立数量投影用例，不能直接重跑整个当前类还期待全绿。
3. `mvn.cmd -B -f exchange-backend/pom.xml "-Dtest=Round1IntegrationTest#nativeQuantityProjection,Round1LifecycleDiagnosticTest#pendingUsesSnapshotAndUnfundedSellRemainsPending" "-DargLine=-Xmx768m" test`，2项1通过1失败，exit1。C:\workspace\fx\new\round1-20260929-032814\diagnostics.log。
4. 每个 `node <入口>` 及真实退出码、工作目录、绝对日志见C:\workspace\fx\new\round1-20260929-032814\frontend-results.json，共10条；包含原6失败、i18n失败、orderSoftDelete通过、permissionCoverage 3/3、crypto数量2214 checks通过。
5. `scripts/crypto-quantity/Test-Migration.ps1 -BackupFile C:\workspace\fx\new\round1-20260929-032814\synthetic-history.sql -ReportDirectory C:\workspace\fx\new\round1-20260929-032814\migration`，PowerShell直接调用；成功、执行exit0。C:\workspace\fx\new\round1-20260929-032814\migration-command.txt保留等价独立pwsh命令，C:\workspace\fx\new\round1-20260929-032814\migration.log、C:\workspace\fx\new\round1-20260929-032814\migration\result.json。没有读取业务备份。

工作目录C:\workspace\fx\705：

6. `& C:\workspace\fx\new\round1-20260929-032814\run-market.ps1 -Report C:\workspace\fx\new\round1-20260929-032814`。临时脚本改用当前快照、只选 `mvn.cmd -B -f C:\workspace\fx\new\round1-20260929-032814/source/exchange-backend/pom.xml "-Dtest=MarketIsolationTest#catalogTradingChain" "-DargLine=-Xmx768m" test`，其余隔离环境创建同历史入口。Maven exit1记录C:\workspace\fx\new\round1-20260929-032814\exits.txt，1项error。C:\workspace\fx\new\round1-20260929-032814\market-final.log。
7. 此次runner测试结束后Copy-Item仍引用旧cache-snapshot路径，证据归档步骤报错，runner exit1；**不是测试未执行**。随后仅从当前快照target复制已有MarketIsolationTest XML至C:\workspace\fx\new\round1-20260929-032814\market-mysql-xml，未重跑或修改断言。finally已清理临时容器。保留C:\workspace\fx\new\round1-20260929-032814\market-runner.log记录该夹具归档失误。

个别Java中文stdout受Windows编码影响；结构化XML、HTTP状态、英文断言和SQL数值可复核，不以乱码解释业务语义。完整测试输出含合成夹具数据，不含真实客户记录；报告不复制token或密码。

## 漂移与调度建议

结束检查发现13个已有文件变化，均在PC/移动模拟账户相关UI、uiMessages、accountMode、OrderShareModal、DemoTrading、Deposit及移动fullSimulation.browser.cjs，详见C:\workspace\fx\new\round1-20260929-032814\source-drift.json。1139个快照原件不变、新增源文件0；本轮后端责任文件相对所测快照未发现漂移。不是整个项目冻结证明，检查之后仍可能继续修改。

先由调度协调D01、D02、D03、D04给相应负责人；不在本测试会话改共享源码。需另补用户两端真实HTTP/页面、删除恢复真实并发、完整代理组合、权限故障浏览器、活动资金和模拟账户交叉路径。开发停止后统一保存新快照再集成回归。本轮不授权提交或部署。