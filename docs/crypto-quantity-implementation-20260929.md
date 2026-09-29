# 加密数量单位实施记录（未发布）

日期：2026-09-29。项目：C:/workspace/fx/705。

## 结论与发布闸门

**已实现主体代码、数据库迁移脚本、专项测试和隔离恢复演练；未完成整体交付。未部署，未迁移业务库，未使用真实客户账户下单。**

禁止把本报告当作上线证明。以下闸门没有通过：

1. 冻结后端源码的完整回归失败：1576 tests，1 failure、10 errors、15 skipped。具体为 MinimalFixRegressionTest 的 timedControlRequiresItsMenuAndValidatesInputs 期待200实际403；DemoSecurityTest 四项因 jwt.expireSeconds 配置缺失无法启动；AdminPermissionIntegrationTest 六项角色名称唯一键冲突。没有删除失败断言、增加跳过或修改业务权限绕过这些问题。
2. 指定集中入口在当前共享树执行：1323 tests，3 failures、0 errors、0 skipped；失败为 MinimalFixRegressionTest 的 timedControlRequiresItsMenuAndValidatesInputs、depositOrdersApiScopeLegacyEntryAndSafeDto、detailReviewCannotBypassOldReviewPermission，涉及403权限回归。与冻结快照结果不同，源码确实正在被其他工作修改。
3. 测试后再次比对发现22个已有后端文件已漂移，含 ContractOrderService 和 ContractOrder：其他工作新增 TrialFunds、trial_reserved，改变冻结/结算/强平资金路径。**本次已通过的430项加密专项只证明冻结快照，不证明这些后续资金改动。**没有覆盖或撤回他人改动。漂移清单：[rollback/crypto-quantity-20260929-022117/backend-snapshot-drift.json](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/backend-snapshot-drift.json)。
4. 运行库5个外汇品种仍为 lotSize=1000、feeMultiplier=30；源码外汇规则要求100000。先前外汇迁移没有落地。未借本任务顺便迁移外汇；不能把当前全部源码直接发布后声称外汇保持正常。
5. 全工作区 git diff --check 非零，存在其他权限界面改动的尾随空白，详见日志。未全局格式化覆盖其他工作。

因此依用户“测试通过后发布”要求停止业务写入和发布，独立编码、构建、隔离测试继续完成。下一步须冻结共享源码，处理上述集成失败及外汇发布依赖，重新测试，再安排本地部署和专用测试账户真实API/浏览器验收。

## 实现内容

- 品种新增 nullable quantityUnitType/minOrderQuantity/quantityStep/minOrderNotional/specVersion；原生币/股乘数必须1；未知新增加密品种没有完整规格时不能启用。没有在交易计算中硬编码BTC/ETH/SOL。
- 新请求携带版本与单位；服务端读取新鲜品种并加共享锁，在资金变动前校验协议、数量、网格、最低USD名义金额。后台规格修改独占锁；交易规格改变递增独立版本，行情更新不递增。
- 新订单保存单位/基础资产/版本/规则快照；历史NULL按原协议处理；旧quantity、lotSize、fee、margin、profit不重写。挂单按自身快照验证，不读新品种门槛。
- 继续 quantity × lotSize、现有固定往返费和原估值结算。迁移按照实际 oldFee/oldLot 精确除法，无法精确表示则拒绝；未引入0.05%费率。
- PC/移动交易页、旧PC交易页、滑块、订单列表、分享图、后台规格与手工单接入单位；新数量请求传十进制字符串。小额费显示“<0.01”，不是免费。
- 后端金额使用BigDecimal；客户端网格/预算、乘积、保证金、权益求和与后台手工预估改用十进制整数计算，最终展示沿用number接口。现有报价/账户JSON及强平预估仍有number边界，并非全站数字传输协议全部升级；服务端始终最终复核，不声称任意16位金额在客户端无损往返。
- 增加并发规格与开仓冲突、并发平仓只结算一次、旧快照、挂单取消/触发、手工订单隔离MySQL测试。

## 实际环境与品种前后对照

已核实 Compose项目 exchange-705；实际覆盖组合 compose.yaml + scripts/market/ws-live.override.yaml。业务容器 exchange-705-mysql-1、库1090、MySQL5.7.44；PC17050、后台17051、移动17052。镜像标识：[rollback/crypto-quantity-20260929-022117/image-identities.txt](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/image-identities.txt)；服务状态：[rollback/crypto-quantity-20260929-022117/final-services.json](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/final-services.json)。

完整盘点14个启用品种：JPY=X、AMD、AAPL、AMZN、GOOG、BABA、ADBE、EURUSD=X、HKD=X、CAD=X、GBPUSD=X、BTCUSDT、ETHUSDT、SOLUSDT。未发现额外_PERP；源身份保持不变。

| ID / 品种 | 实际业务库原值（目前仍是这些值） | 隔离库已验证的目标值 |
|---|---|---|
|72 BTCUSDT / binance / Crypto / BTC / USDT|旧手协议；lot1000；fee30|BASE_ASSET/BTC；lot1；fee0.03；min0.001；step0.001；minNotional10；version1|
|73 ETHUSDT / binance / Crypto / ETH / USDT|旧手协议；lot1000；fee30|BASE_ASSET/ETH；lot1；fee0.03；min0.001；step0.001；minNotional10；version1|
|74 SOLUSDT / binance / Crypto / SOL / USDT|旧手协议；lot1000；fee30|BASE_ASSET/SOL；lot1；fee0.03；min0.01；step0.01；minNotional10；version1|
|5外汇 / 6股票|lot1000；fee30|本迁移不修改；外汇实际与标准手目标不一致，单独作为发布阻塞|

固定0.03 USD/币往返很低，原因仅是30/1000等价转换，不是交易所标准费率。旧0.01手=10BTC、费0.3；新10BTC仍费0.3。新0.01BTC在80000、100倍时保证金8、费0.0003、总占用8.0003。

业务库最终只读复核：仍无spec_version列，订单4，账户51，全部品种原乘数费率未切换。[rollback/crypto-quantity-20260929-022117/final-business-readonly.log](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/final-business-readonly.log)。

## 真实测试命令与结果

以下均为本轮执行，不引用旧报告冒充本轮。工作目录 C:/workspace/fx/705。

| 命令 | 结果 / 日志 |
|---|---|
|pwsh -NoProfile -File scripts/crypto-quantity/Test-CryptoQuantity.ps1 -CheckOnly|exit0，仅工具/文件预检；final-precheck.log|
|mvn.cmd -B -f reports/crypto-quantity-20260929-final/source/exchange-backend/pom.xml -Dtest=CryptoQuantity*Test,DepositOrderAccessTest test|exit0；434 tests=430加密+4权限fixture；0 failure/error/skip；final-focused.log|
|mvn.cmd -B -f reports/crypto-quantity-20260929-final/source/exchange-backend/pom.xml test|exit1；1576/1 failure/10 errors/15 skipped；final-full.log|
|pwsh -NoProfile -File scripts/crypto-quantity/Test-CryptoQuantity.ps1|exit1；1323/3 failures/0 errors/0 skipped；final-runner.log；入口停止后，独立继续下列前端测试与构建|
|pwsh -NoProfile -File reports/crypto-quantity-20260929-final/source/scripts/manual-order/Test-MySql.ps1|exit0；真实隔离MySQL5.7，41 tests=36 MySQL+5计算，0 failure/error/skip；final-mysql.log；临时容器已移除|
|pwsh -NoProfile -File scripts/crypto-quantity/Test-Migration.ps1 -BackupFile C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/database.sql|exit0；恢复/迁移/幂等/失败原子性/历史不变/回滚保护通过；migration.log|
|node scripts/crypto-quantity/test.mjs|exit0；2214 checks；scripts_crypto-quantity_test.mjs.log|
|node scripts/fx-standard/test.mjs|exit0；11569 checks；目标外汇夹具，不代表运行库已达目标；scripts_fx-standard_test.mjs.log|
|node scripts/all-instruments/audit.mjs|exit0；14品种2016 assertions，本轮实际重跑；scripts_all-instruments_audit.mjs.log|
|node exchange-pc/tests/contract.test.mjs|最终exit0；final-contract-regression.log；十进制权益断言修正说明见下|
|node exchange-admin/tests/manualOrderEstimate.test.mjs、manualOrderGeneration.test.mjs、manualOrderGenerationMatrix.test.mjs、manualOrderLifecycle.test.mjs|全部exit0；逐条见final-js-results.json|
|node exchange-frontend/tests/assetEquityHistory.test.mjs、manualOrderBadge.test.mjs|全部exit0；逐条见final-js-results.json|
|npm.cmd --prefix exchange-admin run build|最终exit0；final-admin-label-build.log|
|npm.cmd --prefix exchange-pc run build|最终exit0；final-pc-label-build.log|
|npm.cmd --prefix exchange-frontend run build|最终重试exit0；final-mobile-retry-build.log；此前共享App.vue半写入导致TS6133，未改该文件，待其完成后重试|
|node scripts/crypto-quantity/browser.cjs|exit0；7组PC/移动BTC/ETH/SOL与后台规格保存刷新夹具；final-browser.log；不是部署验收|
|git diff --check|非零；final-diff-check.log；他人权限界面尾随空白保留|

日志根：[rollback/crypto-quantity-20260929-022117](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117)。15个条件跳过属于完整套件原有环境条件；没有跳过本次新增专项。MySQL测试单独在隔离库实际执行，不依靠跳过得到绿色。

断言改动说明：原contract.test.mjs将旧仓权益1+10-11+0.3期望写成IEEE浮点残差0.30000000000000004。改为严格等于数学值0.3，保留原测试并新增0.1+0.2严格等于0.3；没有放宽容差或删除失败断言。DepositOrderAccessTest仅适配并发权限重构新增构造参数，使用真实AdminPermissionService及原权限fixture，原四个断言全部保留并通过。

## 备份与恢复证据

备份：[rollback/crypto-quantity-20260929-022117/database.sql](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/database.sql)，本机受限目录，不入Git、不上传。
SHA256：1795F189429E60B6BC28B040E914815D3336A5C0F66AF9366255BEC38DDB1B9C。

一致性dump包含routines/events/triggers；已在独立tmpfs MySQL5.7中真实恢复。恢复14品种、4订单、51账户；按主键比较旧订单与账户所有原列，迁移后完全一致；非加密配置完全一致。schema、migrate各执行两次验证幂等；不可精确转换费率拒绝且无部分配置更新；模拟新规格订单后rollback拒绝。证据：[reports/crypto-quantity-migration/result.json](C:/workspace/fx/705/reports/crypto-quantity-migration/result.json)。隔离容器按名称+标签核验后清理；业务容器临时dump仅删除记录的确切路径，宿主备份保留。

源码原件、初始未提交状态/diff保留在备份目录；当前文件哈希：[rollback/crypto-quantity-20260929-022117/source-manifest.json](C:/workspace/fx/705/rollback/crypto-quantity-20260929-022117/source-manifest.json)。冻结后端副本不等于最新待发布源码，必须重测，不能用旧快照报告授权部署。

## 浏览器截图（仅夹具）

测试全部API由脚本拦截，账号为fixture@example.invalid类测试标记；不会产生业务交易。图表没有行情夹具，所以可见加载失败/空图；不作为行情验收。

- [reports/crypto-quantity-20260929-final/screenshots/pc-BTC.png](C:/workspace/fx/705/reports/crypto-quantity-20260929-final/screenshots/pc-BTC.png)
- [reports/crypto-quantity-20260929-final/screenshots/mobile-BTC.png](C:/workspace/fx/705/reports/crypto-quantity-20260929-final/screenshots/mobile-BTC.png)
- [reports/crypto-quantity-20260929-final/screenshots/admin-spec.png](C:/workspace/fx/705/reports/crypto-quantity-20260929-final/screenshots/admin-spec.png)
- 同目录含ETH/SOL及result.json，共7截图。

**未完成已部署环境的专用账户登录、真实接口下单/平仓/撤单/余额核对和三端浏览器联调。**发布闸门失败，不能用拦截API截图替代。

## 迁移与回滚使用边界

脚本位于 scripts/crypto-quantity。schema.sql只加nullable列，MySQL5.7 DDL隐式提交，不能靠事务回滚删列。preview.sql只读白名单；migrate.sql须显式设置@cq_apply=1和@cq_database=真实已确认库名，锁定ID及完整源身份复核，并写持久trading_symbol_cq_backup_20260929备份。只改变新品种配置，不修改旧订单和客户资金。

尚无新规格订单时，在停止相关新开仓后使用受保护rollback.sql恢复原品种配置，保留新增列及兼容程序；验证历史/余额后才可回退已验证旧镜像。

存在任何新规格订单时rollback.sql主动拒绝。禁止切回无法识别新单位的旧程序，禁止用旧全库dump覆盖后续业务。停新开仓、保留快照兼容程序、前向修复。代码备份仅供逐段合并恢复，不得整文件覆盖当前他人工作。

目前业务环境未改，无需业务数据回滚。不要直接运行compose build/up：需要先合并最新资金与权限改动、处理外汇依赖、重新备份并恢复验证、全套测试绿色；随后使用已核实compose覆盖组合发布兼容版本，再短维护窗口迁移。

## 修改文件清单

下列为本任务触及文件，不代表这些文件中的全部未提交内容均属本任务：

- [.gitignore](C:/workspace/fx/705/.gitignore)
- [exchange-admin/src/components/ManualContractOrder.vue](C:/workspace/fx/705/exchange-admin/src/components/ManualContractOrder.vue)
- [exchange-admin/src/utils/manualOrderEstimate.ts](C:/workspace/fx/705/exchange-admin/src/utils/manualOrderEstimate.ts)
- [exchange-admin/src/utils/orderShare.ts](C:/workspace/fx/705/exchange-admin/src/utils/orderShare.ts)
- [exchange-admin/src/views/Orders.vue](C:/workspace/fx/705/exchange-admin/src/views/Orders.vue)
- [exchange-admin/src/views/Symbols.vue](C:/workspace/fx/705/exchange-admin/src/views/Symbols.vue)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminSymbolService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminSymbolService.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/entity/ContractOrder.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/entity/ContractOrder.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/entity/TradingSymbol.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/entity/TradingSymbol.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderCalculation.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderCalculation.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderGenerator.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderGenerator.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/QuantityRules.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/QuantityRules.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/trade/dto/CreateContractOrderRequest.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/dto/CreateContractOrderRequest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityCompatibilityTest.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityCompatibilityTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityLifecycleTest.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityLifecycleTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityPersistenceTest.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityPersistenceTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityRulesTest.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityRulesTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderMySqlIT.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderMySqlIT.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/user/DepositOrderAccessTest.java](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/user/DepositOrderAccessTest.java)
- [exchange-backend/src/test/resources/manual-order-schema.sql](C:/workspace/fx/705/exchange-backend/src/test/resources/manual-order-schema.sql)
- [exchange-frontend/src/components/TradeOrders.vue](C:/workspace/fx/705/exchange-frontend/src/components/TradeOrders.vue)
- [exchange-frontend/src/utils/contract.ts](C:/workspace/fx/705/exchange-frontend/src/utils/contract.ts)
- [exchange-frontend/src/utils/orderShare.ts](C:/workspace/fx/705/exchange-frontend/src/utils/orderShare.ts)
- [exchange-frontend/src/utils/useOrderSizing.ts](C:/workspace/fx/705/exchange-frontend/src/utils/useOrderSizing.ts)
- [exchange-frontend/src/views/Orders.vue](C:/workspace/fx/705/exchange-frontend/src/views/Orders.vue)
- [exchange-frontend/src/views/Trade.vue](C:/workspace/fx/705/exchange-frontend/src/views/Trade.vue)
- [exchange-pc/src/utils/contract.ts](C:/workspace/fx/705/exchange-pc/src/utils/contract.ts)
- [exchange-pc/src/utils/orderShare.ts](C:/workspace/fx/705/exchange-pc/src/utils/orderShare.ts)
- [exchange-pc/src/utils/useOrderSizing.ts](C:/workspace/fx/705/exchange-pc/src/utils/useOrderSizing.ts)
- [exchange-pc/src/views/DesktopTrade.vue](C:/workspace/fx/705/exchange-pc/src/views/DesktopTrade.vue)
- [exchange-pc/src/views/Orders.vue](C:/workspace/fx/705/exchange-pc/src/views/Orders.vue)
- [exchange-pc/src/views/Trade.vue](C:/workspace/fx/705/exchange-pc/src/views/Trade.vue)
- [exchange-pc/tests/contract.test.mjs](C:/workspace/fx/705/exchange-pc/tests/contract.test.mjs)
- [scripts/crypto-quantity/Test-CryptoQuantity.ps1](C:/workspace/fx/705/scripts/crypto-quantity/Test-CryptoQuantity.ps1)
- [scripts/crypto-quantity/Test-Migration.ps1](C:/workspace/fx/705/scripts/crypto-quantity/Test-Migration.ps1)
- [scripts/crypto-quantity/browser.cjs](C:/workspace/fx/705/scripts/crypto-quantity/browser.cjs)
- [scripts/crypto-quantity/migrate.sql](C:/workspace/fx/705/scripts/crypto-quantity/migrate.sql)
- [scripts/crypto-quantity/preview.sql](C:/workspace/fx/705/scripts/crypto-quantity/preview.sql)
- [scripts/crypto-quantity/rollback.sql](C:/workspace/fx/705/scripts/crypto-quantity/rollback.sql)
- [scripts/crypto-quantity/schema.sql](C:/workspace/fx/705/scripts/crypto-quantity/schema.sql)
- [scripts/crypto-quantity/test.mjs](C:/workspace/fx/705/scripts/crypto-quantity/test.mjs)

另新增本报告。临时生成器已移至受限备份目录，不作为运行产品交付。

## 2026-09-29 04:14 独立补测后续（仍未发布）

依据 `docs/session-monitor/tests/round1-20260929-result.md` 对 D01、D02 和手工单动态语义断言作最小修复。未修改 ContractOrderService、KycIdentityService、TrialFunds、模拟账户与公共资金路径；未部署、写业务库、提交或推送。原报告早期失败数字保留为当时实测，不以本次定向通过覆盖。

- D01：`AdminOrderController.convertContractOrderToMap` 追加 `quantityUnitType`、`quantityAsset`、`specVersion` 原订单快照字段；原数量、软删除字段及权限注解保留。后台订单表和后台分享模块均消费这条列表响应快照；详情也使用所选行对象。新增 `AdminOrderQuantityProjectionTest` 覆盖币、股、旧NULL、软删除及原数量不变。
- D02：生命周期夹具改为真实 `KycIdentityService` 搭配合成KYC记录。APPROVED非模拟用户可以触发挂单；资金不足维持待成交，原保证金/手续费断言保留。PENDING/REJECTED非模拟用户触发被拒，订单与资金不变。未修改生产鉴权。
- D03手工单：保留原目标净收益、仓位比例、数量输入断言，改为断言模板动态ARIA和BTC/股/手三种计算标签；不再要求所有品种固定 `aria-label="手数"`。其他D03市场模块缺陷不属本次改动。

独立验证目录：`C:/workspace/fx/new/crypto-quantity-followup-20260929-041118`。复制当时后端源码与pom，验证目标 `target` 只在该目录；另复制第一轮严格HTTP夹具 `Round1IntegrationTest`，未作为生产源码部署。真实命令与结果：

1. `mvn.cmd -B -f C:/workspace/fx/new/crypto-quantity-followup-20260929-041118/exchange-backend/pom.xml -Dtest=AdminOrderQuantityProjectionTest,CryptoQuantityLifecycleTest,Round1IntegrationTest#nativeQuantityProjection -DargLine=-Xmx768m test`：6/6，0失败/错误/跳过，exit0；`focused.log`。其中严格HTTP原生币投影返回BASE_ASSET/BTC，不再退化成手。
2. `mvn.cmd -B -f C:/workspace/fx/new/crypto-quantity-followup-20260929-041118/exchange-backend/pom.xml -Dtest=Round1IntegrationTest -DargLine=-Xmx768m test`：2/2，0失败/错误/跳过，exit0；`http-regression.log`。合成管理员真实本地HTTP登录、权限、软删除/恢复以及订单和余额不变的现有断言保留。
3. 追加股与软删除断言后，`mvn.cmd -B -f C:/workspace/fx/new/crypto-quantity-followup-20260929-041118/exchange-backend/pom.xml -Dtest=AdminOrderQuantityProjectionTest,CryptoQuantityLifecycleTest -DargLine=-Xmx768m test`：5/5，0失败/错误/跳过，exit0；`final-focused.log`。
4. `node exchange-admin/tests/manualOrderLifecycle.test.mjs`：exit0；`manual-order-lifecycle.log`。

修改前文件逐份备份在 `C:/workspace/fx/705/rollback/crypto-quantity-followup-20260929-041118`，`before-hashes.json`；修改后SHA-256在 `C:/workspace/fx/new/crypto-quantity-followup-20260929-041118/final-file-hashes.json`。三个后端相关文件与独立验证副本逐个哈希一致；前端测试文件在共享目录执行。测试不证明其他并行修改之后的最新共享树全量绿色；发布闸门与原报告保持未通过。