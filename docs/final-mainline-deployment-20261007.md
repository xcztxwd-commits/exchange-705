# 三会话合并、控盘恢复修复与主线部署交付记录

## 当前状态：共同发布、限定 API 与公共静态/客服 GET 均 PASS

**配置漂移已先停止、核清来源，再经用户授权协调合并。共同 e947646 版本的 main/admin 已单次真实发布，完整制品、正常配置、双服务 90 秒健康和 175 个非目标保护 PASS。新独立键取消 200、晚到 START 202 且同一 CANCELLED 凭证、零新任务已真实通过。新的四入口公共资源及客服固定 GET 已独立真实通过；这些结果不是浏览器渲染或完整应用验收，也不解除正式生产四项门禁。旧 805 漂移拦截与原 500 失败证据保留。**

日期：2026-10-07。**这是基于实际证据的交付记录：原完整版本与后续共同兼容修复均已真实发布；受限负向 API 验证 PASS。H5 `/mobile/` 与共同发布后的公共四入口、客服配置 GET 均有真实局部 PASS。不得将静态资源、服务健康或本地测试等同于全部业务验收。**

本次继续使用指定的现有 `3d6b/705` 工作树，没有新建、重置工作树或覆盖原来源工作树的未提交改动。以下结果更新之前报告对应时间窗口的状态；旧失败、跳过和阻断证据仍保留，不改写为当时已成功。

## 1. 已完成、待补与未实施

| 项目 | 当前真实结果 | 验证边界 |
| --- | --- | --- |
| 当前控盘及两个指定会话、后续客服兼容修复的代码整理与主线合并 | 已完成；当前应用主线 `e947646` | PR 5 合并共同源码；候选 OCI revision 不冒称等于后续文档提交。 |
| 控盘 P0/P1、列设置刷新丢失、权限故障恢复 | 代码已落实，并完成下述本地验证 | 不等于所有线上身份和交互已验收。 |
| 完整不可变应用与静态制品 | 共同 main/admin 已核验并真实发布；维护回滚制品已有分层内容验证 | 历史 805 未部署；EA 仅 Java/JAR 验证、b27 仅静态 42 文件验证，配置依赖完整启动与正常业务回滚未演练。 |
| 仅变化的目标库 `1090` 备份及独立完整恢复 | 真实 PASS，无 mock | 113 表、12,262,965 行、全部字段、161 个触发器、1 个存储过程保全。未对其它业务库或全实例备份、迁移。 |
| 0701/0702 精确受控增量 | 真实 COMPLETE | 只新增两张业务表的五列及一条新版本收据，保留原始数据、旧版本收据和隔离保护。 |
| 真实 SELECT-only 候选启动 | PASS，激活前已停止、排空 | 实际完整 JAR、Java 8、Spring 日志及稳定健康取证；不替代正常权限全业务验收。 |
| 0702 单次受控激活及 package-check | 真实 COMPLETE / PASS | 只激活精确的 0702 收据；不重放迁移，不手改任务，不伪造双签批准。 |
| main-api/admin/control/mobile/pc 原完整版本正常配置发布及 main 原地恢复 | 先前版本真实 PASS；不含 Latin1 后续修复 | 当时 main 稳定 healthy 至少 90 秒，实际 JAR、Java、Spring 日志、五服务完整配置和 172 个受保护容器比对通过；不能作为 805 发布结果。 |
| gateway 旧 DNS 导致的 index 错投 | 已诊断并 reload 原 gateway | 只 reload，未改 gateway 配置、镜像或转发规则；后续四入口原 href 静态资源另有真实验收。 |
| 正常 admin 身份及限定负向 API | 登录 200、五项 GET 200；共同版本新键取消 200、晚到 START 202，同一 CANCELLED 凭证且零新任务，六组旧范围行精确不变 | 原 403、500 及失败键零 command/task 证据保留。未验证 fresh START/RESTORE、线上重启/网络/断源或其它租户写入。 |
| H5 与共同发布后四入口原 href 资源 | 新独立真实 GET 轮次 PASS | admin/control 各 2 个主资产、PC/mobile 各 7，mobile 85 依赖、14 图片、home/history/version 的 normal TLS/hash/MIME 通过；不是浏览器渲染或业务。 |
| 客服公开配置与新 admin 内容 | 四个固定 GET 实验 PASS | 两客服接口 200/external/link hash 一致，available=true；真实新 admin index/JS 及说明精确匹配，不导航第三方、不称完整用户 UI。 |
| 共同 Latin1/客服 main/admin 后续发布 | **真实 PASS** | 仅替换两个镜像，六个现有源配置不覆盖；177 全配置、175 保护、demo 热 JAR、双服务 90 秒健康及 main JAR/admin 整目录通过。 |
| 当前热语义 maintenance rollback 制品 | 分层制品验证完成；完整回滚未验收 | EA JAR/Java8/0702 PASS，b27 静态 42 文件/nginx 字节 PASS；network-none nginx-t 真 FAIL 原证据保留，不能正常开写。 |
| 线上实际回滚、持续故障注入、p99 | 未实施 | 保留为明确风险和后续验收，不以局部测试替代。 |

**部署不是零停机热替换。** 本次有真实维护、main 停止、数据库只读、独立全恢复验证、只读 canary 启动及再次排空；发布后因比较器误拒又进入维护，之后仅原地恢复已部署的新 main。不能报告“零停机”。精确的整段业务不可用时间尚未从连续外部观测汇总，应由执行负责人补录，不能只拿最后的 90 秒健康窗口计算。

## 2. 实际代码改动

### 2.1 控盘与渐进恢复 P0/P1

主要改动位于 `ControlHistoryStore`、`PersistentPriceControl`、`MarketControlCommands`、`ControlRecoveryFlow`、`ForexQuoteMarketService`、`MarketEngineFailure`、`ControlPlanBudget`、`AdminAiControlController` 及前端回执/进度状态。

1. **保留 latest 查询修复和完整历史去重。** latest 两个源分支分别先取符合截止条件的最大记录，再整体排序；保留租户、品种及 source_time/received_at 条件。完整历史继续使用精确 `NOT EXISTS` 去重，未为加速 latest 全局删除历史规则。
2. **短事务应急 SOURCE 回源。** 使用原 runtime 写者锁、代际和 revision 保护，关闭旧控制于最后已提交水位、释放 hold、切换 SOURCE；不再等待旧任务 advance、旧计划解码或 hold 激活。已提交历史保留，待发布范围以 `history_pending_until` 持久化并由有限预算后台最终化。断源不伪造样本或有效交易报价，最后可信价格仅能作为显示信息。
3. **START/RESTORE 复用持久命令队列并返回 HTTP 202。** 接受阶段保留原键幂等、参数哈希、额度、鉴权及审计，不等待长计划计算、采样或引擎激活。后台再次核对配置、代际和实际提交价后准备、激活。
4. **未知请求键取消也持久化 CANCELLED 凭证。** 范围严格绑定租户、品种、原 requestKey，阻止晚到请求和旧 worker 复活；最终回执使用 MySQL 当前读，处理 REPEATABLE READ 下取消与激活的竞态。前端不因超时或 404 换键、清 pending、自动重发；迟到响应不能覆盖新操作，待确认状态不再阻止有权限的应急回源/取消。
5. **事务预算、规范化失败和连接清理。** 原 15 秒租约未放大。对应阶段使用 JDBC 5 秒、runtime 4.5 秒、MySQL 行锁等待 2 秒、latest 查询提示 1 秒，保留每物理事务 512 点、每 SQL 批 500 点的限额。清理失败时驱逐连接，避免旧 fencing session 状态回池。沿 cause/SQLException.nextException 区分 ENGINE_FENCED、预算、锁忙与瞬时故障，未把全部 SQLSTATE 45000 都当成 fence。
6. **有限退避、实际进度及请求阶段日志。** 持久化 retry_count/retry_at，最多 5 次有限重试，旧代际不能重新授权；GET 只计算真实提交/预期水位、lag/degraded，不采样、不续租。识别末秒停滞和终点提交后生命周期未完成，拒绝 stalled 交易报价。日志覆盖接受、锁、查源、采样、hold、snapshot、COMMITTED、ROLLED_BACK，并关联 traceId/key/command/task，不输出凭证或完整请求体。

新增 DDL 只有：

- `market_control_command.retry_count`、`retry_at`；
- `market_control_flow.history_pending_until`、`history_retry_at`、`history_error`；
- `tenant_schema_version` 的一条 0702 收据，minimum_application_epoch=0603，先 inactive，再经真实单次激活转 ready。

没有删除隔离触发器、直接 SQL 修改任务状态、清空行情、改写已发布的旧 SQL，或只增加超时掩盖问题。

### 2.2 来源一：后台列设置刷新丢失

合并 `5e1a/705` 的全部 47 个改动文件，保留目标已有控盘队列和待确认状态。

- 共享表格采用稳定英文 column-key，不再把中文标题当作持久化身份；原 table-key 以及账号、租户、入口隔离保留。
- 在当前表格已知列内兼容历史中文及已观察到的乱码别名，英文精确 ID 优先；歧义不猜测，不批量转码或清库。
- 读取必须为合法数组，错误响应不能冒充默认配置；加载完成前不闪现默认列。保存只在 `success === true` 后确认，失败保留草稿。
- admin 与独立 control 使用各自身份和端点，明确 UTF-8；旧账号迟到响应不能覆盖新账号。

本次没有声称已经证明原生产乱码发生在数据库、JDBC 或代理哪一层。稳定 ID 已实现，线上保存/刷新持久化流程仍需真实 UI 验收。

### 2.3 来源二：登录后 Forbidden 与权限故障恢复

合并 `e09b/705` 的全部 10 个未提交改动文件；使用三方合并，不以旧整树覆盖新功能。

- 权限快照绑定当前 token/generation，合并并发读取；身份切换清缓存，旧响应不能覆盖新身份。
- 合法无权限与权限服务临时故障分开处理；网络、维护 503 或无效响应保留可恢复错误，不误跳永久 Forbidden。故障期间写操作继续 fail closed。
- 权限指令同步隐藏和阻断交互；路由、菜单只使用已确认的当前权限。只读轮询按完成驱动、有限退避，离页停止。
- 全套回归额外修复两处客服权限：重试使用 `support_settings:view`，收件箱切换使用 `support_settings:save`，未放宽后端鉴权。

### 2.4 实际线上 500 的补充根因与最小兼容修复（PR 4）

合法 admin 登录和只读 GET 通过后，未知新 requestKey 的取消实际返回 500。日志与限定目标表的只读元数据确认：真实旧 `market_control_command` 的 `parameters_json`、`prepared_json`、`message` 等文本列均为 `latin1_swedish_ci`，`message` 仍是 `VARCHAR(255)`。直接持久化中文触发 MySQL 1366 `Incorrect string value`。事务已回滚，失败键不存在 command/task；没有重试该键或尝试晚到接受。

这也揭示原 UTF-8 MySQL 夹具的覆盖缺口。没有对线上执行 charset DDL、修改任务、清空表或用 ASCII 错误码替换完整用户文案：

- `ControlHistoryStore` 共享 JSON mapper 对**新写入**启用 `ESCAPE_NON_ASCII`，使 Unicode 公式、参数与嵌套 prepared JSON 按 ASCII 字节持久化，读出仍保留原语义；不改写旧 JSON。
- message 采用标准库 UTF-8/Base64 和明确 `~mcc1~` 标记；新非 ASCII 或标记碰撞可逆编码，ASCII 可保留 plain，旧 plain 仍原样读取。未知取消、待准备取消、worker FAILED、有限退避/耗尽以及 `PersistentPriceControl.emergencySource` 统一使用同一 helper，API 回执统一解码。
- 不截断。现有全部有界文案及最大 257 位置 `INVALID_FORMULA` 均经过长度回归；最长实际静态失败编码为 218 字符，低于 255。非法 UTF-16 或未来超长消息明确拒绝，不静默替换或截掉内容。
- `OrderRequest.hash` 的独立 mapper 和语义计划 checksum 未改变。审计所有共享 encode 的 hash/identity 调用后，另用 immutable `ObjectWriter.without(ESCAPE_NON_ASCII)` 保留 `HistoryOrdering` **旧 exact request 的 JSON 字节与 SHA 身份**；不切换共享 mapper，不更新旧 hash。新增 Unicode 原归档的 seal、响应丢失重试、冷重读和原收据逐字段不变回归。
- 真实 runner 只在新建、独占的本地夹具中显式使用 legacy Latin1 TEXT/MEDIUMTEXT/VARCHAR。原始中文直接写入实际产生 1366，随后六条新应用路径均通过；原隔离触发器、旧取消收据和 0702 inactive/minimum=0603 保全。

PR 4 的最小实现为四个 Java 文件、三个测试文件及现有 MySQL runner，共八个文件；代码提交 `d3b063f`，合入主线 `a42fbb3`。原 backend-only 805 计划被真实漂移拦截，没有部署。随后按 PR 5 与客服修复共同集成、重建 main/admin，**不重放备份/DDL/0702 activation**。

### 2.5 配置漂移来源协调与客服浏览器链接（PR 5）

用户明确允许会话同步、协调合并发布后，客服修复会话提供真实 handoff，并停止后续 main/admin/demo/Compose 写入。root 独立捕获确认：对方此前只热更新 main/demo 的 HTTPS policy 方法与容器 JAR，另更新 admin 客服资源入口；容器 Id/Image/Config 不变不能证明运行内容不变。

共同合并没有直接发布缺少 Latin1 codec 的 ea 候选。纳入已审核正式补丁及测试：`OutboundEndpointPolicy` 的 `support` 用途支持无凭据、无片段的公网 HTTPS 浏览器链接，仍拒绝 IP、本地/私网以及混合解析；SMTP 和 callback 继续使用精确平台授权目的地。`TenantReadinessService` 与 admin 设置说明同步，新增 `SupportExternalLinkTest` 验证两个真实 Controller 的对外配置语义。

PR 5 共六个文件（含独立复核的 source registry），提交 `a868191`，合入主线 `e947646d3842d23663f6ffe64d66a1392fd7501b`。支持链接仅交给浏览器，不授予服务器任意出站能力；不保证第三方客服身份、iframe 可用、重定向目标或第三方服务安全。原功能锁定及 disabled channel 仍生效。

本轮根任务**不部署 demo，不读写其数据库**。保留对方实际 demo JAR `b10062…` 与 821 源 Compose，只作为精确保全对象。共同不可变候选采用同一已测试源码，未用 class 热修替代完整发布。
## 3. 本地真实测试结果与范围

各层结果分开列示；重叠场景不重复累加为“全仓库全部通过”。

| 验证 | 实测结果 | 不能替代的范围 |
| --- | --- | --- |
| 共同版本：控盘、列设置、编码/历史、客服/出站/就绪/隔离回归 | 219 项：218 通过、1 跳过，0 失败/错误 | 包含 PR 4 的既有范围，不与此前 184 项重复累加。唯一 skip 仍为 `S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot`，缺专属百万行全应用配对快照。 |
| 独立 SMTP/callback 与租户 policy 生命周期边界 | 9/9，0 skip，0 失败/错误 | 单独运行 EmailOutboundTest/TenantPolicyLifecycleTest，不冒称真实第三方邮件或客服端点验收。 |
| 独占真实 MySQL 5.7 应用/JDBC，显式 legacy Latin1 command 列 | 共同集成后再次 48/48，0 skip；原 42 项与新增 6 项 | 每轮自建专属实例、保留触发器并安全清理；不是生产完整鉴权/JPA、供应商断源或持续负载。 |
| 最新迁移工具离线合同 | 218/218，其中 owner live-test 28/28 | 证明守卫和拒绝路径，不替代真实数据库恢复。 |
| 先前 admin Node / 账户访问 CJS / PC 恢复 | 45/45、6/6、4/4 | 共同 admin 又执行十二个定向脚本、类型检查及正常构建，全部 exit 0；不同范围不合并虚构总数。 |
| Chrome/Vue | 控盘 14 项、权限 6 组、故障恢复 7 组、列设置 10 组、客服 7 组通过 | API 为专属本地合成拦截，不是线上身份或数据验收。 |
| PR 4 期间 `MinimalFixRegressionTest` 默认执行 | 当轮 48/48；此前 46/48 的 2 项失败证据仍保留 | 未改 auth/consumer，单次通过不能证明既有异步 rowVersion race 已修复或稳定消失。 |
| 同一 MinimalFix，用既有消费延迟属性隔离竞争 | 本轮 48/48，0 skip | 仅验证消费者隔离条件下的定向回归，不抹去此前默认失败。 |
| 类型检查、构建和最终串行打包 | PASS | `-DskipTests package` 仅打包，不再次计为测试。 |

原 42 项真实 MySQL 测试包括：终点租约过期、实际查询/连接中断、COMMIT 响应丢失、HTTP/socket 响应丢失、未知键取消、晚到接受、新旧 worker/JVM 重启、SOURCE 断源拒绝交易、多租户隔离、旧取消凭证、旧版本收据和 trigger 保全。不是只运行最小 SQL 示例后宣称应用验收。新增六项在真正 Latin1 列上覆盖未知取消/晚到/重开及旧 plain、Unicode 新参数与 READY nested JSON、最长实际公式失败、最长有界 worker FAILED、五次退避耗尽、pending/emergency 取消文案；最终 48 项全通过。

PR 4 期间默认与隔离 MinimalFix 均为 48/48，但此前两项默认失败的原始证据保留，不把偶然复跑通过写成已修复竞争。实际 SQL 证明既有消费者清除 `promotion_pending`，资金和业务终态不变；相关生产实现与合并前基线相同。隔离时使用已有属性 `activity.order-events.initial-delay-ms=86400000`，没有放宽断言。H2 夹具缺归档表的后台日志也保留，不声称完整消费者接线通过。

本轮编码单元首次执行为 5/6，通过之外的一项因测试起点 `90` 未按两位精度写成 `90.00` 而触发原参数快照严格比较；保留原 FAIL 日志，修正的是测试夹具，未放宽业务 snapshot/checksum 守卫。随后编码与历史针对性 12/12 通过，并包含在当轮 184 项、后续共同范围 219 项中，不重复累计。

早期原生验证的中断、空表 AUTO_INCREMENT 重启严格相等失败、native source 漂移失败均保留。后续使用合法合成锚点的独占 MySQL 严格重启/独立恢复通过，不把失败门禁改为忽略差异。合成 native02 的启动/source review 是明确 mock，只证明合同；不作为本次真实 `1090` 发布证据。

### 可复用本地命令

从现有工作树根目录执行。Maven 共用 target 时必须串行；所有输出目录均须全新。`$junction` 必须指向当前工作树已核对的 ASCII junction；不能指向另一 checkout。

```powershell
$root = (Get-Location).Path
$junction = 'C:/workspace/fx/new/control-timeout-20261006-worktree'
$evidence = Join-Path $root ('reports/final-recheck-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/market/Test-ControlTimeout.ps1 `
  -MavenRoot $junction -OutputDirectory $evidence -WithMysql -WithBrowser
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=AdminTablePreferenceTest,ControlTablePreferenceTest,ControlCommandEncodingTest' test
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=SupportExternalLinkTest,OutboundEndpointPolicyTest,SupportLocaleTest,TenantReadinessTest,ControlSupportIsolationTest' test
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=EmailOutboundTest,TenantPolicyLifecycleTest' test
# 保留每轮默认执行的真实结果，不以偶然通过抹去已观察到的竞争；再跑隔离条件。
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=MinimalFixRegressionTest' test
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=MinimalFixRegressionTest' '-Dactivity.order-events.initial-delay-ms=86400000' test
python -B -m unittest discover -s scripts/multitenant -p 'test_*.py'
python -B -m unittest discover -s scripts/multitenant -p 'test_owner_live_test_migration.py'
python -B scripts/multitenant/build_manifest.py
python -B scripts/database/check_snapshot.py --self-test
python -B scripts/database/check_snapshot.py
```

真实 MySQL runner 自建专属 loopback 实例，按完整容器 ID/name/owner 验证后只清理自身夹具。不能使用生产 datasource 或别人已有 fixture：

```powershell
python -B scripts/market/run_control_recovery_mysql.py `
  --maven-root $junction --output "$evidence/mysql-owned"
$env:NODE_PATH = "$root/exchange-admin/node_modules;$root/exchange-pc/node_modules"
node --experimental-strip-types --test (Get-ChildItem "$root/exchange-admin/tests/*.test.mjs" | ForEach-Object FullName)
node --test exchange-admin/tests/accountTable.test.cjs
node --experimental-strip-types --test exchange-pc/tests/controlRecovery.test.mjs
node exchange-admin/node_modules/vue-tsc/bin/vue-tsc.js -b exchange-admin/tsconfig.json
```

列设置 Chrome 复验另启专属 `tablePreferences.fixture.mjs` 并设置 `COLUMN_QA_URL`，实际命令与专属进程信息见前端测试汇总。不要将合成接口指向线上。

## 4. 真实数据库保全与严格迁移

本次只处理实际存在新增 DDL 的目标逻辑库 `1090`。demo 使用另一物理 MySQL，不受该目标实例的维护措施影响；未备份、迁移 demo 或其它业务库，未使用 `--all-databases`。目标库的一致完整备份用于保全它自己的外键、历史、资金、元数据和触发器，不是全实例迁移。

- 原始完整单库备份：**1,166,426,506 字节**。
- SHA-256：`f325d52497c6108a58ace010e66d894b3e1f9645da3c1ab70503b36829a95df5`。
- 在不同 UUID/datadir、独立卷的专属恢复实例实际导入：**113 张表、12,262,965 行、161 个触发器、1 个存储过程**。
- 原始 schema 与全部字段指纹逐项一致，包含精确 decimal/binary、历史、资金、取消凭证和 metadata。未仅凭表数或样本查询判定恢复成功。
- 已完成的全恢复 proof 通过严格绑定复用，没有为 plan 再次 dump/restore 同一业务库。

### 结构参考差异没有被隐瞒或泛化忽略

初次固定公共 0603 fixture 参考发现 32 个对象差异，真实记录 BLOCKED。没有删除六个既有索引，未改现有字符集、default、SQL_MODE 或触发器以“对齐测试库”。

后续找到同一物理目标库的真实 0404 冻结、原完整备份、独立全恢复 proof 和真实执行过的 0601–03 phase 收据。112 个原 CREATE 均逐表匹配旧冻结；108 张表定义仅有活动计数差异，四张变表和一张新增表严格对应原迁移。

新增显式 native-witness 分支绑定实际源身份、原备份和证据 SHA。在新专属结构参考库只执行原 112 CREATE 及原 0601–03 SQL。**表结构参考不冒称包含原存储对象**；当前触发器、过程、参数、事件和库默认值分别与真实历史对象哈希一致。结构参考仅归一末尾 table-option AUTO_INCREMENT；当前源与完整恢复的 raw-state 和数据计数从未忽略。默认公共 fixture 和 signed production 分支保持原门禁。

### 0701/0702 和单次激活

- 受控顺序精确为 0603 后 `[0701,0702]`；0701 完成时 0702 收据须不存在，0702 完成时必须是准确 inactive/minimum=0603 收据。
- 0701 SQL SHA：`3b91805f8750ba5f310a192e6c36d6b51dd04c7cd131cb38fa5de208e07c23f4`。
- 0702 SQL SHA：`ad96d9a17ac8aaac7fd6995c1ef35bb32b3e97f579c56e1b96039e28d81fa087`。
- plan SHA：`d2cdf0a4df88055add3b222b19a80d923f09b7e8862b9d9191b7a56c53f1db33`。
- restore proof SHA：`fac9790a708342cbc315911674918f373f53911bddc586d1af18aaa517cfbe3b`。
- 实际迁移 COMPLETE，0702 最初 inactive；实际 SELECT-only 应用取证后停止、排空，再单次受控激活为 ready。`OWNER_LIVE_TEST_ACTIVATION_COMPLETE` 和 package-check 均已真实完成。
- 激活只更新精确的一条 0702 收据，minimum 仍为 0603；原 applied_at、旧收据、所有原业务字段、历史和 trigger 保留。激活不自动释放业务写者，也不等于独立双签生产批准。

## 5. 不可变制品、正常配置与真实服务恢复

当前已部署并实际核验的镜像如下。main-api/admin 是后续共同 e947646 完整制品，包含 Latin1 与客服浏览器链接修复；mobile 保留独立任务已验收的 `/mobile/` 镜像，control/pc 保持不变。历史 805 候选及其失败预检单独保留，不冒称它曾上线。

| 服务 | 已部署镜像 ID |
| --- | --- |
| main-api，共同源码完整制品 | `sha256:6f1f6e376956f466386a8d3334fff300a46f4fd98351f8a63a9c2b77e5b89d95` |
| admin，共同源码完整静态制品 | `sha256:4c3771426d77d742b9b1264a4c542174a795f1378117cb6c65f59f9169a3aa4c` |
| control | `sha256:56b2f12c98b6e9b28d33aad259d2b3450a9a07e8851cdf70301b583a19e78db4` |
| mobile，已实际发布并完成独立验收 | `sha256:bfb9abd037337234a7faee86bc4d760668903fcc5428dec0f1d1656a50a9a5df` |
| pc | `sha256:5326ee82b066a944db137f228056943d9c08d91964cca94cb917f94bf715b4cc` |

先前已正常恢复的完整后端 JAR SHA 为 `6ca2cb3dab67993e15b72d7ed156d1700dbdd921e284e04265cc65374b40f286`，packaged epoch=0702。原镜像和 Compose 候选的漂移已逐字段核对，并另存真实基线及独立 release/rollback 配置；没有覆盖未知待发布配置。各前端保留实际 nginx，不猜换上游。

### 历史 Latin1 backend-only 805：本地完成，但被漂移拦截且未部署

- 绑定应用 revision `a42fbb388ed5fdbe41a317ff2598ef6ee825923d`，packaged epoch 仍为 0702。
- 新镜像：`sha256:805e4bc69b4d17864b59088b64150365004b9fed86f7ba4ac459ca1f6b1f0491`。
- 完整 JAR：`f8202e923353a22e72b10b7d646879fcc78c6d75b0d9169368ba7e3245f89b88`。
- 镜像 archive：179,696,128 字节，SHA `bc8febed266b56d277385a7fc462925d75e9f6f827c3b8c69c1c54456b6676a0`。
- 测试后封存 550 个 main/source/resources/proto/pom 文件、769 个实际编译输出（含 685 个 class）。重新打包后的 JAR 逐 entry/字节匹配；既有 migration、0702 SQL 与旧 6ca JAR 完全同字节。epoch 位于 JAR 根 `META-INF/mt705-schema-epoch`，SHA `53629e11ddad2c8db9e137f5aa56eee8f0b6be45e143ec243da247f67e31d8eb`，也与旧包完全相同。
- `tested-build.json` SHA：`956c29b4e9e185b6505ed8fcf7c90482bbc8a021bc3017b5ed79baa45a0e2f02`。测试期间所有 550 个 main 输入未变；`-DskipTests package` 只负责打包，没有当作又一次测试通过。
- 两次独占、无生产网络的新容器重建验证完整 JAR 与 Java 持久性通过，**不是两次完整应用启动或线上验收**。制品 manifest 当时明确 `UNRELEASED / normalProductionApproved=false`。
- 这个 805 镜像只加载到服务端；原 v3 run01 状态是 **STOPPED_CONFIGURATION_DRIFT**，只有 invocation，未发 deployment intent、Compose up、预定发布捕获或新容器。配置层的 177 对象当时相等，不等于热层内容相等。该计划已被下述共同 e947646 制品替代，805 没有成为运行容器。

### 共同 e947646 完整 main/admin：真实发布 PASS

- 完整 JAR：`04be0a88031e80c04f15ce09eae8a28e4ef7cd53deecf85953a70f6e439bcb41`。同一源码交接 `tested-build.json` SHA 为 `bc5421aad9ad4c1499d2ea507a724df6ec45dfdd7e811f4661a6c391acfa37c5`，685 个实际 class 及资源逐字节匹配打包；相对原 6ca 完整 JAR 仅审核的 13 个 class 变化，其它 entries、原迁移和 epoch 精确相同。
- main archive：179,696,128 字节，SHA `5e244078d173236e9bf36a01c41954f816818b2528062daaf6c356d6979d7d2b`。admin archive：32,970,752 字节，SHA `1369eb613d88b17416a71869cc46c5a9d4dfdb301efd6c7b796a8f5359120555`。index→linux/amd64 manifest→config 关系逐 SHA 验证，不混用 Compose manifest label 与 index ID。
- 后端两次无网络离线重建只验证完整 JAR/Java 持久性；admin 两次离线重建验证全部 41 个 webroot 文件、nginx 及语法。均不冒称完整应用或浏览器验收；`-DskipTests package` 只打包。
- 创建新的唯一共同 Compose，SHA `7e7c70faa24ddf3e36936e1381b914d84f7146c583aa19956bcb107c4e606303`，从实际 c58/mobile 配置仅替换 main-api/admin 两个 image。没有覆盖历史 a42f、另一会话的 33b 或 demo 821 源配置。
- 先与临 dispatch 严格核对六个真实源配置原字节、全部 177 对象，以及实际热 main JAR `4f25fe…`、demo JAR `b10062…`、admin 42 个旧 webroot 文件和 nginx。Id/Image/Config 没变化不再用来替代内容证据。
- 单次 `--no-deps --pull never main-api admin` 后，真实完整 Spring Started 日志、新 JAR/Java、双服务连续至少 90 秒 healthy、固定 CID/StartedAt、零 restart，以及最终 fresh 捕获 PASS。main 的 StartedAt 为 2026-10-07 02:30:39 UTC，admin 为 02:31:00 UTC，收据观察时间 02:32:50 UTC。
- post 严格核对新 main JAR、admin 全部 41 个目录文件及原 nginx、177 全配置、其它 175 非选择对象及 demo 热 JAR `b10062bbc3e374b28da3490e8d4e912409ecc42b6dbaa0513830cc7a1915149c`。mobile bfb/control/pc/demo 都未由本轮发布替换；六个源配置不覆盖，demo 库没有读写。
- 实际收据为 `PASS_COORDINATED_MAIN_ADMIN_FULL_CONFIG_REAL_STARTUP_AND_STATIC`，SHA `117590e82a4f8201362f7aa44ad1a204556649c74ecd11ef61d251e0479c5d1c`。迁移和 activation 没有重放，helper 没有 SQL/DDL/自动恢复或全实例备份动作。
- 该发布收据的 API/public 字段当时为 NOT_EXECUTED。随后独立受限 API、新公共四入口与客服 GET 均已通过，分别保留独立收据，不回写当时收据假称全部同时通过。
### 当前正常 Compose 的后续核验命令与再发布边界

当前正常配置的唯一路径是 `/opt/exchange-705-releases/mainline-20261007-9595e8c/private/compose.coordinated-support-codec-e947646.json`，固定 SHA 为 `7e7c70faa24ddf3e36936e1381b914d84f7146c583aa19956bcb107c4e606303`。以下在已授权目标主机上**只读核验**，不启动、重建、开写、迁移或打印私有配置正文：

```bash
BASE=/opt/exchange-705-releases/mainline-20261007-9595e8c
COMPOSE="$BASE/private/compose.coordinated-support-codec-e947646.json"
EXPECTED=7e7c70faa24ddf3e36936e1381b914d84f7146c583aa19956bcb107c4e606303
test -f "$COMPOSE" || exit 1
test "$(sha256sum "$COMPOSE" | cut -d ' ' -f 1)" = "$EXPECTED" || exit 1
docker compose -f "$COMPOSE" config --quiet
docker compose -f "$COMPOSE" ps
```

**不能使用旧 33b 五服务文件整套 up：它含旧 ccd mobile；不能使用 c58 裸 7b/dfb 文件整套 up：它会撤回当前共同/热语义。** 不覆盖原配置，不重新执行已完成 oneshot、0701/0702、activation 或放开 read_only。真正再次发布须从当前精确配置、实际容器和内容新基线衍生新唯一 sealed plan，通过 source/image/JAR/static/六配置/非目标门禁后，由负责人执行新的单次范围命令；此报告不提供能误开写的裸 rollback up/start。
### 先前五服务执行顺序与维护再入

1. 冻结配置、镜像和 source 身份，停止 main、排空会话、目标只读；完成上述真实单库备份和独立恢复。
2. 受控执行 0701/0702；使用唯一账号，仅允许目标库 SELECT，取证真实候选 JAR、Java、Spring 日志、固定 StartedAt、无 restart 和至少 90 秒 healthy；再次停止、排空后单次激活。
3. 仅用独立五服务 release 配置和固定镜像，执行精确目标的 `--no-deps --pull never`，未执行 down/remove-orphans，未顺带替换 MySQL、demo、Redis 或其它服务。
4. 首次新五服务实际正常启动、健康和制品检查已通过，但旧 v2 比较器错误要求 Compose image label 等于 inspect 顶层 OCI index，实际 label 为 linux/amd64 manifest，导致误拒。失败后**确实重新只读、停止新 main 并排空**；没有自动数据回灌、重放 Compose、迁移或激活。
5. v3 从固定 archive 逐 SHA/size 推导 index→platform manifest→config 的精确关系，同时修正 Docker 缺省 User 字段序列化和冻结 Compose 的依赖 label。不是把实际值随意加入允许列表；八项 label/User/HostConfig/启动凭证/依赖/非目标变化负例全部拒绝。
6. 在临开写前重新核验实际停止的新 main、私有配置原字节、007 收据、物理源/ready/read-only/drain、实际 JAR、未过期的正常启动有界日志及完整 Docker 清单。旧只读 canary 已被正常发布替换，它的收据仅作为历史激活证据，不再冒充当前对象。
7. 使用保留的单次 durable intent，仅 `docker start` 原已部署的新 main；未重放 Compose/DDL/activation。再次实际观察至少 90 秒 healthy、固定 ID/StartedAt、无 restart、完整 JAR、Java 和 Spring 日志，之后重新核验五服务及 172 个受保护容器。

实际恢复收据：`PASS_EXISTING_MAIN_NORMAL_CONFIGURATION_RESUME`，观察时间 **2026-10-07 01:22:37 UTC（09:22:37 +08）**；read_only=false，正常凭证/挂载恢复，v3 五服务及非目标保护检查 PASS。该收据的静态/业务 smoke 字段明确仍为待执行，不能单凭它宣布业务全部完成。

v3 比较覆盖完整 Config、HostConfig、Mounts、固定网络字段、静态 IP、aliases/DNSNames，以及原 172 个非目标容器的 ID、image、name、配置、挂载、网络、StartedAt、Running、RestartCount。该 PASS 对应恢复后的捕获时间；后续 gateway reload 和 H5 修正必须另留证据，不能沿用旧捕获冒称后续操作未发生。

### gateway DNS 与 H5 base 是两个不同问题

同一个 gateway 曾保留被替换容器的旧 DNS 解析，导致入口 index 错投。执行负责人仅 reload 原 gateway，配置和镜像未改，解决 DNS 路由问题；没有更改上游或新增开放路由。

公共 H5 最初 HTML 引用根 `/assets/`，三个请求真实 404，证据保留。另一任务随后只发布明确 `--base=/mobile/` 的独立 mobile 候选，固定 image 为上表 `bfb9…`；主执行会话再次独立比对实际全部 177 容器及其余 176 个受保护对象，完整 Config/HostConfig/Mounts/网络/身份/运行时间等严格检查通过。不是只看 mobile 容器 200，也没有顺带重建 main/admin/control/pc 或改 gateway。

同时将默认生产构建路径修复 `73303b7` 合入主线：`vite.config.ts` 在 production 默认 `/mobile/`、开发仍 `/`，并新增默认路径回归。默认生产构建的 231 个输出文件逐字节等于已固定的 mobile 构建，没有临时附加 version 文件以迁就验证。

最终通过实际公共四入口的 HTML 原 href/src 和递归依赖 GET/类型/内容核对；mobile 85 个依赖、14 个图片、`/mobile/home` 及 version 均真实 PASS。首次含原始空格 PNG 的脚本请求曾返回 500；该请求没有使用浏览器相同的 URL quoting。保留原失败后，使用准确 `%20` 独立单 GET 及随后完整资源轮次确认通过。没有改图片、预期 SHA、网关配置或把失败资源加入忽略项。此处是公共静态资源验收，不替代实际浏览器登录/业务交互或旧标签页刷新验收。

## 6. 共同版本已发布；限定 API、公共静态与客服 GET 通过

### A. 真实限定负向 API 与未覆盖范围

- 最初所提供身份没有有效入口/租户映射，实际返回 403，未绕过租户隔离。
- 用户另行提供的正常 admin 身份经限定只读映射核验后，官方 login 200、五项业务 GET 200。账号、密码、token、Cookie、原始链接参数与私有域名不写入公开报告。
- 共同发布前，未知新键 cancel 曾实际 500，根因是 legacy Latin1 中文写入；事务回滚，原失败键不存在 command/task，没有重试该键或向该键晚到接受。原证据保留。
- 共同版本采用新独立键，仅执行一次限定负验：未知键取消 HTTP 200；晚到 START HTTP 202，返回同一 CANCELLED 凭证；`newTaskCountForKey=0`；六组旧 scope 行字节精确不变。实际结果 `SCOPED_NEGATIVE_API_PASS`，脚本 exit 0，不重放不确定请求。
- 状态 GET 实际为 SOURCE、offset=0、running/restoring/holding/enabled/degraded=false、available=true、remaining=0；没有为验证而启动新的真实控制任务。上述只是本次状态字段，不外推持续供应商可用性。
- **这不是完整应用验收。** fresh START/RESTORE、晚到 RESTORE、线上重启/网络丢失、真实供应商断源或其它租户 mutation 未执行。普通 admin 登录/GET 不替代 CONTROL/MFA；列偏好读取不替代真实浏览器保存/刷新。

### B. 历史 805 配置漂移拦截及真实来源协调

原 805 预检在 2026-10-07 02:09:58 UTC 发现真实漂移，状态 **STOPPED_CONFIGURATION_DRIFT**。原五服务 Compose SHA `a42f358b052639cbff71ce6c2b8f58c748a7abcf883c38449a60ca50f7692321`，实际为 `33b641503dccb38d778443c2d07b5a14ef306019a5011fad65b795a804a38218`；mtime 04:07:29 +0200。唯一语义变动是 main-api image→`ea1531…`、admin image→`b27c81…`。run01 只有 invocation，无 deployment intent/up，805 未成为新容器，原备份未覆盖回去。

手机会话确认不是其写入。用户随后明确允许会话同步并协调合并；客服会话给出真实来源 handoff、正式 source patch 与测试，并停止继续写入。root 独立捕获实际 main 热 JAR `4f25fe…`、demo `b10062…`、admin 热资源：**旧 177 容器配置等同不代表实际运行内容未变**。ea 只含客服 policy 修复、不含 codec，不能直接 up 作为共同修复。原 805/ea 计划没有被虚报部署成功。

共同源码 PR 5 合入 e947646 后，采用当前真实 c58 配置与热内容基线、新独立 Compose 和两个新不可变制品；不覆盖另一候选或忽略内容漂移。随后才执行上节真实共同 main/admin 单次发布，旧阻断历史保留而非改写。

### C. 只读 schema 兼容与公共资源后验收

实际只读 native witness 检查 maxVersion=0702、minimumApplicationEpoch=0603、businessActivationReady=1，源 read_only=0；13 项固定 schema/trigger/routine 等对象与历史 activation 精确一致。第一次本地证据组合误用不存在表名导致后处理失败，原失败保留；使用真实 `market_history_ordering` 后 PASS。共同发布前另取得绑定新候选、未过期的 witness。既有凭证/收据未改，没有业务行扫描、源 SQL 写入、二次迁移/activation 或全实例备份。

共同发布后的公共四入口独立实际轮次为 `PUBLIC_COORDINATED_FOUR_FRONTEND_STATIC_PASS`，结果 SHA `67fbf62a59f80c24a37e9511729bcf59e97325de854d16fd1c6e8f3b970f41a8`。按原 HTML href/src 和真实浏览器 URL quoting 进行 normal TLS GET，admin/control 各 2 个主资产、PC/mobile 各 7 个，mobile 85 个依赖、14 个图片、home/history/version 的实际 hash/MIME/内容全部通过。gateway 与五运行对象前后精确一致，没有 reload、登录、SQL、改配置、部署、外部爬取或自动重试。**这是 HTTP 静态交付验证，不是实际浏览器渲染、身份或业务验收。**

客服另有四个固定 GET 的独立结果 `PASS_FRESH_COORDINATED_SUPPORT_PUBLIC_GET_AND_ADMIN_STATIC`，SHA `ceaad49dd20e215eb9d32b701fdf31626d259b8a729fca4c45ceb8cf9899d6f5`。`/api/user/support/config` 与 `/api/user/customer-service/link` 均 200/application-json，mode=external，link SHA `b06608d266b4ea2432ec19ad389f4a6529fa3637416db2983ddac7e66fe83496` 精确匹配，后者 available=true；不公开原始链接或参数。真实新 admin index SHA `7fd70d…`、实际 index-713 JS 全 SHA 和“无需加入域名白名单”说明都通过。六个运行对象的 Config/StartedAt 及实际 main6f/JAR04be/admin4c 前后精确不变。无 SQL/login/POST/第三方导航，也不证明 iframe、重定向或完整用户界面业务。

另在 2026-10-07 02:42:42 UTC 只读取得 since 当前启动时间的 **tail 3,000 行有界窗口**：ERROR header、ENGINE_FENCED、sampling failure、Incorrect string value/MySQL 1366 与 budget 计数均为 0；运行身份/完整配置/StartedAt/restart0/healthy 前后精确一致。已达到 3,000 行上限，**只能声明这个窗口为 0，不能声明全部启动以来或今后无错误**。原日志保持受限，不提交 Git。

继续禁止直接改 task/metadata、清行情、放大超时掩盖故障或灌回迁移前备份覆盖新提交。后续发现未知 source/Compose/image/content 漂移必须再次停止，不以更新预期 SHA 绕过。

## 7. 运行时补丁版本变化与剩余风险

本次 main 候选不是原 JVM 环境完全不变：原运行容器的 `JAVA_VERSION` 为 **jdk8u504-b01**，候选为 **jdk8u502-b07**；镜像默认 `TOMCAT_VERSION` 从 **9.0.122** 变为 **9.0.121**，对应 TOMCAT_SHA512 同步变化。PATH/JAVA_HOME 等其它环境保持审定值，实际运行 Java 已核对为 `1.8.0_502`。

这是已明确披露的补丁版本降级风险。真实启动/健康通过不证明安全修补水平等价，也不是 CVE 无风险声明。后续应独立审查并固定兼容的较新基底制品；本轮没有偷偷替换 JDK/Tomcat 或虚称版本未变。

其它仍未完成或不能由现有证据替代的项目：

- 独立 CONTROL/MFA 身份、正常权限完整业务接线，以及线上真实浏览器列设置保存/刷新、202 队列、待确认/应急回源按钮全过程。
- 限定未知键取消/晚到 START 已真实通过，但 fresh START/RESTORE、晚到 RESTORE、真正运行型 SOURCE、实际供应商断源、线上重启/网络丢失、长时分区或其它租户写入尚未实施。MySQL 48 项不代表这些线上动作已执行。
- 持续多租户/多品种百万级负载及 p99；接受 <500 ms、查询 <250 ms、关键事务 <2 s 仍是待压测目标。1,226 万行保全扫描不是吞吐/延迟验收。
- 默认 MinimalFix 此前两项既有竞争失败、本轮单次默认/隔离通过、H2 后台缺归档表日志及唯一配对快照 skip 都保留；没有宣称 race 修复。
- 保留的实际 nginx 可能使旧标签页引用的旧 hash chunk 获得 SPA HTML fallback；需要完整刷新并核对实际新资源。不能清 sessionStorage、清 pending、换键重发来消除提示。
- H5、共同版本四入口静态、限定负向 API 与客服固定 GET 已真实通过，但完整线上交互和权限仍未全部验收。未来新增超长 message 的 255 上限拒绝也需维持，不得改为截断。
- 全部后续部署和回滚都必须保留已提交历史/资金/取消凭证，不能将迁移前备份覆盖回已产生新提交的源库。

## 8. 回滚制品分层已核验；完整启动与正常业务回滚未演练

**没有线上业务回滚演练，也没有完整 offline 配置依赖启动 PASS。最新结果是维护用途制品内容已分层验证，EA 缺 codec 仍不能正常开写。**

先前完整旧热修 image `sha256:715dab04585eea446092dc412a885332044928d137fe0c6a90dfefa76105a39d`、JAR `8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`、packaged epoch=0603 曾验证制品持久性和结构门禁；它不是当前客服热语义或共同 codec 的回滚证明。裸 c58 配置仍固定旧 7b/dfb image，**不能恢复对方实际 main4f25/admin 热资源**。

current-hot maintenance Compose SHA `fd22f53aecce77baa57f39d68728ea72cdea3733f68caef667dc626e2495d6fa` 及原合同 SHA `480d4691cf8f6cf3578e1d5aa6dc7035c46cc35950c1aec2283ecfa0d97f8ead` 记录当时本地镜像缺失、尚未验证。**原合同和失败没有修改**；后续经单独授权，对服务端已存在的固定 EA/b27 镜像完成以下新证据，不拉取、不部署、不开放业务：

1. **EA 受限 network-none/read-only probe：** 仅 `java -version` 和文件读取，Java `1.8.0_502-b07`、完整 JAR `4f25fe3b4b8683cc90cf353a88f977600af5c5ade5828ad584ed8c1187272151` 精确匹配真实旧热基线；927 个 ZIP entries、685 个 Java 8 class 与 packaged 0702 已核。没有启动 Spring，也没有正常应用权限/配置依赖启动验收。
2. **b27 原 network-none `nginx -t`：真实 FAIL。** upstream `backend` DNS 在刻意无网络的 probe 中不存在。失败原文、exit 和部分结果保留；不改 DNS/network/nginx、不重试，不把它解释为实际生产 nginx 已失败。
3. **另建独立 STOPPED/static-only b27 reader：PASS。** 不 start/run/exec、无网络或挂载，读取全部 42 个 webroot 文件的字节/map/SHA、429 字节 index `1b78c19b…` 和 nginx `e125284d…`，精确匹配真实热基线；它只证明内容，不证明 nginx 启动或正常业务回滚。
4. 两组 proof 都完整比较原 177 个 raw inspect 对象，**只忽略 `State.Health.Log`**，其余所有字段精确相等；image full Config/RootFS/platform 前后不变。只创建并按固定 owner/完整 ID 删除自身探针，无 SQL/HTTP/Compose 或既有服务修改。

原部分失败汇总 SHA：`11cfd373b14493ed9911c3bfa634719b004ae76e88a6ab367b306d118cf5c00a`；独立 stopped static proof SHA：`2531128258c35fdf5d50ec0b49e910853d17d996269b273cdcff4f0d41983b70`；最新分层汇总 SHA：`8bf27ea988e8eb72773937b88d442db5ec7b175f02e5d68048417670860a8867`。均明确 `springStarted=false / onlineBusinessRollbackExercised=false`。EA 仍有 legacy Latin1 回执问题，不能将内容匹配当作 codec 已回补。

发生故障时的最小安全顺序如下，仍是**方案与门禁，不是实演结果**：

1. 通过当前正式 API 查询、取消或排空新队列，记录回执/已提交水位并确认 `history_pending_until` 最终化；不得直接 SQL 改 task/command。
2. 精确停止当前 main、排空会话，再按现有受控流程进入目标只读维护。保留当前库卷、全部提交、资金和取消凭证；不写 demo 库，不自动恢复 SQL。
3. 明确目标是维护用途还是受控前滚；先核 fixed image/OCI/Config、实际 JAR/0702/admin 全目录与 nginx、当前热语义、六源文件、非目标 175/demo b100/mobile bfb。现有内容 proof 不代替受审定配置依赖、正式 package-check 和完整应用启动。
4. 结构兼容还须真实 activation COMPLETE/allready、minimum≤候选 epoch、精确 package-check 和无未知漂移；旧包能启动不等于业务可开放。缺 codec 的 EA/b27 维护方案**不得正常开写**，未验证完整依赖启动时不自动调用 up/start 回滚。
5. 保留 additive 列、版本收据、tombstone、已提交历史和资金；禁止 DROP/TRUNCATE、手改 metadata，或把迁移前备份覆盖回已有新提交的源库。不能恢复的场景保持维护、审定前滚，不强启旧包。

0702 inactive、激活响应不确定、新协议 pending/历史最终化未完成、任意未知 source/config/image/content 漂移，或回滚启动失败，均不能回滚开写。`SchemaPackageGuard` 的最低 epoch 检查不代替业务激活门禁；非 test package-check 仍要求 allready。回到旧包会撤回队列/预算/Latin1 能力，不能称本轮修复永久保留。

## 9. 授权、证据与公开提交范围

本次实际执行基于用户明确的主线部署/全部操作授权，以及“线上无真实用户、无真实资金、均为测试数据，同时尽量保全”的事实声明。完整原话保存于受限 owner receipt；没有冒称旧 magic 指令、伪造 operator/approver 签名或过去 COMPLETE。

采用显式 **owner-authorized live-test** 链不等于独立签名生产批准。默认 signed production 路线、双签和正式 release 门禁没有解除；一旦承载真实用户或真实资金，不得沿用该 unsigned 测试环境例外。

正式签名生产路线的四个阻断原样保留：`Production release approval is absent`、`ORM_DIRTY_WRITE_PREDICATE`、`PRODUCTION_LEGACY_ORPHANS`、`RUNTIME_ACCEPTANCE_PENDING`。没有把 owner live-test 的实际恢复/迁移结果改写成这些 production flags 已 resolved。

本次 Latin1 四个 Java 改动后，纯本地 release source gate 曾另发现七条旧 fingerprint/occurrence inventory 差异，当时共 11 issues，真实 FAIL，原结果保留。执行负责人逐项完成实际源码复核与登记后，普通 `python -B scripts/multitenant/isolation_gate.py --check` 实际 PASS（465 个 Java 文件、2,295 个敏感点）；加 `--release` 的最新检查仍为 FAIL，但只剩上述四个原有 production blockers，七条登记差异已不再出现。这次源复核没有伪造签名、自动刷新生产批准或解除四项门禁。**本报告不声称当前正式 source release gate 已通过。**

应用提交关系：三会话合并 `33c759a` / 主线 `9595e8c`，真实 owner 单库证据 `1939b2d` / 合并 `e54dd4a`，native-witness `f8123b3` / 合并 `ae2203f`，默认 mobile 路径 `73303b7`，Latin1 `d3b063f` / 合并 `a42fbb3`，共同客服/codec `a868191` / PR 5 合并 `e947646`。原前三个 PR 流程与发布证据保留；PR 4/5 不重写历史发布批准。本文档的后续提交不冒称候选 OCI 应用 revision。

可追溯的报告与证据：

- 原三会话合并、来源保全和初始发布阻断：`docs/combined-three-chat-release-20261007.md`。
- 控盘实施及中断测试：`docs/control-target-recovery-implementation-20261007.md`、`docs/control-target-recovery-interruptions-20261007.md`。
- 正式结构包装和本地严格原生合同：`docs/control0702-release-contract-20261007.md`。
- 初始后端/JDBC：`reports/three-chat-20261007-control-01/`；前端：`reports/combined-three-chat-20261007-frontend/frontend-summary.json`。Latin1 后续的实际完整回归、独占 MySQL、默认/隔离 MinimalFix、test-input/class 指纹和候选包证明保存在本轮受限 evidence 目录，公开只记录结果和 SHA。
- 当前真实单库恢复、迁移、激活、正常服务恢复、OCI 比较和后续 smoke 的原始资料保存在受限、被忽略的本轮 evidence/rollback 目录。完整源身份、备份、配置、日志和执行绑定由发布负责人保管。

**公开 Git/GitHub 只提交代码、脱敏数量/哈希/结果和明确边界；不提交数据库备份、私有配置、域名、账号/密码、token、Cookie、SSH 材料或含用户数据的日志。**

当前交付结论：**共同源码 e947646 已合入主线，219 项本地共同回归（218 通过、1 原跳过）、独立 9 项边界、真实 Latin1 MySQL 48 项、单目标库备份/全恢复、0701/0702 及单次激活已完成；共同 main/admin 完整制品真实发布，177 全配置/175 保护/双 90 秒健康/热内容保全 PASS，限定新键取消与晚到 START 也已真实通过。新公共四入口和客服固定 GET 已独立真实通过，日志仅声明 3,000 行窗口零匹配。历史 805 漂移拦截、原 500 与既有 46/48 竞争失败保留；未做全实例备份或二次迁移，demo 本轮未部署且其 DB 未读写。维护回滚制品已分层验证，但无完整配置依赖启动或正常业务回滚演练，EA 缺 codec 不能开写；完整 UI/CONTROL-MFA、运行型 START/RESTORE/SOURCE、持续故障和 p99 未实施，四项正式签名生产门禁未解除。**

### 最终业务范围与剩余完整验收清单

已完成的线上业务范围：合法 admin 登录及限定 GET；新未知键取消和同一键晚到 START 的 CANCELLED 幂等/零新任务；六组既有范围行精确保全；两个客服公开配置 GET 与新 admin 编译说明；四入口原 href 静态内容/TLS/hash/MIME。均有独立实际证据，不把 HTTP GET 当作浏览器交互。

剩余完整验收：真实浏览器列设置保存/刷新、权限故障恢复和待确认/应急按钮流程；独立 CONTROL/MFA 与跨租户写边界；fresh START/RESTORE、晚到 RESTORE、运行型 SOURCE、真实断源/重启/网络丢失及持续负载/p99；当前维护回滚配置依赖完整启动与正常业务回滚演练；JDK/Tomcat 补丁版本降级审查。配对百万行快照 skip、原默认竞争失败和四项正式生产门禁仍未解除。
