# 手动模拟单一键生成：交接记录（2026-09-28）

来源会话：[分析原因并按规则测试](codex://threads/01a0e84b-e9fc-7fa2-b654-0b1ca025f87e)。该会话最后一次执行因远端上下文压缩连接失败中断，不代表其未提交的代码已验收。本文件记录当前会话接手后的核对与继续执行结果。

## 本轮需求

- 平仓分钟留空：取最近七天内**最新有有效分钟行情和换算率的已结束分钟**作为平仓时间；在其之前最多七天找符合其他输入目标的开仓分钟。无数据或无解须在提示中明确说明。
- 只填写目标平仓价：平仓时间仍取最近有效分钟；自动选择开仓时间、手数和仓位。实际平仓价必须在目标价 ±5% 内；不改历史行情价。
- 沿用上一轮规则：填写的手数、仓位比例、净收益是可同时满足的 ±5% 目标；方向、杠杆和显式填写的时间保持固定。生成只预览，不创建订单或调整资金。

## 接手时状态

- 工作树位于 `C:/workspace/fx/705` 的 `main`，有大量其他未提交变更。**不要执行全仓 reset/clean，也不要覆盖其他任务文件。**
- 中断前已修改生成器、服务、后台组件与请求工具，增加目标平仓价、最新平仓分钟、七天回找及提示；相关测试和设计文档也已改。源代码已存在，不要重做第二套实现。
- 本轮代码备份已由原会话写入 `C:/workspace/fx/705/rollback/manual-order-latest-close-20260928/`。此目录是本轮修改前快照，不是整个仓库的可直接覆盖式回滚。

## 关键实现位置

- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java`：`generate` 在 `closeLocal` 留空时选择 `recent.lastKey()`；仅在固定的平仓分钟前七天搜索；最终仍调用权威 `preview` 并重查目标。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderPrices.java`：`generationCandles` 批量取得有效分钟及换算率，缺值不造价。
- `exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderGenerator.java`：`targetClosePrice` 与其他 ±5% 目标并行校验；只从真实分钟开盘价选候选。
- `exchange-admin/src/utils/manualOrderGeneration.ts` 与 `exchange-admin/src/components/ManualContractOrder.vue`：目标价单独输入；未填写时间不随自动结果被反向固定；失败信息展示在表单。
- `docs/manual-order-generation.md`：业务规则及验收口径。

## 接手后验证记录

- 前端 `manualOrderGeneration.test.mjs`、`manualOrderGenerationMatrix.test.mjs`、`manualOrderLifecycle.test.mjs`：退出码 0；矩阵脚本报告 256 组请求掩码通过。
- 后端定向 `ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderScreenshotRuleTest,ManualOrderMinutesTest,ManualOrderCalculationTest`：在项目原有 Maven 编译配置下退出码 0，合计 162 项，失败/错误/跳过均为 0。
- `scripts/manual-order/Test-MySql.ps1` 第一次运行：39 项中 1 项错误。旧事务测试把 2026-09-20 的固定开仓分钟与“留空平仓取 2026-09-28 最近分钟”组合，违反新七天规则。已将该**事务测试**改成显式历史平仓分钟；最新分钟自动选择由 `blankCloseUsesLatestRealMinuteAndSearchesOnlyPriorSevenDays` 单独覆盖。修改前备份：`rollback/manual-order-resume-20260928/ManualOrderMySqlIT.java`。
- 隔离 MySQL 5.7 重新运行 `scripts/manual-order/Test-MySql.ps1`：退出码 0，39 项通过，失败/错误/跳过均为 0；其中 `ManualOrderMySqlIT` 34 项。临时数据库容器由脚本核对标签后清理，未触碰既有容器。完整运行日志：`%TEMP%/manual-order-resume-mysql-20260928.log`。
- `npm run build`（`exchange-admin`）：退出码 0。`manualOrderLatestClose.browser.cjs`：退出码 0，隔离 API 的真实 Chrome 页面验证了只填目标价时不固定开平仓时间、显示自动平仓结果、七天无解提示和不调用创建接口。测试文件：`exchange-admin/tests/manualOrderLatestClose.browser.cjs`；本地 Vite 测试服务已停止。该浏览器测试使用夹具，不代表生产环境联调。
- 本任务相关 `git diff --check`：退出码 0。
- 额外强制 `-Dmaven.compiler.release=8` 时，全量测试编译先被**其他任务的** `BalancedControlPlanTest.java:96,99` 阻断：`BigInteger.TWO`、`List.of` 不属于 Java 8 API。该失败不应写成生成器测试失败，也不能写成 Java 8 兼容已验证。

## 2026-09-29 续测更新

发现并修复手续费占满目标仓位预算时的误报无解：生成器现在除精确仓位反算外，也试常用杠杆和仓位容差上界反算；最终计算仍严格执行填写目标的 ±5% 限制。新增 `ManualOrderFeasibilityTest.java`，4 组随机行情 × 128 种已知可行条件，512/512 成功，另含手续费底线、非常用杠杆、杠杆精度上界及真正无解拒绝。隔离 MySQL 服务矩阵扩展至 256 种条件，256/256 成功且未创建订单或改变钱包；新增服务级手续费案例。真实 Chrome 夹具扩展为只填价格、多目标混合、七天无解三种流程，均通过。

本次后端定向单测 166 项通过；隔离 MySQL 40 项通过；后台构建及 Java 8 API 约束下**主代码**打包通过。全测试强制 Java 8 编译仍受上述并行测试文件阻断。本轮完整命令、证据、基准、边界及回滚见 `docs/manual-order-feasibility-20260929.md`。新增修改前快照在 `rollback/manual-order-feasibility-20260928/`。

## 剩余与边界

本轮已完成源码、隔离数据库与浏览器夹具验证；**未部署到线上，也未使用真实账户或行情发起生成/创建**。若后续要在测试环境联调，先确认环境归属，再以专用测试账户验证真实行情覆盖、最近分钟选择与无解提示；不要把夹具结果写成线上验收。Java 8 强制 release 下的全仓测试编译问题属于并行任务，需要其所有者修复后再做该兼容验证。

## 回滚

本轮如需回退，仅对照 `rollback/manual-order-latest-close-20260928/` 与 `rollback/manual-order-resume-20260928/` 恢复与本需求对应的差异；其中源文件含有先前及并行任务改动，不可直接整目录覆盖。生成预览不写订单、钱包或历史表；本轮尚未部署。
