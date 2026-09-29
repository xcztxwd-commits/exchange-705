# 一键生成 ±5% 独立补充验收结果

日期：2026-09-29，Asia/Singapore；快照04:26:58，测试约04:27–04:32。

## 结论

**本轮验收未全部通过：确认手动生成器没有执行品种杠杆上下限校验。** 目标相对容差本身、合法原生币五目标计算、现有只预览/事务专项与第一轮数量修复定向回归通过；不能用这些通过项掩盖限制绕过。

新增独立MySQL服务层测试实测：

- 合成品种 maxLeverage=5，目标杠杆10，generate返回10.00，没有拒绝。
- 目标杠杆0.5，generate返回0.50，没有拒绝低于1的值。
- 同夹具合法原生币五目标通过：独立公式核对保证金、手续费、净收益；所有目标在±5%内、原请求不变、版本/单位错误拒绝、订单/钱包不变。

以上是实际ManualOrderService、真实隔离MySQL、合成行情/品种Repository的服务调用；不是生产HTTP、浏览器真实下单，也未调用真实订单。失败测试未修改断言换绿。

## 版本、安全与证据位置

独立证据目录：C:/workspace/fx/new/manual-tolerance-20260929-042658。

- HEAD见C:/workspace/fx/new/manual-tolerance-20260929-042658\head.txt，a3818cb44d854dff76e1a66770d9772d8c0f5d5b；未提交状态见C:/workspace/fx/new/manual-tolerance-20260929-042658\git-status-before.txt。
- 复制backend/admin/scripts共664文件，排除node_modules/target/dist/.git；没有复制.env、业务dump、上传文件。初始逐文件SHA-256见C:/workspace/fx/new/manual-tolerance-20260929-042658\source-before.json，清单SHA-256为6677C16095C96E42FA3C45333C87B655487AE8745CD64B85598F4ABFAEFC1234。
- C:/workspace/fx/new/manual-tolerance-20260929-042658\copy-verification.json：复制前源/复制后源/副本三者一致，漂移0。
- C:/workspace/fx/new/manual-tolerance-20260929-042658\end-verification.json、C:/workspace/fx/new/manual-tolerance-20260929-042658\source-drift.json：结束共享源2文件变化，为SupportService.java、SupportServiceTest.java；未读写修复其中内容。本轮四个主要责任文件和数量修复文件未发现漂移。新增共享源文件0，C:/workspace/fx/new/manual-tolerance-20260929-042658\new-files-after.json。此结论只覆盖检查时点。
- 快照生产代码及原有Java/前端公共测试均未修改。唯一原文件本地调整为快照scripts/manual-order/Test-MySql.ps1：增加独立测试选择和临时容器资源限制；原件C:/workspace/fx/new/manual-tolerance-20260929-042658\Test-MySql.original.ps1，变化C:/workspace/fx/new/manual-tolerance-20260929-042658\snapshot-local-changes.json。新增QA Java、浏览器脚本、Vite配置和独立runner指纹见C:/workspace/fx/new/manual-tolerance-20260929-042658\fixture-hashes.json。
- Java21.0.11 / Maven3.9.9 / Node24.18.0 / MySQL5.7；Windows。Maven堆256MB，测试JVM768MB；重型Maven串行。MySQL新建随机名容器、随机127.0.0.1端口、tmpfs、2CPU/2GB、合成数据，测试后清理，remaining-test-containers.txt记录0。
- 未修改共享源码、公共测试、Git索引；未提交、部署、重启业务服务、修改业务数据库、真实订单、行情或资金。
- 前端node_modules仅Junction复用既有依赖，未安装；Vite缓存强制在独立快照.qa-vite，API代理指向127.0.0.1:1而非业务8080，浏览器另拦截全部API。独立Vite端口18298，按记录PID及命令行核验后停止，C:/workspace/fx/new/manual-tolerance-20260929-042658\cleanup.txt。

## 读取与复用

已读计划、docs/manual-order-generation.md、源开发会话01a0e9a6-e02c-7041-837b-efcdf9c7032f最终交付、crypto-quantity报告04:14补测段。核对ManualOrderGenerator、ManualOrderService、ManualContractOrder.vue、manualOrderGeneration.ts实际实现：杠杆候选/反算档位、最小数量/名义金额候选、最终preview复查、原目标与实际值分离。

第一轮全仓测试及04:14严格HTTP数量投影仅作为历史证据复用，不重复全仓回归、不将其视为本次生成器的API证明。本轮重新运行相关数量投影、生命周期和手工单标签测试。

## 六项逐项结果

全部继承上述版本、环境及指纹；日志路径为本次实际证据，不混合历史数字。

### 1. 五目标、±5%边界、负值及零目标：通过（算法/服务范围）

- 定向8类684项，失败/错误/跳过0；其中当前ManualOrderToleranceTest实际518项，按XML和日志计数，不照抄源会话519数字。
- 杠杆上下5%及紧邻超界、五目标扰动、方向/时间固定、正负净收益、零值精确要求、不满足时失败的现有检查通过。
- 新增ManualToleranceIndependentTest.allTargetsIndependent使用BTC单位、数量0.01、杠杆10、仓位0.01003%、净收益0.0997、平仓价110，五目标同时输入。
- 独立公式：fee=q×0.03；margin=ceil16(q×100/leverage)；net=q×(110−100)−fee；不是再次调用ManualOrderCalculation来构造预期。逐项比较，独立误差不等式abs(actual−target)<=abs(target)×0.05；保存原请求JSON前后相同。
- 前端目标约束脚本通过，生成状态允许±5%、普通预览仍精确杠杆。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\focused.log exit0；C:/workspace/fx/new/manual-tolerance-20260929-042658\independent-mysql.log中该单项通过（整组exit1因第3项）；C:/workspace/fx/new/manual-tolerance-20260929-042658\manualOrderGeneration.log exit0。

限制：没有逐个字段穷举所有数据库精度和HTTP边界；这是所列样例和定向矩阵，不是数学完备性证明。

### 2. 固定身份、时间、方向、时区、开关及真实分钟：通过（现有定向场景）

MySQL原有37项服务测试通过，包含latest已结束有效分钟固定、前七天有界开仓搜索、最新价与目标冲突明确拒绝、不回退旧平仓分钟、缺数据/换算与未来时间拒绝、生成256组合只读、固定开平仓时间与时区/开关。算法分钟测试4/4通过。行情来自合成历史fixture，没有写入或篡改真实历史。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\mysql.log，ManualOrderMySqlIT 37/37；C:/workspace/fx/new/manual-tolerance-20260929-042658\focused.log。mysql整组exit1是随后独立类重复建表的夹具错误，不是这37项失败。

### 3. 数量规格、杠杆限制、各单位及小额费：失败

通过部分：原生币版本/单位校验、步长、最小数量/名义金额候选；MySQL原生币持久化快照及小额费，普通NULL规格原有场景。第一轮币/股/NULL列表投影修复和生命周期检查通过。

**失败部分：生成和权威preview没有限制品种maxLeverage，也没有保证leverage>=1。** 最小复现见下。不得把“目标±5%符合”当成“品种约束符合”。整数约束不作为本次缺陷：本需求明确允许0.01精度杠杆，但上下限依然必须遵守。

币/股/外汇新协议的全部组合及小额手续费浏览器显示没有全部新增验证；股主要覆盖投影，不声称SHARE生成全链路通过。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\independent-mysql.log，3项1通过2失败，0错误/跳过，Maven exit1；原始XML位于C:/workspace/fx/new/manual-tolerance-20260929-042658\source\exchange-backend\target\surefire-reports\TEST-com.gtcfesk.exchange.trade.ManualToleranceIndependentTest.xml。

### 4. 杠杆调整漏解、最小数量边界与独立公式：通过（所测合法场景）

原Tolerance/Feasibility/Matrix/Generator中调整杠杆救回整数数量、minQuantity/minNotional候选和真正无解场景通过。独立MySQL五目标样例核对fee/margin/net及只读状态通过。没有改行情、手续费和利润来拟合目标；未证明搜索能发现所有数学可行解。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\focused.log exit0；C:/workspace/fx/new/manual-tolerance-20260929-042658\independent-mysql.log中allTargetsIndependent单项通过。

### 5. 浏览器、旧响应、只预览与create：未验证（主要定向场景通过）

- 新建headless Chromium页面，挂载独立快照的真实Vue组件，API全部mock。填写五个目标后显示实际10.4杠杆、偏差4%，原输入仍10；其他目标保持，确认按钮启用；目标冲突提示且确认禁用；没有发送create。
- 浏览器截图C:/workspace/fx/new/manual-tolerance-20260929-042658\browser.png已人工检查，组件非空、无Vite错误覆盖层；脚本断言pageerror为空、全部API在拦截清单中。
- lifecycle组件脚本验证关闭/重开弹窗后的旧响应不覆盖新请求、旧finally不解锁新请求、旧用户余额响应不覆盖新用户；生成中表单控件禁用。未穷举浏览器中所有用户/品种切换竞态。
- MySQL现有服务专项验证generate不增加订单、不变钱包/权益，随后专用库create的权限、手动标记、事务回滚、幂等、净收益复校验；不是HTTP登录+浏览器连接该真实服务。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\browser-retry.log exit0，C:/workspace/fx/new/manual-tolerance-20260929-042658\manualOrderLifecycle.log exit0，C:/workspace/fx/new/manual-tolerance-20260929-042658\mysql.log中37/37。**浏览器mock与真实MySQL服务测试是两条链，不能合称真实端到端通过。**

浏览器路径依据：当前无Browser skill，使用技能允许的Playwright fallback（C:/workspace/fx/new/manual-tolerance-20260929-042658\browser-mode.txt）。首次CSS动态import失败，C:/workspace/fx/new/manual-tolerance-20260929-042658\browser.log exit1；仅在本地QA浏览器脚本将CSS加载改为link标签，未改产品源码或断言，复测成功。

### 6. 第一轮D01/D02/手工单标签修复兼容：通过（定向范围）

当前新快照：AdminOrderQuantityProjectionTest 1/1；CryptoQuantityLifecycleTest 4/4（已使用真实KycIdentityService的批准/未批准路径）；manualOrderLifecycle.test.mjs exit0，动态币/股/手语义断言保留。与当前生成器编译、定向运行兼容。04:14HTTP补测保留为历史，本轮没有重跑整套HTTP权限/软删除验收。

证据：C:/workspace/fx/new/manual-tolerance-20260929-042658\focused.log exit0；C:/workspace/fx/new/manual-tolerance-20260929-042658\manualOrderLifecycle.log exit0。

## 新缺陷：MT01 杠杆范围没有落实到生成/预览

责任文件（本轮只读）：

- C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\trade\ManualOrderGenerator.java:36–39：只检查正数及两位小数。
- C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\trade\ManualOrderService.java:148–150：求解调用未传入品种maxLeverage；:181起calculate只复核QuantityRules.quantity/notional，不复核杠杆范围。
- C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\trade\ManualOrderCalculation.java:48：同样仅正数/精度校验。

普通合约ContractOrderService:77–82、common/TradeValidation.java:18–21具有范围控制；本次手动路径允许小数是明确新需求，但没有任何对应上下限逻辑。

合成品种公共设置：lot=1、fee=0.03、BASE_ASSET/BTC、version1、min=step0.001、minNotional=0；钱包1000；历史两分钟open100、close110；BUY；数量0.01、目标净收益0.0997、目标close110。

A. 品种maxLeverage=5，输入leverage10、percent0.01003。期望拒绝，因为全部可接受杠杆9.5–10.5均大于上限。实际返回10.00。日志：`LIMIT_BREACH max=5 result=10.00`。

B. 品种maxLeverage=20，输入leverage0.5、不填percent。期望拒绝，因为全部可接受杠杆0.475–0.525均低于1。实际返回0.50。日志：`LIMIT_BREACH min=1 result=0.50`。

两个严格assertThrows均失败：Expected BusinessException to be thrown, but nothing was thrown。该测试只generate，没有create违法杠杆订单，也没有资金变动。

建议原生成器负责人处理：从配置取得有效范围，在候选筛选和最终preview/create共同校验；保留新需求两位小数精度，不能照搬旧整数校验导致10.4合法目标回归。规格内无解必须拒绝；不能截断返回值却不重算保证金/仓位。优先级建议P1（违反本轮明确品种限制要求）；未推断其影响到普通真实交易接口。

## 完整命令与实际退出码

除特别说明，工作目录C:/workspace/fx/new/manual-tolerance-20260929-042658\source；运行时路径同上，JAVA_HOME=C:\Environment\Java\jdk-21.0.11，MAVEN_OPTS=-Xmx256m，PATH补Java及C:\Environment\Docker\4.81.0\resources\bin。

1. `mvn.cmd -B -f exchange-backend/pom.xml -Dtest=ManualOrderToleranceTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderFeasibilityTest,ManualOrderScreenshotRuleTest,ManualOrderMinutesTest,AdminOrderQuantityProjectionTest,CryptoQuantityLifecycleTest -DargLine=-Xmx768m test`。exit0，684/684。C:/workspace/fx/new/manual-tolerance-20260929-042658\focused.log及focused-exit.txt。
2. `node exchange-admin/tests/manualOrderEstimate.test.mjs`、`node exchange-admin/tests/manualOrderGeneration.test.mjs`、`node exchange-admin/tests/manualOrderGenerationMatrix.test.mjs`、`node exchange-admin/tests/manualOrderLifecycle.test.mjs`。各exit0；C:/workspace/fx/new/manual-tolerance-20260929-042658\node-results.json含各日志绝对路径。
3. `& C:/workspace/fx/new/manual-tolerance-20260929-042658\source\scripts\manual-order\Test-MySql.ps1`。临时runner使用 `mvn -B -f C:/workspace/fx/new/manual-tolerance-20260929-042658/source/exchange-backend/pom.xml`，完整测试选择及JVM参数为：`-Dtest=ManualOrderCalculationTest,ManualOrderMySqlIT,ManualToleranceIndependentTest#allTargetsIndependent+rejectConfiguredMax+rejectSubOneLeverage -DargLine=-Xmx768m test`。Maven/runner失败1；5计算+37MySQL通过，新增类BeforeAll因同库重复CREATE TABLE而错误1，43条报告。**该次新增业务测试没有执行，不能误报为业务失败。** C:/workspace/fx/new/manual-tolerance-20260929-042658\mysql.log、mysql-runner-exit.txt。
4. 改为独立新建数据库，仅选新增3项，不修改原测试：`& C:/workspace/fx/new/manual-tolerance-20260929-042658\source\scripts\manual-order\Test-QA.ps1`；其Maven `mvn -B -f C:/workspace/fx/new/manual-tolerance-20260929-042658/source/exchange-backend/pom.xml -Dtest=ManualToleranceIndependentTest#allTargetsIndependent+rejectConfiguredMax+rejectSubOneLeverage -DargLine=-Xmx768m test`。1通过、2失败、0错误/跳过，Maven/runner失败1，C:/workspace/fx/new/manual-tolerance-20260929-042658\independent-mysql.log、independent-exit.txt。runner外围catch只负责保存状态，不能以外层shell正常结束冒充测试通过。
5. Vite工作目录C:/workspace/fx/new/manual-tolerance-20260929-042658\source\exchange-admin：`C:/Environment/Node.js/24.18.0/node.exe node_modules/vite/bin/vite.js --config qa.vite.config.ts --host 127.0.0.1 --port 18298 --strictPort`。随机新进程，仅本会话使用。服务日志C:/workspace/fx/new/manual-tolerance-20260929-042658\vite.log、vite-error.log，正常启动并显式清理。
6. `C:/Environment/Node.js/24.18.0/node.exe exchange-admin/tests/qa-manual.browser.cjs`。首次exit1（CSS fixture加载），最终exit0；C:/workspace/fx/new/manual-tolerance-20260929-042658\browser.log、browser-retry.log及对应exit文件。新增脚本仅调整端口、缓存路径、截图路径、CSS载入方式，业务断言未删改。

本轮不重跑三端构建、全仓后端、生产环境或真实HTTP生成器登录。各套件包含重复场景，不将总数相加宣称独立覆盖。两处本地夹具错误及重试原始日志全部保留。

## 后续

将MT01交原生成器负责人协调，测试会话不修公共源码。修复后先重跑独立上下限反例、10.4合法小数目标、min/max边界及现有684项范围内相关用例；再补真实隔离HTTP/浏览器联调与未覆盖的单位组合。客服持续修改导致整个项目仍未冻结，本轮不授权部署或提交。