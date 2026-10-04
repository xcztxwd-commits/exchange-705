# 财经事件日历验收报告（阶段 2）

验收日期：2026-10-03，Asia/Singapore。项目：C:/workspace/fx/705；main / 6b93d62b5a3e748162f9eaea02f851e9a188e30e。

## 1. 结论与交付范围

阶段 2 已完成后端、指定后台、持久化站内提醒和手机/PC Figma 设计及对接契约，满足工单要求的隔离验收。日历测试通过 25 项，前阶段深度及相关权限/行情回归通过 35 项，合计 60 项；不是全仓库测试通过声明。后台真实浏览器 42 项检查通过，覆盖七种宽度。

生产适配器匿名实测六个固定官方渠道，五个 HTTP 200 且业务/解析成功，BLS ICS 仍为 HTTP 403。后端与后台构建成功。没有部署生产，没有修改或构建 exchange-frontend、exchange-pc，没有执行交易、修改资金或购买/注册外部服务。用户端源码接入尚未进行。

- [唯一实际接口合同](C:/workspace/fx/705/docs/calendar-contract.md)
- [增量迁移](C:/workspace/fx/705/exchange-backend/src/main/resources/db/migration/V2026100301__economic_calendar.sql)
- [最终测试汇总](C:/workspace/fx/705/reports/calendar-20261003/test-summary.json)
- [浏览器检查及真实接口状态](C:/workspace/fx/705/reports/calendar-20261003/browser-qa.json)
- [来源匿名实测](C:/workspace/fx/705/reports/calendar-20261003/source-live-adapter.json)
- [阶段 1 保留检查](C:/workspace/fx/705/reports/calendar-20261003/stage1-preservation.json)
- [源码与范围审计](C:/workspace/fx/705/reports/calendar-20261003/scope-and-artifacts.json)

先行接口/指标合同存档时间为 13:44，Figma 首次实际写入为 13:46，领域目录建立于 13:48，之后才实现领域代码。实际合同随实现更新，先行原稿保留在 C:/workspace/fx/705/reports/calendar-20261003/calendar-design-first.md；Figma 首次写入证据在 figma-write.json。

新增领域代码位于 C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/insights/；管理员入口为 C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminCalendarController.java；后台为 C:/workspace/fx/705/exchange-admin/src/views/CalendarManagement.vue。只在四个现有共享文件增量集成：.gitignore 的精确 SQL 例外、后台路由、GET 日历公开读取规则、日历菜单及动作权限。未改资金、订单、结算与用户授权规则。

阶段 1 的 18 个文件 SHA-256 保持一致。深度合同、源实现与配置均未重新修改；不使用 reset、checkout 或整体覆盖回退。

## 2. 来源实测、缓存与真实覆盖

两类证据分别保留，不互相冒充：

1. 05:39 UTC 的匿名真实 HTTP 响应和原始文件，在 source-http.json、同目录 *.raw 及 C:/workspace/fx/705/exchange-backend/src/test/resources/calendar-official-20261003/。解析器、MVC 集成和浏览器中的 upstream fetch 重放这些已捕获响应；重放不是实时联调。
2. 最终实际 CalendarSync Java HTTP 适配器，在 06:36 UTC 对六个固定官方 HTTPS 来源各请求一次，真实执行解析、SQL 缓存及事件关联。source-live-adapter.json 保存逐步状态，最后一个数组是六源完成后快照；api-live-source-events.json 是直接从真实服务与隔离数据库读取的列表，不把它称为 HTTP 请求回包或生产数据。

| 来源 | 最终真实 HTTP / 状态 | 验证含义 |
|---|---|---|
| BLS 官方 ICS | 403 / BLOCKED | 明确失败、12 小时退避；没有绕过 |
| BEA 官方 ICS | 200 / OK | 原始 119 条中，仅纳入支持的全国 GDP/PCE 日程；地区 GDP 未错贴 |
| Fed FOMC 日程 HTML | 200 / OK | 真实会议日期，未确认时刻保持 DATE |
| BEA RSS | 200 / OK | 真实 GDP 结构化值及单位；不拿收入/消费增速替代核心 PCE |
| Fed 货币政策 RSS | 200 / OK | 确认已发生公告/纪要时刻；不混入贴现率董事会公告 |
| BLS V1 API | 200 / OK | 三系列批量，验证 REQUEST_SUCCEEDED 后映射月度指标 |

首次 Java BEA RSS 请求返回 406，保留 source-live-adapter-initial.json/log。根因是 Accept 内容协商；使用 RSS/XML 类型后单源复测及最终六源复测均 200。没有使用代理、伪装认证、绕过 403 或静默替换供应商。对应证据为 source-live-adapter-BEA_DATA.json/log 和最终日志。

实际自动导入的已发布 coverage 为核心 PCE 日程 23、GDP 日程 23、FOMC 决定 24、纪要 13，跨 2025–2027 年共 83 个身份；2026 日期窗口返回 39 条。支持七种 metric 不等于七种都已自动导入。BLS 无可用自动日程，因此不能从实际统计 API 制造发布日期；就业、失业率、CPI 使用官方年度日程核验后人工导入，发布并显式解锁后才与 BLS 实际序列关联。

BLS 来源仍显示 403；不会因为人工材料导入成功变为自动采集成功。日程从 [BLS 官方 2026 年日程](https://www.bls.gov/schedule/2026/home.htm)核验：2026-10-02 Employment Situation 对应 2026-09，2026-09-11 CPI 对应 2026-08，均为官方 08:30 America/New_York。

使用范围仅为统计事实、必要标题及来源链接。遵守 [BLS 使用说明](https://www.bls.gov/bls/linksite.htm)、[BEA 公开资料说明](https://www.bea.gov/help/faq/147)和[美联储声明](https://www.federalreserve.gov/disclaimer.htm)的归属及版权例外，不采集照片、徽标、全文或第三方受保护内容，不声称政府背书。未引入 Forex Factory 或许可未确认的聚合日历。

## 3. 实际值与 API 样本

以下数值来自实际捕获的官方 API/RSS，经过真实 MVC、业务服务和隔离 SQL 关联后保存。重放处理时间在 sourceAsOf 中，真实原始响应时间另见 source-http.json。

| 指标 / 统计期 | actual / previous | 正确口径与样本 |
|---|---|---|
| 非农 / 2026-09 | 29 / 133 | 季调千人月差，不是就业总水平；api-real-nfp.json |
| 失业率 / 2026-09 | 4.2 / 4.1 | 季调百分比水平；api-real-unemployment.json |
| CPI / 2026-08 | 3.4 / 3.4 | 非季调 12 个月同比，由指数派生并按一位小数四舍五入；api-real-cpi.json |
| GDP / 2026-Q2 THIRD | 2.2 / 2.5 | 实际季度环比年化百分比，前值是 RSS 前季度，不是本季度第二估计；api-real-gdp.json |
| 核心 PCE / 2026-08 | 0.2 / 未知 | 季调价格指数环比；人工核验官方发布材料，不称 RSS 自动提供 |

CPI 对应当前/去年同月指数 334.980/323.976；previous 对应前月及其去年同月 333.918/323.048。原指数不作为同比百分比直接显示。

核心 PCE 0.2 的人工依据为 [BEA 2026 年 8 月个人收入与支出发布](https://www.bea.gov/news/2026/personal-income-and-outlays-august-2026)。来源不足的 previous 保留 null；未来日程的 actual 也为 null。RSS 未提供的核心 PCE 不自动补零。

所有 forecast 为 null / NOT_PROVIDED；管理员估计单独标 ADMIN_ESTIMATE。BLS 值基于 LATEST_TIME_SERIES，originalReleaseValue=null、historicalInitialKnown=false，不声称未经修订历史初值。原 GDP Initial Estimate 特殊阶段保留 INITIAL_ESTIMATE，不改成 ADVANCE。

测试中出现的非农修订 30、2030 年提醒/窗口、时间移动以及 -0.1 管理员估计，均为明确 TEST_ONLY 的隔离场景，不是新观察到的官方值。真实 NFP 样本保留修订测试之前的 29/133。最后修正测试助手按 UTF-8 字节读取 MockMvc JSON，增加中文标题断言；避免把证据解码乱码写回隔离数据库。重新运行了完整选定测试组，实际 UTF-8 样本与浏览器显示均正确。

## 4. 后端、迁移和提醒验收

| 测试组 | 通过 | 默认跳过 |
|---|---:|---:|
| CalendarParserTest | 9 | 0 |
| CalendarIntegrationTest | 15 | 2 |
| CalendarMigrationTest | 1 | 0 |
| 深度及行情/权限相关选定回归 | 35 | 1 |
| 合计 | 60 | 3 |

零 failure、零 error。日历两个跳过项分别为显式开启的真实上游探测与浏览器保活窗口，二者另行实际运行成功；深度的可选浏览器保活默认跳过，不冒充此次重新实测深度上游。

解析覆盖 ICS 折行/时区/取消/序列、官方 BEA RSS/GDP 阶段、Fed 实际日期与时刻、BLS 三系列的单位/统计期/缺值；禁用 XXE/DTD，限制响应与导入大小。不支持 RRULE/RDATE 时明确拒绝，不猜测周期。

集成覆盖：
- 真实官方材料草稿私有 404、发布公开、纠正与 rowVersion 409、审计操作者和前后值。
- 幂等导入、同 ID 延期、官方取消、人工覆盖锁定、重复缓存不制造新审计；已确认 RSS 公布时刻不被低精度日程降级。
- from/to 日期闭区间、月底窗口、分页、过期 SCHEDULED 的 AWAITING_RELEASE 筛选、跨日时区、夏令时缺口、错误单位、空 metric 与无效 JSON 的 400。
- 来源状态、预算、403 退避、缓存不清空；公共 GET 的 429 预算，并验证 GET 不触发上游。
- 真实 JWT、角色及动作权限、Host 解析、跨租户、未发布、当前环境边界；纯查看角色写入 403。
- 在 REAL 隔离服务的数据库中额外写入 DEMO 行，证明不能读出或投递。没有部署或测试独立物理 DEMO 服务。

提醒不是前端 ref：设置/取消接口持久化当前用户的 tenant/environment/user/event/lead，使用既有站内信 InboxLetter。当前用户 GET /api/user/support/unified-inbox 可读取已投递通知，另一用户不能读取；证据在 reminder-inbox.json。

同事件重复设置、重复任务及并发任务仅投递一次；取消、发布时间调整、未发布、DATE/UNKNOWN 精度、到期、禁用用户、关闭 inbox 功能与非活跃租户均测试。已投递回执不因重新设置或改期被清空。每次提醒与 InboxLetter 在同一事务提交，没有放宽原授权，也没有伪造 HTTP 会话身份。

restart-acceptance.json 记录三个不同 Spring startup 时间：在同一 JVM 内关闭并重建服务上下文两次，保留同一隔离 H2。首次重启恢复未投递设置，第二次重启读取回执后不重复发，总通知为 1。该证据是实际服务上下文重启，不是宿主机/数据库进程重启或生产演练。

迁移仅新增五张表、索引及唯一键。已实际执行 H2 MySQL 兼容 SQL 并验证约束；没有运行真实 MySQL 5.7/8.0 的部署迁移。项目未新增 Flyway 或自动 SQL 执行器，迁移需按原审核流程执行，不改旧收件箱/交易/资金表。

后端 package 成功，Java 8 字节码 major=52；后台 npm run build 和 npm run test:tenant 成功（五组 PASS 输出）。既有 Sass legacy API/chunk 大小警告保留，不当作日历运行错误。证据为 backend-package.log、admin-build.log、admin-tenant-tests.log、final-regression-tests.log。

## 5. 后台 QA 报告

### Findings（已修复）

1. 320px 视口曾被卡片最小宽度和分页尺寸撑出，触控区不足 44px。将本模块 min-width 调整为可收缩，表格使用原生水平滚动，按钮、分页、核验标签及弹窗关闭按钮达到 44px；未重做既有全站侧栏。
2. 仅依赖 ElForm disabled 时，数值 ElInput 的显式 disabled=false 会覆盖只读权限。实际值、前值和管理员估计现在显式合并 calendar:edit 权限；纯查看角色界面不可编辑，服务端仍独立返回 403。
3. 日历 320/375 副本的五项底栏曾沿用固定 390px 子项，导致“我的”裁切。仅调整日历副本的容器与 Auto Layout 填充；保留原 93:2455 A 版组件、箭头及反应，无全局修改。320 五项均在 x=12..308 内，最小宽度 59.2，字体保持 Noto Sans SC 12px；430 使用居中的原 390px 底栏。figma-nav-fix.json 和修正后截图为证据。

自动化过程中还修复了两类脚本问题：点击隐藏原生 checkbox 改为点击可见核验标签；对默认灰白按钮的错误绿色预期改为校验实际绿色 hover 并等动画完成。保留 browser-qa-attempt1..4.json；最终 42 项全部通过，不把失败尝试隐藏成一次成功。

### Environment

- URL：https://admin.mt705.test/calendar；实际 Vite 构建预览与随机端口隔离 Spring 后端。
- Browser 分类：Absent；原因：Browser plugin not available。使用已有 Playwright 1.62.1 与 Edge headless，没有安装依赖。
- 视口宽度：320、375、390、430、1280、1440、1920。
- 域名只做传输转发，Host/Origin 与真实 JWT 保持；API 响应体及状态码来自实际服务器和 SQL，没有 JSON 假接口。
- 浏览器窗口中官方源响应使用捕获文件重放；实时匿名上游验证单独记录于第 2 节。
- 临时 browser-access.json 已删除，测试/预览辅助服务已停止，无真实用户凭证保留。

### Changes Verified / Checks

| 检查 | 结果 | 实际证据 |
|---|---|---|
| 页面身份 | PASS | URL /calendar，标题 Exchange system |
| 非空页面 | PASS | 当前覆盖、官方状态与实际事件表 |
| 无框架错误覆盖层 | PASS | 正式构建预览，无 vite-error-overlay |
| Console 健康 | PASS | console=[]、pageErrors=[] |
| 截图 | PASS | 已保存桌面、390px、只读弹窗及无权限状态，实际查看 |
| 操作闭环 | PASS | 真实导入/保存/发布/重载/取消与审计、权限对照 |

### Interaction Loop

查询覆盖及来源健康，显示 BLS BLOCKED/403；未勾选核验时导入禁用；点击可见核验标签后导入真实 NFP 材料，新增为草稿且公共详情 404。填写核验理由、长中文标题及明确测试管理员估计，保存、发布、重新加载，实际数据库保持更改且公共详情 200 / forecast=null。查看真实前后审计，再在隔离数据库取消并重载验证。只读角色没有修改动作，数值字段禁用且强行写入 403；无菜单角色进入 forbidden。

七种视口分别运行查询、检查本模块边界与新控件高度。320px 下既有侧栏折叠后模块 left=84、right=300、width=216，表格列可水平滚动到末端；不是裁掉无法访问的列。长标题正常换行；键盘可聚焦查询；只读弹窗在 390px 内。

### Commands / APIs

以下只针对隔离测试，不启用生产：

    # C:/workspace/fx/705/exchange-backend
    C:/Environment/Maven/3.9.9/bin/mvn.cmd -q "-Dtest=CalendarParserTest,CalendarMigrationTest,CalendarIntegrationTest,MarketDepthTest,DepthStreamTest,DepthPushTest,MarketPushTest,ExchangeQuoteTest,ExchangeConnectionTest,AdminPermissionIntegrationTest,MarketDepthIntegrationTest" "-Dmarket.depth.enabled=false" test
    C:/Environment/Maven/3.9.9/bin/mvn.cmd -q -DskipTests package

    # C:/workspace/fx/705/exchange-admin
    npm run build
    npm run test:tenant

真实上游为 CalendarIntegrationTest#actualFixedOfficialAdapterAnonymousHttpProbe，显式 "-Dcalendar.live=true"；请求受官方配额限制，不应循环运行。浏览器为 CalendarIntegrationTest#browserAcceptanceWindow，显式 "-Dcalendar.browserHold=true"，另起构建预览 localhost:5188 后执行 C:/workspace/fx/705/reports/calendar-20261003/browser-qa.mjs。只有保活窗口可产生临时测试 JWT，脚本结束自动删除凭证文件。脚本关键序列为 chromium.launch(msedge)、真实 API 传输转发、goto、fill/click、reload、状态断言与截图。

建议后续可安装 Browser 插件以便在应用内读取 DOM、Console 和操作截图；本轮未安装。

## 6. Figma 可核实交付

同文件 v4V9L597ML6EhPMLi3kW8O，旧完整页面未删除。新增 EventCard 201:356 是可编辑文本属性组件，按钮复用 3:46；字体 Noto Sans SC，无完整 UI 图片层或扁平截图代替设计。

| 视口 / 内容 | 实际节点 |
|---|---|
| 手机 320 | [201:452](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-452) |
| 手机 375 | [201:590](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-590) |
| 手机 390 原日历 | [37:2373](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=37-2373) |
| 手机 430 | [201:682](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-682) |
| PC 1280 | [201:868](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-868) |
| PC 1440 原日历 | [55:2762](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=55-2762) |
| PC 1920 | [201:962](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-962) |
| 12 个状态与数字压力 | [201:1032](https://www.figma.com/design/v4V9L597ML6EhPMLi3kW8O?node-id=201-1032) |

主页 170:6335、探索 91:2280、PC 总览 48:547 使用同一核心 PCE 2026-09 事件 ID 14470937-5c28-3a64-bb16-e40208b32661，保留上下文并实际设置 ON_CLICK / NAVIGATE 至相应日历详情。不是只有文字名称的假链接。该日程为 2026-10-29 UTC12:30、SGT20:30。

数值发布与 FOMC 公告分开；未来实际值为空，预期来源未提供；政策仅 DATE 时不显示假 UTC 时刻。状态包含待公布、缺預期、提醒设置/取消、延期、取消、GDP 修订、来源失效、加载、空态、无权限、未发布及已投递恢复；极大/极小/零/负值压力样例明确是设计 TEST 状态，不能称真实统计。GDP 修订卡使用实际 2026-Q2 THIRD 的 f6cf3a4d-59ba-3aa0-b0d5-2880d2e3dd23，2.2/前季度2.5，不混用核心 PCE 身份。

七种根页面实际回读为可编辑层与实例，无图片节点、正文水平溢出，字体一致。底栏五项仍为主页、订单、交易、探索、我的；原 A 版组件和事件反应不改，只有日历适配副本布局被修复。未重新渲染动画视频，不把静止截图称为动效验收。

旧内容隐藏备份为 201:365、201:774；底栏副本布局备份为 208:876、208:926、208:976。证据为 figma-write.json、figma-final.json、figma-nav-fix.json，及外部截图目录。

## 7. 配置、运行与回滚

实际配置和完整 API 细节以唯一合同为准。本工单未修改现有 application.yml。

    calendar.sync.enabled=false
    calendar.reminders.enabled=true
    calendar.job-ms=60000
    calendar.initial-delay-ms=60000

现有 Spring 调度注册 CalendarJobs，每轮完成后 60 秒再执行。集中取数及提醒为独立开关。默认采集关闭，不把测试文件当运行时回退；新部署未取数时显示真实空覆盖和 NEVER_FETCHED。

BLS 无注册 V1 的 [官方配额](https://www.bls.gov/developers/api_faqs.htm)为 25 次/日，系统保守分配 20 次/UTC 日，三系列同一 POST，最短六小时；其他来源各 24 次/日、最短一小时。持久化预算、60 秒租约、条件请求/304、403 最少 12 小时退避及 429 Retry-After，不按每用户刷新取数。连接 4 秒、单次读 8 秒、读取循环检查 12 秒预算/2MB，禁止任意 URL 抓取及重定向。

提醒扫描使用 TenantJobRunner、既有 inbox 授权及真实站内信；每租户每轮至多 100 个 24 小时内未来事件、每事件 200 个用户。高负载/网络/停机影响提前时效，不保证交易级精确计时或多节点大规模吞吐。跨独立 REAL/DEMO 部署若共用出口，应集中一处取数；本地配额不是跨所有独立数据库的出口协调器。

回滚顺序：
1. 关闭 calendar.sync.enabled 与 calendar.reminders.enabled，停止相关任务。
2. 保存并保留五表、来源更新、审计与投递回执；不执行 DROP 或删除真实数据。
3. 依据 C:/workspace/fx/705/rollback/calendar-20261003/ 备份只回撤日历增量。共享文件若被后续阶段修改，先核对新版本，不整体恢复旧文件。
4. Figma 仅在确认不覆盖后续设计时恢复上述隐藏备份。不改全局 A 版组件。
5. 再验证原深度/权限合同；不使用 git reset、checkout --，不触碰用户端构建。

## 8. 仍未验证或未提供的事项

- BLS ICS 403：未接通自动日程，没有绕过；人工官方材料功能已验收。
- BEA RSS 缺核心 PCE actual：合法材料补录已验收，不能称自动核心 PCE 上游完成。
- 市场共识预期没有合法来源：forecast=null；没有全球覆盖、完整历史初值承诺。
- BEA 更细历史 API Key 未提供，本期未接入；未自动申请账号/Key，现有无 Key 六源及核心逻辑不依赖它。
- 迁移只做隔离 H2 MySQL 兼容验证，真实 MySQL 5.7/8.0 部署、物理 DEMO 部署、长期提醒 SLA、多节点压力及 Safari/原生手机浏览器未验证。
- 后台和 backend 已实现，手机/PC 只有 Figma 与对接合同，生产未部署。
- 前阶段事实保持：35 项选定回归通过；Binance 现货连续 65,168ms、明确另选 OKX 现货/永续连续 65,110ms；Binance 永续目录/REST 超时，真实 WebSocket 未尝试、未验证。再发布许可未确认，无隐藏回退、无生产及用户端接入。详情仍以 C:/workspace/fx/705/docs/market-depth-acceptance.md 为准。

## 9. Screenshots

以下是实际 Figma 或真实后台浏览器输出，保存在仓库外。后台为隔离数据库；Figma 不是用户端已接入截图。发布画面中的 -0.1 为明确测试管理员估计，取消画面只反映隔离测试，不是官方取消。

![Figma 手机 320（五项底栏修正后）](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/figma-mobile-320.png)
![Figma PC 1280](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/figma-pc-1280.png)
![Figma 全状态](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/figma-states-final.png)
![后台保存并发布](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/admin-calendar-published.png)
![后台 390](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/admin-calendar-390.png)
![只读数值禁用 390](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/admin-calendar-readonly-390.png)
![无菜单权限](C:/Users/徐乾妖/.codex/visualizations/2026/10/03/01a10042-6ae6-7cf0-923d-6c0abf221cb2/calendar-stage2/admin-calendar-no-permission.png)
