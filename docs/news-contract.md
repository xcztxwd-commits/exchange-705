# 外部财经新闻唯一接口合同（阶段 3）

## 已实现合同（2026-10-03）

范围仅 exchange-backend、指定 exchange-admin 与手机/PC Figma。交易、余额、公告、用户授权及用户端源码保持不动。来源聊天《重设计手机端交易页面 UI (4)》仅作设计背景。先行设计与实测时间另存 reports/news-20261003。

### 领域与内容

NewsArticle：稳定 articleId，租户及 REAL/DEMO，sourceId、publisher、原始语言标题、许可允许的纯文本简述或 null、原文 URL、分类、语言、publishedAt 或 null、首次 discoveredAt、updatedAt、hidden、sortOrder、rowVersion。UUID 根据来源及规范 URL 派生，不把不同来源错误合并。没有原文正文、图片或自动翻译字段。

NewsSourceSetting：本租户/环境启用、许可核验记录及版本。NewsFeed：按环境共享的官方采集缓存、请求租约、每日额度、下次时间、HTTP/业务状态、成功时间、净化后的条数及内容指纹；不含管理员内容或 Key。NewsAudit：租户/环境及操作者、操作、核验依据、前后值。共享缓存仅通过启用来源导入本租户，公开查询绝不请求上游。

默认 Fed/BEA 官方宏观 RSS，ECB 仅非署名官方新闻稿标题与原文链接。Fed/BEA 第三方、版权标识或署名条目不自动复制。GDELT 默认关闭，低频、标题索引、publishedAt=null，discoveredAt 不冒充发布时间。NewsData 默认关闭，须后端 Key 与账户/许可核验；12 小时延迟独立字段，尚未认证验证。

### 实际路径与权限

- GET /api/insights/news：category、sourceId、language、from/to（UTC Instant；按 publishedAt，未知时间条目不混入该窗口）、page=0、size=20（1–100）；排序推荐权重、已知发布时间/发现时间倒序、稳定 ID。返回 content、分页总数、sources、asOf、dataStatus、environment。
- GET /api/insights/news/{articleId}：只读已入库可见记录，不接收抓取 URL；隐藏、禁用、其他租户/环境均 404。
- GET /api/admin/insights/news 与 /{id}：包括隐藏内容。GET /sources：包括关闭来源、许可、预算、错误、过期和版本，但无秘密。GET /audit：操作历史分页。
- PUT /sources/{sourceId}：enabled、rowVersion、reason、licenseReviewed、licenseEvidence；静态官方 URL 不可改。
- POST /sources/{sourceId}/sync：仅有同步动作权限的管理员可请求；遵守共享预算/租约/退避，不强制绕过。返回 requested、changed、sources。
- PUT /{id}：category、sortOrder、hidden、rowVersion、reason；不改写官方标题/时间或伪造来源。
- POST /import：sourceId、format=RSS/ATOM、content、verified=true、material；仅白名单官方合法材料；净化、大小/条数限制；管理动作权限。

响应沿用项目原始 JSON 与现有异常处理。参数错误 400、未登录 401、无权限 403、不可见 404、版本竞争 409。公开查询按租户/进程每个 UTC 分钟最多 600 次，超出返回 429；最多保存 256 个活跃租户计数桶，不是集群共享限流。来源同步在预算、租约或退避窗口内返回 HTTP 200 与 `requested=false`，不会再次请求上游；上游 429 通过来源 `RATE_LIMITED`、`httpStatus=429` 和 `nextAttempt` 表达。未知来源拒绝；分类 MACRO/CRYPTO/FINANCE，官方宏观文章不可修改为 CRYPTO/FINANCE。

后台菜单为 `news`，读取列表/详情/来源/审计需要该菜单权限；写来源、同步、写文章、导入分别需要 `news:source`、`news:sync`、`news:edit`、`news:import`。菜单与动作由原权限启动器登记，不自动给普通角色授予新权限。仅 GET 公开新闻路由免登录，仍经过项目原租户解析；客户端不能通过文章 ID 切换租户或 REAL/DEMO。菜单、操作和令牌校验沿用现有实现，不增加授权旁路。

### 状态与安全

来源 NEVER_FETCHED / OK / EMPTY / RATE_LIMITED / BLOCKED / ERROR / BUDGET_EXHAUSTED / UNCONFIGURED；公开集成 FRESH / EMPTY / PARTIAL / STALE / UNAVAILABLE。304 必须已有缓存；429 按 Retry-After，超时不清空旧内容。官方 1 小时/24 次日额度；GDELT 6 小时/4 次；NewsData 12 小时/2 次。来源禁用和文章隐藏直接通过 SQL 可见性条件生效，无负缓存延迟。

XML 禁用 DTD、外部实体、XInclude。HTTP 固定官方 HTTPS、无重定向、连接/读取/整体时限、2 MB；不代理客户端 URL，不抓文章正文。外链仅正常公共 http(s)、拒绝用户信息/内网/IP/控制字符；打开新窗口 rel=noopener noreferrer，不 iframe。HTML 转为纯文本，脚本/样式/图片不留字段。来源 Key 只读后端环境变量，不回包、不存库、不记录完整请求 URL。

XML 上限为 2,000,000 字符、500 个条目；HTTP 下载为 2,000,000 字节。连接超时 4 秒、读取超时 8 秒、读取环路有 12 秒预算检查，不承诺硬实时总时限。HTML 容器先删除，再由现有 JDK 纯文本解析器处理；Fed 的 UTF-8 BOM 在中央 XML 入口净化。导入/写配置必须有非空净化后的核验依据。首次人工导入默认隐藏，核验恢复后才公开；重复导入不改变已有隐藏/排序设置。指纹不包含本机发现时间，避免数据库时间精度截断导致非幂等更新。

官方链接另限制到 Fed 的 `www.federalreserve.gov/newsevents/pressreleases/`、BEA 的 `www.bea.gov/news/`、ECB 的 `/press/pr/date/` 或 `/press/govcdec/`。未命中路径、署名/版权例外条目跳过并计数，不补抓正文、不伪造简述。BEA 当前旧历史 `apps.bea.gov/newsreleases`、无 scheme 和非 www 链接不自动扩展范围。

### 字段示例与时间语义

实际 Java 适配器拉取并入库的列表：`reports/news-20261003/api-live-list.json`；实际详情：`reports/news-20261003/api-live-detail.json`。详情示例 ID 为 `032219e9-06b6-3953-8183-98598822799a`，来源 ECB，发布时间 `2026-10-02T13:00:00Z`，`summary=null`、`category=MACRO`、`environment=REAL`、`sourceStatus=OK`。这是隔离验收数据库，不是已部署生产内容。

- `articleId`：来源 ID 与规范 URL 的稳定 UUID；不同来源不合并。
- `publisher/title/summary/originalUrl/language/attribution`：原始来源归属与净化后的允许字段；没有正文、图片、自动翻译字段。
- `publishedAt`：可核对的原始发布时间；缺失/异常/无明确时区保持 null，`timePrecision=UNKNOWN`。`discoveredAt` 是首次采集发现，不能当发布时间。
- `updatedAt/rowVersion`：本租户记录更新时间与写入并发版本。编辑需回传当前 `rowVersion`；新来源配置初始值为 -1。
- `sourceAsOf`：最近成功检查，包括有旧缓存的 304；`sourceContentAsOf`：最近一次 200 内容接收时间，不是新闻发布时间。
- `delayHours/delayed`：NewsData 适配固定至少 12 小时；官方为 0，但不表示覆盖所有实时快讯。
- `hidden/sortOrder`：管理展示控制；公开接口只返回可见记录。排序为权重倒序、已知发布时间或首次发现时间倒序、稳定 ID。
- `asOf`：本次查询时间，不证明所有条目内容均新。`stale` 使用来源最近成功时间与最小间隔的两倍阈值。

只有 from 或 to 时，缺 to 用当前时间、缺 from 用 to 前 30 日；最大窗口 366 日。日期筛选只按 publishedAt，排除未知发布时间。页码范围 0–10000，size 1–100。

### 配置、运行和迁移

使用现有 Spring 配置机制，无新增依赖或真实密钥文件：

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `NEWS_SYNC_ENABLED` | false | 开启每分钟检查的集中调度；来源自身仍受小时/日预算约束 |
| `NEWS_GDELT_ENABLED` | false | 允许可选 GDELT 标题索引适配；还需后台本租户启用与许可记录 |
| `NEWSDATA_API_KEY` | 空 | 后端秘密；不得写入前端、回包、数据库或报告 |
| `NEWS_NEWSDATA_LICENSE_APPROVED` | false | 经账户套餐/展示权益核验后才允许 NewsData 适配；还需后台启用 |

定时开关关闭时，有动作权限的管理员仍可按同一预算手动同步默认官方来源。调度先汇总启用来源集中采集，再通过 TenantJobRunner 按租户导入缓存。来源禁用只影响本租户公开可见性与导入，不清空其他租户共享的公共元数据缓存。

增量迁移为 `exchange-backend/src/main/resources/db/migration/V2026100302__external_news.sql`，新增四张新闻表，保留现有业务表。自动化只验证 H2 MySQL 模式；上线前需在明确的 MySQL 5.7/8.0 测试库备份并核验索引、时间精度和锁行为，不把该文件自动执行到生产。代码使用 Java 8 字节码，项目 Spring Boot 2.7.18；运行可通过现有部署入口，不新增守护程序。

### 设计映射

首页摘要与探索列表使用同一 articleId；新闻与现有平台公告分栏（公告仍 /api/user/announcements）。详情明确来源、发布时间未知、首次发现时间、同步时间、简述许可和阅读原文。覆盖加载、同步、空、加密筛选无源、部分失败、旧缓存、延迟、来源关闭/文章下架 404、后台无权限；320/375/390/430、1280/1440/1920。Noto Sans SC、绿色 tokens；真实五项底栏复用原 A 组件，不修改该组件或动效。

Figma 手机新增看板 `211:7050`，PC 新增看板 `211:10667`；原新闻节点 `37:2546`、`55:2978` 和原 A 导航保留。手机/PC 用户端本次只有设计与对接规格，没有源码修改、构建或生产接入。后台实际页面为 `/news`，组件 `exchange-admin/src/views/NewsManagement.vue`。

### 回滚

先明确运行环境，关闭 `NEWS_SYNC_ENABLED`，停止要回滚的已确认后端实例；不得停掉不相关服务。运行 `python reports/news-20261003/rollback_news.py` 默认仅核对路径、当前哈希及前两阶段基线；没有文件改动。显式 `--apply` 仅在所有哈希匹配时归档新闻新增文件并移除共享文件的新闻增量，保留前两阶段变更；若有后续编辑则拒绝覆盖。四张数据库表和已采集数据不删除，生产数据库/部署始终由管理员另行确认处理。详见验收报告。
