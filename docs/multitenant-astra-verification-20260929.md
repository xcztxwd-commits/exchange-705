# 多租户接续修复与实测结果

日期：2026-09-29，Asia/Singapore。本文记录本轮实际执行，不表示全功能验收或生产发布。

## 结论

已修复特殊 ORM 强制版本递增的租户谓词缺口、共享计算夹具和四处测试夹具，并实审更新源码登记。最新隔离全量测试仍失败：**2393 项，2141 通过，8 failures，213 errors，31 skipped**。剩余表格偏好整合、测试夹具及当前版本端到端验收没有完成；未部署生产。

## 版本与证据

- 工作区：`C:\workspace\fx\705`，起始 HEAD：`668edcbc72ebda33d0e50172d119bc92ccebb479`。存在大量原有未提交修改，HEAD 不足以标识本轮版本。
- 原始证据：`C:\Windows\Temp\mt705-astra-20260929`。日志可能含测试内部配置，不应整目录对外分享。
- 定向构建：`C:\workspace\fx\new\mt705-astra-20260929-build`。
- 全量构建：`C:\workspace\fx\new\mt705-astra-20260929-suite`。
- 第二轮全量的逐文件 SHA256：证据目录的 `full-second-source-manifest.json`；117 份 Surefire XML 及其摘要分别为 `full-second-xml`、`full-second-summary.json`。
- 定向 XML 重计数：`targeted-recount.json`。第二轮快照与工作区比对结果见 `snapshot-workspace-drift.json`；记录时零漂移，不保证以后其他会话不修改源码。
- 修改前文件保存在证据目录 `backups`。未执行 git reset/clean，未提交、推送或覆盖其他人的代码。

## 已完成的修复

### 1. Maven 隔离运行器

原 Windows Temp 快照中，javap 能读取 class，但 javac 无法正常解析同一路径；jar classpath 探针出现 `AccessDeniedException`，涉及 `WindowsPath.toRealPath`。启用 compiler fork 和单独 testCompile 均未解决。

将相同源码及 pom 复制到上述 ASCII 工作区路径后，测试编译和执行恢复。没有修改生产 pom、降低 JDK 版本或放宽系统 ACL。完整快照必须保留 `exchange-backend`、`scripts/multitenant` 的根目录布局以及 `docker/nginx.conf`，否则门禁或音频配置测试会因快照不完整失败。

### 2. 共享计算夹具

新增 `CalculationTenantExtension`，分别拦截普通测试和动态测试的实际执行，在 try-with-resources 中建立并恢复租户上下文。仅在 TestFactory 方法上建上下文不能覆盖之后执行的动态用例。

六个原有计算测试类接入该扩展，`FeeCalculationAuditTest.Fixture` 注入租户策略 mock。保留原断言；没有在生产代码加入默认租户或宽松策略。

新增四个负对照：缺上下文拒绝且不写资金、异租户不能读取租户一的 mock 数据、禁用策略先于资金和仓库操作拒绝、普通/动态执行成功或异常后均恢复上下文。

### 3. ORM 特殊强制版本递增

`TenantEntityPersister.forceVersionIncrement` 现在要求有效租户上下文，以参数化 SQL 同时约束主键、原版本和 `tenant_id`，并检查恰好更新一行。保留普通 update/delete 路径及事务回滚。

`TenantForceVersionTest` 在专用 MySQL 合成库中执行同一组七项断言：无上下文、异租户已知 ID、合法 owner 与旧版本冲突、乐观/悲观 FORCE 锁、普通 dirty update、强制递增回滚。StatementInspector 只观察 SQL，不改写它。

数据库限定于现有本地测试容器的 `127.0.0.1:64029`；创建独立空库后先做备份和独立恢复验证，再运行测试。连接文件受限存放，本文不包含凭据。此证据证明该特殊路径的修复，不证明所有实体和所有 ORM 写路径均已验收，也不证明曾有可被用户触发的线上漏洞。

### 4. 源码登记与其他夹具

逐项实审交接中的十个生产文件，另审 ORM 修改，共更新十一项登记的理由和指纹。活动结构化布局需要 V04，不是只部署 V03。一次并发产生的 SupportSettings 空行变化经差异复核后单独登记。

四处测试修复：ControlReadQueryTest 与 ControlSupportIsolationTest 对齐 welcome 三参数重载，后者补 ActivityCampaign 映射；SupportAuditLogTest 补上下文及清理；AdminTablePreferenceTest 对齐租户作用域和仓库参数位置。未删除断言或新增跳过。

未改 `approved`、`release_approved` 或任何 release blocker 的状态。

## 实际测试结果

| 执行范围 | 结果 | 证据与解释 |
|---|---|---|
| 六类计算修复前 | 1057 项：1 通过、21 failures、1035 errors | `calculation-baseline.log` 与 XML |
| 六类计算及新增负对照修复后 | 1061/1061 通过，退出码 0 | `calculation-fixed.log` 与 XML |
| ORM MySQL 修复前负对照 | 7 项：1 通过、6 failures | `force-before.log`、`force-before.xml` |
| ORM MySQL 修复后 | 7/7 通过，退出码 0 | `force-after.log`、`force-after.xml` |
| 活动/客服/控制面/偏好八类定向复测 | 52/52 通过，退出码 0 | `review-regression-fixed.log`、`review-fixed-xml` |
| 本轮第一遍全量 | 2393 项：2131 通过、9 failures、222 errors、31 skipped | 含尚未修复的四处夹具，以及快照缺 nginx 文件造成的一个错误；`full-first-summary.json` |
| 本轮第二遍全量 | 2393 项：2141 通过、8 failures、213 errors、31 skipped；退出码 1 | 纳入四处夹具修复并补齐 nginx 快照；`full-second-test.log`、`full-second-summary.json` |
| 源码门禁 | 355 文件、1466 敏感点，通过，退出码 0 | `source-gate-final.log` |
| 门禁变异自测 | 11 项通过 | `gate-selftest.log` |
| 发布门禁 | 4 项阻断，退出码 1 | `release-gate-final.log` |
| 当前前端表格偏好测试 | 失败，退出码 1 | `table-preferences-current.log`：BackendAccounts.vue 未转换 |

上述范围有重叠，不应将通过数量相加。31 个跳过项不是通过；MySQL 专项与默认 H2 全量也不能混为同一数据库验收。

## 尚未完成，按类别区分

### 实现/整合缺口

表格偏好尚未统一接入。BackendAccounts、OnlineUsers 等组件同时用于普通后台和独立总控；AdminTable 依赖普通后台 Pinia 身份与 `/admin/table-preferences`，而独立总控使用单独入口、身份及 `/control/*` 请求约束。不能仅替换标签或排除测试来制造通过。应先明确独立总控偏好的身份隔离和存储契约，再完成组件整合与双环境验证。

### 测试夹具仍需修复或进一步归因

第二轮剩余 26 个失败套件完整列在 JSON 中。已观察到的共同原因包括缺少 TenantContext、TenantPolicyService 未注入、H2 缺少 MARKET_CONTROL_TASK schema、客服测试 ApplicationContext 缺 Bean。涉及权限、注册、模拟账户、客服、持久化资金等范围。

这些执行失败不能按次数换算为独立产品漏洞；同样，不能仅凭“看起来是夹具问题”把未通过的权限断言算作验收成功。并发测试还需在每个工作线程建立和清理上下文，而不是只补主线程 setup。

### 当前版本未完整验收

未完成当前 jar 的隔离部署、完整 API、Chrome 真实交互、移动视口及剩余并发/故障恢复矩阵。历史版本的成功记录不覆盖本轮版本。本轮未新跑全套前端类型检查、构建或浏览器脚本。

### 发布及生产治理仍被阻断

发布门禁保留：发布批准缺失、ORM_DIRTY_WRITE_PREDICATE、PRODUCTION_LEGACY_ORPHANS、RUNTIME_ACCEPTANCE_PENDING。特殊路径七项通过不足以自行关闭宽泛 ORM blocker。实际历史孤儿处理、真实文件迁移和正式迁移/恢复批准未完成；没有生产部署或真实资金操作。

## 重跑入口及接续顺序

在完整 ASCII 快照中执行：

```powershell
& C:\Environment\PowerShell\7.6.4\pwsh.exe -NoProfile -File C:\workspace\fx\new\mt705-astra-20260929-suite\scripts\multitenant\verify-build.ps1 -MavenArguments test
```

先修当前 JSON 中剩余夹具并保留拒绝/无副作用断言，再冻结新指纹、重跑全量。随后完成表格偏好的两套身份整合。只有当前版本隔离部署及 API/浏览器矩阵通过后，才进入发布批准流程。不要复用旧 XML 或旧 jar 冒充新版本结果。

回滚本轮代码时，先检查文件是否被其他会话继续修改，再逐文件比较证据目录的备份；不要整目录覆盖或使用 git reset。新增测试文件无原始备份，删除前也要核对后续修改。
