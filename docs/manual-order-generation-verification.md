# 约束联动与一键生成：验收记录

日期：2026-09-28。项目：`C:/workspace/fx/705`。

## 交付与环境

在既有手动模拟合约单上新增约束生成，不另外创建交易引擎。用户、品种、时区确定后，净收益、开平仓时间、方向、杠杆、手数、仓位比例都可以选择固定或交给生成器。未手动输入的默认值、自动结果不自动变成约束。

已构建并在隔离后台 `http://127.0.0.1:18151/orders` 验证。后端使用标记为 `exchange-manual-browser=true` 的 `exchange-705-manual-browser-backend`；测试数据库是对应的 `manual_browser`，测试用户为 `9000004 / manual-slider@local.invalid`。没有改动真实用户资金，没有部署到原 17051 服务或公网生产环境，没有提交/推送 Git。

最终后端 JAR SHA256：`32CEEAF99E18DD69732C3B434E4FB3815F9A631DBE33FB3627270ADAC9748CF5`；最终后台入口脚本：`index-4136bf93.js`。后端重启后健康接口 HTTP 200。复用已有测试管理员，未新增账号或输出凭据。

## 实现

- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderGenerator.java`：无副作用的有界候选计算，固定条件匹配、收益容差、小时级持仓评分。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderPrices.java`：复用窗口行情缓存，批量提取分钟 open 和同分钟换算率。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java`：生成、权威预览及目标净收益约束；既有创建事务不另建。目标保留到审计；空目标在请求摘要中省略，保持旧幂等请求兼容。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/admin/ManualOrderController.java`：新增 `POST /api/admin/orders/contract/manual/generate`。
- `exchange-admin/src/utils/manualOrderGeneration.ts`：固定条件请求组装及预览冲突提示。
- `exchange-admin/src/components/ManualContractOrder.vue`：固定选择、一键生成、误差/时长结果、冲突时禁止确认、请求期间禁用编辑。
- `exchange-admin/src/components/MarketMinutePicker.vue`：非 active 时禁用入口，避免生成过程中修改时间。
- 后端 `ManualOrderGeneratorTest`、`ManualOrderMySqlIT` 与后台 `manualOrderGeneration.test.mjs` 增加对应检查。

没有新增数据库迁移，没有修改普通交易或权益归集算法。保留工作区其他未提交修改，包括同一组件已有的预览超时调整。

补齐了两个组合边界：固定比例与杠杆时，手数先由该组合确定，再验证收益容差；仅固定比例时，手数步长取整后重新求未固定的杠杆，不能因默认杠杆枚举过粗而误报无解。

## 实际测试

### 自动化及构建

1. 后端 package：**23 项，0 失败、0 错误、0 跳过**。包含 15 项生成器测试、5 项原计算测试、3 项分钟行情测试。
2. `scripts/manual-order/Test-MySql.ps1`：一次性隔离 MySQL 5.7，**33 项，0 失败、0 错误、0 跳过**。其中 28 项真实数据库集成、5 项计算测试；与上一组有重复，不能合称 56 项独立测试。
3. `exchange-admin/tests/manualOrderGeneration.test.mjs`、`manualOrderEstimate.test.mjs`：均通过。
4. 后台 TypeScript 与 Vite 构建通过。现有大于 500 kB 分包警告仍存在，未为此做无关打包重构。

命令均在项目根目录执行：

```powershell
mvn -B -f exchange-backend/pom.xml '-Dmaven.compiler.release=8' '-Dtest=ManualOrderGeneratorTest,ManualOrderCalculationTest,ManualOrderMinutesTest' package
& scripts/manual-order/Test-MySql.ps1
node --experimental-strip-types exchange-admin/tests/manualOrderGeneration.test.mjs
node --experimental-strip-types exchange-admin/tests/manualOrderEstimate.test.mjs
$env:VITE_API_BASE_URL='/api'
npm --prefix exchange-admin run build
```

注意：当前隔离后端绑定 target JAR。再次 package 前应确认服务归属并停止该后端，避免运行中覆盖 JAR；不要停止 tmpfs MySQL。构建结束启动原隔离后端并检查健康。

生成器测试覆盖：仅净收益、净收益加开仓时间、固定平仓和负收益、方向/杠杆/手数组合、比例反算杠杆、比例单独固定、固定比例和杠杆且收益允许偏差、零目标、精确 5% 和略超 5%、冲突、缺失分钟、短持仓软惩罚和明确固定短时间、小余额最小手数。

数据库集成新增验证：生成仅预览不入账，随后沿用创建和幂等；仅负净收益生成、不可能约束不写钱、非法开关和权限拒绝；目标净收益随预览保留。既有事务、钱包/权益、并发、故障回滚等集成测试一并通过。

日志：
- `C:/workspace/fx/705/reports/manual-generation-package.log`
- `C:/workspace/fx/705/reports/manual-generation-mysql.log`
- `C:/workspace/fx/705/reports/manual-generation-ui-tests.log`
- `C:/workspace/fx/705/reports/manual-generation-admin-build.log`

### 浏览器与数据库

在实际后台页面选择测试用户与 BTCUSDT，UTC 输入。不是只调用纯算法。

- 仅净收益 1000：首次结果 999.8，误差 0.02%，持仓 247 分钟；最终构建再次生成 991.5，误差 0.85%，持仓 360 分钟。默认方向/杠杆/时间均未被错误固定。
- 固定开仓 `2026-09-26 09:37 UTC` 和净收益 1500：保留开仓时间，自动平仓为 `12:20 UTC`，净收益 1499.7，误差 0.02%，持仓 163 分钟。
- 仅固定仓位 120%：生成 353 分钟持仓、0.01 手、杠杆 414.49；不再因最小手数导致默认杠杆不匹配而误报失败。
- 同时固定 1 手与 120%：自动求杠杆 42053.25，保留两个固定值。这里高杠杆由测试约束造成，不声称是普通可负担交易。
- 修改手数后使普通预览不符合固定比例：显示冲突提示，确认按钮禁用；重新生成可以满足组合。
- 固定方向、开平仓和 1 手，再要求净收益 100：明确报最接近净收益 -3539820、超出 5%，目标仍为 100，不创建订单。
- 最终前端生成请求期间，比例、杠杆、钱包、历史等输入禁用，防止返回结果覆盖用户请求中的改动。

浏览器确认创建了两笔隔离模拟单：

| 订单 | 目标净收益 | 实际净收益 | 手数 | 持仓分钟 | 来源/状态 | 钱包/历史 |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 101 | 1500 | 1499.7 | 0.01 | 163 | MANUAL_TEST / CLOSED | 均关 |
| 102 | 1000 | 991.5 | 0.01 | 360 | MANUAL_TEST / CLOSED | 均关 |

SQL 核对两笔订单、净收益、时间与审计目标一致。测试钱包仍为 1639.4，冻结资金仍为 0。订单 101 刷新后仍存在；102 由最终构建创建并经 SQL 核对。仅生成不写钱的断言另外由真实服务集成测试覆盖。

证据目录：`C:/workspace/fx/705/reports/manual-generation-browser/`，包含 `net-only.png`、`net-open.png`、`percent-only.png`、`quantity-percent.png`、`conflict-guard.png`、`outside-tolerance.png`、`final-net-only.png`、`created-refresh.png` 和 `database-final.txt`。

## 口径、限制与回滚

- 收益容差为绝对差除目标绝对值，不大于 5%，边界用 BigDecimal 无舍入比较。零目标要求精确零。
- 未固定时间搜索最近七天，一端固定则该端向另一侧最多七天；两端固定直接验证。是有界候选搜索，不保证穷尽全部历史或找到数学全局最近解。
- 优先小时级持仓，少量随机扰动；短时间不是禁止项。明确固定的短时间保留并提示。数据不足、固定条件冲突时明确失败，不伪造价格。
- 行情来自当前系统历史链路，可能包含原有价格配置；不宣称全部为未经处理的外部真实成交数据。
- 生成只预览，确认才创建。超额仓位仍允许且提示，不把模拟数据解释为真实风险可承受能力。
- 已部署的是隔离入口 18151，不是原 17051，也未发布公网。PC/移动显示链及数据库结构本轮未改，不将旧端到端报告当成本轮重新跑过的证据。

备份位于 `C:/workspace/fx/705/rollback/manual-generation-20260928-121038/` 与 `C:/workspace/fx/705/rollback/manual-generation-combination-20260928/`。回滚只逆转生成相关差异，重新构建对应隔离服务；不可整份覆盖已有并行工作。新功能无迁移可撤销；两笔隔离测试订单保留作证据。不要清空原业务库或删除数据卷。
