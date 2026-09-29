# 外汇标准手、保证金与佣金修复验收

日期：2026-09-28。范围：当前 5 个 Forex 品种及新增标准外汇品种。用户已明确选择 **3.50 USD / 标准手 / 单边**。

## 结论与权威依据

- [MetaTrader 5 官方保证金说明](https://www.metatrader5.com/en/terminal/help/trading_advanced/margin_forex)：Forex 基础保证金为手数 × 合约单位 ÷ 杠杆，随后将保证金币种转换到账户币种。不能把日元报价当作美元金额。
- [OANDA 官方计算说明](https://www.oanda.com/us-en/trading/calculating-margin/)：按基础货币持仓规模和保证金比例计算，再转换账户币种。各地区、账户及品种的杠杆限制并不相同。
- [IC Markets 外汇规格](https://cdn.icmarkets.com/uploads/FSA/Forex-Product-Specificiation-Sheet.pdf)：标准手 100,000 基础货币单位、最小及步长 0.01 手、Raw 佣金每标准手往返 7 USD。
- [IC Markets 官方交易成本](https://www.icmarkets.eu/en/trading-pricing/trading-costs)：Raw 单边 3.50 USD / 手。这里采用的是用户选定的收费方案，不是全球统一的强制标准。

本项目保留 1～100 倍可选杠杆；不是宣称所有受监管平台均允许 100 倍。当前行情只有单一成交参考价，没有实现完整 bid/ask 点差、分档保证金、对冲抵扣和隔夜利息模型，不能据此认证整个平台完全等同于真实经纪商。

## 为什么截图是 9.90 USD

原配置每手只有 1,000 基础货币单位，且原公式将交易价格与独立缓存的 JPY→USD 换算率相乘。

```text
旧例：1 × 1,000 × 157.2 × 0.0063 ÷ 100 = 9.9036 USD
正确标准手：1 × 100,000 ÷ 100 × 1 = 1,000 USD
```

157.2 的单位是 JPY/USD。如果先用它换算成日元保证金，最后仍须换回 USD；使用同一汇率时两者相消。不能得到 157,200 USD 保证金。

固定测试例：

| 品种/假设汇率 | 手数 | 杠杆 | 保证金 USD | 单边佣金 USD | 往返佣金 USD |
| --- | ---: | ---: | ---: | ---: | ---: |
| USDJPY = 157.2 | 1 | 100 | 1000 | 3.50 | 7.00 |
| USDJPY = 157.2 | 0.01 | 100 | 10 | 0.035 | 0.070 |
| USDCAD / USDHKD | 1 | 100 | 1000 | 3.50 | 7.00 |
| EURUSD = 1.1 | 1 | 100 | 1100 | 3.50 | 7.00 |
| GBPUSD = 1.25 | 1 | 100 | 1250 | 3.50 | 7.00 |
| EURJPY，EURUSD = 1.1 | 1 | 100 | 1100 | 3.50 | 7.00 |

这些是固定测试输入，不是当前实时报价。

## 已修改的实现

1. `FxContractRules` 统一 Forex 新增配置为每手 100000、最小/步长 0.01、数量精度 2、往返佣金 7。服务端拒绝不合规则的手数和旧规格下的新外汇订单。
2. 新外汇订单按基础币种计算保证金。USD 基础币种固定为 1；USD 报价币种使用订单/成交价格；交叉盘查询新鲜基础币种兑 USD 行情，支持直接/倒数换算，缺失或过期拒绝计算。
3. 开仓、限价预留、实际撮合、PC/移动端预估、仓位比例和后台手工订单的保证金计算同步。手续费不乘杠杆。
4. 新增订单快照 `fx_base_currency`。旧订单保留 NULL、原手数单位、原佣金和旧撮合规则，不根据新配置重算历史订单；非 Forex 品种保持原规则。
5. 后台以单边佣金编辑，内部 `feeMultiplier` 继续储存往返合计。API 提供佣金模式、币种、单边价格；前端标明往返预留金额。

### 资金预留与扣费时点

为兼容现有资金账本，**下单时仍预留保证金及全部往返佣金，平仓一次结清；未成交撤单全部退还**。例如 1 手 USDJPY / 100 倍需要可用余额 1007 USD，其中保证金 1000、往返佣金 7。

单边费率已拆分为 3.50，但没有新增“开仓立即单独入账 3.50、平仓再单独入账 3.50”的两条资金流水。接口 openCommission/closeCommission 表示该订单分摊的单边费用，不是新增两次扣款。现有净收益扣除往返费用一次，避免重复扣费。

### 尚未解决的审计项

非 USD 报价币种的浮动盈亏/结算仍使用现有 quote→USD 换算服务及其缓存策略。本次保证金已从该长缓存中分离，但**没有修复全部盈亏汇率缓存时效问题**，也没有调整旧订单盈亏、点差、swap、强平制度或股票/加密货币费率。不要将本次通过的测试理解为这些功能全部符合券商标准。

## 测试证据

所有测试均使用离线输入、H2 或一次性 MySQL 数据库；没有向现有用户账户下测试订单。

- 后端选定回归：**571 项，0 失败、0 错误、0 跳过**。含 196 项标准外汇测试，覆盖手数、杠杆、多空、直盘/交叉盘、佣金、撤单、重复结算、过期汇率、旧快照隔离。H2 验证新旧挂单共存、落库、撮合及资金守恒。
- MySQL 5.7 手工订单回归：**38 项通过**，含实际 SQL 插入、事务及回滚。
- PC / 移动 / 后台实际计算函数：**11569 项断言通过**；另后台原手工预估测试通过。
- 一次性 MySQL 5.7 迁移测试：迁移、重复执行幂等、非外汇隔离、旧订单/资金不变和配置回滚均通过。
- PC、移动端、管理端生产构建通过。存在原有打包体积警告；未做浏览器端到端验收。

运行方式（项目根目录）：

```powershell
node scripts/fx-standard/test.mjs
node --test exchange-admin/tests/manualOrderEstimate.test.mjs
mvn -B -f exchange-backend/pom.xml '-Dtest=FxStandardContractTest,FeeCalculationAuditTest,ManualOrderCalculationTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderMinutesTest,QuoteCurrencyConversionTest,MinimalFixRegressionTest' test
./scripts/manual-order/Test-MySql.ps1
./scripts/fx-standard/Test-Migration.ps1
```

日志目录：`C:/workspace/fx/705/reports/fx-standard-20260928/`。

## 发布状态与迁移

**源码已修复、测试已通过；尚未发布到当前运行容器，尚未迁移业务数据库。** 当前工作区同时含其他模块修改，未将这些未经过本任务完整验收的修改一起部署。最后只读 API 核实：5 个 Forex 仍为 lotSize=1000、feeMultiplier=30；因此当前网页仍可能显示旧数值。

- 迁移：`C:/workspace/fx/705/scripts/fx-standard/migrate.sql`。
- 回滚：`C:/workspace/fx/705/scripts/fx-standard/rollback.sql`。
- 修改前文件备份：`C:/workspace/fx/705/rollback/fx-standard-20260928/`，保留原有未提交工作。

发布顺序：准备并验证本次后端及三端镜像；备份业务数据库和旧镜像；停止交易入口/撮合；执行迁移；启动新后端及三端；核实 5 个 Forex 配置和页面 USDJPY 1 手 / 100 倍的保证金 1000、单边费 3.50、往返预留 7；再开放交易。不能在旧后端继续接单时先将每手数量改为 100000。

SQL 仅自动迁移 `source_category='Forex' AND lot_size=1000 AND fee_multiplier=30` 的原始配置；其他定制配置不会被覆盖，需逐个核对。备份表 `trading_symbol_fx_backup_20260928` 保留首次快照。脚本不更新订单或余额。

回滚应先停止新订单入口。若已经产生 `fx_base_currency IS NOT NULL` 的订单，不得直接退回不识别该字段的旧后端，尤其不能让旧撮合逻辑处理新挂单；保留新后端处理存量订单，并选择前向修复。不要删除订单快照，不要整目录恢复而覆盖其他任务的新修改。
