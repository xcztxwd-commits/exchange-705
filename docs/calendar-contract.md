# 财经事件日历唯一合同（阶段 2，实际实现）

日期：2026-10-03，Asia/Singapore。实现范围为 backend/admin；手机和 PC 仅更新 Figma，不代表用户端已接入。上一阶段深度实现与验收事实保持不变。

## 数据与身份

- 首期只覆盖美国高重要性：非农月度变动、失业率、CPI 同比、核心 PCE 环比、GDP 实际季度年化增速、常规 FOMC 决定/纪要。
- 稳定 `eventId` 使用 Java `UUID.nameUUIDFromBytes(agency|metric|statisticalPeriod|releaseStage)`；未知统计期的 ICS 使用官方 UID 作为统计身份，不从中文标题/显示时刻生成。同一期 GDP ADVANCE/SECOND/THIRD 是不同事件；2025-Q3 官方特殊“Initial Estimate”保留 INITIAL_ESTIMATE，不能改称 ADVANCE。GDP 允许 UPDATED，其他月度统计使用 INITIAL，数值修订不制造新身份；改时间不改 ID。FOMC 常规会议按官方会议月份关联，跨月会议按开始月份，不从固定月份数组合成会议。
- `calendar_event` 持久化当前租户、服务环境、索引字段与完整 JSON；`calendar_audit` 持久化操作者、原因、前后 JSON、时间。`calendar_reminder` 保存用户设置及投递回执；全局官方 `calendar_source` 保存响应/解析缓存、HTTP/业务状态、最后成功、下次允许请求、预算及同步租约；`calendar_source_update` 记录每次尝试的 HTTP 状态、去 BOM 后缓存文本的 SHA-256 或错误。共五张增量表；官方取数不按用户轮询。
- 环境由已有 SimulationEnvironment 决定，沿用项目 REAL/DEMO 独立数据库架构，新增记录仍显式带 environment；租户来自已验证 TenantContext，不接受客户端指定租户、用户或环境。
- 官方更新优先于未锁定记录。核验人工补录/纠正锁定整条记录；自动同步记录 SKIPPED_MANUAL_LOCK，不静默替换；按官方来源通道的内容指纹去重，反复读取同一缓存不重复制造审计。确认实际公布的 RSS 时刻不会被更低精度日程覆盖；单个来源失败不清空既有事实。管理员带 rowVersion 显式解除锁定后才恢复自动更新。
- 取消不能通过“本次源文件没出现”推断；只接受官方 STATUS:CANCELLED 或经核验人工取消。延期保存原事件身份与新时间；日期未知时不生成确定时刻。

## 指标映射

| metric | 官方字段/系列 | actual / previous | 单位/定义 |
|---|---|---|---|
| NFP_CHANGE | BLS CES0000000001 | 对应月份就业水平差；前值为上月水平差 | THOUSAND_PERSONS，季调，月度绝对变动；不把就业总水平当新增就业 |
| UNEMPLOYMENT_RATE | BLS LNS14000000 | 对应月及前月 | PERCENT，季调，失业率水平 |
| CPI_YOY | BLS CUUR0000SA0 | 指数相对 12 个月前的百分比变化；前值为前月同比 | PERCENT，非季调，1982–84=100 原始指数派生同比；不是环比 |
| CORE_PCE_MOM | BEA Personal Income and Outlays 日程 | RSS 不提供核心价格指标时为空；可核验官方发布材料补录 | PERCENT，季调，剔除食品与能源的价格指数环比；不是个人收入或 PCE 支出增速 |
| GDP_QOQ_ANNUALIZED | BEA GDP RSS data/main/current/percentChange | 同统计期/发布阶段；previous 为 RSS 前季度 | PERCENT，季调、实际 GDP、季度环比年化；校验 changeUnit=PCT、发布阶段 |
| FOMC_DECISION / FOMC_MINUTES | 官方会议日程/货币政策 RSS | 政策公告，无数字则 actual/previous=null | NONE，不将公告伪装成利率数值 |

BLS API 为当前可用时间序列，actualBasis=LATEST_TIME_SERIES；不是当时未经修订初值。`originalReleaseValue=null`、`historicalInitialKnown=false`。sourceAsOf 是当前事实版本的取数/处理时间；相同内容再次成功读取不会只为更新时间制造新版本，当前采集新鲜度另查 sources.lastSuccess/stale。保留脚注、数据更新时间；发现值变化记修订版本，但不臆测此前未保存的历史修订。BEA RSS 的 previous 不是同季度上次 GDP 估计。

`forecast` 永远 null，`forecastStatus=NOT_PROVIDED`，显示“— / 来源未提供”。管理员估计用独立 `adminEstimate`、`estimateKind=ADMIN_ESTIMATE`、说明字段，不混入 official/consensus。

时间字段：`releaseAt` 为 UTC ISO 字符串，仅 MINUTE 精度有值；DATE 精度使用 `releaseDate` 与 `sourceTimezone`，releaseAt=null；UNKNOWN 可完全无日期。统计期与发布时间分开。FOMC 日程未给时刻则 DATE，只有 RSS 或合法材料确认时刻才升级 MINUTE；不按惯例填 14:00。

## HTTP

正常响应为现有直接 JSON；错误沿用 HTTP 400/401/403/404/409/429 和 success=false/message。数字以十进制字符串返回，不用随机、0 或 AI 填补未知。

- GET `/api/insights/calendar`：from/to 为源日历日期，闭区间，默认当天至未来 30 日，最长 366 日；country=US、importance=HIGH、metric、status、page=0..10000、size=1..100。稳定按 releaseDate/releaseAt/eventId 排序。返回 content/number/size/totalElements/totalPages、coverage、sources、asOf、environment。国家未覆盖返回空且 coverage 仍为 US，不承诺全球。coverage.metrics 是支持类型，persistedCoverage 是本租户本环境真实已发布数量/日期范围，不能把支持类型当已导入数量。AWAITING_RELEASE 筛选包含已到时但未收到 actual 的 SCHEDULED；SCHEDULED 筛选排除已到时记录。UNKNOWN 无日期事件只能通过详情读取，不塞入日期窗口。
- GET `/api/insights/calendar/{eventId}`：只读取本租户当前环境已发布事件；取消/延期仍可解释。未发布/其他租户/环境返回 404。
- GET `/api/insights/calendar/{eventId}.ics`：单个合法事件导出，UID 使用 eventId；系统日历与持久化站内提醒不同。无日期返回409；DATE 导出全日 DTSTART;VALUE=DATE + TENTATIVE，不填假时刻。UTF-8 折行每行最多75 octets，标题转义逗号/分号/反斜线。取消导出 CANCELLED。
- GET/PUT/DELETE `/api/user/calendar/reminders/{eventId}`：只允许当前 ROLE_USER；PUT `{leadMinutes:5|15|30|60|1440,timezone:合法 IANA 时区}`，缺省15分钟、Asia/Singapore。DELETE 带相同 leadMinutes（默认15）；成功空体 HTTP200，不提供他人 userId 参数。GET `/api/user/calendar/reminders` 或 `/{eventId}` 返回当前用户数组，包括 deliveredAt/deliveredReleaseAt/letterId、当前事件精度/时间，以及 PENDING/CANCELLED/DELIVERED/EVENT_CANCELLED/UNPUBLISHED/TIME_UNCONFIRMED/EXPIRED/EVENT_UNAVAILABLE。每个用户最多500条提醒；重复设置保持原投递回执。
- GET `/api/admin/insights/calendar`、`/{eventId}`、`/sources`、`/{eventId}/audit`、`/sources/{sourceId}/history`：需 calendar:view；审计/来源历史各50条分页。
- POST `/api/admin/insights/calendar/import`：需 calendar:import。支持官方 ICS 原文及 VERIFIED_JSON 材料，内容最多2,000,000 UTF-8 bytes，ICS最多2000事件，VERIFIED_JSON为1..200条数组，verified=true、官方来源、材料说明；服务端记录操作者，新增事件为未发布草稿；已有事件的发布状态保留，核验覆盖仍需理由和审计。不会请求管理员提供的 material/sourceUrl 链接。人工导入不称自动联调。
- PUT `/api/admin/insights/calendar/{eventId}`：需 calendar:edit；带 rowVersion、reason、完整核验字段，校验指标、单位、时间精度、状态；锁定覆盖，不允许更换身份。
- POST `/api/admin/insights/calendar/{eventId}/publication`：需 calendar:publish；带 rowVersion、published、status、reason；取消停止提醒。
- POST `/api/admin/insights/calendar/sources/{sourceId}/sync`：需 calendar:sync；仅固定官方渠道，缓存/退避/配额不可由请求绕过；GET 不发起外部请求。

## 采集与提醒

仅 BLS ICS/API、BEA ICS/RSS、Fed 官方会议 HTML/RSS 六个固定 HTTPS 来源；不提供任意 URL 代理。不自动注册/购买，默认不启动采集；启用 `calendar.sync.enabled=true` 后后台集中低频执行。[BLS 官方 FAQ](https://www.bls.gov/developers/api_faqs.htm)列出 V1 无注册25次/日；系统保守分配20次/日，三系列一个 POST，最短 6 小时；其他渠道最短1小时、各24次/UTC日。连接4秒、单次读8秒，读取过程中检查12秒预算、响应2 MB上限；禁重定向。支持 ETag/Last-Modified/304 复用已校验缓存，Retry-After、指数退避1..24小时，403最少12小时；持久化同步租约60秒防重复取数。管理员点击不能绕过配额/退避。无注册预算在单一生产环境内统一；同一出口多个独立 REAL/DEMO 部署应集中启用一处取数，20次预算不是跨数据库/外部程序的全出口配额协调器。公共GET限每节点每租户600次/分钟，最多256个租户计数；不是分布式网关限流。XXE/DTD 禁用，非数值与不匹配统计期不会错贴 actual。

站内提醒复用 InboxLetter 与统一站内信列表。需已有 support.settings.inboxEnabled 和租户 inbox 功能开启；关闭时拒绝设置且任务不投递。reminder 唯一键为 tenant/environment/user/event/lead；事务锁定事件、当前收件人和提醒，InboxLetter 写入及 deliveredAt 同事务提交，重启/多次任务只发一次。时间改变以当前事件重新计算截止点；取消、未发布、日期精度、过期不发送。已经投递的提醒不因重设/改期再发，未投递提醒可取消/恢复。数据库架构与 environment 双重检查；隔离测试额外写入DEMO行，REAL不读不投递。后台投递已保存的同意，不伪造HTTP身份，也不调用要求即时Bearer的模拟新增业务接口；设置提醒仍调用既有 requireNewBusiness("inbox")，没有改变既有授权规则。每次每租户最多100个未来24小时内事件、每事件200个待投递用户；超过此上限需要分批扩容，不承诺高容量送达SLA。

## UI 先行设计与验收

复用 Figma v4V9L597ML6EhPMLi3kW8O 既有绿色 tokens、Noto Sans SC、五项导航及 A 版实例。首页/探索/详情同 eventId。数字发布与公告分开；缺预期、待公布、延期、取消、修订、来源失效、提醒设置/取消、加载/空/无权限等均有文字状态。320/375/390/430 与 1280/1440/1920 有真实可编辑适配设计。未来事件实际值为空；原有静态 162K/90K/4.1% 示例移除，不冒充新接口真实值。

验收分开记录：实现、隔离运行、上游实测、手动核验材料、Figma 可编辑层与截图。BLS ICS 403 是失败；BEA 核心 PCE RSS 不提供是缺字段；BEA 历史 API Key 未提供且不需要替代 RSS 中真实 GDP。未部署生产、未接用户端代码。

## 配置、迁移与运行

不修改上一阶段 `application.yml`。现有 Spring `@EnableScheduling` 注册 CalendarJobs，默认每次执行完成后60秒再次执行；默认首轮等待60秒。采集与提醒独立开关：

```properties
# 外部配置示例；本工单没有对生产启用。
calendar.sync.enabled=false
calendar.reminders.enabled=true
calendar.job-ms=60000
calendar.initial-delay-ms=60000
```

按现有 Spring 配置加载外部 properties 或显式环境变量；不要把测试源文件装成运行时回退。默认没有日历数据，GET显示空coverage与NEVER_FETCHED，不预置测试月份。启用自动采集时，每轮先按全局持久化窗口尝试六来源，再通过 TenantJobRunner 将真实缓存导入各活跃租户；提醒通过同一任务按租户扫描。调度是尽力提前提醒，不是交易策略或精确计时保证；源网络超时、任务量和节点停机可能影响时效。

迁移：`C:/workspace/fx/705/exchange-backend/src/main/resources/db/migration/V2026100301__economic_calendar.sql`。仅新增五表及tenant/environment/event/lead唯一键，MySQL InnoDB/utf8mb4，不修改资金、订单、收件箱旧表。项目没有新增Flyway或自动执行迁移；按已有审核部署流程执行一次，已存在由ddl-auto创建的表必须先比对结构，不盲目重复CREATE。此次只在独立H2 MySQL兼容模式执行迁移并校验唯一键，不声称真实MySQL5.7/8.0部署成功。

后台菜单由现有权限bootstrap加入 calendar 与import/edit/publish/sync动作，不给普通角色自动授权。路由 `C:/workspace/fx/705/exchange-admin/src/router/index.ts` 的 `/calendar` 为懒加载管理页。站内信从现有 GET `/api/user/support/unified-inbox` 读取；不新增短信、邮件、系统Push。

回滚前关闭两个calendar开关并停任务，保留新表、来源历史、审计与投递回执。原共享文件位于 `C:/workspace/fx/705/rollback/calendar-20261003/`，包括 `.gitignore`、router、SecurityConfig、admin-permissions；只回撤calendar增量且先检查后续工单是否又修改了这些文件，不使用git reset/checkout或删除数据库。Figma旧日历内容已保存在隐藏备份201:365、201:774；仅在确认不覆盖后续设计后恢复本模块。详情及证据见 `C:/workspace/fx/705/docs/calendar-acceptance.md`。

## 来源展示规则

本实现只存统计事实、必要标题和官方链接，不采集新闻全文/图片/徽标，不假称政府为平台背书。[BLS 使用说明](https://www.bls.gov/bls/linksite.htm)、[BEA 公开资料说明](https://www.bea.gov/help/faq/147)与[美联储声明](https://www.federalreserve.gov/disclaimer.htm)对公开信息允许使用并要求或建议来源标注，另有标明版权内容/徽标等例外。该规则不延伸为对其他国家、Forex Factory或媒体数据库的展示许可；具体产品上线仍须检查其实际内容是否落入例外。

先行设计的原稿与写入时间证据保存在 `C:/workspace/fx/705/reports/calendar-20261003/calendar-design-first.md` 和figma-write.json；接口合同按当前实际实现维护，本文件不是虚构的已有接口清单。
