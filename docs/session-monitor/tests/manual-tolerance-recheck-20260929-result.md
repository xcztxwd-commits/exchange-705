# MT01 修复独立复验 — 2026-09-29

## 结论

**MT01 杠杆范围修复通过；整体验收不通过。新发现 MT02：生成后直接确认，真实浏览器返回 HTTP 400「预览过期或参数已变更，请重新预览」。** 两次本机隔离浏览器复现。没有删除失败断言、修改业务源码或部署。

- Java 定向 700/700、原 MySQL 44/44、上轮独立夹具原样 3/3，全部退出 0。
- Node 四个定向测试脚本 4/4，退出 0。
- 真实 HTTP 集成用例 1 项失败（退出 1）：登录、权限、generate/preview/create 杠杆负例、10.4 正例、幂等均先通过；最后浏览器直接创建失败。
- 独立数值等价 hash 回归 1 项失败（退出 1），确认请求摘要对 BigDecimal scale 敏感。
- 浏览器五目标保留、实际值/偏差、BTC 单位与 0.0003 手续费通过；浏览器生成后直接创建不通过。

## 版本与隔离证据

证据根目录：`C:/workspace/fx/new/manual-recheck-20260929-045631`（下文 R）。独立源码：`R/source`。
HEAD：`a3818cb44d854dff76e1a66770d9772d8c0f5d5b`，实际版本以未提交文件哈希为准。

已读取原计划 `C:/workspace/fx/705/docs/session-monitor/tests/manual-tolerance-20260929.md` 与修复交付 `C:/workspace/fx/705/docs/session-monitor/tests/manual-tolerance-mt01-fix-20260929.md`。

交付三个哈希全部匹配：
- ManualOrderCalculation：b7a5ee505a924b4b70634f45f8d09ed79d7edf11ff09f7195a5b108b107f6cf4
- ManualOrderGenerator：b77091060db45174ec2cd951405da3c009589a71619ca960ce4b1b711afdc2c7
- ManualOrderService：acbcfd66b73eed5d3bb95ed3239932ebaa36b47dc91871c9f8c05a73d6c88981

`R/source-before.json`、`R/copy-verification.json`、`R/source-after.json` 记录 668 个原文件；复制前后匹配，结束共享源漂移 0、快照原文件漂移 0。新增夹具仅在独立目录。原独立测试与上轮 SHA-256 一致，见 `R/original-test-hashes.json`。

重测试串行；随机 loopback 端口、MySQL 5.7 tmpfs 独立容器（2 CPU/2GB）、合成账户/钱包/行情。真实 Spring Boot、JWT 登录、权限、控制器、服务、MySQL；行情源、Redis/邮件等外部设施用测试替身。不是生产行情实测。Vite 独立缓存，复用只读依赖。未部署、未重启已有服务、未写 Git index。自建 Vite 已停止，自建 HTTP 容器已删除。

### 必须披露的执行偏差

最初三次浏览器调试继承快照 `.env.development` 的远程 API 基址 `https://705api.haiwaiym38.top/api`，组件 context GET 意外发往该地址并返回 404；本地登录产生的合成测试 JWT 可能随这些 GET 发出。登录本身仍通过本机代理；没有进入该远程接口的 generate/create，也没有取得业务数据。不能将这三次称作全程网络隔离。发现后停止该轮，仅在独立 Vite 配置强制 `/api`，并在浏览器阻断非 127.0.0.1 请求。第四、第五轮 context/generate/create 均为本机真实 HTTP。保留 first/second/third 日志，不隐瞒该偏差。

## 已执行覆盖

1. **两个原反例**：maxLeverage=5、目标10拒绝；目标0.5拒绝。原样 `ManualToleranceIndependentTest` 3/3（上一轮1通过2失败）。合法五目标同时成立；独立公式核对毛利、费、净收益和向上取16位保证金；generate 不写订单、不动钱包；错误规格协议拒绝。
2. **边界交集**：目标0.96/1/4.99/5/5.26与上限5的有效交集；0.5/0.95/5.27/10无交集；20目标/19上限恰好-5%接受，20.01拒绝；1.05目标/1上限接受、1.06拒绝；1.23小数上限及10.4小数杠杆；非法配置上限拒绝。通过 `ManualOrderLeverageBoundsTest`，并入700项。
3. **直接 preview/create**：HTTP preview杠杆0.5返回400，10.4返回200；预览后直接SQL将上限改5且不改rowVersion，create返回400；订单/审计0、钱包不变。恢复上限20后create200，10.4持久化，重复幂等请求仍1单；MANUAL_TEST标签通过。
4. **真实登录/权限**：真实密码编码与JWT登录；readonly调用generate403，super_admin允许；未使用伪造登录响应。
5. **真实浏览器目标保持**：生产组件挂在独立HTML壳，Vue/Pinia/Element Plus真实运行；仅壳被替换、API无mock。选择合成用户/QA_BTCUSD/做多，输入杠杆10、数量0.01、仓位0.01003%、净收益0.0997、平仓价110。generate200，五目标仍保持原输入、各偏差0%，手续费0.0003，BTC单位正确；自动时间对应合成最新已结束有效分钟。截图已人工查看。
6. **D01/D02兼容**：AdminOrderQuantityProjectionTest、CryptoQuantityLifecycleTest并入当前700项通过，不引用旧快照结果充数。

## MT02 — 生成预览摘要与浏览器提交不一致（阻断创建）

责任位置：
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java:204-206`：hash直接序列化包含BigDecimal的Map，未统一数值表示；233行拒绝不一致摘要。
- `C:/workspace/fx/705/exchange-admin/src/components/ManualContractOrder.vue:90-93`：JSON数值转String后丢失尾零；137-141行将form提交。前端数值往返是正常行为，服务端摘要应对数值语义一致的请求稳定。

最小复现：本机真实登录后，填写上述五目标，点击「一键生成」成功，再立即点击「确认创建已平仓单」，没有编辑任何字段，立即400。第四和第五轮均出现，非预览超时。

第五轮wire：generate返回 `request.input:0.0100000000000000`，浏览器提交 `input:"0.01"`；其他时间/方向/用户/开关保持。响应为「预览过期或参数已变更，请重新预览」。`R/browser-wire.jsonl`保存本机URL、请求体、响应体（无登录令牌）。

独立 `ManualHashRecheckTest` 使用实际服务私有hash：数值相等的input 0.0100000000000000/0.01、leverage 10.00/10产生不同摘要；assertEquals失败。`R/hash-probe.log`保存差异。建议在摘要入口规范化BigDecimal数值表示，保持真正不同参数仍被拒绝；补JSON数值往返的generate→create回归。本轮不修改业务代码。

第五轮失败后SQL：orders=1、audit=1（均为先前合法10.4直接HTTP创建），available=1000.0000000000000000、frozen=0；浏览器失败未增加订单/审计、未改变钱包。最终期望第二单的断言未通过，不能宣称端到端闭环成功。

## 命令、退出码、证据

均在R/source运行；Java21，Maven3.9.9，MAVEN_OPTS=-Xmx256m，测试JVM=-Xmx768m。

- `mvn.cmd -B -f exchange-backend/pom.xml -Dtest=ManualOrderLeverageBoundsTest,ManualOrderToleranceTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderFeasibilityTest,ManualOrderScreenshotRuleTest,ManualOrderCalculationTest,AdminOrderQuantityProjectionTest,CryptoQuantityLifecycleTest -DargLine=-Xmx768m test`：0；`R/focused.log`、`R/focused-exit.txt`，700项。
- `R/source/scripts/manual-order/Test-Recheck.ps1`：Maven选择ManualOrderCalculationTest,ManualOrderMySqlIT；0；`R/mysql.log`、`R/mysql-exit.txt`，44项。
- `R/source/scripts/manual-order/Test-Independent.ps1`：原样ManualToleranceIndependentTest；0；`R/independent.log`、`R/independent-exit.txt`，3项。
- `node.exe --test` 四脚本 manualOrderEstimate、manualOrderGeneration、manualOrderGenerationMatrix、manualOrderLifecycle（R/source/exchange-admin/tests内）：0；`R/node.log`、`R/node-exit.txt`。
- `R/run-http.ps1 -Root R`：Maven选择ManualRecheckHttpTest；测试退出1，外层容器清理脚本结束码不等于测试通过；`R/http.log`、`R/http-exit.txt`。
- `node.exe R/source/exchange-admin/tests/recheck-real.browser.cjs`，QA_EVIDENCE=R：1；`R/browser-real.log`、`R/browser-real-exit.txt`、`R/browser-wire.jsonl`、`R/browser-real.png`、`R/browser-failed.png`。
- `mvn.cmd -B -f exchange-backend/pom.xml -Dtest=ManualHashRecheckTest -DargLine=-Xmx768m test`：1；`R/hash-probe.log`、`R/hash-probe-exit.txt`。

## 未验证 / 不扩大结论

本轮浏览器为真实组件加独立壳，不是完整管理后台登录页导航；未在浏览器逐个跑币/股/外汇/NULL单位，未重复上轮全部旧响应竞态场景。当前浏览器原生币五目标通过；其他覆盖以列出的Java/Node断言为准。未验证线上配置、实际外部行情、集群跨实例预览缓存，也未部署。MT02未修复，整体验收保持不通过；MT01本身可以关闭。