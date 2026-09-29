# 第二轮独立验收结果

日期：2026-09-29，03:57–04:18，Asia/Singapore。

## 结论

**不通过发布验收。新增确认两个 MySQL 客服并发缺陷：重复创建会话抛唯一键异常；容量为 1 时两个不同会话均可被同一客服接待。H2 对照均通过，不能用 H2 结果代替 MySQL。**

本轮已补真实隔离 HTTP、真实身份网关与双 H2 环境、移动/PC 实际组件连接真实后端、活动领取与账户刷新、客服双向消息及模拟模式共用真实客服。资金定向 MySQL 回归 18/18 通过。没有修改共享业务代码、公共测试或 Git 索引，没有部署、业务数据库、真实资金或客户数据操作。

按完整编号：通过 A01、A02、A04；失败 S01、S03；其余 M01、M02、M03、A03、S02、S04、X01 为未验证（存在已通过子项，不扩大为整项通过）。

## 版本、隔离和证据口径

- 独立目录：`C:\workspace\fx\new\round2-20260929-035731`；实际源码：`C:\workspace\fx\new\round2-20260929-035731\source`。
- HEAD 为 `a3818cb44d854dff76e1a66770d9772d8c0f5d5b`，不是完整工作树版本。
- 初始复制 802 个源码、配置及测试文件，逐文件 SHA-256：`C:\workspace\fx\new\round2-20260929-035731\source-before.json`；清单 SHA-256：`12532DEB7DF9F6878D9F98BBEE330CEA5947BFC6C357A9EAC8727A594143C459`。
- 未复制 `.env`、业务 dump、uploads、共享 target/dist 或客户数据。前端依赖以只读用途的 junction 复用已安装 node_modules；Vite cacheDir、Java target、浏览器页面和产物全部独立。没有新装依赖。
- 初次复制前后执行逐文件比较，无差异；空集合管道没有生成原计划的 copy-drift.json。`C:\workspace\fx\new\round2-20260929-035731\copy-audit.json` 如实记录此取证缺口，并以未改快照和唯一改动文件的原件备份复核。不是补造当时的文件。
- 独立快照仅改动既有 `SupportServiceTest.java` 的数据源选择，新增可选 localhost MySQL 测试端口；所有原测试方法、预期金额、断言与异常判断保留。原件为 `C:\workspace\fx\new\round2-20260929-035731\SupportServiceTest.original.java`；差异清单 `C:\workspace\fx\new\round2-20260929-035731\snapshot-changes.json`。另外新增 Round2HttpTest、Round2DualTest、Round2SupportExtraTest 和浏览器夹具，不写回共享测试。
- 初期漂移检查为 0；04:14检查发现共享 `AdminOrderController.java`、`CryptoQuantityLifecycleTest.java` 改变，并新增 `AdminOrderQuantityProjectionTest.java`。详见 `C:\workspace\fx\new\round2-20260929-035731\source-drift-final.json`、`C:\workspace\fx\new\round2-20260929-035731\source-added-final.json`。上述数量文件属于被排除任务责任范围，本会话只计算指纹，没有分析其新增实现、续做修复或运行其测试。04:18最终复核已有文件漂移增至10项，另外涉及ManualOrderGenerator/ManualOrderService、四份ManualOrder测试及后台ManualContractOrder.vue/manualOrderGeneration.ts；均未吸收到快照或追改。最终明细以source-drift-final.json为准。结论仅对应本轮快照，不能覆盖结束时最新全树。
- MySQL 使用本地已有 `mysql:5.7` 镜像，实际 5.7.44、REPEATABLE-READ。每轮独占新容器、tmpfs、随机 `127.0.0.1` 端口，标签 `qa705-round2=true`，库 `activity_test`、`support_test`。端口在实际命令记录中为 59686、65444 及最后容量轮的记录值。仅清理自己标签匹配的容器，结束无残留。
- HTTP 与浏览器后端为完整 Spring Boot/Tomcat，随机端口、合成用户、真实 JWT 过滤链、控制器、事务和 H2；行情/Redis 服务是明确的测试替身，未验证真实 Redis 部署。双环境测试另外启动独立模拟应用/H2，SimulationGateway 实际访问真实端 `/api/simulation/session` 和 `/catalog`，不是身份网关 mock。
- 用户与管理员会话是测试内创建的合成账户并签发有效 JWT，**没有执行密码/验证码登录入口**。不能把此口径称为完整登录端到端测试。
- 外部行情地址限为本地不可用地址，流关闭；邮件限 localhost:1。没有外部支付、邮件、下单。重型 Maven/MySQL/浏览器批次串行。
- 夹具最终 SHA-256：`C:\workspace\fx\new\round2-20260929-035731\fixture-hashes.json`；日志/XML/截图哈希：`C:\workspace\fx\new\round2-20260929-035731\evidence-hashes.json`。日志中的身份、令牌、初始化账号均为临时合成数据；报告不转录令牌。

## 历史证据复用

已读计划指定四份功能文档、round1 结果及 D04 补充结果。模拟 857 项、活动历史 296 项、客服历史 64 项以及过去的 mock 浏览器结果只作历史证据，**未累计为本轮新通过数量**。

- 模拟历史边界：`C:\workspace\fx\705\docs\demo-full-account-validation.md`；原身份和行情测试替身不能代替本轮双环境真实身份网关。
- 模拟 Node 修复：`C:\workspace\fx\705\reports\full-simulation-20260929\runtime-compat\REPORT.md`；本轮原入口重跑，不改断言。
- 活动历史 MySQL/资金：`C:\workspace\fx\new\activity-qa-20260929\mysql-tests.log`；本轮在新快照、新 MySQL 又执行原 18 项。
- 客服历史：`C:\workspace\fx\new\support-20260929\isolated-backend.log` 与 `browser-result.json`。旧浏览器只有 mock，不冒充真实接口；原提示音替身只证明触发逻辑。
- 既有数量单位/旧夹具阻断独立保留，不因当前共享文件漂移视为解决。D04 两次通过及一次生产平仓乐观锁冲突仍按 `C:\workspace\fx\705\docs\session-monitor\tests\d04-market-fixture-20260929-result.md` 保留，本轮没有重跑/修改该交易场景或生产锁。

## 编号矩阵

所有编号源码指纹均为上述 source-before.json；环境和命令编号在下一节定义。

| 编号 | 结果 | 步骤、预期与实测 | 证据绝对路径 |
|---|---|---|---|
| M01 | 未验证（部分通过） | 双应用、双 H2、真实身份 HTTP 网关；6 线程共 12 次首次进入全部 200，seed=1，三个钱包各 100000、冻结 0，KYC 记录 0；匿名及真实 sid 撤销后 401，重新签发当前 sid 后余额未重发。预期一致。但没有密码/验证码登录入口、真实到期 JWT、双 MySQL 开户验收。 | `C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2.log`；`C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2-xml\TEST-com.gtcfesk.exchange.Round2DualTest.xml`（C7 exit0） |
| M02 | 未验证（部分通过） | 独立 REAL/DEMO H2、HTTP 双向错误模式 409；实际网关仍检查真实会话，模拟身份不覆盖真实用户。未执行双 MySQL/Redis、完整模拟交易前后真实订单/资金/KYC 对账；切换失败保持原模式仅复用历史 mock 浏览器，不把它当本轮真实环境通过。 | `C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2-xml\TEST-com.gtcfesk.exchange.Round2DualTest.xml`；`C:\workspace\fx\705\docs\demo-full-account-validation.md` |
| M03 | 未验证（部分通过） | 原四份 Node 脚本覆盖原五个失败入口，包括 PC/移动 transport；20/20，exit0。模拟日语专项两端共 26/26。全局日语仍 exit1，首缺 Trade.vue 的 Quantity must satisfy instrument minimum and step；不属于新增模拟文案。未完成真实后端 PC/移动全账户切换、重新登录、行情订阅与历史残留验收。 | `C:\workspace\fx\new\round2-20260929-035731\node-five-entry.log`；`C:\workspace\fx\new\round2-20260929-035731\i18n-simulation.log`；`C:\workspace\fx\new\round2-20260929-035731\i18n-all.log`（C3） |
| A01 | 通过 | 真实隔离 HTTP 超管创建活动、定向两用户；第三用户领取 400。重复领取只入账 300；暂停/关闭/过期后另一用户均 400。RECEIVED/CLOSED/OPENED/OPENED 后 sent=2、opened=1、closedWithoutOpening=0、claimed=1；回执 openCount=2、closeCount=1。不是把“打开次数”与“打开人数”混为一谈。 | `C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final.log`；`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final-xml\TEST-com.gtcfesk.exchange.Round2HttpTest.xml`（C8 exit0） |
| A02 | 通过（隔离 MySQL） | 原 18 项集成在 MySQL 5.7 RR 下通过，包含 12 次并发重复领取只 300/单流水、两活动同用户同时领取 600、预算 300 竞争仅一人成功、名额、失败批次回滚、并发资金占用不超发。没有修改原金额/阈值。 | `C:\workspace\fx\new\round2-20260929-035731\ActivityIntegrationTest-mysql.log`；`C:\workspace\fx\new\round2-20260929-035731\xml\ActivityIntegrationTest\TEST-com.gtcfesk.exchange.activity.ActivityIntegrationTest.xml`（C1 exit0） |
| A03 | 未验证（部分通过） | MySQL 原资金服务：未实名只花体验金、混合资金撤单、亏损和手续费优先、净收益真实归属、合约/期权、重复平仓、冻结与资金数值断言通过。例如 300 本金、100 真实，获利平仓本金仍300、真实109；亏损21后本金279、真实100；混合撤单恢复300/100。原本金 TRIAL 划转拒绝及历史提现 KYC 保护保留。但没有用本轮真实 HTTP 穷举混合下单/撤单/平仓/提现/划转全部入口，不能宣称无法直接接口突破已完整验收。 | `C:\workspace\fx\new\round2-20260929-035731\ActivityIntegrationTest-mysql.log`；`C:\workspace\fx\new\activity-qa-20260929\security-tests.log` |
| A04 | 通过（实际组件与真实隔离接口） | 手机390×844、PC1366×900实际 ActivityCenter/TrialAccountCard；真实后端双击只一次成功领取，各300，关闭重开不再发放，账户卡刷新。第二活动在打开详情后由后台暂停，客户端仍点击领取，真实 HTTP400、显示错误，余额仍300。模拟模式不显示活动入口、不请求真实活动。未使用 API response mock；不是整个登录/交易应用页面回归。 | `C:\workspace\fx\new\round2-20260929-035731\browser-real-result.json`；`C:\workspace\fx\new\round2-20260929-035731\browser-real.log`；`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final-xml\TEST-com.gtcfesk.exchange.Round2HttpTest.xml`；两端 `*-activity.png`（C8 exit0） |
| S01 | 失败 | 真实 HTTP 两客户、两客服，顺序排队/容量拒绝/抢单冲突/转接后旧客服403/其他客户403/撤销sid401通过。MySQL并发开会话抛唯一键异常；更严重：同一客服容量1、两个待接会话并发接待，两次都成功。离线/心跳历史测试通过不抵消并发缺陷。 | `C:\workspace\fx\new\round2-20260929-035731\xml\SupportServiceTest\TEST-com.gtcfesk.exchange.support.SupportServiceTest.xml`；`C:\workspace\fx\new\round2-20260929-035731\capacity-xml\TEST-com.gtcfesk.exchange.support.Round2SupportExtraTest.xml`；`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final.log` |
| S02 | 未验证（部分通过） | MySQL原HTTP4/4：真实过滤链/权限、私有图片跨用户403、匿名401、超管导出；原服务除并发开户外11项通过，涵盖幂等/已读/开关/转接/监督。H2日志隐私1/1；本轮真实 HTTP 站内信两人发送、重发0、他人已读403、重复已读200。尚未补“外部客服模式”真实后端切换及全部接口/导出日志的联合隐私核验，不能用过去mock外链测试代替。 | `C:\workspace\fx\new\round2-20260929-035731\SupportHttpTest-mysql.log`；`C:\workspace\fx\new\round2-20260929-035731\h2-final-xml\TEST-com.gtcfesk.exchange.support.SupportAuditLogTest.xml`；`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final.log` |
| S03 | 失败 | 新MySQL复现原并发开户异常两次；新增并发同requestId消息返回同id、双向不同消息、导出chainValid及既有会话抢单通过；容量竞争失败。相同H2原12项、新增消息/抢单2项、容量1项均通过，确认数据库差异。没有完成迁移/应用重启后的持久化恢复；该部分未验证，不能以DDL create-drop冒充迁移验收。 | `C:\workspace\fx\new\round2-20260929-035731\Round2SupportExtraTest-mysql.log`；`C:\workspace\fx\new\round2-20260929-035731\capacity-xml\TEST-com.gtcfesk.exchange.support.Round2SupportExtraTest.xml`；`C:\workspace\fx\new\round2-20260929-035731\h2-final-xml`；`C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2-xml` |
| S04 | 未验证（部分通过） | PC/移动实际 SupportThread 连接真实隔离HTTP，客户发送与后台真实API回复均展示；后台操作由API完成，不宣称后台工作台全页面联调。reply.wav从真实接口取回，用户手势后原生Audio.play成功，浏览器解码时长0.499955秒；未mock播放。通知触发逻辑仍复用历史证据；未验证实际扬声器听觉、arrival.wav实际播放、iOS/Android真机、锁屏/后台，不承诺锁屏响铃。 | `C:\workspace\fx\new\round2-20260929-035731\browser-real-result.json`；两端 `*-support.png`；`C:\workspace\fx\new\support-20260929\browser-result.json` |
| X01 | 未验证（部分通过） | 实际组件切到DEMO后客服仍访问真实 `/api/user/support/`，原聊天可读，活动零请求；真实HTTP跨用户403、转接后旧客服403、sid撤销401；完整应用初始化保留support/inbox/support_settings/announcement/orders目录且无活动权限客服403。未覆盖所有会话切换后的UI内存清空，以及实际模拟交易与真实活动资金的交叉对账；没有单独模拟后台菜单可当三功能完整目录证明。 | `C:\workspace\fx\new\round2-20260929-035731\browser-real-result.json`；`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final.log`；`C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2.log` |

## 新缺陷及责任范围

### R2-D01：MySQL 并发创建同一客户会话，第二事务唯一键异常

- 最小复现：原 `SupportServiceTest.concurrentStartAndClaimAreSerialized`；两个线程同步调用同一用户 `service.start()`。本应都返回同一个会话ID。
- MySQL 5.7 RR 实测：一个事务失败，`SQLIntegrityConstraintViolationException: Duplicate entry '7000013' for key 'UK_c9eu56wfqjm9qowf0mksq7je'`；位置 `SupportService.start` 的 persist。第二个全新MySQL轮次也复现。H2原测试通过。
- 唯一约束阻止实际创建两个活动会话，但接口服务调用不能按设计幂等成功。没有声称本轮已测到HTTP具体500；此异常在真实服务/数据库层证实。
- 责任文件：`C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\support\SupportService.java:62–72`，关联 `SupportSettings.java:31–34`、`admin\SystemConfigService.java:22–24`。
- 机制推断：事务先读设置建立RR一致性快照，再锁用户；第65行普通查询仍可能看不到另一事务刚提交的活动会话。用户锁并未让后续普通一致性读成为当前读。此解释与差异吻合，但没有抓取InnoDB锁/事务快照，不冒充引擎级完整因果证明。
- 建议客服负责人核对锁前读取、查询当前读和幂等恢复的事务边界；保留数据库唯一约束，不改为允许重复，不吞异常后假报成功。
- 证据：`C:\workspace\fx\new\round2-20260929-035731\xml\SupportServiceTest\com.gtcfesk.exchange.support.SupportServiceTest.txt`，`C:\workspace\fx\new\round2-20260929-035731\xml\Round2SupportExtraTest\com.gtcfesk.exchange.support.Round2SupportExtraTest.txt`。

### R2-D02：MySQL 并发接待突破客服容量1

- 两个合成客户各有WAITING会话，同一个在线客服容量设为1。两个线程同时 `claim(first, admin)`、`claim(second, admin)`。
- 预期一成功、一409；实测两个都返回成功，严格断言失败：`capacity=1 must admit exactly one conversation ==> expected: not equal but was: <true>`。同夹具H2通过。
- 责任文件：`C:\workspace\fx\705\exchange-backend\src\main\java\com\gtcfesk\exchange\support\SupportService.java:125–143`；同类转接入口145行以后也使用checkCapacity，需要负责人一并检查，不等于已确认转接同样失败。
- 机制推断：claim中的设置/权限读取发生在管理员锁前，锁后capacity count是普通查询，可能继续读取旧RR快照；因此两次都认为容量空闲。管理员行锁存在，不代表聚合容量读取具备当前读语义。
- 建议仅在客服事务/容量串行化范围修复，保留容量断言与权限保护；不要把容量改大或切低隔离级别当作产品通过证据。
- 最小夹具：`C:\workspace\fx\new\round2-20260929-035731\source\exchange-backend\src\test\java\com\gtcfesk\exchange\support\Round2SupportExtraTest.java` 的 `concurrentCapacityCannotOverbook`。
- 证据：`C:\workspace\fx\new\round2-20260929-035731\capacity-xml\com.gtcfesk.exchange.support.Round2SupportExtraTest.txt`；H2对照 `C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2-xml\TEST-com.gtcfesk.exchange.support.Round2SupportExtraTest.xml`。

两项共同被测生产文件 SHA-256：`1A79BE18F0EEEAA2B29702A1B902B2E926F2079E54FB13F81A8B0E0491CF28BD`。本会话没有修复共享生产文件。

## 命令与实际退出码

执行器：Java `C:\Environment\Java\jdk-21.0.11`，Maven `C:\Environment\Maven\3.9.9\bin\mvn.cmd`，Node `C:\Environment\Node.js\24.18.0\node.exe`；MAVEN_OPTS=-Xmx256m，测试JVM -Xmx768m。Maven均使用 `-B -o`，没有联网下载依赖。

统一 Maven 参数：`-f C:\workspace\fx\new\round2-20260929-035731\source\exchange-backend\pom.xml -DargLine="-Xmx768m -Dfile.encoding=UTF-8" test`。以下选择器为实际执行，不应与共享工作树混用。

- C1：`& 'C:\workspace\fx\new\round2-20260929-035731\run-mysql.ps1' -Report 'C:\workspace\fx\new\round2-20260929-035731'`。内部顺序 `-Dtest=ActivityIntegrationTest` exit0（18/18）；`-Dtest=SupportServiceTest` exit1（12项1error）；`-Dtest=SupportHttpTest` exit0（4/4）。MySQL端口参数由新容器生成，具体命令 `C:\workspace\fx\new\round2-20260929-035731\commands.txt`。
- C2：`-Dtest=Round2HttpTest` 初次 exit1（测试夹具错读delivery层级、TestRestTemplate匿名POST流式401异常）；第二次 exit1（UI夹具缺claim文案）；第三次 exit1（zh-CN测试语言不在客服locale枚举，错误查找中文按钮）。分别保留 `C:\workspace\fx\new\round2-20260929-035731\real-http.log`、`real-http-attempt2.log`、`real-http-final.log` 与 http-attempt1/2/3 子目录。仅修新增夹具，不删原断言；zh-CN由活动支持、客服回退英语，因此后续查找实际Message/Send，不判为产品文字缺陷。
- C3：在 `C:\workspace\fx\new\round2-20260929-035731\source` 执行 `node --test exchange-pc/tests/chartData.test.mjs exchange-pc/tests/controlRecovery.test.mjs exchange-pc/tests/marketSimulation.test.mjs exchange-pc/tests/marketTransport.test.mjs` exit0（20/20）；`node scripts/check-i18n.cjs` exit1；`node C:\workspace\fx\new\round2-20260929-035731\check-simulation-i18n.cjs` exit0（26条）。
- C4：`-Dtest=Round2HttpTest,Round2DualTest#realIdentityGatewayAndTwoDatabases,SupportServiceTest,SupportAuditLogTest,Round2SupportExtraTest#concurrentMessagesRetainHashChain+concurrentClaimExistingSession -Dround2.report=C:\workspace\fx\new\round2-20260929-035731`，整体exit1。HTTP4/4、客服12/12、日志1/1、消息/抢单2/2通过；双应用新增夹具扫描到其他测试配置而启动失败。日志 `C:\workspace\fx\new\round2-20260929-035731\h2-final.log`，不能把整体exit1写成绿色。
- C5：`-Dtest=Round2DualTest#realIdentityGatewayAndTwoDatabases` 两次诊断均exit1：第一次仍扫描到IT夹具；第二次最后一步误用已撤销旧token期待409，实际401符合身份保护。日志 `C:\workspace\fx\new\round2-20260929-035731\dual-final.log`、`dual-attempt3.log`；备份保留每次新增夹具。修正仅限排除test-classes配置和重入后使用新token，期望401/409、余额和并发断言未放宽。
- C6：`& 'C:\workspace\fx\new\round2-20260929-035731\run-mysql-extra.ps1' -Report 'C:\workspace\fx\new\round2-20260929-035731'`，`-Dtest=Round2SupportExtraTest` exit1；14项含继承的原12项，13通过、原并发开户再次error，**不是14项全新覆盖**。`& 'C:\workspace\fx\new\round2-20260929-035731\run-mysql-capacity.ps1' -Report 'C:\workspace\fx\new\round2-20260929-035731'`，`-Dtest=Round2SupportExtraTest#concurrentCapacityCannotOverbook` exit1（1项失败）。日志 `C:\workspace\fx\new\round2-20260929-035731\Round2SupportExtraTest-mysql.log`、`C:\workspace\fx\new\round2-20260929-035731\Round2SupportExtraTest#concurrentCapacityCannotOverbook-mysql.log`。
- C7：`-Dtest=Round2DualTest#realIdentityGatewayAndTwoDatabases,Round2SupportExtraTest#concurrentCapacityCannotOverbook`，exit0，2/2。日志 `C:\workspace\fx\new\round2-20260929-035731\dual-and-capacity-h2.log`。
- C8：`-Dtest=Round2HttpTest#browserRealComponents -Dround2.report=C:\workspace\fx\new\round2-20260929-035731` exit0；最后增加回执次数和浏览器响应取证，再执行 `-Dtest=Round2HttpTest -Dround2.report=C:\workspace\fx\new\round2-20260929-035731`，exit0，4/4。日志 `C:\workspace\fx\new\round2-20260929-035731\browser-extra.log`、`C:\workspace\fx\new\round2-20260929-035731\http-acceptance-final.log`。Java测试启动已有Node/Playwright脚本并严格断言进程exit0；浏览器日志 `C:\workspace\fx\new\round2-20260929-035731\browser-real.log`，脚本 `C:\workspace\fx\new\round2-20260929-035731\browser-real.cjs`。

全部原始实际退出码汇总：`C:\workspace\fx\new\round2-20260929-035731\exits.txt`。容器清理成功造成外层PowerShell exit0时，不代替内部Maven exit1。辅助读取阶段几个不存在路径的探查错误没有当作产品测试失败。

## 浏览器证据与限制

Browser plugin/browser技能未列出，按测试技能使用已安装Playwright及本机Chrome，headless、独立上下文，不操作已有页面。前端在独立快照通过Vite随机localhost端口运行，实际Vue组件；只新建薄挂载页面与合成身份，不mock API、账户余额、领取或聊天响应。

- 页面标题正确、非空、核心交互完成，无pageerror、无Vite错误遮罩；截图已查看，无明显裁切。
- 控制台不能写“零错误”：暂停活动负向领取的400是预期错误；另外保留每端两条资源404，响应取证没有对应到URL，来源未确定，不凭推测认定favicon。对“全部控制台资源健康”仍未验证。它们没有阻止本轮已断言的真实业务流程。
- 声音测试只是浏览器媒体解码及play promise成功，不是扬声器听觉或真机锁屏测试。
- 组件挂载没有覆盖完整布局、登录页面、后台工作台、所有路由及全局通知组件。不能用局部界面通过代表所有页面通过。

截图：
- `C:\workspace\fx\new\round2-20260929-035731\exchange-frontend-activity.png`
- `C:\workspace\fx\new\round2-20260929-035731\exchange-pc-activity.png`
- `C:\workspace\fx\new\round2-20260929-035731\exchange-frontend-support.png`
- `C:\workspace\fx\new\round2-20260929-035731\exchange-pc-support.png`

## 下一步

1. 调度将R2-D01/R2-D02交客服负责人，保留本轮原断言，在新MySQL RR环境修后复验；一并核查transfer容量竞争。
2. 按矩阵剩余项补密码/验证码登录、JWT真实到期、双MySQL/Redis模拟交易对账、完整交易HTTP权限、客服迁移/重启持久化、外部模式、真机声音与全局会话切换。
3. 数量单位/旧夹具任务和D04平仓冲突仍独立阻断。结束共享漂移不纳入本轮结论；冻结最终整合版本后再统一集成回归。未授权提交、推送或部署。

当前:第二轮新增功能独立验收 / 两个MySQL客服并发缺陷，报告与原始证据已保存 / 修复后定向复验并补齐未验证项

