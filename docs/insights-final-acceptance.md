# 四个业务域总验收

日期：2026-10-03（Asia/Singapore）。总状态：**本机分域实现与隔离验收已完成；存在明确上游网络、许可、Key 和 MySQL/生产部署阻塞，不能判定全链路上线或生产验收通过。** 手机/PC 用户端只更新 Figma/对接合同，源码和构建未做。

## 分域结果：实现与外部验证分开

| 域 | 实现状态 | 实际验证 | 未通过 / 未验证 / 阻塞 |
|---|---|---|---|
| 市场深度 | Binance/OKX 分来源、spot/swap、真实档数与单位、来源时间、STALE/空侧/错误状态、REST 刷新和独立 WS；后台按租户启停；不跨源伪装。 | 35 项域回归通过；Binance spot 匿名连续 65.168 秒、OKX spot + swap 65.110 秒真实数据/序列/重连；本机管理和 API 验收见 [`market-depth-acceptance.md`](market-depth-acceptance.md)。 | Binance USDⓈ-M 永续真实目录/REST 本机超时，未成功 REST/WS 验证；Bybit REST 超时，未接入。免费读取不等于展示/再分发许可，OKX 书面许可未提供、Binance 商用展示权本次未确认；深度产品上线前保持禁用。匿名公开源不要求 Key；未用账户 Key。 |
| 财经日历 | 固定白名单官方 BLS/BEA/Fed 来源、日历/数据/actual/previous 与 null forecast；管理员发布、来源状态、预算、提示；地区固定 US。 | 25 项日历域测试及后台浏览器 42 项检查（按原接受报告记录）；六个独立官方入口中五个返回 HTTP 200，并实际解析/持久化，证据见 [`calendar-acceptance.md`](calendar-acceptance.md)。 | BLS ICS HTTP 403（1/6）。无合法市场共识 forecast，保持 null，不造预期；BEA 更深数据 API Key 未提供/未用。来源覆盖不是全球完整日历。H2 隔离库，不等于 MySQL/生产。 |
| 外部新闻 | 仅固定源 RSS/官方元数据、去重/过滤/披露；详情不抓正文、不接媒体图；GDELT 被禁用。 | Fed/BEA/ECB 官方 RSS HTTP 200；61 条已过滤元数据真实写入隔离 H2（Fed 15、BEA 42、ECB 4）；23 项新闻域测试 21 通过/2 可选跳过，浏览器检查 50 项，见 [`news-acceptance.md`](news-acceptance.md)。 | NewsData.ai 账户 API Key 未提供、展示许可/延迟条款冲突未解决，故关闭；CryptoCompare HTTP 401，未解锁或绕过；GDELT 首次 HTTP 429、后续单次裸请求 200 不算稳定适配，仍禁用。RSS 许可只覆盖受允许的元数据，不含文章全文/图片。 |
| 交易员展示 | 管理员维护 CRUD、预览、发布/下架/禁用/推荐、手工曲线、闭仓历史、原子导入、审计、租户隔离和鉴权；拒绝未验证平台声称；手机/PC Figma+API 对接合同。 | Maven 14 项集成测试（13 通过/1 可选跳过）+ 1 项迁移测试通过；后台 npm 构建 exit 0；Playwright/Edge 对真实本机 H2+HTTP 30 项检查通过，七种视口；节点文案/尺寸已核对，详见 [`traders-acceptance.md`](traders-acceptance.md)。 | 本域无外部数据 Key；无真实人物/账户数据。没有第三方记录授权/核验链，维持 `UNVERIFIED` 并拒绝 `VERIFIED_PLATFORM`；授权说明不是第三方书面许可或真实业绩证明。用户端代码未接入/未构建。MySQL 真实迁移、生产与用户端 E2E 未验。 |

## 总体交叉验收

- 四域单独接口、权限、租户/环境、状态、空值口径；用户首页不聚合第二数据管线、不混 source/symbol/traderId，不把 API 200 等同于上游 LIVE。
- 本轮追加一次四域联合确定性回归：深度 `MarketDepthTest/MarketDepthIntegrationTest/DepthPushTest/DepthStreamTest`，日历 `CalendarParserTest/CalendarIntegrationTest/CalendarMigrationTest`，新闻 `NewsParserTest/NewsIntegrationTest/NewsMigrationTest`，交易员 `TraderIntegrationTest/TraderMigrationTest`，共 80 项，74 通过、6 跳过、0 失败/错误、Maven exit 0。外部联网探测不在此命令中，使用各域独立的来源实测报告。
- 新增了四域后端与管理界面以及增量迁移；本机测试使用 H2 隔离数据，不使用客户交易订单、生产账户或外部账户凭证；不向用户端源码写入私钥或 Key。
- Figma 和用户端对接清单见 [`traders-ui-design.md`](traders-ui-design.md)、[`insights-integration-contract.md`](insights-integration-contract.md)。`exchange-frontend` 和 `exchange-pc` 的路径 diff 为空；没有对它们运行构建。
- 各阶段域测试通过不等于同一个 MySQL/生产部署完成的端到端测试。本轮未执行生产部署/迁移、未发订单、未打开管理员 Key、未申请许可、未通过网络策略绕过来源阻塞。
- 后台构建仍报告既有大 chunk（>500 kB）警告；非本次验收阻塞。目标 MySQL migration、外部 Key/许可/网络与生产运维窗口是上线前门槛。

## 工作区保护

未提交、未重置全仓。阶段基线 `reports/traders-20261003/baseline.json`（HEAD `6b93d62b5a3e748162f9eaea02f851e9a188e30e`，采于 2026-10-03 16:14:27）用于保全比对。基线后变更中 trader 相关为菜单/权限/图片访问与 `.gitignore` 迁移 allowlist；同时检测到五条非交易员路径的后快照差异：`exchange-backend/src/main/java/com/gtcfesk/exchange/market/ControlHistoryStore.java`、`ForexQuoteMarketService.java`、`PersistentPriceControl.java`、`exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlFlowMarketIntegrationTest.java`、`scripts/multitenant/source_isolation_registry.json`。这些文件含其他业务域状态，来源/作者无法从仓库证据确定；本工单没有改写或回退它们。保留并待对应工单核对。
