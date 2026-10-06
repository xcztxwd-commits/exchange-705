# 控盘与渐进恢复：中断、暂停、回源扩展验收

日期：2026-10-07，时间使用 Asia/Singapore（UTC+8）。本轮仅在现有工作树 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705` 继续修改；没有新建或重置工作树，没有覆盖先前未提交修改，没有提交、推送或发布生产版本。

## 1. 已完成与待实施

**已完成本地扩展验证，并修复测试暴露的缺陷。** 原真实 MySQL 20 项全部保留，扩为 **42 项，42 通过、0 失败、0 错误、0 跳过**；原浏览器 4 项保留，扩为 **14 项，全部通过**。后端独立回归 **169 项：168 通过、1 明确跳过**，Node client **5/5**。综合入口五个阶段退出码均为 0，最后阶段完成于 2026-10-07 02:56:26 +08:00。

在最终测试后串行执行 `-DskipTests package`，成功；它没有再次运行测试。新 JAR 的全部 **685 个应用 class** 集合及逐项字节哈希与测试后的编译产物完全相同，main/admin **669 个源码及资源文件**指纹未变。新完整后端镜像已本地构建、归档，并实际无网络重建两次验证制品不会丢失。

**未完成生产发布、生产完整应用启动及生产回滚演练。** 已知运行版本与 Compose 候选存在漂移，0701/0702 的受控发布清单、epoch、双尾迁移契约、schema 快照及源码审批门禁未完成。新候选明确为 `UNRELEASED / deploymentReady=false`，不能把本地通过解释成已上线。

总结果与原始日志：
- [综合结果](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/verified-summary.json)。
- [真实 MySQL 的全部 42 个独立方法及结果](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/mysql-application/result.json)。
- [五阶段退出码与起止时间](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/result.json)。
- [测试编译产物与源码指纹](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/tested-build.json)。

## 2. 本轮实际代码改动

相比上一轮已测试、已保存的候选，本轮 main 代码只变更以下六个文件；admin/src 全量 **120/120** 指纹相同，没有借扩展测试重写前端业务代码。此前持久队列、未知键取消凭证、短事务 SOURCE、前端 pending/救场按钮修复继续保留；latest 查询优化及完整历史精确去重规则没有改动。

1. [PersistentPriceControl.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/PersistentPriceControl.java)：在恢复断源进入 WAITING_SOURCE 后执行 STOP，也准备并激活真实持久 hold。原缺陷会只改 flow 为 HOLDING，却没有有效 hold，后续源价回来了仍保留旧价格且显示健康。现在保留已提交显示价对应的固定偏移，重新有源时随 raw 变化；断源仍不可交易。只处理该租户/任务的 hold，没有清空表或删除隔离保护。
2. [ControlRecoveryFlow.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/ControlRecoveryFlow.java)：同进程调度空档超过已有 2 秒进度阈值，也复用暂停/重基逻辑，从最后已提交显示价继续恢复，不把没有观察到的时间直接耗尽并跳到 SOURCE。没有增大前端、查询或租约超时。TARGET 仍按既有墙钟时间轴有界补采，业务 STOP、调度停顿、断源是不同语义。
3. [MarketEngineFailure.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/MarketEngineFailure.java)：真实连接断开 SQLState 08*、未知提交 ACK 08007、查询中断 70100/1317 规范化为 ENGINE_TRANSIENT；既有 ENGINE_FENCED、45000 业务错误区分保留，继续有限退避，不能借此重新授权旧 worker。
4. [MarketControlCommands.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/MarketControlCommands.java)：START/RESTORE 遇到已有 task、没有 command 的旧同步请求键，明确返回 409 `LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION`，不制造误关联新回执或清除后来 MANUAL/TARGET。worker 复核旧 task 使用当前读 `FOR UPDATE`，避免等待锁之前建立的 REPEATABLE READ 快照把已提交旧任务藏住。claim 代次只在成功提交后发布；不确定提交的退避以 runtime/command 当前读、相同 owner/generation 核实事实，按 runtime 在前的锁顺序执行，不续租、不抢回授权。
5. [MarketRuntime.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/MarketRuntime.java)：共享 writer 修复首次 COMMIT ACK 丢失的漏洞，不只补队列路径。UNKNOWN 保存首次尝试代次；下一次获取 runtime 当前行时，不能把原 writer 当成全新进程去抢回其他 owner 的更新代次。已捕获代次永不覆盖。当前代次低于尝试代次，证明首次 claim 未提交，允许正常重试；已知 ROLLED_BACK 不污染授权。UNKNOWN 回调不写 DB、不新建连接。
6. [ControlHistoryStore.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/ControlHistoryStore.java)：事务日志正确区分 COMMITTED、ROLLED_BACK、UNKNOWN，不能把已实际 COMMIT 但确认丢失伪记为回滚。最终日志实际观察到 `outcome=UNKNOWN`。

保存改动前备份及逐次修复原件：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-interruptions-20261007/baseline/`；此前整体工作树备份继续保留于 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-20261007/baseline/`。原报告、原失败证据、旧候选和旧镜像未覆盖。

### 测试与工具改动

- [ControlRecoveryMysqlTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlRecoveryMysqlTest.java)：原 20 + 新 13 = 33 项。
- [ControlRecoveryTransportMysqlTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlRecoveryTransportMysqlTest.java)：5 项实际连接/查询中断与提交 ACK 注入。
- [ControlRecoveryFreshClaimMysqlTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlRecoveryFreshClaimMysqlTest.java)：4 项首次授权/当前读边界。不继承并重复统计原测试。
- [ControlRecoveryFlowTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlRecoveryFlowTest.java) 与 [ControlFlowMarketIntegrationTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/ControlFlowMarketIntegrationTest.java)：正常恢复通过向真实服务注入不超过 1 秒的业务时间采样推进，原终点/断源/重启断言保留，另测同进程注入 4 秒业务时间空档；不是墙钟 sleep 或实际暂停生产调度器，未把未观察的长空档冒充正常恢复时间。
- [MarketEngineFailureTest.java](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/test/java/com/gtcfesk/exchange/market/MarketEngineFailureTest.java)：SQL 08S01/08007/70100/1317 分类断言，42000 仍为普通失败。
- [aiControlRecovery.browser.cjs](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-admin/tests/aiControlRecovery.browser.cjs)：原 4 + 新 10 = 14 项。
- [run_control_recovery_mysql.py](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/run_control_recovery_mysql.py)：明确要求三个套件实际出现，并且 0 failure/error/skip，拒绝旧 Surefire 结果或缺套件假通过。仍仅使用本轮新建、随机端口及 schema 的专属 MySQL 5.7。
- [Test-ControlTimeout.ps1](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/Test-ControlTimeout.ps1)：client 阶段仅临时使用当前工作树已有 node_modules 的 NODE_PATH 并恢复环境，不再依赖此前 shell 暗含的 TypeScript 路径。
- [build_control_interruption_candidate.py](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/build_control_interruption_candidate.py)：只建新的本地未发布 backend；验证测试结果、编译 class 集合/字节、源码、复用 admin 指纹、旧回滚归档、实际 JAR epoch/迁移哈希，拒绝覆盖目录或镜像 tag。

## 3. 已实际覆盖的中断、暂停、恢复场景

### 真实 MySQL 应用服务/JDBC/fencing：42/42

下列通过实际服务逻辑和 MySQL 事务核对，而非只调用最小 SQL。provider、repository、audit、身份边界使用受控 mock/valve，不能称完整生产鉴权/JPA 接线通过。

1. **TARGET 中途 STOP**：保留已提交水位及固定 offset，重建组件后随新源价变化；不是冻结绝对价格。TARGET 调度空档按原计划补采，GET 状态不代为采样或续租。
2. **RESTORE 中途 STOP，再恢复价格**：新请求键从实际已提交显示价起步，在动态 raw 上逐步归零；重复 STOP/SOURCE/旧 RESTORE 回执不新建或重启任务。
3. **恢复中断源后 STOP**：必须建立真正持久 hold，重建组件后 raw 变化仍跟随，不得旧价格不动却报告 HEALTHY。
4. **连续三轮断源、恢复来价**：WAITING_SOURCE 保留剩余时间，断源区间不生成样本，不消耗未观察恢复时间，不把最后可信价格当有效可交易源价。
5. **同一进程注入 4 秒业务时间采样空档、再次调用恢复**：恢复从先前已提交显示价重基，不能直接跳到 SOURCE；组件重建也保留恢复时间。这项不是真实调度器 suspend/resume 或墙钟等待，测试注入业务 now。没有新增名为 pause/resume 的业务 API，业务暂停通过真实服务调用现有 STOP/HOLD。
6. **恢复途中应急 SOURCE**：不等待损坏旧计划、旧 hold 激活或历史补偿；保留已提交历史，组件重建、晚到旧队列、重放原键均不能复活旧控制。
7. **源价突然跳变、迟到或重复事件**：从实际 sourceQuote 路径写入；控制/恢复水位和版本不倒退，同一终点不会生成重复样本。
8. **最后 1 毫秒 STOP、精确终点快速恢复**：STOP 不被自动恢复覆盖；终点唯一，重复源事件不重复采样。
9. **终点写入租约过期/被触发器拒绝**：最终样本、快照、水位和持有写入回滚，随后从原已提交水位恢复；真实 15 秒租约到期另有独立等待测试。512 点共享预算、真实 JDBC deadline、4.5 秒写入预算均保持，未增大期限。
10. **接受响应丢失**：真实 Tomcat 的 TCP 客户端断开、不读取 202；START/RESTORE 原键查询恢复，各只有一个任务。另测实际子 JVM `halt` 后持久回执仍可恢复。
11. **未知键先取消、原请求晚到**：持久 tombstone 阻止接受；PREPARING、READY 与取消/SOURCE 竞争，旧 worker 不复活；同请求键跨租户隔离。取消与激活的真实 RR 竞争返回当前真实结果。
12. **实际 MySQL 连接被切断**：独立物理连接执行本轮自身 session 的 KILL CONNECTION；分别切在 claim COMMIT 前、sample INSERT 后，以及从未 primed 的首次 writer claim 内。首次 writer KILL 用例逐表保全完整 runtime、任务、sample、flow、hold、minute、publication，同 owner 可正常重试，最终单任务、三个唯一样本，无假 fence；另外两个 KILL 用例分别核回执、任务水位、快照与分钟事实，并非每项都逐表全行比较。
13. **实际 MySQL 正在运行的查询被中断**：先通过 SHOW FULL PROCESSLIST 确认查询，独立连接 KILL QUERY，物理 SQLException 必须为 **70100/1317**；不把业务命令取消，只有限退避后恢复。
14. **真实 COMMIT 后 ACK 丢失注入**：接收 command 或 sample 已由 MySQL COMMIT 后，JDBC proxy 再抛 **08007**。原键恢复不重复建任务，同水位重试不重复 sample。这是确认丢失注入，不是实际 TCP 丢包/网络分区。
15. **首次授权的最危险边界**：fresh B 首次 claim 或通用 pump COMMIT/ACK 丢失，C 接管后显式令本轮专属夹具的 C 租约过期，旧 B 两轮也不得改 runtime/command 全行或任何已提交历史；自然等待 15 秒到期属于独立原用例。fresh D 接管后原键/原任务只执行一次。通用 pump 场景不经过 queue defer，防止只修队列留下共享根因。
16. **旧同步请求键及 RR 并发**：旧 START/RESTORE task-only 键精确 409，不动当前 MANUAL/TARGET；真实锁等待前建立旧 RR 快照后，另一同步 API 提交 task，worker 当前读仍必须识别 LEGACY，而不是错误 START_BASIS_CHANGED。

42 个原引擎隔离 trigger 前后哈希完全一致：`a766811d1f481f0988776382fd10383060981b3f0675bc1959ab63e7ed12fbfa`。0701/0702 在旧夹具结构上实际升级，原取消回执保留；夹具完整 ID、owner、ServerUuid、schema/账户限定检查通过后只清理本轮专属容器和匿名卷。没有连接共享 MySQL/Redis，也没有人工改业务 task 状态或清空行情表。

实际故障摘录均在 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/mysql-application/`：`worker-connection-interruption.json`（08S01）、`physical-connection-interruption.json`（08003/EOF）、`worker-query-interruption.json`（70100/1317）、`fresh-owner-claim-acknowledgement-loss.json`、`generic-first-writer-acknowledgement-loss.json`、`generic-first-writer-physical-rollback.json`。其中首次物理回滚证据明确 `sameOwnerRetriedWithoutFalseFence=true`。

### 真实 Chrome、本地应用 UI：14/14

使用实际 AiControl.vue、Vue router、权限组件、Element Plus 与 Axios，检查 DOM、sessionStorage、请求键与 POST 次数。所有 API 被 fixture 拦截，其他来源阻断，不是生产身份或后端数据库验收。

- 原四项：未知键取消凭证；未知键应急 SOURCE/断源不可交易；取消确认后读取失败仍保留 pending；RESTORE 202、刷新不重发、真实恢复水位展示。
- 新十项：RESTORE 丢响应后 reload 保留原键只查询；恢复中 STOP/SOURCE 各自 503 后可重试；STOP/SOURCE 各自真实 Axios **10000ms** 超时；未知 pending 下 STOP/SOURCE 失败不假清、不另启；晚到 START/RESTORE 的 RUNNING 不覆盖已经确认 SOURCE；WAITING_SOURCE 保持 **20%**，快照刷新不能伪推进，恢复新段实际 **25%/50%**，最终 SOURCE。

原四项执行块与备份逐字相同；两项超时没有改成短假计时。日志：[browser.log](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-interruptions-20261007-all-03/browser.log)。

### SQL 机制复现单列，不混入应用验收

独立 MySQL 两账本各 100,000 行：旧查询 **184.942 ms / 200,005 handler reads**，修复查询 **0.656 ms / 6 reads**，结果完全相同。最小 trigger 事务显式注入 15.1 秒延迟、两次回滚，优化后正常完成。这不是百万行自然 15 秒慢查询、生产 p99 或完整应用验收；上面的 42 项应用测试单独统计。

## 4. 失败证据保留及修复闭环

不是只保留最终绿灯。下列目录均位于 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/`，未覆盖：

- `control-recovery-interruptions-20261007-mysql-01`：Docker Desktop engine 未启动，基础设施失败，未算应用测试。
- `control-recovery-interruptions-20261007-mysql-02`：37 项、8 失败；包括 WAITING_SOURCE STOP、调度空档、旧键误关联和 ACK 分类的真实缺陷，也包括切断同线程事务连接、SLEEP 中断行为等测试装置错误。不能把 8 项都叫产品缺陷。
- `control-recovery-interruptions-20261007-all-01`：client 隐含 NODE_PATH 缺失，MySQL 查询中断装置未取得真实异常；对应脚本修正，不改超时掩盖。
- `control-recovery-interruptions-20261007-mysql-03`：40 项、2 真实失败，fresh queue claim ACK 丢失后旧 B 抢回新代次，以及 RR 旧快照错误分类；均修复。
- `control-recovery-interruptions-20261007-all-02`：41 项、1 真实失败，通用首次 writer ACK 丢失未被队列局部修复覆盖；共享 MarketRuntime 修复后，新加首次实际回滚无假 fence 对照项。
- 最终 `control-recovery-interruptions-20261007-all-03`：169 项回归（168 通过、1 跳过）、Node 5/5、Chrome 14/14、SQL 机制通过、应用 MySQL 42/42。

真实 KILL QUERY 最终不再使用可能正常返回的 SLEEP；有界重查询的实际运行观察、KILL 成功、SQLState/errorCode 三项断言同时满足。真实 KILL CONNECTION 改用独立物理连接，不用同线程 JdbcTemplate 误切自己的 kill 会话。

本地 Docker daemon 最初未启动，执行 `docker desktop start` 后才继续。该动作也使已有本地测试栈按 restart policy 自动恢复；本轮没有显式启动/重建/删除其容器，没有访问它的共享 MySQL/Redis，没有进行故障注入。不能声称 daemon 启动对其他本地容器生命周期没有间接影响。未操作远端生产。

## 5. 可复用命令

需既有 Maven、Docker、Python、Node、Chrome/Playwright 及已安装依赖。测试只创建自己拥有的 MySQL 夹具；不要借用共享数据库。ASCII junction 已存在且指向本工作树，入口会校验，不是另建工作树。Maven 使用同一 target 必须串行运行。

```powershell
$root = 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705'
$evidence = Join-Path $root ('reports/control-recovery-interruptions-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$root/scripts/market/Test-ControlTimeout.ps1" `
  -MavenRoot 'C:/workspace/fx/new/control-timeout-20261006-worktree' `
  -OutputDirectory $evidence -WithMysql -WithBrowser
```

不再需要人为预设 NODE_PATH；脚本临时复用当前工作树已有依赖并恢复原值。单独真实 MySQL：

```powershell
python -B 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/run_control_recovery_mysql.py' `
  --maven-root 'C:/workspace/fx/new/control-timeout-20261006-worktree' `
  --output 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-recovery-mysql-new-run'
```

单独真实 UI：

```powershell
node 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/run_control_recovery_browser.cjs'
```

每次证据目录必须全新，runner 拒绝覆盖。`-WithMysql` 同时包含 SQL 机制和完整应用服务/JDBC 两个不同阶段，不能只看前者通过。

## 6. 新不可变候选、备份与已验证步骤

新目录：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-interruptions-20261007/release-candidate-verified/`。本地构建材料没有上传生产。私有部署配置、fixture 凭据和完整业务日志不应分享或提交 Git。

- 新后端 JAR SHA-256：`4475be6f589d7f6d82ddabdb922015b5fb7a8f060cd5b7759afcb138ee57d16a`。
- 新 backend tag：`exchange-705-backend:control-interruptions-20261007-4475be6f589d7f6d`。
- 新 backend image ID：`sha256:dccc32e3fac1632d3a2301a5a6775475d52c24fe457eab73b8bbf666dc65039c`。
- 新 `backend-image.tar`：**179,693,056 字节**，SHA-256 `bf0480cca157668248f69bb47ed88958cf78a50707f4b6cbc3beae881fff746e`，**仅含新 backend，不含 admin 或 rollback**。
- 本轮复用旧 admin image ID：`sha256:877f7b27d0e17b4842e97f82015818cd31fabe838c46152f5325bdf6176f48df`；120 个 admin/src 文件指纹完全一致才允许复用。
- 当前查询热修 rollback image ID：`sha256:715dab04585eea446092dc412a885332044928d137fe0c6a90dfefa76105a39d`；原热修 JAR SHA `8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`，保存在 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-20261007/live-backup/app.jar`。
- admin/rollback 归档仍为 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-20261007/release-candidate-verified/images.tar`；实际核对 SHA `4eba0ce749ecaea4e4a0408f41a600779bdfbe035e2a3bf215a25be57cfcaeb2`。不能只保存新 backend tar 后声称它已包含回滚。
- `C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-interruptions-20261007/pre-interruption-package.jar` 保留本轮打包前 target JAR，旧候选没有被替换。

新 manifest 的 `supersedesBackendImage` 明确旧后端候选已经过时，只替代本地候选引用，不覆盖原镜像或原审批。实际 JAR 应用 class 最大 major **52**，固定 digest Java 8 base；重建日志 Java **1.8.0_502**。精确生产 JVM 1.8.0_504/完整依赖的 Spring Boot 启动未验证。

已执行顺序：最终综合测试；保存测试 class 与源码快照；备份旧 target JAR；串行打包成功；核对新 JAR class 集合和每项字节；核 actual epoch **2026100603**、0701/0702 migration SHA 与上一轮完全相同；核旧归档；只本地新 tag build/save；两个 owner 标识的新容器在无网络中创建、启动、检查 JAR SHA 和 java -version，再按完整 ID 清理。

**两次重建证明制品在容器重新创建后仍在，不是整个 Spring Boot 应用 healthy、生产业务启动、数据库恢复或生产回滚演练。** 新 manifest 保留旧发布硬门禁，并记录其来源 SHA，不称审批已刷新。

可只读复核：

```powershell
Get-FileHash -Algorithm SHA256 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-interruptions-20261007/release-candidate-verified/backend-image.tar'
docker image inspect sha256:dccc32e3fac1632d3a2301a5a6775475d52c24fe457eab73b8bbf666dc65039c --format '{{.Id}}'
Get-Content -LiteralPath 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-recovery-interruptions-20261007/release-candidate-verified/manifest.json'
```

构建脚本已实际执行通过。复用 builder 需要全新输出目录/不存在的 tag、真实最终测试结果及打包前的 tested-build.json；同一版本不应再建相同 tag。正式流程尚未批准，不能直接把这份 candidate 当部署命令执行。

## 7. 发布仍停止，回滚验证等级

最后远端只读核对时间为 **2026-10-07 01:02:59 +08:00**，不是本轮末尾再次实时核验：main-api/admin/mobile/pc 运行 image 与 Compose 候选不同，`configDrift=true`。main-api 运行 base `f30bda…96d0b`，Compose 候选 `613adb…22b79`；运行中的查询单类热修仍有重建丢失风险。

原 Compose `/opt/exchange-705-private-runtimes/main-only-50d65fdc5908/compose.owner-qa.json` 的既有只读 SHA 为 `7e40c0e9d4719da557c3e568b403cbc012495e3acfd0c46f29fb207bb7e13c7b`。本轮没有查询或修改远端，没有写 Compose、迁移业务库、替换容器或发布镜像。部署前必须重新核对当前运行身份、热修 JAR、环境/挂载/网络与 Compose 候选；有漂移或其他待发布版本，继续停止覆盖。

### 待批准并实施的生产步骤

1. 发布负责人先明确多个候选版本的归属/合并顺序，固定经批准的 Compose 基线；不得改别人的候选来制造“无漂移”。
2. 审定源码隔离登记与本轮租户/锁/回调变更。上轮门禁实际 FAIL 26 项，含本轮及既有漂移；本轮没有刷新审批指纹。这不是新版门禁通过结果。
3. 完成 0603→[0701,0702] 严格双尾受控迁移的清单、epoch、完整 schema 快照、旧回执保全、中断恢复、空库往返测试及最低应用版本审定；之后重测、重建新的 SHA 镜像。当前包保留 0603 是明确阻断，不是已经完成 0702 受控发布。
4. 在正式业务快照的独立恢复库验证完整鉴权/JPA/ApplicationContext、各租户流程、持续负载及数据库一致备份/恢复证明。当前完整应用 JAR 备份不能替代业务数据库可恢复备份。
5. 受控发布工具绑定真实目标、独立 restore 目标、plan/proof/审批/ledger，经门禁允许才切换经批准服务到不可变 image；协调新 admin 与 RESTORE 队列协议。重建后实际检查 artifact SHA、healthy、SOURCE/恢复/取消、交易可用性、各租户实际进度和阶段预算。

### 应用回滚材料已验证，生产操作未演练

回滚前停止新 START/RESTORE 接受，通过正式 API 查询、受控取消或排空 pending，保存任务水位、回执及未完成历史补偿。旧 hotfix worker 不理解新版 RESTORE command 语义，不能带着新 pending 直接回切。

只在批准配置基线切换所需服务：backend 回到已保存的**当前查询热修** JAR/image，不回未热修旧慢查询镜像；admin 使用核验的原运行版本备份。其他候选与服务不动。保留 additive 迁移、取消 tombstone 和已提交业务历史，不 DROP 新列、不全库回灌、不 TRUNCATE、不人工改 task 状态。最低应用 epoch 若不兼容，走审定前滚，不强启旧包。

回滚后必须重新实际验证 SHA、healthy、权限、租户隔离、有效源价/不可交易标志和回执/水位，记录证据。上轮远端已核验存在的热修镜像为 `exchange-705-backend:control-lookup-20261006t143735z`，ID `sha256:3e5fcaaec6e0fe317df8a404dea2139a5fec2ac8066f469f0ccac9889b0200de`；发布/回退之前仍需重新只读核验，不能据旧记录自动执行。

完整此前实现和门禁记录：[上一轮交付](C:/Users/徐乾妖/.codex/worktrees/3d6b/705/docs/control-target-recovery-implementation-20261007.md)。

## 8. 明确剩余风险与未覆盖项

- 唯一 JUnit skipped：`S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot`，没有明确标识的百万行全应用快照。百万级不同镜像分布、持续多租户/多品种负载、p99、自然慢 SQL 整机负载均未验收。
- 真实 source 断流用可控 raw availability/event 夹具，不是实际外部行情供应商 WebSocket/互联网断开；没有执行 OS 断电、MySQL server 中途重启、长期网络分区或真实 TCP COMMIT ACK 丢包。组件重建不等于完整进程重启；真实子 JVM halt 和客户端 TCP 断开是另列的实际动作。
- MySQL JDBC 故障场景使用 DriverManagerDataSource；Hikari 清理/evict 另有本地回归，不能称已完成真实 MySQL 连接池长期故障压力测试。
- UNKNOWN 后若另一 owner 取得与尝试相同代次，保守 fence 原 writer，需 fresh owner 接管。这是明确安全优先的可用性边界，不把不确定提交当授权；低代次已证明回滚时仍可同 owner 重试。
- full admin 全套此前 **43 项中 1 失败**（SupportChannelSettings 既有权限缺口），生产权限/JPA `MinimalFixRegressionTest` 此前因 BootTenantFixture DOMAIN_VERSION=NULL 启动失败。未修改无关问题、本轮未重跑这些全套，不能称全仓库测试全绿。
- 新候选只有本地制品持久性验证；生产鉴权、full Spring Boot startup、业务 DB backup restore、实际生产发布/回滚仍待完成，审批/版本协调硬门禁未解除。

**最终状态：本地业务修复、扩展中断场景测试、不可变候选与回滚材料完成；生产发布及上述完整验收待实施。**
