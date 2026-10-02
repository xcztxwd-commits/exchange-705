# 手动历史已平仓订单：端到端复验（2026-09-28，Asia/Singapore）

## 1. 五项独立结论

| 验收项 | 本次结论 | 直接证据与边界 |
|---|---|---|
| 四表更新是否正确 | **通过** | 真实后台 API 订单 #89、#90 各净收益 `-4484.20`；目标及既有后续有效分钟各只加一次；已成熟的两个小时桶、一个 4 小时桶、一个日桶在创建响应后可查询；OHLC、极值时间、样本数、逻辑时间核对。另 #93 为缺失目标分钟、正收益，#94 为零净收益；#95–#100 用真实 API 覆盖 NULL、零/负基础及 24 小时边界。 |
| 历史接口是否及时读取新值 | **通过，按现有轮询语义** | 移动端 #89 成功响应至首次新 API 响应 `2.775 s`；PC #90 为 `3.395 s`。四个范围的实际 `sourceTable` 分别是 1m/1h/4h/1d；CDP 均非磁盘缓存。没有毫秒级推送承诺。 |
| PC 曲线是否自动更新 | **原版失败；最小修复后通过** | 原 PC“我的资产”无资金曲线、无该历史请求。复用现有移动端组件后，保持页面打开且不手动刷新，#90 的月收益 `-18772.60 → -23256.80`、总权益 `-13009.80 → -17494.00`，首次观察到图形及文字新值不晚于成功响应后 `9.896 s`。 |
| 移动端曲线是否自动更新 | **通过** | 保持 `/profile` 1Y 打开、不刷新，#89 经 10 秒正常轮询显示新值；图形首次观察不晚于成功响应后 `15.064 s`。另直接 SQL 视觉夹具在 1D 显示正转负、零、NULL 与稀疏段，之后恢复。 |
| 刷新后是否持久化一致 | **通过** | PC 普通刷新及重进资产页、移动端重登录及普通刷新，均显示 #90 后 `-17494.00`；四范围接口与数据库一致。测试末轮换了专用账号密码，旧浏览器会话会失效；轮换后的本地 API 重新登录为 200。 |

**不能扩张的结论：**保留的浏览器 backend 明确设置 `ASSET_HISTORY_EQUITY_COLLECT_ENABLED=false`，因此没有在该容器里观察到真实定时 tick 对延迟父桶的后续生成。新增真实 MySQL 集成用例直接运行生产 `AssetEquityJobs.aggregate()`，验证水位到位后正常归集生成；它不是浏览器环境定时任务实测。另一个用户的数据库控制行和四个 API 读取路径已核对，未留“修改前另一用户页面”截图。

## 2. 范围、安全与备份

先完整阅读 `docs/manual-history-order-execution.md`、`docs/manual-history-order-design.md`、`docs/manual-history-order-verification.md`，再运行用户指定的旧测试。只使用已有六个 `exchange-705-manual-browser-*`、标签 `exchange-manual-browser=true` 的专用容器；MySQL 5.7 数据库 `manual_browser`，主机仅绑定 `127.0.0.1:62476`。Redis/backend 在隔离 Docker 网络内；后台、PC、移动端分别为 `http://127.0.0.1:18151`、`:18150`、`:18152`，三站首页为 HTTP 200，匿名资金历史为 401。专用账号 ID 9000001、9000004；控制用户 9000002。另为六个 API 基础值场景在此隔离库中专门生成 9000010–9000015，不对应真实客户。未连接/覆写原业务库，未使用真实资金，也未清理其他未提交文件。

修改前全库快照：`C:/workspace/fx/705/rollback/manual-history-order-e2e-20260928/database-before.sql`，892134 bytes，SHA-256 `1E8011B0A4B92FE811F4C36E82CA1CF9307D3F4FD3D27AC0702747D2D217CC8E`。最终清理和测试密码轮换后快照：`C:/workspace/fx/705/rollback/manual-history-order-e2e-20260928/database-final-clean.sql`，1408504 bytes，SHA-256 `7EBFDDBF6C731F4DFDAEEDCAB621376F5645558489CF5650830B380F6207BCCE`。中间快照 `database-after.sql`、`database-final.sql` 同目录保留。六场景 API 追加前快照 `database-before-api-base-matrix.sql` 为 1544905 bytes、SHA-256 `173B4B4D106F7D3F39BC8796F1D8D3260BD64A02761A32EFA81166897B321167`；追加后最终快照 `database-after-api-base-matrix.sql` 为 1586649 bytes、SHA-256 `7C67E885E0A4FA05511FA91C1293DC01DE7565BBEFAF82218F657B7C8C292820`。备份和忽略的账号秘密文件均沿用当前用户 ACL；报告不含密码、哈希、JWT、预览 token、幂等键。回滚只能向**新隔离库**导入快照，不能机械覆盖已产生新交易的库。

**安全事件及处置。**首次 PC 修复构建遗漏 `$env:VITE_API_BASE_URL='/api'`，浏览器网络记录显示专用测试账号登录请求误发至构建默认的外部 `705api.haiwaiym38.top/api/auth/login`（观察到 200、400；200 不能单凭状态码判定登录成功）。没有下单或资金请求。立即用 `/api` 重建三端，检查最终 PC bundle 不含该外部域名，并从浏览器确认后续登录仅到 `127.0.0.1:18150/api/auth/login`。测试末轮换了四个专用隔离账号的共享测试密码，更新受限的忽略文件并以本地 API 重新登录验证。旧密码仅保存在受限回滚备份，不写本报告。外部站点是否留有旧测试凭据/账号需由其运营方审计；不得将这里的本地轮换当作外部凭据撤销。

## 3. 执行记录：不是空跑

| 执行 | 实数及退出状态 | 日志 |
|---|---|---|
| 用户指定的 `& 'C:/workspace/fx/705/scripts/manual-order/Test-MySql.ps1' -NormalRules`，**修改前先跑** | 5 + 24 + 41 = **70** 项，失败 0 / 错误 0 / 跳过 0，退出 0 | `C:/workspace/fx/705/reports/manual-history-e2e-baseline.log` |
| 加入延迟父桶正常归集回归后，同命令再跑 | 5 + **25** + 41 = **71** 项，失败 0 / 错误 0 / 跳过 0，退出 0 | `C:/workspace/fx/705/reports/manual-history-order-e2e/manual-mysql-final.log` |
| `& 'C:/workspace/fx/705/scripts/asset-equity/Test-MySql.ps1'` | **30** 项，失败 0 / 错误 0 / 跳过 0，退出 0 | `C:/workspace/fx/705/reports/manual-history-order-e2e/equity-mysql-regression.log` |
| `mvn -B -f C:/workspace/fx/705/exchange-backend/pom.xml -Dmaven.compiler.release=8 -Dtest=ManualOrderCalculationTest,MinimalFixRegressionTest,ExecutionQuoteTest,QuoteCurrencyConversionTest,AssetEquityValuationTest,AssetHistoryRollupTest,AssetHistoryTest,AssetEquityScheduleTest,AssetEquityCarryTest test` | **78** 项，失败 0 / 错误 0 / 跳过 0，退出 0 | `C:/workspace/fx/705/reports/manual-history-order-e2e/backend-regression.log` |
| 四个 `exchange-frontend/tests/asset*.test.mjs`、一个 `exchange-pc/tests/contract.test.mjs`，逐个 `node --experimental-strip-types` | **5 个脚本全部退出 0**；日志含 15 组 PASS 输出，不把脚本数冒充断言数 | `C:/workspace/fx/705/reports/manual-history-order-e2e/frontend-chart-tests.log` |
| 后台、PC、移动端各在 `$env:VITE_API_BASE_URL='/api'` 下 `npm run build` | **3/3 退出 0**；chunk size 警告不算测试证据 | 同目录 `admin-build.log`、`pc-build.log`、`mobile-build.log` |
| 新增七个 Python 夹具/API/SQL 检查脚本 | `py_compile` 7/7；实际 API 负收益 2 单、开关及正/零收益 4 单、六个 24h 基础分支 6 单；直接 SQL 四粒度各变更/恢复、UI 边界夹具变更/恢复、另一用户只读 API，均退出 0 | 同目录 `business-create-first.json`、`business-create.json`、`api-switch-matrix.json`、`api-base-matrix.json`、`direct-sql-*.json`、`ui-edge-fixture.json`、`other-user-api.json` |

新增用例 `ManualOrderMySqlIT.deferredParentsAreFinalizedByNormalRollupWithoutBackfillingMinutes`：先让下级水位不足，手动订单只修复成熟小时，4h/日保持 0 条；调用现有 `AssetEquityJobs.aggregate()` 后，4h/日均 finalized、close=1100，分钟总数仍 2，不填缺失分钟。无第二套业务算法。

## 4. 后台订单到四表：原始 SQL 与值

按实际 API 预览 token、真实 `binance` 历史分钟价创建，不在脚本中重算价格或净利。#89/#90 均 BTCUSDT，UTC `2026-09-26 17:00 → 17:10`，BUY 0.14、杠杆 10000，预览实际净收益各 `-4484.20`。两次均 `CLOSED/MANUAL_TEST`，`changedMinutes=5`、`repairedParents=4`。#89 首次前数据库、四范围 API、移动页面截图；#90 是同一已缓存范围再次调整，用于 PC 不刷新的自动更新。实际查询见 `C:/workspace/fx/705/reports/manual-history-order-e2e/snapshot.sql`，原始结果见 `four-tables-before.txt`、`four-tables-after.txt`、`four-tables-final.txt`。核心 SQL：

```sql
SELECT bucket_start, net_equity, manual_adjustment, origin,
       COALESCE(effective_at, observed_at)
FROM asset_history_1m
WHERE user_id=9000001 AND basis_version='net_equity_v1'
ORDER BY bucket_start;

SELECT bucket_start, open_value, high_value, low_value, close_value,
       open_at, high_at, low_at, close_at,
       source_count, valid_sample_count, invalid_sample_count, finalized
FROM asset_history_1h
WHERE user_id=9000001 AND basis_version='net_equity_v1'
ORDER BY bucket_start;
-- 相同列分别查询 asset_history_4h、asset_history_1d。
```

| 行（UTC） | #89 前 | #89 后 | #90 后 | 结论 |
|---|---:|---:|---:|---|
| 09-26 17:07 | NULL | NULL | NULL | 原无效点未被假作 0、未改写 |
| 09-26 17:08 | 0 | 0 | 0 | 平仓前真实零值不变 |
| 09-26 17:09 | 1000 | 1000 | 1000 | 平仓前不变 |
| 09-26 17:10 | 1000 | -3484.20 | -7968.40 | 每单各加一次 `-4484.20`，`effective_at=17:11` |
| 09-26 17:59 | 1050 | -3434.20 | -7918.40 | 后续有效分钟每单各加一次，`effective_at=18:00` |
| 09-27 17:09 | 1000 | -3484.20 | -7968.40 | 跨 UTC 日后续分钟同样调整 |
| 09-27 17:10 | -3762.80 | -8247.00 | -12731.20 | 既有分钟，不重复插入 |
| 09-27 17:59 | -3712.80 | -8197.00 | -12681.20 | 后续分钟同样调整 |
| CONTRACT.available | -8525.60 | -13009.80 | -17494.00 | 每笔一次；frozen 均 0，版本 2→3→4 |

最终 09-26 17h 的 1h：O=0/H=1000/L=-7968.40/C=-7918.40，high_at=17:10、low_at=17:11，source/valid/invalid=5/4/1。09-26 16h 的 4h 和 09-26 UTC 日桶同样 O/H/L/C 与极值时刻，子桶 source_count=1、原始有效/无效样本=4/1。09-27 17h 的 1h：O/H=-7968.40、L=-12731.20、C=-12681.20，source/valid/invalid=3/3/0。上述父桶在创建事务返回后查询；09-27 4h/日桶被列入 `deferredPeriods`，没有被提前造出。`rollup_1=1790535600000`、`rollup_2=1790524800000` 前后不退。控制行：用户 9000002 的 777、用户 9000001 的 `other_basis_v1` 的 888 前后相同。

另一专用账号 9000004 的真实 API 开关矩阵：

| 订单 | 钱包/历史 | 净收益 | 钱包 1000 起的变化 | 历史行数 |
|---|---|---:|---:|---|
| #91 | 关/关 | +319.70 | 1000 不变 | 四表 0/0/0/0 |
| #92 | 开/关 | +319.70 | 1000→1319.70 | 四表仍 0/0/0/0 |
| #93 | 开/开 | +319.70 | 1319.70→1639.40 | 1m/1h/4h/1d 各 1，仅补目标分钟 |
| 非法 | 关/开 | — | preview HTTP 400，订单/钱包/历史快照不变 | 不落库 |
| #94 | 开/开，零净收益 | 0 | 1639.40 不变，审计/订单各 +1 | 历史值不重复增益 |

六个新建隔离账号的目标平仓分钟均为 `2026-09-26 17:10 UTC`，由同一个真实后台 API 预览行情并创建 #95–#100；只有测试分钟夹具由 SQL 注入，不重写价格或订单算法。运行 `python C:/workspace/fx/705/scripts/manual-order/verify_api_base_matrix.py` 退出 0，证据为 `C:/workspace/fx/705/reports/manual-history-order-e2e/api-base-matrix.json`。每单净收益均由后台返回 `+319.70`：

| 场景 / 订单 | 目标分钟 / 候选基础 | 预览 `baseSource` | 创建后目标分钟 | `origin` |
|---|---|---|---:|---|
| 已有 NULL / #95 | NULL；前 1 分钟 `+100` | `CARRY_24H` | 419.70 | `MANUAL_CARRY` |
| 缺失、零基础 / #96 | 前 1 分钟 `0` | `CARRY_24H` | 319.70 | `MANUAL_CARRY` |
| 缺失、负基础 / #97 | 前 1 分钟 `-100` | `CARRY_24H` | 219.70 | `MANUAL_CARRY` |
| 恰好 24h / #98 | 前 24 小时 `+200` | `CARRY_24H` | 519.70 | `MANUAL_CARRY` |
| 超过 24h / #99 | 前 24h 1 分钟 `+200`，不准回溯 | `ZERO` | 319.70 | `MANUAL_ZERO` |
| 完全无基础 / #100 | 无任何先前分钟 | `ZERO` | 319.70 | `MANUAL_ZERO` |

六单各 `changedMinutes=1`、已成熟父桶 1h/4h/1d 各生成一行且 finalized，O/H/L/C、四极值时刻、source/valid/invalid 逐字段和实际分钟序列对拍；此前分钟、全站水位、用户 9000002 控制行不变。每单钱包从 1000 增至 1319.70，订单/审计各 +1；没有补其它缺失分钟。#95 把原 NULL 行原地修复，分钟数仍 2。#98 的前 24h 行不被平仓后的调整误改，#99 的过期前值未被误用。

#94 为隔离库中暂时将该品种手续费调 0 的同分钟零毛利订单；手续费与 `row_version` 已恢复到原值。目标分钟既有/NULL/缺失、基础正/0/负、恰好 24h/超过 24h/完全不存在均有本次真实 API 订单；多单同分钟及倒序补单另由 **25 项真实 MySQL 业务服务测试**覆盖，其中 `idempotenceDifferentContentSameMinuteAndReverseOrder` 实际通过。六个基础分支是独立 API + 数据库验证，不伪称浏览器逐单点击。

事务/并发证据：同键回放 #89/#90 不增一行，同键不同内容与历史开/钱包关为 400；MySQL 用例的四线程同键只一次落账，订单/钱包/分钟/父桶/审计五个注入故障点全回滚且释放锁；采集、归集、普通钱包并发看不到半笔提交；锁忙超时拒绝、真实下次采样读钱包不双加；普通余额不足、已有 OPEN 单、真实强平/清零保留原规则。未将这些集成测试写成浏览器端逐故障模拟。

## 5. 真实资金曲线：接口、缓存、页面

实际 `/user/asset-history` 在同一事务读取当前钱包点及对应四表，`sourceTable` 由 `AssetEquityHistoryService.history` 选择。两个端在 #90 后的浏览器 CDP 响应相同，均 HTTP 200、`fromDiskCache=false`：

| 范围 | 实际表 | 点数 | 总权益 | 收益摘要 |
|---|---|---:|---:|---:|
| 1D | `asset_history_1m` | 3 | -17494.00 | -14288.40 |
| 1W | `asset_history_1h` | 2 | -17494.00 | -23256.80 |
| 1M | `asset_history_4h` | 1 | -17494.00 | -23256.80 |
| 1Y | `asset_history_1d` | 1 | -17494.00 | -23256.80 |

脱敏响应证据：`C:/workspace/fx/705/reports/manual-history-order-e2e/curve-api-before.json`、`curve-api-after.json`、`pc-range-api.json`、`mobile-range-api.json`。范围切换不是仅看按钮：每次捕获到对应新 GET 和返回体 `sourceTable`。`1D` 的收益未随前一日订单再变，是按“今日”摘要边界的正确表现；更长范围收益改变。当前实时钱包 `live.value=-17494.00` 只作为当前点，不再叠加历史净利。

原 PC 在 `exchange-pc/src/views/DesktopTrade.vue` 的 `activeUserMenu === 'assets'` 仅有钱包卡片；无资金曲线，属于本次范围内缺陷。修复只在资产页接入已有 `exchange-frontend/src/components/AssetPixelChart.vue`，通过 `exchange-pc/src/utils/assetPixelWindow.ts` 复用现有显示规则，`@total` 同步顶部最新权益；关闭资产弹窗会卸载组件，没有新全站轮询平台。原文件备份 `C:/workspace/fx/705/rollback/manual-history-order-e2e-20260928/DesktopTrade.vue.before-pc-curve`。组件 `load` 在第 68 行请求、`choose` 在第 203 行切换、`resume` 在第 252 行关注前台，**第 266 行每 10000 ms 轮询**。无 WebSocket 推送；隐藏页可能暂停至重新聚焦。网络响应未走磁盘缓存，当前前端只把上次点保留在组件内存，下一次成功请求覆盖；失败则提示显示旧数据。服务端 `AssetEquityHistoryService.history` 第 15–26 行直接查库，无该响应专属应用缓存或错误粒度映射。

时序按 UTC，`DB 首次观察`是查询上界，不是假装得知精确提交瞬间：

| 订单/页面 | 成功响应 | DB 首次观察已提交 | 首次新 API 响应 | 图形/数字首次观察 | 可据此得出的延迟 |
|---|---|---|---|---|---|
| #89 移动端，页面保持打开 1Y | 19:55:27.000 | ≤19:55:28.486 | 19:55:29.775 | ≤19:55:42.064 | API 2.775 s；图形 ≤15.064 s |
| #90 PC，页面保持打开 1M、已有缓存 | 20:06:51.123 | ≤20:06:52.395 | 20:06:54.518 | ≤20:07:01.019 | API 3.395 s；图形 ≤9.896 s |

手动切换四范围、关闭再进入、普通刷新、重新登录均另做；**这些动作没有替代上表不刷新的自动更新判断**。移动端 1D 键盘/悬浮实测期初沿用 `09-26 18:00 · -7918.40`，最新 `20:21 · -17494.00`，UTC/页面时区显示与 API 时间相符；正负轴、最高/最低、NULL/0 不被伪造成未来点的规则由浏览器视觉夹具及前端断言覆盖。1Y 稀疏历史高/低标签见截图。对其它用户，9000002 的 777 分钟在前后 SQL 中不变，独立登录的四范围只读 API 仅返回该用户，`1D carryIn=777`、`live=-50`；无其修改前页面截图，不能称已做视觉差分。

截图（均为真实页面，非订单历史页或 mock）：

![PC 修复前没有资金曲线](C:/workspace/fx/705/reports/manual-history-order-e2e/pc-before-no-curve.jpg)
![PC 修复后实际资金曲线](C:/workspace/fx/705/reports/manual-history-order-e2e/pc-after-curve.jpg)
![移动端调整前 1M](C:/workspace/fx/705/reports/manual-history-order-e2e/mobile-before-1M.jpg)
![移动端调整后 1Y](C:/workspace/fx/705/reports/manual-history-order-e2e/mobile-after-1Y.jpg)
![移动端正零负和稀疏 1D](C:/workspace/fx/705/reports/manual-history-order-e2e/mobile-edge-1D.jpg)

## 6. 直接 SQL 与业务更新明确分离

`scripts/manual-order/verify_direct_sql.py` 在唯一隔离用户上，分别对 1m、1h、4h、1d 的一个值临时加 `123.45`，核对其余三张目标行未变，然后逐表恢复原值；四份 `direct-sql-*.json` 的 `restored=true`。1m 直接改 09-27 17:10：`-12731.20→-12607.75`，**不会调用 `ManualOrderHistory.apply`，也不会自动修复已 finalized 的 1h/4h/1d**。PC 保持 1D，20:09:34.509 UTC 的前次轮询仍旧值，20:09:44.508 UTC 的轮询返回新值，20:09:45.980 UTC 前截图变化。截图：

![直接 SQL 前 PC 1D](C:/workspace/fx/705/reports/manual-history-order-e2e/pc-direct-1m-before.jpg)
![直接 SQL 后 PC 1D](C:/workspace/fx/705/reports/manual-history-order-e2e/pc-direct-1m-after.jpg)

分别直接改 1h/4h/1d 后，PC 实际切到 1W/1M/1Y，相应接口各返回唯一目标新值 `-12557.75`/`-7794.95`/`-7794.95`，HTTP 200、非磁盘缓存；未断言修改某表会使其它表或收益摘要/真实钱包跟着变。各范围切换后的单独图形截图未保存，所以这一组精确证明是“接口读该粒度 + 页面装载该接口”，不是四组视觉像素差分。修改均已恢复。

额外临时 UI 夹具只向 9000001 的 1m 插入 `+1000、0、NULL、-500`，父桶不变；移动端保持 1D 打开，经普通轮询在 20:19:22.423 UTC 得到七点响应（含上述四点），图形在 20:19:27.994 前变为正转负/稀疏走势；`scripts/manual-order/verify_ui_edge_fixture.py restore` 删除**仅这四条带 `E2E_VIEW_FIXTURE` 来源的临时行**并核对父桶，后续接口点数回到 3。测试不把直接 SQL 的行为算作业务合同通过。

## 7. 可复跑与剩余限制

新增入口：`scripts/manual-order/curve_fixture.py seed`、`verify_curve_business.py`、`verify_api_matrix.py`、`verify_api_base_matrix.py --user-start 9000010`、`verify_direct_sql.py mutate|restore <grain>`、`verify_ui_edge_fixture.py seed|restore`、`verify_other_user.py`。`verify_api_base_matrix.py` 只接受 9000010–9000900 的全新连续六账号区间；同库复跑改用未用区间，不覆盖旧测试用户。脚本首先验证容器身份/环回绑定/库名；旧用例各自建一次性 MySQL 5.7。业务订单脚本重复执行会新增新的独立订单，开关矩阵故意要求 9000004 在干净夹具中订单为 0；完整重跑应在**新隔离克隆库**恢复 `database-before.sql` 和对应的受限测试账号秘密，然后依序 seed、跑测试，不能覆盖现在的隔离库，更不能导入原业务库。直接 SQL 和视觉夹具每次必须成对 mutate/restore，失败时先 restore 再离开。

剩余限制：保留浏览器环境的正常采集开关为 false，因此“下一个定时 tick 成熟后生成父桶”仅在真实 MySQL `AssetEquityJobs.aggregate()` 新回归用例验证，不宣称该浏览器容器已经自动跑过 tick；第三方市场历史报价可用性不由本测试保证；单用户另一端登录会按现有单设备登录规则踢掉旧会话。原有 10000 点同步上限、预览 5 分钟、命名锁等待和正常采样延迟继续适用。没有新增触发器、永久轮询平台或全站水位回退。
