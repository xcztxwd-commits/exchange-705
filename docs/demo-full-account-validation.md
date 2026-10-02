# 全功能模拟账户验证报告

日期：2026-09-29。工作目录：C:/workspace/fx/705。未改动生产数据，未启动生产模拟环境。

## 已通过

### 后端：857 项，0 失败、0 错误、0 跳过

- SimulationApplicationTest：3 项。启动完整 Spring 应用、真实 HTTP/security/filter/controller；校验登录后开户、未实名可用、匿名拒绝、错环境拒绝、本地注册提前阻断。
- SimulationGatewayTest：3 项。真实身份来源、过期会话拒绝、自认证递归/错环境拒绝。
- SimulationSecurityTest：8 项。错误数据库拒绝启动、豁免不伪造 APPROVED、实盘仍执行实名规则、各金融 API 的双向环境边界、编码 URL 的本地身份防绕过。
- SimulationPersistenceTest：12 项。H2 MySQL 模式真实事务、12 次并发开户只发一次、失败回滚、余额持久化、不同用户及两个数据库隔离、目录同步、虚拟充值幂等、提现、贷款签约/放款/还款、理财申购/赎回、划转幂等。
- 其中六市场类别链路使用实际 ContractOrderService、OptionOrderService、TrialFunds 与 JPA repositories：每类买/卖开平仓、限价撤单、看涨/看跌到期结算，共 24 笔合约和 12 笔期权，不创建任何实名记录，冻结余额最终为零。
- IdentityLoanFlowTest：13 项，保留真实实名与贷款资料校验回归。
- CryptoQuantityRulesTest：423 项；FeeCalculationAuditTest：166 项；FxStandardContractTest：196 项，复用引擎精度、费用、外汇回归。
- ActivityIntegrationTest：18 项，确认原活动体验金、真实实名门槛及资金结算未受模拟豁免影响。
- 旧 demo 后端回归：15 项，确保保留的历史模块未被破坏；旧简化前端已退役，不作为当前模拟入口。

运行命令：
```powershell
mvn -q -f exchange-backend/pom.xml '-Dtest=SimulationApplicationTest,SimulationPersistenceTest,SimulationSecurityTest,SimulationGatewayTest,IdentityLoanFlowTest,Demo*Test,FxStandardContractTest,CryptoQuantityRulesTest,FeeCalculationAuditTest,ActivityIntegrationTest' test
```

实际执行分为主批次、Gateway、活动回归与最终边界复测。明细可查看 exchange-backend/target/surefire-reports 的对应 XML；日志为 simulation-final-test.log、simulation-gateway-test.log、simulation-activity-regression.log、simulation-boundary-final.log。不要把其他未选中的既有报告算入本次结果。

### 双端构建

- exchange-frontend：`npm run build` 通过 TypeScript 与 Vite。
- exchange-pc：`npm run build` 通过 TypeScript 与 Vite。
- 仍有既有的大 chunk 警告，不影响本次构建；未为本功能引入依赖。

### 浏览器：手机与桌面均通过

Browser plugin not available；使用已安装的 Playwright 和本机 Chrome，无新增依赖。

脚本：exchange-frontend/tests/fullSimulation.browser.cjs。测试服务器：127.0.0.1:5413、127.0.0.1:5415。

覆盖：
1. 原有完整页面加载，不是简化 demo 页。
2. 模拟服务故障时保持真实账户，不自动回退或改变选择。
3. 成功切换后资产请求发往 `/demo-api`，包含 DEMO 标记；心跳仍去真实身份接口。
4. 重载后仍处于当前模拟账户。
5. 手机未实名访问模拟提现不再被实名路由拦截。
6. 存在未完成划转请求时仍可切换；请求保持原环境，页面重载后无旧状态串入。
7. 模拟导出绘制固定水印，验证像素颜色。
8. 切回真实账户；真实业务请求不携带 DEMO 标记。
9. 无 pageerror、无 Vite 错误遮罩。等待开场动画隐藏后截图，人工检查两端账户条与业务页面。

这些浏览器用例使用 HTTP fixture，不代表生产后端成交联调。服务端业务则由上述实际服务和数据库事务测试验证。

截图：
- C:/Users/徐乾妖/AppData/Local/Temp/full-simulation-qa/mobile-demo.png
- C:/Users/徐乾妖/AppData/Local/Temp/full-simulation-qa/pc-demo.png

### 部署配置

`docker compose -f compose.yaml -f compose.demo.yaml config --no-interpolate --quiet` 通过。
只验证 Compose 结构；未使用真实密码启动容器，未执行实盘服务重建。

## 已发现并修复

- 初版账户切换被未完成写请求阻塞；现在允许切换，并保留原请求绑定。
- 行情直连/开发 WebSocket 仍可能走真实端口；统一账户 API 基址及 Vite WebSocket 代理。
- 手机提现路由、PC 内嵌提现/贷款守卫仍强制实名；改为使用模拟豁免，实盘规则不变。
- PC 使用内嵌充值页而不是独立 Deposit.vue；两个入口均解除模拟凭据要求。
- 模拟认证豁免未纳入 TrialFunds，可导致余额不可交易；统一资金资格判定并覆盖实际交易测试。
- 模拟注册请求先被验证码/限流过滤器处理；新增前置身份边界，稳定返回 409。
- 模拟分享图没有环境标识；现在强制水印。
- 开户并发、部分失败、重入补发等问题由同事务、用户行锁、seed 主键防止。

## 未完成的上线验收与既有检查问题

- 尚未运行真实双 MySQL / Redis 部署、生产用户验收、网络分区/长时间定时结算压测、吞吐和延迟压测。
- 身份源和行情源在集成用例中为测试替身；实际身份网关通过 MockRestServiceServer 单独验证。
- 模拟服务须配置独立凭据并部署后，用户才能在正式环境切换。缺失服务时前端明确报错，不会调用真实资金接口冒充模拟。
- 全量 `node scripts/check-i18n.cjs` 未通过：当前首个剩余问题是 Trade.vue 既有文案 `Quantity must satisfy instrument minimum and step` 缺日语翻译。本任务账户切换、模拟提示及关联贷款提示已登记；未修改其他交易/i18n 任务内容来掩盖全量检查失败。日志：simulation-i18n-test.log。
- 未声称完整仓库全量回归通过。共享工作区存在其他任务修改，按本功能选定用例报告结果。

## 回退

停止新模拟入口/服务，不删除隔离数据库和审计记录；不要整体回滚实盘数据库。文件修改前备份位于 rollback/demo-full-account-20260929，含退役简化前端。存在并行修改，必须选择性恢复，不能直接整目录覆盖。

## 追加：原生 Node 模块解析复验

2026-09-29 03:43：修复 marketWebSocket 的 Vite 别名依赖与非 Vite 环境读取，原 Node 20 项全过，双端独立构建及浏览器复验通过；模拟账户新日语文案 26 项专项通过。全量 i18n 仍有 Trade.vue 既有缺项。完整证据及当前文件 SHA-256 见 C:/workspace/fx/705/reports/full-simulation-20260929/runtime-compat/REPORT.md 和 version.json；本轮未重跑后端，不重复累计后端测试数。
