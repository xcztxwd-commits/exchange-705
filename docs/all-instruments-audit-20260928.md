# 全部品种手续费、保证金与盈亏复核（100 倍）

核验日期：2026-09-28。范围是本项目数据库全部 14 个品种（含停用品种查询；当前 14 个均启用）：5 个外汇、6 个美股、3 个加密资产。数据库清单与公开品种 API 已逐项比对，不只是抽查 USDJPY。没有配置金属、能源或指数，因此不虚构这些品种的验收结论。

## 判定原则

- 全部按用户指定的 **100 倍**复算，同时测试 50 倍以及请求省略杠杆时默认 100 倍。现有上限全部为 100，默认行为已经正确，没有改写正常逻辑。
- 算式、单位、费率条款分开核验。合约单位和收费价格没有跨平台统一值，不能因与某经纪商不同，就把该机构的费率擅自复制到本项目。
- 外汇沿用用户已确认的标准手 100000、3.50 USD/手/单边。非外汇未收到更换收费方案的选择，暂保留自定义每手 1000 单位、往返 30 USD，并明确列为“自定义规格”，不是“已认证券商标准”。
- 当前 Crypto 的 sourceCategory 是现货行情 `Crypto`，不是 `CryptoPerpetual`。这里运行的是基于该行情的本地杠杆合约，不能冒充 Binance 现货或永续实盘。

## 官方依据及逐品种覆盖

1. **EURUSD、GBPUSD、USDJPY、USDCAD、USDHKD**：采用 [MetaTrader 官方 Forex 保证金公式](https://www.metatrader5.com/en/terminal/help/trading_advanced/margin_forex)，基础保证金为手数×合约单位÷杠杆，再换到账户 USD。[IC Markets 官方外汇规格](https://cdn.icmarkets.com/uploads/FSA/Forex-Product-Specificiation-Sheet.pdf)提供标准手 100000、0.01 手步长及 Raw 往返 7 USD 的对照。USDJPY/USDCAD/USDHKD 的基础货币为 USD，1 手/100 倍就是 1000 USD，不能再因反向换汇缓存产生偏差。
2. **AAPL、ADBE、AMD、AMZN、BABA**：逐个在 [FXOpen 官方合约表](https://support.fxopen.com/portal/en/kb/articles/ecn-account)核对，均列为 USD 股票 CFD；该机构示例每手 1 股、保证金 20%，与本项目每手 1000 股、100 倍不同。不是数学错误，而是产品规格差异。
3. **GOOG**：单独核对 [IG Alphabet Class C 官方页面](https://www.ig.com/en/shares/markets-shares/google-inc-GOOG-US?siteId=igm)，避免拿 GOOGL 的 Class A 规格代替。IG 页面保证金起点为 20% 且分档，并非 100 倍。
4. 六个美股的公式均符合杠杆 CFD 的 `股数×股价÷杠杆`。佣金对照 [Pepperstone 官方美股 CFD 成本](https://pepperstone.com/en/markets/shares)：0.02 USD/股/单边，最低 0.02 USD。对齐本项目 1000 股敞口时，该例往返为 40 USD，本项目为 30 USD；没有证据证明本项目必须从 30 改成 40，因此不修改。
5. **BTCUSDT、ETHUSDT、SOLUSDT**：逐个按配置的 base/quote 和实际快照计算线性名义金额；`币数×价格÷100` 的算式正确。[Binance 官方永续规格](https://www.binance.com/en/support/faq/detail/360033161972)和[官方费用公式](https://www.binance.com/en/support/faq/detail/360033544231)用于说明线性合约与按成交名义价值收费的区别。其手续费取决于 maker/taker、账户等级等，且永续有 funding；本项目固定往返佣金不是该模式，不直接移植。
6. Binance [官方数量过滤器](https://developers.binance.com/en/docs/products/spot/filters)区分 minQty、stepSize 和最小名义金额。这些基础币单位限制不能直接当作本项目的“手”。本次尝试读取三个币种的公开 exchangeInfo，连接超时；未宣称已在线验证最新交易所数量限制。

**100 倍只是本次指定的本地计算假设，不表示上述平台都提供该杠杆，也不构成合规认证。**

## 逐品种复算

价格来自本地 GET 快照，不是外部实时报价认证。金额单位 USD；USDT 沿用项目现有 1:1 记账约定。以下“修复后”是新代码及外汇迁移方案的预期值，**不是已经上线的配置**。

| 品种 | 类型 | 快照价格 | 每手单位（修复后） | 1手/100x保证金（旧） | 1手/100x保证金（修复后） | 往返费（修复后） |
|---|---|---:|---:|---:|---:|---:|
| JPY=X | Forex | 157.458 | 100000 | 9.919854 | 1000.00 | 7.00 |
| AMD | US | 598.41 | 1000 | 5984.10 | 5984.10 | 30.00 |
| AAPL | US | 340.725 | 1000 | 3407.25 | 3407.25 | 30.00 |
| AMZN | US | 246.99 | 1000 | 2469.90 | 2469.90 | 30.00 |
| GOOG | US | 337.49 | 1000 | 3374.90 | 3374.90 | 30.00 |
| BABA | US | 109.375 | 1000 | 1093.75 | 1093.75 | 30.00 |
| ADBE | US | 230.59 | 1000 | 2305.90 | 2305.90 | 30.00 |
| EURUSD=X | Forex | 1.13666 | 100000 | 11.3666 | 1136.66 | 7.00 |
| HKD=X | Forex | 7.8443 | 100000 | 10.001483 | 1000.00 | 7.00 |
| CAD=X | Forex | 1.41689 | 100000 | 10.007494 | 1000.00 | 7.00 |
| GBPUSD=X | Forex | 1.32512 | 100000 | 13.2512 | 1325.12 | 7.00 |
| BTCUSDT | Crypto | 82891.95 | 1000 | 828919.50 | 828919.50 | 30.00 |
| ETHUSDT | Crypto | 2659.55 | 1000 | 26595.50 | 26595.50 | 30.00 |
| SOLUSDT | Crypto | 117.75 | 1000 | 1177.50 | 1177.50 | 30.00 |

非外汇每手 1000 股/币会产生很大的头寸，例如本次 BTC 1 手代表 1000 BTC，100 倍保证金 828919.50 USD。若产品意图是“1 手 = 1 BTC”，必须同时修改合约规格、费用模型、最小数量和历史快照兼容，不能仅改保证金显示。当前没有把它误称为普通交易所的 1 BTC 订单。

## 本轮实际修复

### 1. 合约实时汇率与法币固定换汇分离

此前保证金/盈亏共用最长数小时固定换汇缓存；即便有更新的行情，交易盈亏仍可能使用旧汇率。

新增 `contractConversion` / `requireContractConversionRate`，合约开平仓、浮盈、自动平仓、前端行情快照与资产权益统一使用新鲜原始行情。优先使用 USDJPY、USDCAD、USDHKD 等直接报价的倒数，避免低精度反向报价与数小时前缓存。超出 60 秒或来源不可用，不返回可用换算率；已有法币充值/提现的固定汇率缓存不变。后台说明已同步区分两者。

非外汇 USD/USDT 记账的币种无需额外汇率换算，原有正确保证金/盈亏算式保留。未修改旧订单合约单位、历史费用、已结算盈亏和余额。

### 2. 全部本地合约的手数校验一致

PC、移动端和后台手工单均使用 0.01 手，但普通下单 API 原来允许更小或不符合步长的数量。本轮补全所有品种的服务端 0.01 手校验，非法数量在资金修改前拒绝；API 明确返回 `contractMinLot`、`contractLotStep`。行情源基础币数量精度与本地合约手数分开说明，不把现货 minQty 当成合约手数。

### 3. 无问题处不改动

- 默认 100 倍、允许用户选择合法较低杠杆；不硬编码所有请求都只能 100。
- 保证金按杠杆反比变化，同等手数的毛盈亏和固定佣金不乘杠杆。
- 美股与加密的线性名义金额保证金、多空价差盈亏、固定往返费计算保持不变。
- 挂单撤销退还预留、重复平仓拒绝及旧订单快照兼容保留。
- 外汇 3.50 单边/7 往返仍沿用前次修复：下单预留往返费用，平仓一次结清，未成交撤单退还。不是新增开平两条独立扣款流水。

## 测试与证据

- **891 项后端测试通过**：最终独立源码快照一次运行 891 项，0 失败、0 错误、0 跳过，包含权益实时汇率集成测试。包含全部 14 品种 × 3 手数 × 3 杠杆条件 × 多空的 252 项真实业务服务测试、14 项逐品种非法手数测试；其余覆盖外汇规则、资金、法币充值/提现、手工计算及权益。
- **2016 项前端断言通过**：对全量数据库清单逐品种检查 PC/移动端保证金、盈亏、仓位资金边界，确认默认杠杆 100。
- 前次外汇三端计算回归 **11569 项断言仍通过**。
- 管理端构建通过（已有体积警告）。本轮没有部署后进行浏览器端到端验收。
- 首次在共享 target 中编译遇到其他任务正在变化的 `BalancedControlPlanTest` 编译问题；未修改该模块或隐藏失败。随后复制当时源文件到任务独立测试目录，完整编译选定回归通过。测试结束后，共享行情文件又有其他任务的月线区间修改；本次合约换汇方法未改变。891 项结果对应留档快照，不代表这些随后修改也已经过本轮验收。

证据目录：`C:/workspace/fx/705/reports/all-instruments-20260928/`。包括数据库/API 全量清单、14 品种行情快照、逐品种 JSON/表格、测试日志、独立测试源码及报告。

复跑：

```powershell
node scripts/all-instruments/audit.mjs
node scripts/fx-standard/test.mjs
mvn -B -f exchange-backend/pom.xml '-Dtest=AllInstrumentCalculationTest,ContractConversionTest,ContractEquityRateTest,FxStandardContractTest,FeeCalculationAuditTest,QuoteCurrencyConversionTest,MinimalFixRegressionTest,ManualOrderCalculationTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,AssetEquityValuationTest,FiatDepositTest,FiatWithdrawTest,DepositOrderServiceTest' test
```

## 发布与未完成边界

**代码修复已完成并测试；本轮仍未部署容器或修改业务数据库。** 当前运行环境尚未执行前次外汇迁移，网页仍可能显示旧配置。前次迁移/回滚脚本位于 `C:/workspace/fx/705/scripts/fx-standard/`，发布必须同时包含此次实时汇率与校验修复，按前次报告的停单、备份、迁移、发布、验收顺序执行。

本轮修改前备份：`C:/workspace/fx/705/rollback/all-instruments-20260928/`；与前次外汇备份分开，避免覆盖其他任务的修改。

非外汇费率/每手规格是待确认的产品政策，不是已经发现并修复的算术错误。没有擅自新增交易所 maker/taker、资金费、股票股息调整、bid/ask 点差、分档保证金或新强平制度；如要求完全复刻某券商或交易所，需要确定对应平台、账户类型和规则版本。未作整个平台全部合规的结论。
