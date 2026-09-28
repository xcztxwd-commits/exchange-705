# 已平仓模拟合约单：实施与验收记录

日期：2026-09-28（Asia/Singapore）。工作目录：`C:/workspace/fx/705`。

## 1. 交付结论与环境边界

已实现并在专用本地环境完成创建、真实历史行情取价、MySQL 5.7 事务、并发、故障回滚、权限、普通交易回归及真实浏览器登录验收。不是上一轮的 15 个内存算例，也不是仅构建成功。

**没有对原业务库运行迁移或测试入账，没有重启原业务服务。** 当前可使用的功能入口在隔离环境；原环境上线仍需先备份、执行增量迁移、部署，再显式开启内测开关。未提交或推送 Git。

- 原环境：`exchange-705-mysql-1`，数据库 `1090`，MySQL 5.7.44；原服务 17050/17051/17052 未改变。原库时区为 SYSTEM，连接使用 UTC。
- 浏览器隔离库：`exchange-705-manual-browser-mysql / manual_browser`，MySQL 5.7.44，宿主绑定 `127.0.0.1:62476`，数据目录为 tmpfs，没有接入原持久化卷。
- 独立网络：`exchange-705-manual-browser`，10.235.72.0/24；创建前确认没有冲突。容器均标记 `exchange-manual-browser=true`。
- 后端：Spring Boot 2.7.18，实际运行 Temurin **Java 8u502**；宿主 Maven 3.9.9/JDK 21，通过 `-Dmaven.compiler.release=8` 重新编译全部 229 个生产源码文件，避免仅设置 target=8 却链接新 JDK API。
- 前端：Node 24.18；后台、PC、移动端均构建完成。内测构建显式设置 `VITE_API_BASE_URL=/api`，Nginx 同源转发至隔离 backend。
- 确定性 SQL 测试使用另建的一次性 MySQL 5.7 容器，数据库 `manual_order_test`；普通交易回归使用其独立 `manual_regression_test` 库。测试结束核验标签后只删除自有临时容器。

### 保留的入口与开关

- 后台：[http://127.0.0.1:18151/orders](http://127.0.0.1:18151/orders)，选择“合约订单”，再选择“新建已平仓模拟合约单”。
- PC：[http://127.0.0.1:18150/](http://127.0.0.1:18150/)，合约历史记录。
- 移动端：[http://127.0.0.1:18152/orders](http://127.0.0.1:18152/orders)，合约历史；已按 390×844 视口实际检查，检查后恢复浏览器视口。
- 生产代码默认 `manual.orders.enabled=false`；仅上述隔离 backend 设置 `MANUAL_ORDERS_ENABLED=true`。权限为 `SUPER_ADMIN`，控制器路径之前的安全规则和服务内校验同时生效。
- 隔离环境 `ASSET_HISTORY_EQUITY_READ_ENABLED=true`，自动权益采集关闭，避免定时写入干扰浏览器固定历史夹具。自动化测试直接调用真实采集/归集任务验证并发及后续采样，不以关闭任务冒充并发通过。

## 2. 备份与数据保护

实施前保存了分支、未提交状态、已有差异及所有本次需修改的现有文件原件：

- `C:/workspace/fx/705/rollback/manual-history-order-20260928/git-status-before.txt`
- `C:/workspace/fx/705/rollback/manual-history-order-20260928/pre-existing.diff`
- `C:/workspace/fx/705/rollback/manual-history-order-20260928/` 下对应源码路径。
- 原相关表结构及数据备份：`C:/workspace/fx/705/rollback/manual-history-order-20260928/database-before.sql`，约 4.2 MB；结构核对为同目录 `schema-before.txt`。已检查备份可读，没有向原库恢复或覆盖。
- 浏览器夹具入账前快照：`C:/workspace/fx/705/reports/manual-order-browser/before-browser.sql`。
- 浏览器验收后快照：`C:/workspace/fx/705/reports/manual-order-browser/after-browser.sql`。tmpfs 停止后数据会丢失，保留该快照供隔离恢复。
- 恢复演练：将入账前快照导入同一自有容器内新建的 `manual_restore_check`，确认钱包 1000、订单 0、审计 0、手动迁移记录 1；未覆盖正在验收的 `manual_browser`。证据：`C:/workspace/fx/705/reports/manual-order-browser/restore-check.txt`。

`rollback/`、`reports/` 均被 Git 忽略。秘密、环境文件、包含凭据哈希/会话的隔离备份已限制为当前 Windows 用户访问。没有把明文秘密写入本报告或源码。

**内测构建配置纠正记录：** 首次沿用现有 `.env.production` 构建，测试登录请求被指向原先配置的外部 API。发现后立即停止该登录路径，改成 `/api` 重建三个客户端，并轮换本轮新生成的两组测试密码、撤销旧本地会话。未使用现有用户密码，未把原数据库凭据放入浏览器。后续登录及所有成功创建均在回环地址/隔离库完成。保留原 `.env.production` 未改，复跑必须继续设置 `/api`。

## 3. 实现范围

### 新增生产代码

- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/ManualOrderController.java`：薄控制器，context/preview/create 三个端点。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderCalculation.java`：独立 BigDecimal 计算、步长、精度、溢出、正值、目标无解、分钟/DST 校验。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderPrices.java`：复用历史行情链路，精确匹配目标分钟，读取实际 `open_price` 字段（兼容 `open`）；不使用邻近价格。固定兑换率与历史非固定兑换率分开处理。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java`：预览绑定、权限、幂等、单连接事务、账户版本及独立审计编排。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/user/ManualOrderHistory.java`：24 小时基础查找、范围增量、只补目标分钟、到期父桶定点重算。
- `C:/workspace/fx/705/exchange-admin/src/components/ManualContractOrder.vue`：独立表单，后端预览驱动联动，滑块扩展、时区、净收益差额及历史影响摘要。

### 精确修改的现有文件

- `C:/workspace/fx/705/exchange-admin/src/views/Orders.vue`：超级管理员创建入口。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/config/SecurityConfig.java`：专用端点优先匹配 SUPER_ADMIN，不落入通用 AGENT 规则。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/entity/ContractOrder.java`：来源、内部开关快照、手动单绝对 UTC 时间透传；旧时间语义不整体迁移。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetEquityStore.java`：逻辑生效时间读取、人工点不登记真实基线。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetHistoryBucket.java`：人工分钟时间兼容，复用同一 OHLC reducer。
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/user/AssetEquityHistoryService.java`：carryIn/期初与逻辑时间一致兼容，不改变不限时长的展示沿用。
- `C:/workspace/fx/705/exchange-pc/src/views/DesktopTrade.vue`：实际 PC 合约历史透传手动标记、UTC 开平仓时间及只对手动单展示固定平仓价；负余额明确提示资金不足。
- `C:/workspace/fx/705/exchange-frontend/src/views/Orders.vue`：移动端实际历史手动标记、时间、手续费。
- `C:/workspace/fx/705/exchange-pc/src/utils/useOrderSizing.ts`、`C:/workspace/fx/705/exchange-frontend/src/utils/useOrderSizing.ts`：负 available 不再被当成快照读取失败；普通下单正余额/资金充足限制未删除。
- `C:/workspace/fx/705/exchange-backend/pom.xml`：Surefire 3.2.5，确保 JUnit 5 真正发现并执行测试。
- `C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/user/AssetEquityMySqlTest.java`、`C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/user/AssetEquityCarryTest.java`：仅兼容新增 effective_at 夹具列，未删断言。
- `C:/workspace/fx/705/.gitignore`：仅放行审阅过的增量迁移和无数据测试 schema。

没有重写交易引擎、正常强平、普通杠杆约束、收益统计 SQL、资金明细、分享、代理业绩或成交量口径。工作区其他注册/验证码/权益图表改动保留，不属于本次新增功能差异。

## 4. 迁移状态与原子性

迁移文件：`C:/workspace/fx/705/exchange-backend/src/main/resources/db/manual/705-manual-history-order.sql`。

- 添加 `contract_order.order_source`（旧单默认 USER）、两个默认关闭开关快照。
- 添加 `asset_history_1m.manual_adjustment`、`effective_at`，允许人工行 observed_at/quote_batch_id 为 NULL。
- 仅新增一张 `manual_order_record`：幂等主键、请求摘要、唯一订单、操作者、时区和证据。
- 在 `asset_history_migration` 记录 `705-manual-history-order`。真实 MySQL 集成初始化连续执行两次，未重复改余额/数据；旧订单仍为 USER。
- 浏览器 `manual_browser` 已迁移，基础四表结构来自当前原库无数据 DDL。原 `1090` **未迁移**。未修改既有 V001，未整库字符集转换。
- DDL 独立提交，不能由业务订单事务回滚。部署顺序必须是备份、迁移、代码、显式内测开关。

业务写入全部使用同一物理 JDBC connection：幂等锁，必要时 capture/rollup 命名锁，然后用户/CONTRACT 行锁和品种配置共享锁。订单、钱包增量及 row_version、四表、审计在一次 commit 中完成。异常 rollback 后释放命名锁。成功幂等查询优先于已失效预览；重启后重试仍返回原订单，同键不同内容拒绝。

行情读取在上述资金事务和全局锁之外。预览绑定操作者、用户参数、账户版本和品种版本；创建重新计算，不采用前端价格/利润。人工创建不调用正常开/平仓，也不冻结或返还保证金。

## 5. 自动化测试结果

以下是不同运行入口的实际数量，包含重叠回归类，**不能相加当成不重复总数**。最终对应运行全部 0 失败、0 错误、0 跳过；退出码均为 0。

| 运行 | 实际执行 | 证据 |
|---|---:|---|
| 新增纯计算 + 真实 MySQL 5.7 手动业务 | 29（计算 5、数据库 24） | `C:/workspace/fx/705/reports/manual-order-mysql.log` |
| 手动业务 + 正常交易独立 MySQL 库回归 | 29 + 41 | `C:/workspace/fx/705/reports/manual-order-mysql-normal.log` |
| 相关完整后端回归（含正常强平/资金/安全） | 78 | `C:/workspace/fx/705/reports/manual-order-full-regression.log` |
| 原权益 MySQL 专用入口 | 30 | `C:/workspace/fx/705/reports/manual-order-equity-mysql.log` |
| 实际 HTTP + SQL 后置断言 | 21 | `C:/workspace/fx/705/reports/manual-order-browser/http-acceptance.json` |

新增测试：
- `C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderCalculationTest.java`
- `C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderMySqlIT.java`
- `C:/workspace/fx/705/exchange-backend/src/test/resources/manual-order-schema.sql`（当前相关完整表结构，不含用户数据/秘密）。

### 已断言的关键合同

- A=1000/P0=100/P1=110/C=1/L=10/F=2：10 手保证金 100、费 20、毛利 100、净利 80、仓位 12%；120% 得 100 手/净利 800；目标净利 100 得 12.50 手、费 25、仓位 15%。做空、越普通杠杆、固定手数杠杆不乘毛利、步长差额、无解、0/负余额、NaN/Infinity/溢出均覆盖。
- 三种开关组合、非法历史单开、wallet 50 加净亏损 -100 得 -50、frozen 不变、无重复手续费/未扣保证金返还。
- 有效目标、NULL 修复、缺失目标、恰好 24 小时/超时、零/负基础、其他用户/basis/未来候选排除、零基础净亏损、同分钟多单、倒序补单。
- 4 线程同键只创建一次；同键不同内容、账户版本改变、篡改/过期预览、开关关闭和 USER/AGENT/ADMIN 拒绝。
- 订单后、钱包后、分钟后、父桶中、审计后的 5 个故障点全部回滚，锁释放后原键可重试。
- 创建在钱包已写但未提交处挂起，同时运行真实采集/归集和另一连接钱包增量：外部看不到半笔数据，正常增量不丢失，下一次真实采样读取 1110，不再叠加手动净利。
- 已 finalized 小时/4 小时/日、UTC 边界与跨日、正负修正、极值时间/样本数、人工分钟裁剪读取。小时 11:00–11:19=1000，后续=1100 得 O=1000/H=1100/L=1000/C=1100。稀疏跨日数据不补空白日历，水位不回退。
- 未结束分钟、正常延迟和下级水位未到时不提前产出父桶。重复采集遇人工行不覆盖、不双加、不污染真实正权益基线；不限时长 carryIn 回归仍通过。
- 存在 OPEN 的正常订单时仍允许创建手动 CLOSED 单，正常持仓及 frozen 原样保留。另在真实 MySQL 普通交易回归中执行真实 ContractOrderService 强平/清零与挂单冻结款保留测试，不创建永久虚拟余额。
- 10000 点事务通过；最近独立性能记录约 **492 ms**（包含预览和创建，一次隔离环境测量，不是生产 SLA）。10001 点明确拒绝；金额溢出无半笔落账。
- 实际 HTTP：匿名 401，普通用户/代理对 context、preview、create 均 403；伪造 source/price/profit、非法开关/未来/非分钟输入均 400；重启后幂等回放不增订单；真实普通 BTC 下单在 -50 余额时返回“合约资产余额不足”。

### 前端与构建

实际执行并退出 0：
- `node --experimental-strip-types C:/workspace/fx/705/exchange-pc/tests/contract.test.mjs`
- `node --experimental-strip-types C:/workspace/fx/705/exchange-frontend/tests/assetEquityHistory.test.mjs`
- `node --experimental-strip-types C:/workspace/fx/705/exchange-frontend/tests/assetPixelWindow.test.mjs`
- `node --experimental-strip-types C:/workspace/fx/705/exchange-frontend/tests/assetCarryForward.test.mjs`

这些入口使用实际断言脚本而非 JUnit 数量；输出分别保存在 `reports/manual-order-pc-regression.log`、`manual-order-assetEquityHistory.log`、`manual-order-assetPixelWindow.log`、`manual-order-assetCarryForward.log`。

后台/PC/移动端 `npm run build` 和 Java 8 API 兼容后端 package 均成功；日志分别为 `reports/manual-order-admin-build.log`、`manual-order-pc-build.log`、`manual-order-mobile-build.log`、`manual-order-java8-build.log`。前端现有 chunk 大小警告不构成测试通过凭据。

## 6. 浏览器与数据库交叉验收

使用新建专用用户真实登录后台、实际 PC 页面、移动页面。不是注入前端登录态，也不是 mock 页面。浏览器实际完成：ID/邮箱搜索、品种选择、UTC/Asia/Singapore 切换且保留绝对时间、滑块真实拖到 120%、输入 300% 扩展范围、NET 与手数驱动联动、两开关联动和重开默认关闭。

真实行情链路读取 BTCUSDT，2026-09-27 17:00/17:10 UTC 对应分钟 open：**84426 / 84392.01**，记录来源 binance。采用复制到隔离库的当前实际配置：每手 1000、每手手续费 30；杠杆 10000、手数 0.14，保证金 1181.964，毛亏损 -4758.60，手续费 4.20，净收益 **-4762.80**。120% 因 0.01 步长实际仓位 118.6164%，最终预览明确展示实际仓位。目标 -1000 反算 0.03 手实际净收益 -1020.60，差额 -20.60。

通过页面创建并刷新后的订单：

| 订单 | 钱包/历史 | CONTRACT.available | 历史影响 |
|---|---|---:|---|
| #86 | 关/关 | 1000 保持不变 | 不写历史 |
| #87 | 开/关 | -3762.80 | 三个预置历史点完全不变 |
| #88 | 开/开 | -8525.60 | 17:09=1000 不变；17:10 从 1000 变 -3762.80；17:59 从 1050 变 -3712.80 |

三笔均 CLOSED/MANUAL_TEST，open_time=17:00、close_time=17:10 UTC，创建时间为真实当前时刻。订单毛利和 fee 分列保存；最终 frozen=0、账户版本=2、审计=3。小时结果 O=1000/H=1000/L=-3762.80/C=-3712.80，source_count=3。浏览器夹具该时段 4h/day 尚未到期，保持 0 条，未提前生成；成熟 4h/day 重算另由确定性真实 MySQL 测试验证。

PC 和移动端刷新后均继续显示三笔手动订单、正确开平仓时间、价格、手续费 4.20、毛盈亏 -4758.60，以及真实负余额 -8525.60。移动端 390px 布局可读；普通下单按钮禁用且提示资金不足。数据库证据：
- `C:/workspace/fx/705/reports/manual-order-browser/after-wallet-only.txt`
- `C:/workspace/fx/705/reports/manual-order-browser/after-three-orders.txt`
- `C:/workspace/fx/705/reports/manual-order-browser/parent-audit.txt`

截图：
- `C:/workspace/fx/705/reports/manual-order-browser/admin-slider-120.png`
- `C:/workspace/fx/705/reports/manual-order-browser/admin-orders-persisted.png`
- `C:/workspace/fx/705/reports/manual-order-browser/pc-history-negative.png`
- `C:/workspace/fx/705/reports/manual-order-browser/mobile-history-negative.png`
- `C:/workspace/fx/705/reports/manual-order-browser/mobile-insufficient-funds.png`

## 7. 专用账号、失败修复与边界

保留的隔离账号均启用，未重置现有业务用户密码：
- 管理员 `manual-admin`，ID 6，super_admin。
- `manual-user@local.invalid`，ID 9000001，用于三笔页面订单与负余额。
- `manual-api@local.invalid`，ID 9000002，普通 API 权限/负余额测试。
- `manual-agent@local.invalid`，ID 9000003，代理权限测试。
- `manual-slider@local.invalid`，ID 9000004，正余额滑块预览，不另创建订单。

新生成的秘密仅存放在 `C:/workspace/fx/705/reports/manual-order-browser/secrets.json`，当前用户 ACL，Git 忽略。角色测试用户/代理/滑块用户使用该隔离 userPassword。现有任何凭据不在报告列出。退出/停止临时服务不会对原用户执行删除或改密。

曾发现并修复后复测的失败包括：SQL 占位数、真实配置 TINYINT JDBC 类型、实际行情字段 open_price、JDK 21 编译链接 `Math.multiplyExact(long,int)` 导致 Java 8 运行错误、UI 类型标注、PC 手动历史误显示浮动当前价，以及测试夹具缺 created_at。普通交易 MySQL 扩展回归首次因新空库默认 latin1 无法保存中文登录地区而失败；只把新建测试库默认字符集设为 utf8mb4，复跑 41 项全部通过，没有转换原库。

最终必需验收无失败/跳过项。未把全仓其他不相关测试宣称全部执行；未在真实用户资金或外部正式环境验收。确定性 MySQL 测试仅替换行情供应对象，不替换业务事务/SQL/归集代码；浏览器三单走正常真实历史行情链路，缺价另经真实 HTTP 验证拒绝。非固定汇率缺失有拒绝测试，不宣称所有外部品种都存在可用历史汇率。

明确限制：预览有效期 5 分钟、最多 1000 个内存预览；重启需重新预览，已成功幂等回放仍持久化可用。同步历史处理最多 10000 个分钟记录（包含边界日需要的记录），命名锁等待 2 秒，单条 SQL 超时 15 秒；超限/锁忙须处理后用原键重试。当前未结束平仓分钟在历史开启时等待结束。没有异步回填平台或永久虚拟权益层。

## 8. 可复跑命令

PowerShell，从项目根目录执行，每一步检查退出码：

```powershell
Set-Location -LiteralPath 'C:/workspace/fx/705'
# 全新一次性真实 MySQL，包含普通交易/强平的另一独立 MySQL 库。
& 'C:/workspace/fx/705/scripts/manual-order/Test-MySql.ps1' -NormalRules
if ($LASTEXITCODE -ne 0) { throw 'MySQL acceptance failed' }

mvn -B -f 'C:/workspace/fx/705/exchange-backend/pom.xml' '-Dmaven.compiler.release=8' '-Dtest=ManualOrderCalculationTest,MinimalFixRegressionTest,ExecutionQuoteTest,QuoteCurrencyConversionTest,AssetEquityValuationTest,AssetHistoryRollupTest,AssetHistoryTest,AssetEquityScheduleTest,AssetEquityCarryTest' test
if ($LASTEXITCODE -ne 0) { throw 'Regression failed' }
& 'C:/workspace/fx/705/scripts/asset-equity/Test-MySql.ps1'
if ($LASTEXITCODE -ne 0) { throw 'Existing equity MySQL tests failed' }

# 已保留浏览器环境的 HTTP 重放，不改变三笔订单金额。
python 'C:/workspace/fx/705/scripts/manual-order/verify_local.py'
if ($LASTEXITCODE -ne 0) { throw 'HTTP acceptance failed' }

# 只重建/启动身份标签核验通过的已保留隔离服务；不是验收测试。
& 'C:/workspace/fx/705/scripts/manual-order/Start-Local.ps1'
```

`Test-MySql.ps1` 未设置环境时会自行建立隔离容器；直接运行 ManualOrderMySqlIT 而缺少环境会失败，绝不静默跳过。`Start-Local.ps1` 不新建/覆盖业务数据库；MySQL 已停止导致 tmpfs 数据丢失时会拒绝，必须先向隔离实例恢复上述快照。浏览器可重复按照第 6 节操作新建表单；重新运行 HTTP 验证会使旧管理员浏览器会话失效，重新登录即可，不关闭单会话安全校验。

单独前端构建时必须设置 `$env:VITE_API_BASE_URL='/api'`，再执行各项目 npm run build。后端 jar 被隔离容器只读挂载，替换 jar 前先停该 backend，构建成功后再启动，避免运行中的类加载器读取被改写的 jar。

## 9. 回滚与清理

1. 先关闭手动入口服务端开关（取消 `MANUAL_ORDERS_ENABLED=true`），重启对应**隔离** backend。原业务环境默认未启用，无须为本次测试回滚余额。
2. 代码回滚只对照本轮文件备份/精确差异合并撤销本功能，不执行 reset --hard，不覆盖注册、权益等他人未提交工作。迁移列和审计可保留，旧代码可忽略；不要删除被订单引用的来源/审计。
3. 如需恢复测试数据，仅针对核验过标签和库名的隔离实例。已将 `before-browser.sql` 实际恢复到 `manual_restore_check` 演练；保留最终状态则使用 `after-browser.sql` 导入新隔离库。入账前快照包含旧测试密码哈希，恢复后应使用本地 `rotate.sql` 对专用用户恢复当前测试密码，不能套用到原业务用户。
4. 不用旧钱包快照覆盖发生后续真实交易的余额；经历强平后机械减 N 不是精确撤销。本版没有撤单 UI。
5. 当前保留六个 `exchange-705-manual-browser-*` 容器便于验收；自动化一次性容器已清理。停止 mysql 会丢失 tmpfs 内容，先确认最终快照。只可在 `docker inspect` 标签为 `exchange-manual-browser=true` 后按这些精确名称停止/删除，不执行原 compose 的 `down -v`，不删原持久化卷。
