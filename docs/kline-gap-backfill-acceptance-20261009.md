# K 线缺口回填验收（2026-10-09）

在 `C:/Users/徐乾妖/.codex/worktrees/72e5/705` 完成实现和隔离验收。证据目录为 `C:/workspace/fx/new/kline-backfill-qa-20261009-112215/`；命令、退出码、最终 JUnit 报告、截图、数据库前后对照和可审查补丁见该目录的 `README.md`、`acceptance.json` 和 `delivery.diff`。

## 已实现

- 最新请求按已结束的 UTC 时间槽逐一检查已配置 Crypto/CryptoPerpetual 的 1m/5m/15m/30m/1h；一次最多检查 1000 槽，拆成每窗最多 200 槽。源覆盖和最终展示覆盖分别统计，窗口外旧记录不计入覆盖。
- 自动请求、历史分页和后台安全补齐复用 `SourceHistoryGapRepair`、同一去重队列及供应商限速。提交后核对真实队列状态，容量不足不会冒充入队。后台范围最多 1440 分钟，不扫描全市场或全历史。
- 上游抓取在事务外；真实 writer 内重查租户、源路由/版本、既有记录和保护。只插入不存在、有效、已结束的源记录，并设置 `historyOnly`。各原生周期的源修订和 dirty 区间与新增源记录同事务提交。
- 保留既有完整/partial/来源冲突记录及 received_at；拒绝冲突重复记录、错误源身份和未完成记录。控盘同周同月扩展、hold、publication、mixed minute、封存、恢复/撤销快照、模拟冻结前缀和 SOURCE 初始资格继续保护；手动操作也不能绕过。
- 提交后处理历史缓存，保留最新缓存。缓存失败保持任务待重试，重复写入幂等。后台提交及实际源提交记录审计，包括操作者、租户、范围、来源、插入数、跳过原因及源修订；实际插入审计在缓存处理前记录。
- PC、移动端通过 klinecharts 公开 `resetData`、DataLoader、overlay 和滚动接口插入中间时间戳，有序去重，保留更早历史、画线、指标、缩放和时间戳视窗。不再修改库内部数组；pending 历史页内部补齐不再报 middle-gap 错误。
- 普通同修订仅填缺失历史时间戳；权威修正沿用既有版本失效规则。HTTP/WS 交错使用递增的 WS 序号，支持同一毫秒内的交错；旧 HTTP 不覆盖新尾部。品种、周期、租户/账号、SOURCE、模拟进入/退出、发布、恢复/撤销变化时丢弃迟到响应。
- 后台新增独立 GET `/admin/ai-control/{symbol}/history-restore/gaps` 和 POST `/gaps/repair`，检查要求 `ai_control` 查看权限，提交要求 `ai_control:restore_history`。展示源/展示缺失、可补、保护、队列状态和最近结果，采用有界只读轮询。

没有新增数据库迁移；没有将 `/history-restore/source` 改作通用修补入口，也没有改变显式恢复/撤销的语义、常规 source upsert、实时报价、启动依据或结算实现。

## 已验证

最终数量及逐类结果以 `acceptance.json` 和复制出的 JUnit XML 为准，不将早期失败或跳过计入通过。

- 后端定向回归和真实打包通过，覆盖 `SourceHistoryGapRepairTest`、`HistorySourceRestoreTest`、`HistoryOrderingTest`、`S2CanonicalKlineMergerTest`、SOURCE dirty/projector/window、模拟路径/租户、权限、行情推送、首页图缓存和手动订单历史图等。
- 新增后端回归覆盖：200 条稀疏数据仍按槽发现缺口、原生周期独立、秒/毫秒与未结束排除、后台只读、401 槽拆窗串行提交、跨租户、重复冲突、冻结/恢复保护、提交时队列变满，以及提交后缓存失败仍保留实际插入审计。
- 真实隔离 MySQL **5.7.44** 使用本任务随机身份容器、独立数据库及生产 writer fence / 恢复账本迁移；两个测试均实际执行。验证 8 次并发总共仅新增 2 行、唯一源修订、插入失败及修订失败整体回滚、提交后缓存失败重试幂等、既有 body/received_at 不变、路由版本变化、lease 到期和 generation 失效、抓取期间新控盘/mixed minute，以及 SOURCE/模拟保护。另实跑显式 RESTORE/UNDO，核对不可变快照、外键和恢复修订。
- `mysql-protected-before.json` 与 `mysql-protected-after.json` 全对象及文件字节相等。比较包含运行中的控盘任务、plan、flow、sample、hold、非空 publication、mixed minute、权威报价、启动依据、当前和已发布区间的 1m/5m/15m/30m/1h/1w/1M OHLCV。使用同一个时间/报价参数，任务保持 RUNNING，没有停止或冻结任务来取得等价结果。
- PC/移动端新增浏览器报告 `client-backfill-results.json` 覆盖中间插入、自动 pending 重读、历史 pending 中间插入、旧 HTTP/新 WS、SOURCE、发布、恢复/撤销、周期/品种/租户及模拟进入/退出。两个宽度下时间戳视窗偏移为 **0 px**；旧历史和画线保持，MA/VOL/RSI 重新计算，数据不重复，加载次数有界。
- 受保护稀疏历史的原浏览器回归通过，含采样缺失、上游无数据、日历不明、失败、partial、封存、重试耗尽后继续更早历史。最新覆盖变完整不会清除更早受保护缺口的提示。
- 后台新增浏览器回归通过，覆盖 1280/390 px 各有/无提交权限共四组，只检查不写、只向安全补齐入口提交、有界轮询、保护停止、独立 5m 覆盖及选区变化拒绝迟到响应。已有“定位最新”、刷新保持选区、框选/大周期展开、丢失回执查询和撤销回归继续通过。
- `exchange-pc`、`exchange-frontend`、`exchange-admin` 的 `npm run build` 均通过；后端实际 Maven `package` 通过，没有使用跳过测试参数。

浏览器使用真实 Vue/klinecharts/Chrome，HTTP/WS 为隔离夹具，并阻断外部请求。数据库写入、锁、事务及失败验证使用真实 MySQL；浏览器夹具没有连接业务后端，不能将两者称为生产端到端验收。

## 环境修复、限制和未验证

- Windows protoc 无法处理中文 basedir；首次命令真实失败，改用 QA 目录内指向同一授权后端目录的 ASCII junction 后完成测试及打包。没有复制或修改其他工作树。
- 后台 `npm ci` 遇现存 esbuild 文件占用（EPERM）。未停止现存进程；以 `npm install --ignore-scripts --package-lock=false --no-audit --no-fund` 完成依赖准备，lockfile 未变，随后真实构建和浏览器验证通过。
- 早期 SQL DISTINCT/FOR UPDATE 兼容错误、测试夹具/定位器错误、视窗基准采集时机错误和队列/审计测试夹具错误均保留对应失败命令与日志；修正后重跑。通过结果只引用最终成功执行。
- 最终证据检查发现旧 publication 夹具的 OHLCV 对照为空，未据此验收；改用真实 `generatedPoints` 生成完整采样及 mixed minute，并对两个受保护区间的七周期响应增加非空断言后重新执行 MySQL 验证。
- **NOT_RUN**：全量旧 S2 固定身份 MySQL/Redis 验收套件没有执行；本次采用新建 owned MySQL 5.7 验证本工单的写入边界，并运行受影响 SOURCE/模拟定向回归。
- **NOT_RUN**：额外 `SourceCandlesBatchRegressionTest.mysqlStatementFailureAfterFirstChunkIsAtomic` 依赖专属 performance MySQL 夹具，早期扩展运行中被其 assumption 跳过，未计作通过。最终明确执行该类四个可执行方法；安全回填的物理插入/修订失败回滚另在真实 MySQL 验证。
- 未访问现有业务数据库、未验证实盘供应商全历史可用性、最初漏采事件或当前 BTC 业务库缺口；不把设计中的旧快照当成当前事实。没有生产写入、真实业务恢复/撤销、订单执行或部署。

## 既有修改与交付状态

开始时 HEAD 为 `852654743491b463ec49c9dc5e115b77486ff302`，已有两个后台文件的“定位最新”修改，保存在 QA 的 `baseline.diff`。执行期间外部 HEAD 变为 `9e342081c0bbb26aa759dbba706ece17f80a1f7a`；本任务没有提交或移动 HEAD，仅在当时文件上增加本任务改动。既有按钮与布局保留，原浏览器回归通过。

交付仍为未提交工作区修改。没有 reset、clean、stash、自动提交、推送、新聊天、子代理或发布。`delivery.diff` 基于交付时 HEAD，含本任务新增测试/脚本/验收文档；工单和设计原文件保留，不列作本任务新增实现。

## 回滚和清理

本次无需业务数据库回滚，因为写入仅发生在新建的隔离数据库。隔离 MySQL 按完整容器 ID 和 owner label 核对后删除；QA Vite 服务只清理该次创建的进程树，详见 `mysql-cleanup.json` 和 `server-cleanup.json`。

代码回退应按 `delivery.diff` 逐项反向移除本任务改动，保留已存在和外部提交的修改，尤其是“定位最新”。不要执行工作树 reset/clean 或使用设计快照覆盖当前文件。

若将来部署后回退应用代码，已新增的 `historyOnly` 行仍属于源事实；应用回退不等于删除这些行，不直接批量 DELETE、降低源修订、修改采样/保护或恢复账本。若确需数据回滚，应另行审查并在真实 writer 边界执行。本次没有部署，该操作未执行。
