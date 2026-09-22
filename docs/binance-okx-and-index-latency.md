# Binance/OKX 接入与 Yahoo 指数延迟验收

日期：2026-09-22。项目：705。本文区分本地故障测试、真实接口短时实测与完整交易时段验收；不能相互替代。

## 指数延迟结果

采样窗口：2026-09-22 17:16:27–17:31:25，Asia/Singapore。每 5 秒读取运行服务的 Yahoo WS 观测快照，共 180 次；按 eventId 去重。延迟定义为服务端接收时间减 Yahoo 源时间，不是客户端渲染延迟或网络 RTT。以下百分位仅针对采样到的不同事件，不能当作全部上游消息的百分位。

| 项目代码 | 实际 Yahoo 指数 | 不同采样事件 | 中位源延迟 | P95 源延迟 | 结论 |
|---|---|---:|---:|---:|---|
| UK100 | `^FTSE`，富时 100 | 175 | 901.522 秒 | 902.544 秒 | 活跃交易时段持续延迟约 15 分钟 |
| GER30 | `^GDAXI`，DAX | 176 | 901.216 秒 | 901.545 秒 | 活跃交易时段持续延迟约 15 分钟 |
| FRA40 | `^FCHI`，CAC 40 | 59 | 901.394 秒 | 902.378 秒 | 活跃交易时段持续延迟约 15 分钟 |
| NAS100 | `^IXIC`，纳斯达克综合 | 5 | 不作固定延迟判断 | 不作固定延迟判断 | 推送旧交易时段报价，当前美国正常交易时段未开始 |
| SPX500 | `^GSPC`，标普 500 | 0 | — | — | 窗口内未收到该代码推送，需开盘时段验证 |
| US30 | `^DJI`，道琼斯工业平均 | 0 | — | — | 窗口内未收到该代码推送，需开盘时段验证 |

三个欧洲指数样本范围分别为 UK100 901.040–915.519 秒、GER30 901.036–913.519 秒、FRA40 901.153–907.337 秒。17:27 左右另查 Yahoo chart 元数据，三者 regularMarketTime 也落后约 900–913 秒，且当前处于其 regular 交易区间。因此 HTTP 切 WS 不能消除这个约 15 分钟的源延迟。

美国指数当日正常交易区间由 Yahoo 元数据给出为新加坡时间 21:30 至次日 04:00，当前采样在此之前。`^IXIC` 少量推送的源时间落后约 12 小时，属于旧报价，不能据此宣称该接口有固定 12 小时延迟。完整美国交易时段尚未测试，不能承诺全品种实时。

映射问题：项目 `NAS100` 实际映射 `^IXIC`，后者是纳斯达克综合指数，不是纳斯达克 100；`GER30` 是现有内部名称。此次记录问题，未擅自改变这些合约的标的映射。所有指数继续保留原有 15 秒成交新鲜度限制，未把 Yahoo WS 从 shadow 放量。

原始证据：[观测 JSONL](market-data-evidence/2026-09-22/observations.jsonl)、[统计摘要](market-data-evidence/2026-09-22/summary.json)、[交易时段元数据](market-data-evidence/2026-09-22/metadata.json)。可复测：

```powershell
node docker/market-index-audit.cjs 900
```

该命令只读行情，无下单操作；完整时段复测需在对应市场开盘期间运行并保存结果。当前没有额外长期后台任务。

## 已替换的接入

| 用途 | Binance 主接口 | OKX 次接口（默认不启用） |
|---|---|---|
| BTC、ETH 等 Crypto | USDT 现货 ticker | USDT 现货 tickers |
| XAUUSD、XAGUSD | XAUUSDT、XAGUSDT USDT 永续合约 | XAU-USDT-SWAP、XAG-USDT-SWAP |
| 现货 WS | `wss://data-stream.binance.vision/ws` | `wss://ws.okx.com:8443/ws/v5/public` |
| 永续 WS | `wss://fstream.binance.com/market/ws` | 同一 public 地址，独立品类连接 |
| 最新价 HTTP | 现货 `/api/v3/ticker/24hr`；永续 `/fapi/v1/ticker/24hr` | `/api/v5/market/ticker` |
| 历史 K 线 HTTP | `/api/v3/klines`、`/fapi/v1/klines` | `/api/v5/market/candles`、`history-candles` |

已移除运行源码中 Bitget 最新价、K 线、域名及转换函数。沿用内部品种代码；输出 `source`、`marketType`、`quoteCurrency=USDT`。贵金属来源是 USDT 永续合约的成交报价，并不是伦敦现货或美元现货基准，不能混淆。加密货币仍对应原本配置的 USDT 交易对。

使用 Binance `@ticker`（约 1 秒更新）和 OKX `tickers`，不额外订阅逐笔成交造成无必要数据库写入。报价时间使用上游 ticker 的统计快照时间：Binance `C`/`closeTime`，OKX `ts`；不是伪造的本地收包时间，也不承诺每笔成交都被接收。K 线按 UTC 对齐，OKX 的 6H/12H/日/周/月使用 UTC bar。

## 故障与恢复

- 默认 `EXCHANGE_MARKET_PROVIDER=binance`、`EXCHANGE_STREAM_ENABLED=true`。只建立所选供应商连接；不自动切 OKX、不在运行服务中旁路连接 OKX。
- Crypto、Metal 两个独立连接和有界处理队列；每队列最多 1,024 条。过载或处理失败关闭该连接并恢复 HTTP，记录 gaps，不将丢事件伪装成完整逐笔历史。
- Binance 的服务端 PING 由 Tomcat JSR-356 原样 PONG；主动 20 秒心跳、10 秒超时。OKX 使用文本 `ping`/`pong`。订阅未确认 10 秒、无文本消息 60 秒均重连。
- 断线指数退避 1–30 秒加抖动；重新订阅。23 小时主动轮换，早于 Binance 24 小时上限；处理 `serverShutdown` 通知。轮换期间允许 HTTP 补价，不承诺无缝逐笔连续。
- 每品种 WS 稳定 30 秒且源时间、接收时间都在 10 秒内，才停该品种 HTTP 轮询。断线、静默、未收到该品种时恢复同供应商 HTTP；原有 HTTP 超时和退避仍约束恢复时间，不保证瞬时接管。
- 初始 HTTP 快照及原有约 3 秒兜底调度保留。限流按 HTTP 主机/市场共享：429 尊重 Retry-After，最低 2 秒；418 最低 120 秒。K 线和价格共享限流冷却。
- 两条入口共同拒绝无效价格、缺失/未来时间、旧报价；原始时间不延长。WS 故障立即取消 WS 报价成交资格，HTTP 成功后恢复。历史事务、前端版本检查保持。

## 真实网络限制

当前网络将 `fapi.binance.com`、`fstream.binance.com` 解析到运营商 `mcmc-redirect.maxis.com.my` / `rpz.blacklist.maxis.com.my`，地址为 `175.139.142.25`。主机与 Docker 的 Binance 合约 HTTP 请求超时，合约 WS 无法连接；未更改 DNS、代理或绕过该限制。

因此 Binance 贵金属线上可用性尚未通过，不能宣称黄金、白银已恢复实时行情。OKX 两个贵金属的公共 HTTP 和 WS 在隔离实测中均可用，但按要求仍不在运行服务中启用。后续需要在合规可达的部署网络重测 Binance 合约；或另行明确启用 OKX。禁用次源意味着主源不可达时仍返回不可用，这是当前预期行为。

## 测试与发布记录

本地新增覆盖：精确品种映射、UTC 周期、两个协议解析、无效/过期时间、错误响应、错误品种、只启用选定供应商、禁用无连接、WS 断开重连、轮换、心跳超时、HTTP 接管、健康 WS 停轮询、429/418 冷却。沿用真实 MySQL/Redis 回归检查控盘、历史和交易可用性隔离。

真实 Java 容器实测：Binance 现货 HTTP 最新价、K 线、WS 与心跳通过；OKX 现货和两种贵金属 HTTP、WS 通过，黄金 K 线通过。实测无下单，OKX 只在一次性测试进程中开启。连接轮换使用本地模拟到期测试，没有声称已持续运行 24 小时。

两端前端 27 项 Node 测试通过；PC、移动浏览器价格流专项通过，覆盖初始快照、重复轮询、旧版本拒绝、状态更新、HTTP 兜底、重连、epoch 与图表交互。此轮只修改后端和部署配置。

最终验证结果：后端测试集合共 110 项。真实 MySQL/Redis 隔离回归执行 109 项，0 失败、0 错误；1 项真实公网测试默认跳过，并已在独立 Java 容器显式运行通过。因此两次运行合计覆盖 110 项，不能将默认跳过误写为全部在单次隔离测试执行。额外 PC/移动端控盘历史、随机历史浏览器回归均通过。

2026-09-22 17:39–17:40 已部署最终后端镜像并 reload 三个前端 Nginx；17050/17051/17052 页面、API、WebSocket 检查通过。生产只读价格测试接收 13 个下游价格帧，BTCUSD/ETHUSD 均为 `source=Binance`、`transport=ws`、`available=true`；最后一组样本源到下游接收分别为 813ms、817ms。这是单组观测，不是全天 p95。Crypto 的 2 个品种均有新鲜报价，队列无缺口；Metal 的 Binance WS/HTTP 仍失败，新鲜报价为 0，与前述网络限制一致。

发布证据：[生产只读验收](market-data-evidence/2026-09-22/binance-production-smoke.json)。最终日志：`%TEMP%\705-binance-isolation-final.log`、`705-binance-live.log`、`705-binance-build-release.log`、`705-binance-node.log`、`705-binance-browser.log`、`705-binance-persistent-browser.log`、`705-binance-random-browser.log`。

## 配置与回退

当前应保持 Binance 主源、OKX 禁用。仅关闭 WS、保留 Binance HTTP：

```powershell
$env:EXCHANGE_MARKET_PROVIDER = 'binance'
$env:EXCHANGE_STREAM_ENABLED = 'false'
docker compose up -d --no-deps backend
docker compose exec -T pc nginx -s reload
docker compose exec -T mobile nginx -s reload
docker compose exec -T admin nginx -s reload
./docker/smoke.ps1
```

恢复 WS 将 `EXCHANGE_STREAM_ENABLED` 设为 `true` 并重建后端。需要启用次源时，配置 `EXCHANGE_MARKET_PROVIDER=okx` 并重建后端；这是人工整组切换 Crypto/Metal，不是自动故障转移。应把长期配置保存到部署环境，临时 PowerShell 变量不持久。

此轮起始备份在 `%TEMP%\705-before-binance`；部署前数据库为 `%TEMP%\705-before-binance.sql`（2,430,482 字节），旧运行后端为 `%TEMP%\705-before-binance-app.jar`。测试日志在 `%TEMP%\705-binance-*.log`。原 Bitget 历史数据保留原样；旧来源缓存不能因切源而获得新鲜度。

## 官方依据

- [Binance 现货 WebSocket](https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md)：订阅、PING/PONG、24 小时限制与市场数据专用域名。
- [Binance 现货 REST](https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md)：公共行情域名、ticker、K 线及限流。
- [Binance 合约 WS 连接](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Connect)与[路径迁移公告](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Important-WebSocket-Change-Notice)：ticker 必须使用 `/market`，不能沿用无路由旧地址。
- [Binance 合约公共市场 REST](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)与[通用规则](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/general-info)。
- [Binance 官方贵金属合约公告](https://www.binance.com/en/square/post/34799625125154)：XAUUSDT、XAGUSDT 产品身份。
- [OKX V5 官方文档](https://app.okx.com/docs-v5/en/)：公共 tickers、HTTP ticker、历史蜡烛、UTC 周期及文本心跳。
