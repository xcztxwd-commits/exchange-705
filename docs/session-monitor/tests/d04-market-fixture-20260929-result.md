# D04 市场目录夹具修复补充验收

日期：2026-09-29；执行时段约 03:40–03:51（Asia/Singapore）。

## 结论

**D04 的规格/启用前置条件已适配；目录交易链路在最终相同测试源码下连续两次通过。仍保留一次平仓乐观锁冲突风险，不宣称整个项目发布通过。**

只修改共享源码中的 `C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/CatalogTradingScenario.java`。`MarketIsolationTest.java` 本轮未改；未改生产代码、数量负责人或模拟账户负责人文件。未重复后端/前端全量测试，未提交或推送。

原 `round1-20260929-result.md`、最初全量 QA 报告及失败证据全部保留，本文件是补充，不替换原结论。

## 根因与适配

### 新加密品种规格与启用

已核验生产 `AdminSymbolService.createSymbol/updateSymbol`：新 Crypto 品种默认停用；启用必须完整规格。旧场景只改 lotSize/feeMultiplier/maxLeverage，直接交易，失败符合保护设计，不能删除保护。

夹具现在通过真实管理 HTTP API 配置 BASE_ASSET、lotSize=1、minOrderQuantity=0.001、quantityStep=0.001、minOrderNotional=0，再合法启用；版本以服务端保存结果为准，订单显式发送 specVersion/quantityUnitType。

新增负向验证：
- 默认停用时拒绝开仓。
- 无规格拒绝启用。
- 缺步长的部分规格拒绝启用，数据库保持停用、未安装部分数量协议。
- 已启用但订单缺协议、旧版本、错误单位，均 HTTP 400。
- 每次负向请求前后核对可用余额、冻结余额及合约订单总数不变。
- 原停用品种拒绝交易、高杠杆拒绝等断言保留。

### 顺着原链路发现的其他夹具过时点

1. **V3 控盘**：旧“1 秒从 100 到 110、强度 1”无法表示且无法满足双向均衡；保留为拒绝用例。成功用例换成合法“3 秒、强度 10、目标 100.01”，仍严格断言目标到达并恢复到 100。没有改算法或缩减控盘链路。
2. **批量行情**：生产使用 `ticker/24hr?symbols=[...]` 批量请求；旧模拟供应商只认识单个 symbol。补充返回正确数组，否则第二个加密品种 ETHBTC 加入后行情一直不可用。
3. **标准外汇手与实时合约换汇**：旧场景用 lotSize=1/10 表示 Forex，且混淆入金固定汇率缓存与合约实时换汇。按当前规则改为 lotSize=100000、合法 0.01/0.02 手，并为 JPY 正向/反向报价返回一致的合成数据。保留固定缓存 100 不随上游更新变化的断言，同时合约平仓明确使用已更新的实时汇率 200。

这些改动仅修夹具。原开仓/手续费/保证金/平仓/挂单成交与取消/期权/六周期图表/控盘/分类杠杆/历史一致性等检查仍执行；**不是保留所有旧数值字面量**，不再合法的量纲与控盘参数按规则重算，未放宽为范围或近似断言。

## 严格金额依据

- BTC 主场景：原 2 手 × 10 改为 20 BTC × 1；价格100、杠杆10，保证金仍200；佣金20 × 0.2仍4。
- 分类杠杆：原 1 × 10 改为 10 BTC × 1；无杠杆保证金仍1000。
- EURUSD 主场景：0.02 × 100000 × 100 / 10 = 20000 保证金；0.02 × 200 = 4 佣金。这里 EURUSD=100 为合成价格，不是市场行情。
- ETHBTC：开仓汇率100，保证金2 × 1 × 100 × 100 / 10 = 2000；平仓实时汇率200，盈利2 × (105−100) × 200 = 2000。
- USDJPY：0.01标准手、100000乘数、USD基础货币，保证金0.01 × 100000 / 10 = 100；平仓合成JPY/USD=200，盈利0.01 × 100000 × 5 × 200 = 1000000。金额夸张来自合成汇率，仅用于精确验证换汇路径。

## 隔离与源码指纹

独立目录：`C:/workspace/fx/new/d04-market-20260929-034038`。

- 仅复制 backend 的 src/pom，共413个文件；未复制业务 dump、uploads、.env 或共享 target。
- 源码初始逐文件 SHA-256：`source-before.json`。清单 SHA-256：`4E9A04E1451B8065A0D848F3A76EFC9169BAAE0ADEC22948AB16694116300048`。
- `snapshot-changes.json` 证明独立快照仅 CatalogTradingScenario.java 改变，快照生产代码未改。
- 测试文件原 SHA-256：`1A824CD537DBEAB2CE36F351FC4A7ECE61F7E2E0569314FEABA0CC48AB57A373`。
- 交付 SHA-256：`A2FE9E22C49485CE53562A3E8305E32F848D279AD7AE62BA1C3F3A2B3C8AE1A0`。首次通过后，检查共享文件仍等于原哈希才原子写回；最终复跑测试源码相同。
- 未改 MarketIsolationTest.java SHA-256：`F3053CBB7C68603AB4ECC3E1FAD6E11941D47CE2CFCD06CDD3ABD7EECCF78B74`。
- 全部重要日志/XML/运行器哈希见 `delivery-hashes.json`；原文件备份 `backup`，本轮差异 `fixture.diff`。

每轮新建独占 MySQL 5.7、Redis 7，随机 127.0.0.1 端口、tmpfs 数据、合成账号。仅替换供应商为本地 HTTP 模拟数据；应用 HTTP、管理权限、KYC、订单、结算、数据库和 Redis 真实执行。Maven/JVM分别256/768MB，临时容器限制2CPU/2GB。邮件指向127.0.0.1:1，行情流关闭，未接触业务库或重启服务。

自建容器通过专属 `qa705-d04=true` 标签核验后清理；最终 `remaining-test-containers.txt` 为空。不清理其他会话容器。

## 命令、退出码与证据

工作目录 `C:/workspace/fx/705`，运行器：

```powershell
$q = 'C:/workspace/fx/new/d04-market-20260929-034038'
& "$q/run-market.ps1" -Report $q
```

运行器内实际测试入口（环境变量由其配置为独立 MySQL/Redis）：

```powershell
mvn.cmd -B -f "$q/source/exchange-backend/pom.xml" '-Dtest=MarketIsolationTest#catalogTradingChain' '-DargLine=-Xmx768m' test
```

**退出码以 Maven 的实际记录为准**，`exits.txt` 依次为1、1、1、0、0；早期运行器随后输出/清理成功导致外层 PowerShell 退出0，不能将该外层退出码当成用例通过。

- 第一轮：Maven exit1；规格前置条件通过，随后旧控盘参数失败。`attempt1-amplitude.log`、`attempt1.xml`。
- 第二轮：Maven exit1；六品类和分类杠杆通过，ETHBTC批量模拟行情缺失超时。`attempt2-batch.log`、`attempt2.xml`。
- 第三轮：Maven exit1；ETHBTC换汇通过，USDJPY手动平仓遇到乐观锁冲突。`attempt3-close-conflict.log`、`attempt3.xml`。
- 第四轮：Maven exit0；1项JUnit方法、0失败/错误/跳过。`pass1.log`、`pass1.xml`。
- 第五轮：同最终测试源码、全新数据库/Redis；Maven exit0；1项JUnit方法、0失败/错误/跳过。`market-final.log`、`market-mysql-xml/TEST-com.gtcfesk.exchange.market.MarketIsolationTest.xml`。
- 本文件 `git diff --check` exit0，未检查或修改其他负责人工作区差异。

最终每轮均验证：六品类完整链路；ETHBTC、USDJPY两条换汇链路；合约数据库/用户历史/后台历史各33条且逐字段一致，期权各6条；控盘价格订单合约8条、期权6条；最终冻结余额为0。不是33+6个独立JUnit测试，也不是全项目通过。

最终日志 SHA-256：`68A6B0A68391F005D672103EBFFF8E3A169113C485A66DC88ED0954B8FB04511`。
最终 XML SHA-256：`989F64663510DC363F537348FF866DD24C1F4364BE1FECB261224868E4A5759B`。

## 尚需调度保留的风险：平仓并发冲突

第三轮真实HTTP平仓返回400：`平仓失败: 数据已变更，请刷新后重试`。日志包含 `ObjectOptimisticLockingFailureException` / `StaleStateException`，`contract_order ... where id=? and row_version=?` 更新0行。

这是执行到新阶段后观察到的服务端并发冲突，**不能只归为缺规格夹具问题**。可能与行情/订单后台刷新竞争有关，但本轮没有进一步定位竞争线程，不把推断写成确定根因。随后两次成功不抹去这次失败，也不能证明并发稳定。

未改生产事务/锁、未自动重试或吞掉此400；只在原平仓调用失败时打印合成订单状态并重新抛出，原断言仍失败。冲突证据SHA-256：`CDA1A0F2DEB71C1D253E5118B97A96E2393CA1790FFDBEE8D8CF32F271CDDA84`。建议订单负责人另行做可控并发复现、核验客户端重试语义及资金幂等性；本轮不越界修改。

## 交付范围

D04已完成夹具适配和两轮定向通过；其他编号、全量发布闸门、最新并行修改均未复验。共享生产服务、数据库、target、Git索引和提交均未操作。回滚只能撤销本轮 `fixture.diff` 中测试改动，不整仓重置；保留其他会话进度。