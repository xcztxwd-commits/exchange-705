# 控盘与渐进恢复超时：实施与验证交付（2026-10-07）

## 1. 完成状态与发布边界

本轮只在既有工作树 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705` 修改。已读取上级 AGENTS.md、默认技能和两份 20261006 报告。没有新建、重置工作树，没有 Git commit/push；既有 latest 查询改动、两份报告、差分测试和 SQL 复现脚本保留。使用已经存在的 ASCII 路径 junction `C:/workspace/fx/new/control-timeout-20261006-worktree` 运行 Maven，目标仍是同一工作树。

**已完成：P0/P1 业务代码、本地回归、真实 MySQL 应用/JDBC 场景、前端状态修复、不可变候选镜像、应用/配置备份及本地镜像重建验证。**

**未完成：正式迁移包装/审定、生产全接线/负载验收、线上发布、生产备份恢复与回滚演练。** 不把候选镜像称为可直接上线的正式发布包。

生产仅作只读核对和当前 JAR 备份下载。main-api、admin、mobile、pc 的运行镜像均与现有 Compose 候选不同；control 相同。已停止覆盖发布，没有编辑服务端 Compose、运行容器、镜像选择、数据库或业务状态。

## 2. 实际代码改动

### 2.1 已验证查询规则继续保留

`ControlHistoryStore.sourceEvents` 的 latest-only 两个分支先各取截止条件内的最大记录，再整体排序。tick 分支不再遍历全部镜像寻找未匹配行；事件的正序号处理同时间排序。租户、品种、source_time/received_at 截止条件不变。完整历史查询仍保留精确 `NOT EXISTS` 去重，未全局删除。

### 2.2 短事务应急 SOURCE

- `PersistentPriceControl.emergencySource` 在现有 runtime 写者锁/代际保护下递增 control_revision、取消待准备命令、终止旧任务于**最后已提交水位**、释放 hold、flow 转 SOURCE；不调用旧任务 advance、旧 V4 plan 解码或 hold 激活。
- `ForexQuoteMarketService.manualControl(false, ...)` 与 `pumpSource` 不进入普通历史最终化；旧任务失败、旧 plan 损坏、到期的旧历史发布都不能成为回源前置条件。
- 所有已提交样本和历史保留。需要发布的范围持久写入 `history_pending_until`，后台原有 lane 每次最多最终化一项，并受墙钟预算保护。仅发布已提交范围，受保护旧历史冲突保留错误/重试时间，不越过保护强制发布。
- 有真实有效原价时，回源仅记录当前分钟的实际返回点以保持报价和图表一致，不 freeze 或扫描旧源账本；断源不写假样本。
- 断源的 SOURCE、MANUAL、HOLDING、渐进恢复不可交易，保留最后可信显示价并等待有效源。自主 TARGET 计划仍使用已有业务授权下的已提交控制样本；推进落后、有效期/授权失效时禁交易，不把它伪装成实时原始源。

### 2.3 统一持久命令回执

`MarketControlCommands` 与 `AdminAiControlController` 复用原队列：START、RESTORE 均先接受并返回 202，后台再准备/激活。接受事务不拿 runtime 锁、不进行长计划计算或采样；保留原 START 参数哈希兼容性、原键幂等、队列额度、配置依据和审计。RESTORE 激活核对代际、revision 与配置，直接从真实提交显示价恢复，不强求旧任务成功完成。

取消未知原键也写入租户/品种/键唯一的 CANCELLED tombstone，晚到接受返回该终态，PREPARING/READY 旧 worker 不能复活。取消最终回执使用 `FOR UPDATE` 当前读：真实 MySQL REPEATABLE READ 下即使激活先赢，也不会返回旧快照 PREPARING。

前端 START/RESTORE 共用 pending 协议。超时/404 不自动清 pending 或换键重发；服务端取消终态与随后状态核实均成功后才清除。pending 不再禁用有权限的回源/取消；operationVersion 丢弃晚到响应。显式旧 200 协议兼容保留，旧无 requestKey 的同步 RESTORE 不会伪造队列回执。

### 2.4 预算、分类、退避与真实进度

- 原租约仍为 **15,000 ms**，没有增大租约或 Axios 超时掩盖问题。
- 写事务 JDBC 截止 **5 秒**、runtime 墙钟预算 **4.5 秒**；实际 MySQL session 行锁等待 **2 秒**，最新截止 SELECT 提示限时 **1 秒**。它们约束不同阶段，不等同于任何负载下的 p99 承诺。
- 保留每个物理事务共享 **512 点**、SQL 最大每批 **500 点**、原租户 lane/有界 worker；advance 分小批提交水位。hold/recovery/其他生成样本也纳入同一计数，不能额外写第 513 点。
- session 清理不使用已经过期的 JdbcTemplate 截止；清理失败明确驱逐 Hikari 连接，不能仅 close 后回池携带旧 fencing 变量。
- `MarketEngineFailure` 沿 cause 与 SQLException.nextException 规范化 ENGINE_FENCED、预算、忙锁、瞬时失败；不会把所有 SQLSTATE 45000 当作 fence。Spring 在 SQL 发送前抛出的 TransactionTimedOutException 也归预算。
- 命令持久 retry_count/retry_at，最多 5 次、有限指数退避加抖动；品种采样也有上限退避。失效旧代际不重新授权，其他品种继续获得机会。重试耗尽是明确 FAILED，客户端不得自动换键产生新启动。
- GET 只读计算水位/预期水位/lag/degraded，不采样、不续租。终点前一秒长期不提交、终点提交后生命周期未完成都会被识别；quote 执行读取也拒绝 stalled 控盘快照。
- 前端优先显示 controlProgressWatermark；区分等待有效源、推进延迟、授权变化。lastControlProgressAt 与 snapshot committedAt、源事件时间、接收时间独立。
- 控盘 HTTP 新增服务器 traceId 与 action/key/command/task 耗时记录；runtime 锁等待、查源、采样、hold、snapshot、实际 COMMITTED/ROLLED_BACK 均有阶段日志。不记录 Token/密码/请求体。客户端断开不会再次写错误响应。

新增 DDL 只有 0701 命令重试两列、0702 历史最终化三列；未改历史迁移，未删除隔离触发器、清行情表或通过运维 SQL 改任务状态。

## 3. 实际测试结果

最终综合证据：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-20261007-all-verified/`，五阶段 exitCode 全为 0。

- 后端精选回归：**168 项，167 通过、1 跳过，0 失败/错误**。覆盖 latest 差分、计划、API、恢复、历史、报价、租户隔离、真实 Hikari 驱逐、异常分类与请求日志。
- 客户端契约：**5/5**。管理端持久回执、PC/移动端恢复与缓存 revision。
- Chrome：**4/4**。真实加载 Vite/router/AiControl.vue/Element Plus DOM，验证未知键取消、pending 回源、状态读失败保留、RESTORE 202 和实际恢复水位进度；API 被隔离拦截，绝非生产 API 验收。
- 前端 vue-tsc 与 Vite build：exit 0，最终输出在 `reports/control-recovery-20261007-frontend-progress-final/build`。
- **真实 MySQL 5.7 应用/JDBC：20/20，0 skipped**。由单独 runner 创建仅本轮拥有的 127.0.0.1 夹具，执行实际 Spring 事务/JDBC、控制引擎/队列、Tomcat/controller/socket；保留真实迁移 fencing。

MySQL 覆盖：实际终点 trigger 租约故障回滚并原水位续跑；自然 15 秒租约失效及新旧 worker；真实 SELECT SLEEP(6) 被 JDBC 5 秒截止中断且下一 SOURCE 写可用；实际 JVM Runtime.halt(74) 后重启；START/RESTORE 已提交 202 响应丢失；未知键取消/晚到接受；PREPARING/READY/激活胜出 RR 竞态；应急 SOURCE 无旧 plan/hold/旧历史依赖；已提交范围发布；断源/动态恢复/重启暂停；MANUAL 断源；512 点物理事务额度；末秒 stalled；只读 GET 和租户隔离。

0701/0702 从旧字段结构实际执行升级，旧 CANCELLED 回执保存。原 **42 个 S2 fencing trigger** 定义哈希前后相同；另保留原 0305 三个触发器，共加载 45 个。完整实例/owner/server UUID 被核对，测试后只删除本轮专属容器及其匿名夹具卷。

十万行源查询机制单独测得 before **188.538 ms / 200,005 handler reads**，after **0.618 ms / 6 reads**，结果完全相同；最小 SQL 显式 15.1 秒延迟验证两次 fence 回滚。**这些不是百万行自然慢 SQL、全应用负载或生产 p99。** 终点应用测试也是实际 trigger 的明确故障注入，自然 15 秒租约另有独立测试。

首轮综合 143 项有 3 失败，日志保留在 `reports/control-recovery-20261007-all-final`：一项暴露回源报价/当前图表不一致，已修复真实返回点；两项是同一个继承测试仍期待断源 HOLD 可交易，已按源依赖规则改为拒绝交易，并验证最后可信价仍只用于显示。随后修复回归 92/92、最终综合 168 项达上述结果；没有删除失败日志冒充一次全绿。

### 明确跳过、失败和未覆盖

1. `S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot`：唯一 JUnit skipped；没有本轮明确授权/标识的百万行完整快照夹具。
2. 新 MySQL application test 的 provider/repository/audit/测试身份边界使用隔离 mock/valve；未验证生产鉴权链、完整 JPA repository/ApplicationContext，也非持续多品种压测。
3. 旧 S2 独立套件要求其他任务固定端口 MySQL/Redis，未借用或伪造该身份；本轮使用新的真实 MySQL 应用 runner，不称旧套件已通过。
4. `MinimalFixRegressionTest#timedControlRequiresItsMenuAndValidatesInputs` 曾尝试但 ApplicationContext 因既有 BootTenantFixture 的 DOMAIN_VERSION 为 NULL 失败；未修改无关 fixture，不能宣称生产权限/JPA 接线通过。
5. 管理端全套 **43 项：42 通过、1 失败**，既有 SupportChannelSettings.vue 第 9/20 行缺权限控制，未用本次控盘修改掩盖。最终控盘 client/typecheck/browser/build 均通过，不等于全部前端测试全绿。
6. 接受 p99<500 ms、截止查询 p99<250 ms、关键事务 p99<2 s 仍是待压测目标；生产容器完整健康启动、发布/回滚、数据库备份恢复未验收。

## 4. 可复用验证命令

在同一工作树执行，证据目录必须全新。现存 ASCII junction 已核对指向 3d6b 工作树，脚本拒绝指向其他 checkout。

```powershell
$root = 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705'
$env:NODE_PATH = 'C:/workspace/fx/705/exchange-pc/node_modules'
$evidence = Join-Path $root ('reports/control-recovery-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$root/scripts/market/Test-ControlTimeout.ps1" `
  -MavenRoot 'C:/workspace/fx/new/control-timeout-20261006-worktree' `
  -OutputDirectory $evidence -WithMysql -WithBrowser
```

单独真实 MySQL（不会使用 application datasource）：

```powershell
python -B 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/run_control_recovery_mysql.py' `
  --maven-root 'C:/workspace/fx/new/control-timeout-20261006-worktree' `
  --output 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-mysql-new-run'
```

单独浏览器：`node C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/run_control_recovery_browser.cjs`。要求已安装依赖、Chrome/Edge 与 Playwright；不自动安装、不请求线上。Maven 同一 target 只能串行跑，避免结果互相覆盖。

## 5. 生产只读核对与发布阻断

最后核对时间 **2026-10-07 01:02:59 +08:00**：原 5 个应用容器均 healthy。

- 当前 main-api image：`sha256:f30bda969988fd34129b3f613ecf9b8e099d27f25b41e5f609fc2efdd8196d0b`。
- Compose 候选 main-api：`sha256:613adb9b9a61a54e06b9fe7001b0d6008d4127339568171fa85089c631922b79`。
- main-api/admin/mobile/pc 均有版本漂移；不能只把 main-api 覆盖后假装其余候选无关。
- 原 Compose：`/opt/exchange-705-private-runtimes/main-only-50d65fdc5908/compose.owner-qa.json`，SHA-256 `7e40c0e9d4719da557c3e568b403cbc012495e3acfd0c46f29fb207bb7e13c7b`。本轮未写入。
- 当前运行热修 JAR：`8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`。只是当前容器单类热修，按原 image 重建仍可能丢失，不能仅重建容器。

另一项**正式发布硬阻断**：0701/0702 尚未进入审定 table_manifest/migration_files、packaged epoch、完整无数据 schema 快照及严格双尾受控迁移契约。现有 epoch 是 0603，执行器不会执行未登记 SQL。旧 manifest/snapshot 静态检查 PASS 只证明旧 0603 清单，不证明本轮 DDL 已纳入。来源隔离审定门禁实际 **FAIL 26 项**，包含本轮代码变化及其他既有文件漂移；未自动刷新审批指纹或伪造批准。

因此候选材料 `deploymentReady=false / UNRELEASED`。不会把 SQL 手工执行到业务库绕过受控迁移，也不会用本地 MySQL fixture 的 DDL 成功替代正式批准。

## 6. 已准备且实际验证的镜像、备份、回滚材料

材料根目录：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-20261007/`，均忽略 Git、未上传。私有 Compose/inspect/log 原件不可公开或提交。

- `baseline/`：改动前逐文件备份与既有未提交 diff；`final-uncommitted.patch`：本轮最终 tracked diff。
- `live-backup/app.jar`：只读下载当前运行完整 JAR；下载前后容器身份、StartedAt、image 和 JAR SHA 均一致，**70,065,962 字节**。它保留最新查询热修，不是旧慢查询原件。
- `live-final-readonly/summary.json`：脱敏版本核对；原 Compose 单独私有备份。
- `release-candidate-verified/manifest.json`：三个候选 image ID、全部制品 SHA、两次重建证据及明确阻断原因。
- `release-candidate-verified/sources.json`：后端 main/resources 与 admin src 共 669 文件指纹，Git HEAD `d74367b747491e9321db55afcb71c27599f9bbfb` 加 dirty 工作树；没有假装来自已提交发布版本。
- `release-candidate-verified/images.tar`：三个本地镜像完整归档，276,331,520 字节；SHA-256 `4eba0ce749ecaea4e4a0408f41a600779bdfbe035e2a3bf215a25be57cfcaeb2`。

候选后端 JAR SHA：`c0c46574562d24521186b7c07c2729791a1ea1782dc2450730cd585725b4c0a9`。

候选 backend image ID：`sha256:9a99cbeed83cd9fc9fb6aea381c7bcdbd7c254bcc986036a2ad1a8e6314cf608`。

候选 admin image ID：`sha256:877f7b27d0e17b4842e97f82015818cd31fabe838c46152f5325bdf6176f48df`。

本地当前热修 rollback image ID：`sha256:715dab04585eea446092dc412a885332044928d137fe0c6a90dfefa76105a39d`。

服务端既有热修 rollback image 已只读核验存在：`exchange-705-backend:control-lookup-20261006t143735z`，ID `sha256:3e5fcaaec6e0fe317df8a404dea2139a5fec2ac8066f469f0ccac9889b0200de`。不要默认回到未经热修的 f30 image 或原慢 JAR。

后端所有应用类 major<=52（Java 8），候选使用固定 digest 的 Java 8 基础镜像；本地运行 Java 1.8.0_502，生产是 1.8.0_504。Maven 测试 JVM 为 21，编译使用 release 8；没有声称已在精确生产 JVM/全环境启动整个应用。

三个镜像各在无网络本轮专属容器中**重建两次，共 6 次**：候选/rollback 的完整 JAR SHA 保持，Java 能运行；admin index SHA 保持且 nginx -t 成功。容器按完整 ID 和 owner 标签核验清理。**这证明镜像不会因容器重建丢失制品，不是完整 Spring Boot healthy/生产回滚验收。** 制品打包 `-DskipTests package` 使用已完成综合测试的同一稳定源码，不额外宣称 package 又跑了一遍测试。

## 7. 发布/回滚步骤及验证等级

### 已执行的验证步骤（不触碰生产）

1. 上述综合测试、真实 MySQL additive DDL 和 fencing 定义保全。
2. 读取生产 inspect、候选 Compose、JAR 哈希及日志，拒绝版本漂移覆盖。
3. 当前热修 JAR/配置备份；新完整 JAR/前端 bundle 固定 digest 构建；image save 归档。
4. 用镜像 ID 创建无网络容器、start 后核对 `sha256sum /app/app.jar` 或 index，再按已拥有 ID 清理；每种两次，证据在 manifest。
5. 旧 manifest/snapshot 只读 PASS、source registry FAIL，明确保持发布停止。

可复核现有材料：

```powershell
Get-FileHash -Algorithm SHA256 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-20261007/release-candidate-verified/images.tar'
docker image inspect sha256:9a99cbeed83cd9fc9fb6aea381c7bcdbd7c254bcc986036a2ad1a8e6314cf608 --format '{{.Id}}'
python -B C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/multitenant/isolation_gate.py --check
```

### 待实施，当前禁止执行的生产发布链

1. 发布负责人明确 main-api/admin/mobile/pc 候选版本的归属和合并顺序，固定批准 Compose 基线；重新核对每个运行 image/配置/挂载/网络，无漂移才继续。不要更改原候选文件来制造一致。
2. 按既有受控发布流程审定本轮租户谓词、锁/回调与源码指纹，处理既有 26 项门禁差异；仅审核通过后更新登记。
3. 完成严格 0603 后 `[0701,0702]` 双尾迁移契约、阶段保全/中断恢复测试与最终新收据设计；如审核确认 additive 兼容，保持最低应用 epoch 0603 以允许当前 hotfix 回滚。不得修改历史收据/DDL/触发器。同步 0702 packaged epoch、完整 schema/data-dictionary/checksum，并从隔离 MySQL 5.7 实际结构导出及新空库往返验证；重跑测试、重新构建新 SHA 镜像。
4. 在真正对应的生产快照恢复库验证完整鉴权/JPA/AppContext、接受/恢复/取消、断源、多租户及持续负载 p99；完成业务数据库的一致备份与独立恢复证明。当前应用备份不能代替 DB 恢复证明。
5. 受控工具 `plan / verify-backup / apply 或 resume / package-check` 必须绑定真实目标、独立 restore 目标、不可变 plan/proof/批准/ledger；现有工具对本轮未审定双尾会拒绝，应保留拒绝而不是传 fixture/local-owner 参数绕过生产批准。
6. 用审定的独立发布 Compose（不是覆盖别人候选）仅切换批准服务到重新构建的不可变 image ID；保持原环境、卷、网络、隔离网关。后端与新版 admin 原键 RESTORE 协议须协调切换。重建后核对 JAR/index hash、应用 healthy、权限与 SOURCE/恢复/取消连续性、每租户进度、阶段预算和错误。

### 生产应用回滚预案（材料已核验，操作未演练）

1. 先停止新 START/RESTORE 接受，按服务端 API 查询并受控取消/排空 ACCEPTED/PREPARING/READY；确认无新协议 pending 和未处理 history finalization，保存回执/已提交水位。旧 worker 不认识新 RESTORE 队列语义，不得带它直接回切。
2. 在同一个已经批准的配置基线上只替换回滚所需服务；backend 使用已核验的**当前热修**镜像/JAR，不用原未热修 image。admin 使用此次备份记录的原运行版本，其他候选/服务不动。
3. 保留两条 additive 迁移及已提交样本、取消 tombstone、历史和业务数据；不执行逆向 DROP COLUMN/全库 restore/TRUNCATE/手改 task。若审批后的 epoch/最低应用版本不再兼容，必须走审定的前滚恢复，不强行启动旧包。
4. 重新核对 artifact SHA、healthy、租户隔离、实际原价/不可交易标志、任务/回执和水位；记录回滚证据。应用回滚会撤回本轮新协议和预算能力，旧问题风险仍在，因此不能称业务功能永久修复。

**当前结论：业务修复与本地证据已交付；正式受控迁移包装和版本协调待实施，线上未发布。**
