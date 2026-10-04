# 四域手机 / PC 用户端统一对接合同

更新时间：2026-10-03（Asia/Singapore）。这是设计与实际后端路由对照，不代表 exchange-frontend 或 exchange-pc 已接入。

## 接入边界

- 本工单只实现 `exchange-backend`、`exchange-admin` 并更新 Figma；不改、不构建 `exchange-frontend` / `exchange-pc`。
- 四域各取自己的缓存与 API。没有引入采集新管线，也不需要聚合：**不新增 `/api/insights/home-summary`**。
- UI 首页只展示摘要。点击时沿用相同品种、事件、新闻或 `traderId`，不重新发明对象身份。REAL/DEMO、租户从服务端 host/session 确定，客户端不能以 query 参数切换。
- 公开 API 成功不是上游实时成功。显示 `status/dataStatus`、`source/asOf/updatedAt`、过期/缺失原因；不得用 demo 或其它供应商补真实缺值。

## 首页 / 探索 / 详情映射

| 域 | 首页卡片 | 手机 / PC 探索列表 | 详情 / 深层状态 | 上下文键 |
|---|---|---|---|---|
| 市场深度 | 已选择品种的摘要，明确现货/永续、来源、档数、更新时间与状态 | 不合并或排序不同单位的品种；有限档订单簿按真实档数展示 | 点选仍沿用同一 `instrumentId/symbol/marketType/levels`；REST 失败可见，WebSocket 断流为 STALE | `instrumentId`, `symbol`, `marketType`, `levels` |
| 财经日历 | 由日期窗口读已发布事件摘要，显示来源与 forecast 缺失 | `from/to` 均为事件当地日程日期，固定当前 `coverage=US` | 以同一 `eventId` 读 actual/previous/forecast/修订和时区；actual 或 forecast 缺失不补零；提醒另走需登录的当前用户接口 | `eventId`, `releaseDate/releaseAt`, `country`, `metric` |
| 外部新闻 | 同一推荐/发布时间/来源规则的短摘要 | 只展示已入库公开元数据，可按分类、来源、语言、UTC 发布时间过滤 | 以同一 `articleId` 读取标题、允许的简述、来源、原文链接；未知发布时间显示未知，不用 discoveredAt 冒充 | `articleId`, `sourceId`, `publishedAt` |
| 交易员 | 复用推荐列表前三项，不使用私有资产或净值历史 | 只列本租户 PUBLISHED 人工资料；排序是“推荐” | 以同一 `traderId` 读取 profile/equity/history；期间使用相同 `statisticPeriodId` | `traderId`, `statisticPeriodId`, `currency` |

## 实际 API

### 深度

```http
GET /api/market/depth/{symbol}?levels=20&marketType=spot
GET /api/market/depth/{symbol}?levels=20&marketType=swap
```

返回直接 JSON：真实供应商 `provider`、`externalReference=true`、`quantityUnit`（`BASE_ASSET` / `CONTRACT`）、请求/可用/显示档数、bids/asks 的 price/quantity/cumulativeQuantity、序号、`sourceAsOf`、`receivedAt`、`sourceTimeBasis`、`refreshMethod`、刷新间隔和 `status`。行情 WS 复用 `/api/ws/market`，订阅帧 `subscribeDepth` / `unsubscribeDepth`；普通价格协议未改。HTTP 200 不是 LIVE 保证。未知品种 404、参数 400、容量 429；不从另一家交易所静默回退。

### 财经日历

```http
GET /api/insights/calendar?from=2026-10-01&to=2026-10-31&country=US&page=0&size=30
GET /api/insights/calendar/{eventId}
GET /api/insights/calendar/{eventId}.ics
GET|PUT|DELETE /api/user/calendar/reminders/{eventId} # 仅当前登录用户
```

列表回 `content/page totals/coverage/sources/asOf/environment`。日程日期不是统计期；时间带精度/时区，`forecast` 无合法数据就为 null。公开详情只允许本租户、本环境已发布对象，其他对象 404。管理员固定上游入口见 `docs/calendar-contract.md`。

### 外部新闻

```http
GET /api/insights/news?category=MACRO&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z&page=0&size=20
GET /api/insights/news/{articleId}
```

列表回分页、`sources/asOf/dataStatus/environment`。详情不接受用户 URL、不抓取正文。新闻类来源固定；key、展示许可、RSS 延迟与发现时间规则见 `docs/news-contract.md`。平台公告仍使用既有公告 API，不能与外部新闻混为一类。

### 交易员

```http
GET /api/insights/traders?recommended=true&sort=RECOMMENDED&page=0&size=3
GET /api/insights/traders/{traderId}
GET /api/insights/traders/{traderId}/equity?period=30D&page=0&size=100
GET /api/insights/traders/{traderId}/history?period=30D&page=0&size=20
```

响应含稳定 `traderId`、`statisticPeriodId`、统计窗口 UTC 起止、币种/单位、人工披露口径、`sourceType`、`metricSource=MANUAL`、`verificationStatus=UNVERIFIED`、`updatedAt`、状态/空曲线状态。曲线仅是人工维护的净资产点，不计算策略 ROI；未知现金流保持未知、缺点不插值。历史以 `closedAt` 排序，只公开管理员发布的闭仓展示字段，不提供私人订单标识、余额或未平仓记录。非 PUBLISHED、下架、禁用、跨租户/环境一律不进入公共列表，详情/子资源均 404。推荐排序不是业绩排名。分页 0 起、size 1–100；HTTP 401/403/404/409/429 按实际区分。

管理员合同与字段见 `docs/traders-contract.md`；后台路由 `/traders`，管理前缀 `/api/admin/insights/traders`。无权菜单或动作不得显示/执行写操作。

## 空值、单位和精度

- 深度数量严格按商品/合约单位显示。Binance spot 未提供源生成时间时只显示接收时间；禁止把接收时刻冒充 `sourceAsOf`。
- 日历的国家覆盖、发布日、统计期、日期/时刻精度及 forecast/actual 独立。当前没有合法市场一致预期来源时显示 `— / 来源未提供`。
- 新闻 `publishedAt`、`discoveredAt` 与后端 `asOf` 分离；未获许可不加媒体全文、图像或自动翻译。
- 交易员金额/比率使用十进制字符串；缺失是 null/`—`。负值、零值、极大值、零基数、区间过短、曲线断档不造数/不插值/不假设入出金。
- Figma TEST_ONLY 展示卡使用 `traderId=e04cd90a-f3a2-4879-a4c8-6db1574ace52`，`statisticPeriodId=e04cd90a-f3a2-4879-a4c8-6db1574ace52:2026-09-01T00:00:00Z/2026-09-30T00:00:00Z`。这是隔离浏览器验收样例，不是生产资料，也不构成真实业绩。

## Figma 和后续用户端上线条件

Figma 文件 `v4V9L597ML6EhPMLi3kW8O` 的交易员页面和状态板节点见 `docs/traders-ui-design.md`。本次真实 UI 联调仅是 exchange-admin 与 H2 隔离后端；手机/PC 仍需另行授权后，按本合同实现并对接。

有意不做的聚合：深度/报价/雷达沿用行情层；主页/探索各域取原有 API/缓存；私人资金曲线继续原账户能力；交易员绝不复用 `asset-history`；日历提醒和平台公告维持原接口。本合同没有把这些能力重新合成一条采集链。
