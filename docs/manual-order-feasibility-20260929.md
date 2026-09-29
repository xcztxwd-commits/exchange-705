# 一键生成：多条件可行性与回归测试（2026-09-29）

## 结果

本轮在 `C:/workspace/fx/705` 接续手动模拟单生成任务。发现并修复一个**可行却误报无解**的情形：目标仓位附近的手续费已占满预算时，旧搜索仅反算“恰好等于目标仓位”的杠杆；预算不大于零便跳过手数。但实际仓位仍可能落在允许的 ±5% 内。现在对每个候选手数同时检查常用杠杆、目标仓位反算杠杆及容差上界反算杠杆，再由权威计算与最终预览逐项校验。固定杠杆不变，绝不放宽用户填写的 ±5% 边界。

## 覆盖与实测

| 层次 | 场景 | 本轮结果 |
| --- | --- | --- |
| 纯计算 | 128 种方向/杠杆/手数/仓位/净收益/价格/开仓时间组合，4 组随机行情与手续费；每例有独立合法解 | 512/512 成功 |
| 纯计算边界 | 手续费底线仍在仓位容差内、需非常用杠杆、杠杆精度上限、真正超界应拒绝 | 全通过 |
| 后端定向测试 | `ManualOrderFeasibilityTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderScreenshotRuleTest,ManualOrderMinutesTest,ManualOrderCalculationTest` | 166 项通过；失败/错误/跳过均为 0 |
| 隔离 MySQL 5.7 | `scripts/manual-order/Test-MySql.ps1`，含真实 `ManualOrderService.generate` 与最终预览、失败不入账 | 40 项通过；失败/错误/跳过均为 0 |
| 服务组合矩阵 | 上述 MySQL 测试中的 256 种输入掩码，新增目标平仓价维度 | 256/256 成功；订单、钱包及历史表保持不变；模拟行情请求不超过 18 次/例 |
| 后台请求与生命周期 | 256 种请求掩码、目标值清空/重填、旧响应隔离 | 全通过 |
| Chrome 页面夹具 | 只填价格、手数/仓位/净收益/方向/杠杆/价格同时填写、七天无解提示 | 全通过；未调用创建接口 |
| 构建 | 后台 `npm run build`；后端强制 Java 8 API 的主代码打包 | 退出码均为 0 |

本地合成的 **10080 个分钟**搜索基准：仅价格约 0.85 秒，手数/仓位/净收益混合约 3.89 秒。这只衡量内存候选选择，不含网络行情或数据库，**不是线上响应时间承诺**。服务矩阵夹具平均约 49.9 毫秒、最大约 85.5 毫秒，同样不是生产性能数据。

## 命令与证据

```powershell
Set-Location C:/workspace/fx/705/exchange-backend
mvn -B -q '-Dtest=ManualOrderFeasibilityTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderScreenshotRuleTest,ManualOrderMinutesTest,ManualOrderCalculationTest' test
mvn -B -q '-Dmaven.compiler.release=8' '-Dmaven.test.skip=true' package
Set-Location C:/workspace/fx/705
& ./scripts/manual-order/Test-MySql.ps1
Set-Location C:/workspace/fx/705/exchange-admin
node tests/manualOrderGeneration.test.mjs
node tests/manualOrderGenerationMatrix.test.mjs
node tests/manualOrderLifecycle.test.mjs
npm run build
# Chrome 夹具测试：先在另一个终端启动 npm run dev -- --host 127.0.0.1 --port 5198 --strictPort
node tests/manualOrderLatestClose.browser.cjs
```

- 本轮最后一次隔离 MySQL 日志：`%TEMP%/manual-order-feasibility-mysql-final-20260928.log`；`ManualOrderMySqlIT` 35 项、计算测试 5 项，均无跳过。
- 服务组合明细：`exchange-backend/target/manual-generation-matrix/service-matrix-256.csv`，257 行含表头，256 行均为 PASS。
- 后端 Surefire XML：`exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.trade.*.xml`。
- `git diff --check` 对本轮相关已跟踪文件退出码 0；隔离数据库脚本结束后没有遗留其标签的容器。

## 文件与回滚

- 逻辑：`exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderGenerator.java`。
- 新增可行性测试：`exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderFeasibilityTest.java`。
- 扩展服务矩阵与手续费场景：`exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualOrderMySqlIT.java`。
- 扩展页面夹具：`exchange-admin/tests/manualOrderLatestClose.browser.cjs`。
- 修改前快照：`rollback/manual-order-feasibility-20260928/`。原工作树存在大量并行未提交修改，回滚必须只对照本轮差异，**不可整文件或全仓覆盖**。没有 Git 提交、推送或线上部署。

## 未验证范围

未对真实行情源、真实账户或线上环境执行一键生成/创建；没有修改资金。强制 `-Dmaven.compiler.release=8` 编译**全测试**仍被另一任务的 `BalancedControlPlanTest.java:96,99` 使用 `BigInteger.TWO` 与 `List.of` 阻断；本轮主代码在 Java 8 API 约束下已打包通过。行情缺失、固定条件真正冲突或目标超出 ±5% 时仍应明确失败，不能以伪造行情或悄悄改目标换取成功率。
