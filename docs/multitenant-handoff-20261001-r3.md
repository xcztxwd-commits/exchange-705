# 多租户接续执行交接：2026-10-01，r3 基线

## 0. 工作要求与结论

用户要求：“写一个交接文档和后续的命令，然后开启新会话执行”。新会话应直接继续实现和验证，不只重述进度或重新设计。

当前结论：核心多租户能力已经实现，真实集成验收、共享工作区合并和正式发布尚未完成。生产从未迁移或部署，不能正式使用。继续本地开发和隔离测试不等于授权生产迁移、真实付款、短信外发或删除真实聊天。

默认简体中文；编码使用 ponytail full，回复使用 caveman full，先读取各自 SKILL.md。文档、代码注释和审查记录使用正常、完整的专业表述。沿用适用的仓库指令和现有安全边界。

## 1. 首先阅读的持久证据

1. 本交接：`C:\workspace\fx\705\docs\multitenant-handoff-20261001-r3.md`。
2. 后续命令：`C:\workspace\fx\705\docs\multitenant-next-commands-20261001-r3.md`。
3. 最新进度：`C:\workspace\fx\705\docs\multitenant-next-progress-20261001-r3.md`，SHA-256 `5a30d4d59502b99b0be04ea89ddf10042133e97e8a3e6bed96dad51392ca5332`。
4. 机器 checkpoint：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3.json`，SHA-256 `b1940ef18068b7307220f7edf11ea32443ea3fc26b4df29e410fa99f653995b2`。
5. 需求矩阵：`C:\workspace\fx\705\docs\multitenant-requirement-matrix.json`，SHA-256 `89e0b9af48cb64f0895c54ba1eaa33c168453a2c4a3d3466b9ddbddd5b050c6f`。保留159条需求、G01–G12、C01–C12、T01–T18；所有总体接受标志仍未通过。
6. 上一阶段：`C:\workspace\fx\705\docs\multitenant-next-progress-20261001-r2.md`。历史浏览器、归档、有限压测仅支持其注明的旧候选和限定范围，不能替代新版本完整验收。

以上 hash 在写交接时已重新核对。共享文件如果后来变化，应记录差异并审查，不能自动更新 hash 或把新版本套用旧通过结果。

完整审计索引均在 `C:\workspace\fx\new\mt705-cont-20261001-r1`：

- 命令、退出码与日志索引：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-command-index.json`。
- 原始测试目录：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-testcase-catalog.json`。
- 跳过清单：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-full-skip-cases.json`。
- 同源及生产 class 一致证明：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-version-coherence.json`。
- 精确改动及原字节备份索引：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-exact-byte-edit-ledger.json`。
- 最新全库独立恢复：`C:\workspace\fx\new\mt705-cont-20261001-r1\s7a-newest-all-owned-restore.json`。
- 收尾核验：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-final-verification.json`。其中 PASS 只代表文档、冻结源及资源检查，不是项目验收。

## 2. 可追溯基线，不等于当前共享工作区

- 项目工作区：`C:\workspace\fx\705`，观察 HEAD `1a2642e4e02033f27141a366376589b09c188a54`。HEAD 不包括大量未提交和并发改动。
- 已验证冻结候选：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate`。
- 源清单：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate-source-manifest.json`，2402份文件；清单文件 SHA-256 `cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed`。
- 实际包：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar`，SHA-256 `37e675bb2965db65e43da0c66f485890bff91b0d305a16fc7fbe97c0c2411758`；包内 epoch `2026100101`。
- s4h/i/j/k/l 的源和实际生产 class 一致。只有明确同源的验证才能组合说明覆盖范围；测试调用数不能相加。
- r3 候选 source 门禁退出0，release 门禁退出1。发布批准缺失，且三项原 blocker 未关闭：`ORM_DIRTY_WRITE_PREDICATE`、`PRODUCTION_LEGACY_ORPHANS`、`RUNTIME_ACCEPTANCE_PENDING`。
- `release_approved=false`，业务 `activation=0`，共享工作区合并验收 pending。最近工作区与候选有232份源差异；工作区 source/release 132/136项是非原子观察，不是独立漏洞数。

继续测试和修改时，新建唯一 ASCII 运行目录、新候选、target、XML和日志；不得在旧候选中重新构建、修改代码或覆盖旧报告。共享工作区仍有其他写者，不做 reset、clean、无审查整文件覆盖或自动合并。

## 3. 已实现的功能及本轮增量

### 已有实现

租户上下文、tenant_id隔离、缺失上下文和跨租户拒绝；总控、普通后台和总控代登录身份分离；票据与撤销、真实操作者审计；独立表格偏好、域名候选准备/验证/激活、UTC监管过滤、开通就绪检查、本地短信契约和可见运行异常；活动素材关系及尺寸约束；持久有界聊天归档、附件授权下载、分块校验及恢复工具。

普通未实名用户仍严格拒绝。D01-A受控管理动作需要真实权限、原因和持久审计，不得伪造操作者、默认为tenant=1或让普通业务绕过实名。

### r3实际增量

1. 必测使用真实、独立恢复证明绑定的 MySQL 夹具，真实 CONTROL/JDBC 审计；充值幂等、拒绝审计回滚和旧 LEGACY 标记保留。
2. 手动订单修正租户scope、权限、操作者、批次/符号关系及FK清理；报价组件仍有mock，不代表全HTTP交易链。
3. 实际Redis7故障与新JVM缓存检查，生产Auth/JWT HTTP graph；修复 `AssetEquityStore.saveBucket` 的错误 SQL。
4. V14新增九条租户revision触发器和NULL限定的确定性LEGACY标记；旧13份DDL逐字不改，不伪造金额、操作者或时间。
5. `SchemaPackageGuard` 在发布DataSource前核验实际数据库与固定包内epoch。旧jar的外部artifact gate拒绝，不代表旧二进制持旧凭据直接启动已被隔离。
6. `C:\workspace\fx\705\scripts\multitenant\controlled_migration.py` 实现 plan、verify-backup、批准绑定apply/resume和package-check，具备物理目标/全状态绑定、实际停写检查、独立恢复证明、签名阶段收据、增量保护和不确定DDL拒绝盲重放。
7. 修复真实mysqldump触发器SQL_MODE恢复差异；原始dump保留，派生输入hash绑定，只改已识别header，schema断言不减。

## 4. 实际验收状态

- 完整verify：2459次调用，2429通过、0 failure、0 error、30 skip，退出0。
- 显式九类必测：103次调用，102通过、1 failure、0 error、0 skip，退出1。
- 同源Python：26项通过。原生schema迁移演练：8项通过。
- Deposit 25、ManualOrder 41、ManualControl 4、ChatArchive 10、Dedicated guard 4、SchemaPackageGuard 3+原生1、bucket SQL 1已通过。Cache 14调用中13通过，C12失败。
- P01记录未请求performance，不是容量通过。本轮没有D03四档压测或长稳。
- 完整verify的30 skip原XML不变。其中ChatArchive 1、ManualControl 4在另次显式检查通过；其余25仍未闭环，不能统称可选或自动豁免。
- 本地SMS sink/契约通过不等于真实供应商；真实沙箱条件仍blocked。

## 5. 第一优先级：C12当前服务浏览器验收

失败方法：`AssetHistoryCacheIT.C12_browserAndAuthenticatedHttp`。

决定性消息：`Current-server UI evidence missing; mandatory C12 fails`。真实登录/JWT HTTP的300次测量已完成，部分Chrome/Vue交互有证据，但没有在六分钟活跃服务窗口内完成十项收据。不能据此直接断言生产功能有bug，也不能把部分证据标PASS。

先读取实际测试和 `C:\workspace\fx\new\mt705-cont-20261001-r1\cache_ui_harness.cjs`，将历史硬编码路径和输出重新绑定到新运行目录。历史脚本只作参考，禁止直接运行覆盖旧输出。

先准备浏览器工具、真实Vue组件、依赖来源记录、独立Vite端口和收据位置，再启动必测。读取本次 `browser-request.json` 的动态API与challenge；所有操作必须在同一仍活跃的服务和challenge下完成。当前合约要求 `cua_repl`；先读取当前 computer-use/front-end测试技能。不要照搬r2的一次性浏览器驱动回退许可。

必须全部验证的十项：

1. `enter-default-1W`
2. `idle-60s-no-fetch`
3. `focus-visibility-resize-privacy-locale-canvas-keyboard`
4. `same-period-no-fetch`
5. `period-1M-fetch`
6. `refresh-inflight-dedup`
7. `reenter-once-1W`
8. `late-response-cannot-replace-latest`
9. `no-page-errors`
10. `screenshot`

每项保留可复核的真实操作、请求计数/响应、DOM、console和截图依据。只在十项全部完成后写带实际API和challenge的PASS收据。缺任何一项，保留PARTIAL/FAIL，不能生成假PASS。

已知协调问题包括浏览器焦点仿真前置、窗口时间、300ms可见计数刷新时差及15秒在途客户端timeout。先协调测试准备和真实状态，不删assert，不改生产timeout制造通过。键盘检查读实际Home/Right时间差，不固定某个小时。使用真实390×844尺寸及截图确认移动视口。

第一阶段出口：C12真正通过并重跑同一新候选九类必测；若仍失败，提交明确根因、当前服务原证据和最小修复，继续可独立完成的工作，不伪装完成。

## 6. 后续顺序与剩余缺口

### A. 补齐测试与资金边界

逐项处理 `C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-full-skip-cases.json` 中其余25项：BalancedControlPlanMySQL、CatalogLive、ContractCloseConcurrency、ExchangeLive、KlineRetention、MarketIsolation、ReceivedIndex、SourceCandlesTEXT、AssetEquityMySQL。按实际case区分缺夹具、真实外部条件、代码错误及尚未运行，不删除skip或改成无条件通过。

随后做本版本充值审批、转账、开平仓/到期、结算、权益、提现、理财/收益和借贷链；检验跨租户拒绝、全部资金字段不变量、审计回滚、幂等、并发、真实/模拟隔离、异步任务scope与故障补偿。真实报价、HTTP链和后台任务不得用组件mock替代。

### B. 迁移仍缺的实现

正式孤儿数据与私有文件批准apply、旧凭据撤销/轮换及旧包直接启动隔离、不确定DDL的人工核对和新审查前向处置仍未完成。现有schema工具不授权这些范围，不得绕开旧orphan/private_files工具的fixture限制。

保留14份迁移的版本记录，旧DDL不得重写；implicit-commit DDL先记INTENT。部分完成必须保留当前增量，取得最新完整备份及不同UUID/datadir的独立恢复证明，再重新批准从下一阶段继续；未知提交结果不能盲重放。

### C. 运营与全端业务验收

新租户完整开通经营链、公网DNS/HTTPS挑战与域名热切换/旧Host拒绝、活动派发/领取/体验金、客服/监管及附件权限、101个超大队头归档与留存保全/删除/故障恢复、四端真实角色和业务交互仍未完整验收。留存清理默认关闭。真实短信沙箱需外部提供，不擅自真实外发。

当前版本的交易、结算、导出、客服、模拟混合容量和长稳尚未完成。读取既有D03批准范围与资源预算；历史有限接口压测不能代替当前版本混合容量，不新增未获批准的压测预算。

### D. 合并与发布

审查共享工作区的并发活动/手动订单等变更，逐份合并、更新有审查理由的登记并重新冻结验证。不能只重算registry hash消除门禁。

发布前必须具备：全部必要验收、明确生产目标、实际停写与连接排空、最新全备份独立恢复、真实责任人与批准、旧凭据隔离、通过的release门禁。当前缺这些条件，继续保持 release_approved=false、原blocker未关闭和activation=0，不部署。

## 7. 数据与资源边界

- 上轮15个数据库的最新全备份及独立恢复均保留，包括失败、部分DDL和资金增量；1161是多个克隆的表实例数，4600是累计行数，不是唯一业务表规模。
- 收尾时本轮33418/33419 MySQL、33417 Redis和自建Vite/探针已关闭，datadir、容器、dump、jar、失败日志未删除。旧CA根库匹配0，当前没有需要继承的CA安装。
- 原3306和其他写者33315没有停止。新会话开始时重新观察所有端口和PID，不能把旧“空闲/已停”当当前事实，更不能按端口批量杀进程。
- 新建独立、可丢弃、loopback实例和数据库；现有Dedicated guard只允许已登记端口，并绑定实际UUID/datadir/数据库/备份hash和独立恢复UUID。匹配守卫前置，不随意扩大目标allowlist。
- Redis实际7版本及容器身份/label必须核对；故障pause/unpause仅限本轮精确容器。
- 写库、DDL或受控测试清理前核验最新全备份和独立恢复。不得将旧dump恢复覆盖源库，不得初始化已有datadir，不得丢掉运行后新增资金、账号、归档或审计。
- 凭据、token、批准密钥及完整敏感备份留在受限证据目录；交接、日志摘要和新会话消息不复制认证值。
- 浏览器不绕过TLS警告，不关闭证书校验，不无授权安装根证书。后台Windows助手使用Hidden窗口。
- 每次修改先保存原字节和hash，再原子写入；不要覆盖他人并发改变。

## 8. 本轮应留下的交付物

1. 新的唯一运行目录、来源/依赖清单、候选源hash及jar/class证明。
2. 每项实际命令、退出码、原始XML、失败/skip目录；真实浏览器/network/console/截图和C12收据。
3. 修复文件、最小差异、原字节备份、复测证据；不得把测试命名当159条需求的语义覆盖率。
4. 新版进度报告和机器checkpoint；逐条区分code_gap、fixture_defect、external_condition、not_run、not_deployed和accepted scope。
5. 最新所有本轮数据库完整备份、独立恢复、增量保全、精确资源收尾记录；原旧证据保持不变。
6. 阶段性通过只更新确实覆盖的需求。总体业务/合并/发布尚有缺口时，不写项目完成。

当前:多租户平台 / r3限定验证，必测C12失败且未上线 / 新会话先执行命令文档第1至4节，再补C12及剩余业务与迁移闭环