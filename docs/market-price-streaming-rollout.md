# 价格流优化发布记录

后续更新（2026-09-22）：Bitget 已替换为 Binance 主源、OKX 禁用次源；指数延迟实测与最新验收见 [Binance/OKX 与指数报告](binance-okx-and-index-latency.md)。本文下方保留首次 Yahoo shadow 发布时的记录。

2026-09-22，项目 `C:\workspace\fx\705`，Docker 项目 `exchange-705`。代码与镜像已完成，backend、PC、mobile 已部署。当前为旁路观测阶段，尚未完成完整交易时段验收。

## 已实现

- Yahoo Java WebSocket 与 Protobuf 解码、共享连接、订阅、心跳、重连、有界接收队列及按品种健康判断。提供 `http_only`、`shadow`、`ws_preferred` 三种模式与代码白名单。
- WS/HTTP 统一报价入口，校验源时间、拒绝旧报价、重复事件幂等；持久化完成后发布。同一外部代码的内部别名在同一事务中保存。
- 新增 `market_source_event`，保留旧 `market_source_tick` 及兼容读取；同时间不同价格不会被旧复合主键吞掉。Redis 最新快照合并写入，历史事件不合并。
- 下游按品种共享快照计算，epoch/quoteVersion 防回退，支持完整报价增量发送、活跃品种 250ms 推送和慢客户端背压。
- PC、移动端共享连接 Promise、持续重连、订阅拥有者与集中 HTTP 兜底；移除移动首页重复价格轮询，按页面、持仓及挂单订阅；隐藏页面暂停图表定时校准。
- 原有报价新鲜度与成交校验保留。Yahoo 涨跌幅标记为相对前收盘；历史 K 线继续 HTTP。

## 当前启用状态

| 配置 | 发布值 | 含义 |
|---|---|---|
| `YAHOO_STREAM_MODE` | `shadow` | 接收并统计 WS；业务报价仍走 HTTP |
| `YAHOO_STREAM_SYMBOLS` | 空 | 没有品种获准以 WS 为主源 |
| `MARKET_PUSH_INTERVAL_MS` | `1000` | 下游保持 1 秒周期 |
| `MARKET_PUSH_DELTA` | `false` | 保持完整推送，暂不开启增量 |

前端订阅、兜底、版本和共享计算优化已生效。250ms 与增量能力已实现，但未启用。沿用现有 Bitget、Alltick 路径，没有引入 Python 服务。

## 验证结果

- 后端真实 MySQL/Redis 隔离回归：100 项通过，0 失败、0 错误、0 跳过；含本地真实 WS 断开/重连、旁路隔离、事件幂等、同时间极值与别名事务回滚。
- Node 测试：27 项通过；两端生产构建通过。
- PC/移动端浏览器专项：旧版本拒绝、无价格故障状态、断线 HTTP 兜底、重连 epoch 切换、旧 epoch 拒绝、图表交互通过。已有控盘与随机历史浏览器回归通过，截图已检查。Browser plugin not available；使用项目 Playwright 脚本。
- 部署后 `docker/smoke.ps1`：17050、17051、17052 的页面、API、WebSocket 全通过。后端重建导致管理端 Nginx 暂存旧地址，已 reload 并重测通过。
- 新事件表已创建；检查时新表 95 条、旧表 15,803 条，旧表仍保留。

2026-09-22 17:00:28（Asia/Singapore）实际 Java 上游快照：shadow、已连接、32 个订阅、24 个代码收到消息、累计 1,280 条、拒绝 0、队列缺口 0、队列深度 0。外汇多数活跃样本源延迟约 2–3.5 秒；`^FCHI`、`^FTSE`、`^GDAXI` 源延迟约 902–903 秒，不能满足现有 15 秒新鲜度要求。该短窗口不是完整时段覆盖或 p95 性能证明。

同次检查 Bitget 的 Crypto/Metal 请求超时，freshQuotes 为 0；这是尚未解决的外部行情可用性问题。本次没有替换其来源，也不能据服务健康检查宣称所有行情可交易。

## 后续放量门槛

1. 保持 shadow，覆盖各品种相关完整交易时段；从 `/api/market/status` 与每分钟 Yahoo 汇总日志核对覆盖、源延迟、重连、拒绝和队列缺口。内存统计重启会重置，验收需保存带时间的样本；目前没有创建额外后台采集任务。
2. 只把时段覆盖、新鲜度、历史一致性与断线恢复均通过的 Yahoo 代码加入 `YAHOO_STREAM_SYMBOLS`，再改为 `ws_preferred`。未通过的品种继续 HTTP；延迟指数不能通过刷新接收时间获得成交资格。
3. WS 稳定达到 30 秒且该品种源/接收时间均在 10 秒内，才抑制该品种 HTTP 轮询；静默或断开恢复 HTTP。原有 15 秒成交条件继续独立执行。
4. 完成实际负载下端到端延迟、数据库负载和慢客户端验收后，单独启用增量与 250ms。两者不可视为当前已验证的性能收益。

## 回退与证据位置

在项目 PowerShell 中临时回退上游/推送开关：

```powershell
$env:YAHOO_STREAM_MODE = 'http_only'
$env:YAHOO_STREAM_SYMBOLS = ''
$env:MARKET_PUSH_INTERVAL_MS = '1000'
$env:MARKET_PUSH_DELTA = 'false'
docker compose up -d --no-deps backend
docker compose exec -T pc nginx -s reload
docker compose exec -T mobile nginx -s reload
docker compose exec -T admin nginx -s reload
./docker/smoke.ps1
```

长期配置需把同样的值保存到部署环境；临时 shell 变量关闭窗口后不保留。此回退不删除新增历史，不需要恢复数据库。

- 部署前数据库备份：`%TEMP%\705-before-stream-deploy.sql`（2,016,897 字节）。
- 原后端镜像标签：`exchange-705-backend:before-stream`；起始工作区副本：`%TEMP%\705-before-price-streaming`。
- 隔离回归日志：`%TEMP%\705-stream-isolation-final.log`；Node：`%TEMP%\705-stream-all-node.log`。
- 浏览器记录：`%TEMP%\705-stream-browser-final.log`、`%TEMP%\705-market-streaming-browser`；发布构建：`%TEMP%\705-stream-images.log`、`%TEMP%\705-stream-mobile-image-final.log`。

工作区原有改动已保留；未创建 Git 提交。本次完成代码与首阶段部署，完整时段验收、WS 主源白名单及高频推送放量仍待完成。
