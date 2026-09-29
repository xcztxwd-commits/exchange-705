# MT02 数值等价摘要修复与真实浏览器闭环

2026-09-29，Asia/Singapore；最终 HTTP 验收完成于 05:34。

## 结论

MT02 已修复。真实本机浏览器从五目标「一键生成」直接「确认创建」返回 HTTP 200；独立数据库最终 orders=2、audit=2（包含先前 HTTP 正例一单和浏览器一单），available=1000、frozen=0。未改条件、未重新 preview、未绕过摘要校验。

未部署、未提交推送、未使用业务账户/密钥、未写业务库或共享 target、未改客服文件。该结论限本机隔离服务与合成行情，不代表生产环境验收。

## 修改

责任生产文件：`C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ManualOrderService.java`。

1. input、leverage、targetNet 从原始 BigDecimal 使用 `stripTrailingZeros().toString()` 得到精确标准表示，然后进入同一排序摘要。尾零、科学计数和正负零等价；不经 double，不舍入金额。使用 toString 而非 toPlainString，避免未验证的大指数被展开为巨量文本。
2. 操作者、用户、品种、时区、时间、方向、规格、资金开关和真实数值变化仍绑定摘要；previewToken/idempotencyKey 仍按原设计排除。
3. 旧版已落库幂等摘要若不匹配，精确解析审计 evidence.request，使用已记录 operator_id 按新规范重算比较。保留老订单的等价重试，不重复下单或入账；不同值/不同操作者仍拒绝，证据无法解析则拒绝。没有“旧记录直接放行”分支。
4. 预览过期、权限/身份绑定、MT01 杠杆、数量和资金规则未移除。已提交订单的幂等重放仍沿用原先在预览时效检查之前返回的语义。

新增共享 `ManualOrderHashTest.java`；扩充 `ManualOrderMySqlIT.java`；更新 `docs/manual-order-generation.md`。前端产品代码无改动。

## 最终复测结果

- **703/703 定向 Java 测试通过**，0 失败/错误/跳过。包括新的 7 项摘要测试、原独立失败的 ManualHashRecheckTest 原样 1 项，以及 MT01、五目标扰动、矩阵、公式等原测试。
- **46/46 隔离 MySQL 测试通过**（ManualOrderMySqlIT 41 项 + CalculationTest 5 项）。新增尾零/科学计数的 preview→create、服务重建后的持久幂等、模拟旧摘要审计记录的等价重放、真实金额改变拒绝、操作者改变拒绝、等价数值不能绕过预览过期。钱包仅入账一次。
- **4/4 Node 脚本通过**：manualOrderEstimate、manualOrderGeneration、manualOrderGenerationMatrix、manualOrderLifecycle。
- **真实 HTTP + 浏览器集成 1/1 通过**，browser 脚本 exit 0。真实 Spring Boot、密码验证/JWT 登录、控制器/服务、MySQL；只 mock 行情和外部依赖，不 mock 业务 API。readonly generate=403；杠杆 generate/preview/create 负例=400；10.4 正例和幂等=200；浏览器五目标 generate=200 后直接 create=200，SQL 校验通过。

这些组包含重复覆盖，不相加宣称独立场景数。

## 浏览器验证与网络隔离

测试路径：本机独立 HTML 壳挂载真实 ManualContractOrder 组件，真实登录，选合成账户/QA_BTCUSD，输入杠杆10、BTC数量0.01、仓位0.01003%、净收益0.0997、目标平仓价110，一键生成后不编辑直接确认。

- 地址 `http://127.0.0.1:18398/__qa`；Chromium headless，1280×1400。
- 未提供 Browser skill，沿用已有 Playwright 本机测试；没有安装依赖。
- 复制源码时排除所有 `.env*`；独立 Vite 设置 `envDir:false`，强制 `VITE_API_BASE_URL='/api'`，代理只指向动态随机 127.0.0.1 后端端口。
- 导航和登录前已设置路由拦截，非 127.0.0.1 请求直接阻断；最终断言没有尝试非本机请求。登录请求禁止跟随重定向。
- JWT 密钥每次运行使用 RandomNumberGenerator 生成随机64字节，仅进程环境传给隔离应用，结束清除；没有业务凭据或固定业务签名密钥。
- 页面身份、非空组件、无 Vite 错误覆盖层、五目标保留、BTC单位、0.0003手续费、真实创建返回值均通过。
- pageerror 为空。但 Chromium 阻断本机 Vite HMR WebSocket，console 有 `ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS` 和 HMR 连接提示；HTTP/API/创建不受影响。不宣称控制台全无警告，也没有关闭浏览器安全检查。
- 已查看 `browser-real.png` 和 `browser-created.png`。创建截图处于弹窗关闭动画附近，**创建成功以 browser-wire.jsonl 的 HTTP 200 和服务端最终 SQL 断言为准**，不单凭截图判断。

所有测试 MySQL 容器仅回环端口、tmpfs、2 CPU/2GB、随机名称；按自建标签核验删除。自建 Vite session 已停止，remaining-http-containers.txt、remaining-mysql-containers.txt 均为空。未重启已有服务。

## 证据、命令与退出码

证据根目录 R：`C:/workspace/fx/new/manual-mt02-fix-20260929`。实际测试副本为 `R/source`，所有 Maven 产物在副本 target。

```powershell
$env:MAVEN_OPTS='-Xmx256m'
mvn -B -f C:/workspace/fx/new/manual-mt02-fix-20260929/source/exchange-backend/pom.xml '-Dtest=ManualOrderHashTest,ManualHashRecheckTest,ManualOrderLeverageBoundsTest,ManualOrderToleranceTest,ManualOrderGeneratorTest,ManualOrderGenerationMatrixTest,ManualOrderFeasibilityTest,ManualOrderScreenshotRuleTest,ManualOrderCalculationTest' '-DargLine=-Xmx768m' test
& C:/workspace/fx/new/manual-mt02-fix-20260929/source/scripts/manual-order/Test-MySql.ps1
& C:/workspace/fx/new/manual-mt02-fix-20260929/run-http.ps1 -Root C:/workspace/fx/new/manual-mt02-fix-20260929
```

- `focused-release.log` / `focused-release-exit.txt`：0，703 项，最终源码。
- `mysql-release.log` / `mysql-release-exit.txt`：0，46 项，最终源码。runner 的 Maven 选择 `ManualOrderCalculationTest,ManualOrderMySqlIT`，`-DargLine=-Xmx768m`。
- `http.log` / `http-exit.txt`：0，ManualRecheckHttpTest 1 项。最终 SQL 原文：`orders=2 audit=2 wallet=[{available=1000.0000000000000000, frozen=0E-16}]`。
- `browser-real.log` / `browser-real-exit.txt`：0。实际命令 `node R/source/exchange-admin/tests/recheck-real.browser.cjs`，QA_EVIDENCE=R；执行前已启动强制 loopback 的独立 Vite。
- `node.log` / `node-exit.txt`：0，`node --test` 指定上述四个 `R/source/exchange-admin/tests/*.test.mjs`。
- `browser-wire.jsonl` 记录本机非登录 API 请求/响应，不记录 JWT；`browser-api.json`、`browser-done.txt` 为浏览器结果。
- XML 在 `R/source/exchange-backend/target/surefire-reports`。`focused.log`、`focused-final.log`、`mysql.log`、`mysql-final.log` 是途中版本成功结果，**最终验收以 release 日志和最终源码指纹为准**。

## 最终源码指纹与回滚

`R/final-source-hashes.json` 比较共享源码与实际测试副本，3/3 一致；`R/fixture-hashes.json` 保存隔离 HTTP runner、Java fixture、Vite配置和浏览器脚本指纹。`R/source-tested.json` 是最初快照清单，不冒充最终补丁指纹。

- ManualOrderService.java：`faef65323e4c716b43f1bb00abe31678b27990715e8fdba2671c80ca9d82f8f6`
- ManualOrderMySqlIT.java：`659519c086d95c48752cfed070f222a99f28d16f45771689511f008ef890891d`
- ManualOrderHashTest.java：`8b6d8476544b3b91fa2d5a0440d9fe29b099bdc7c1a10b58f949100b77c75d19`

`R/before` 保存修改前生产文件、原 MySQL 测试和文档。回滚须先核对最终指纹无他人后续改动，再逐文件恢复；不要整体覆盖工作区。部署回滚涉及新旧摘要格式，需另行验收持久幂等兼容，不在本轮执行。

## 未验证

未跑完整后台登录页导航、移动端、多浏览器、真实外部行情、生产环境或全仓测试；只覆盖本机真实组件壳到隔离 HTTP/MySQL 的指定闭环。浏览器钱包关闭，钱包入账一次及拒绝/回滚在 MySQL 服务测试覆盖。未部署。
