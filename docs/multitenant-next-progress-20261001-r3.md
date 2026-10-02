# 多租户接续实现与隔离候选验证：2026-10-01 r3

**阶段进展，不是项目完成。整体验收未通过，不能正式使用；生产从未迁移或部署。**

本报告接续r2，不重新设计，也不覆盖旧证据。其他写者继续，主工作区合并验收仍待定。本轮优先修复必测MySQL/Redis夹具并实现批准绑定的schema迁移；完整资金、模拟、任务、文件、域名、开通、留存业务链仍未闭环。

## 1. 当前版本与证据口径

- 冻结候选：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate`，2402份源文件，manifest SHA-256 `cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed`。本轮相对旧H只有21份源文件差异，逐份before/after及精确原字节备份索引：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-owned-source-delta.json`、`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-exact-byte-edit-ledger.json`。
- 实际jar：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar`，SHA-256 `37e675bb2965db65e43da0c66f485890bff91b0d305a16fc7fbe97c0c2411758`；实际包内epoch `2026100101`。s4h/i/j/k/l源清单逐份相同，实际编译的全部生产.class逐份相同。完整verify、显式IT和迁移演练因此有明确的同源证明，不是把不同版本测试结果拼成一次通过。证明：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-version-coherence.json`。
- 前端源码未改。缓存图表验证使用候选真实`AssetPixelChart.vue`及既有依赖的只读junction；依赖版本、锁文件和实际来源：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4l-cache-evidence\dependency-reuse.json`。不是四端新构建、生产部署或完整前端验收。
- 候选source门禁退出0；release门禁退出1，4项为发布批准缺失及三个原blocker，未改为已解决。主工作区观察source 132/release 136项退出1，候选与工作区232份源差异；不是132个独立漏洞。D04使工作区盘点非原子，不能据此宣称合并通过。原始命令：`C:\workspace\fx\new\mt705-cont-20261001-r1\s7b-current-gates.json`。
- 报告/矩阵在测试后更新，属于文档增量；它们未混入已经冻结并测试的候选，不能把后续工作区冒充完整验收版本。

## 2. 实际代码与夹具修复

### 必测与真实操作者

- `DedicatedMysqlFixture`保留旧安全端口，新增本轮原生33418时必须校验完整证明SHA、实际UUID/datadir/端口、不同UUID的独立恢复证明；不是解除目标限制或默认tenant=1。
- `DepositOrderMySqlIT`继承的20项加5项原生检查实际执行。CONTROL使用真实账户、真实JDBC审计；审计插入拒绝回滚、幂等只入账一次、旧LEGACY资金标记不伪造新操作者/重入账、已知跨租户id和缺上下文拒绝均保留。
- `ManualOrderMySqlIT`实际41项，`ManualControlJdbcMySqlTest`实际4项。修复夹具真实CONTROL权限/操作者、租户scope、真实批次/符号关系和FK清理顺序，不删生产约束。D01-A普通未实名仍拒绝；负钱包无副作用、合法损失规则保留。行情feed/报价仍有组件mock，不是整条真实HTTP交易链。
- `AssetEquityStore.saveBucket`定位并修复`values(tenant,field)`错误为合法`values(field)`。工作区同一完整文件的既有修复已逐字复核，只把相同正确变更带入隔离候选；参数20位检查通过。
- 缓存IT使用实际MySQL、Redis7容器、故障pause/unpause及新JVM探针；租户隔离、revision/TTL/损坏回退/并发等原断言保留。实际HTTP graph补齐生产AuthService、JWT过滤、TenantJobRunner、OperationalIssueService等依赖，不用身份mock替代真实登录。
- `ChatArchiveTest`原生只读DATETIME(6)检查经同一Dedicated guard进入33418，UTC与六位微秒实际通过。没有启动留存删除，也没有重跑/冒称本jar的旧H 10001条归档验收。

### V14与启动旧包保护

- 新增`V2026100101__tenant_history_revision_and_legacy_markers.sql`，旧13份DDL逐字不变。九条revision触发器按租户隔离，updated_at无变化不虚增；只对NULL补确定性LEGACY充值标记，不补造金额、操作者或时间。
- 新增`SchemaPackageGuard`，发布DataSource之前读实际MySQL最高minimum_application_epoch，缺失/非法/超出当前固定包内epoch拒绝启动；只有原有H2 mem单元夹具例外。不能用环境变量伪装包版本。
- 真实旧H jar没有该包内资源，外部artifact gate读出epoch0并拒绝；当前jar维护检查通过，未批准业务激活仍拒绝。`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-actual-package-check.json`。
- **不能把guard逆向补进旧二进制。旧包直接持有旧凭据启动的隔离、凭据撤销/轮换仍未完成。**

## 3. 正式schema迁移入口：已实现的范围

`C:\workspace\fx\705\scripts\multitenant\controlled_migration.py`提供`plan`、`verify-backup`、批准绑定`apply`/`resume`及实际artifact `package-check`；旧`mysql_migration.py`非test直接迁移仍拒绝并指向正式入口。孤儿与文件工具的fixture限制原样保留。

1. 计划绑定实际物理数据库、当前源和14份DDL hash、完整schema/全部列数据；已有多租户库须提供验证过的COMPLETE baseline ledger，不靠猜索引/表名判断哪些DDL做过。
2. 每次备份/DDL前检查实际global read_only=1和其他连接已排空；命令行声称“停写”不能代替实际检查。
3. 最新完整dump须在不同UUID/datadir的新独立库恢复，完整表DDL、触发器ACTION_ORDER/SQL_MODE、routine/parameter/event定义及全部原业务列一致；绝不恢复覆盖源库或新增增量。
4. 生产固定外部policy未提供就拒绝。预配置独立operator/approver的HMAC批准绑定计划、证明、备份、目标、当前源、scope、过期、停写与恢复责任人。**本轮合成批准只为fixture演练，不是人类生产批准。**
5. 追加不可覆盖、hash链及签名收据，implicit-commit DDL前先写INTENT；逐阶段保存当前状态。部分执行须同计划、当前状态、最新部分库全备份/独立恢复及重新批准，才从下一阶段继续。
6. 丢失提交应答或阶段部分失败标FAILED_UNCERTAIN；不会盲重放CREATE INDEX。当前没有不确定结果人工核对/修复命令，需新审查后的前向处理计划。
7. 第16位小数资金增量也使旧计划/恢复授权失效，保留新值，不回灌旧dump。COMPLETE不可重放，业务激活维持0。生产release和必要完整验收不满足仍拒绝。

### 真实恢复缺陷与修复

MySQL5.7.44 mysqldump实际把触发器的`NO_AUTO_CREATE_USER`从SQL_MODE剥离，s4c最新部分库恢复的全部行相等但74条trigger模式不相等，严格检查按预期FAIL。没有删schema断言。最小严格parser仅校正已识别trigger header的源模式，兼容实际四条无反引号名称；原始dump保留不改，派生restore-input的hash绑定证明，未知布局、重复/缺失trigger或其他模式差异仍拒绝。s4e的无引号失败及离线parser负例失败也保留。

实际同源s4i原生演练8检查退出0：批准错绑定拒绝；0–2阶段完成后中断收据；旧备份不能授权恢复；最新部分库独立恢复与新批准后从3继续，14阶段恰好一次；COMPLETE重放拒绝；第16位小数增量拒绝旧计划并保全；旧计划不能生成新proof；实际DDL丢失应答拒绝盲续跑。`C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-controlled-native-result.json`。

**未完成范围：正式孤儿/私有文件批准apply、旧凭据隔离、不确定DDL人工处置、真实生产目标/批准、完整发布业务链。不能把schema工具的通过扩大成G07/C12整链通过。**

## 4. 当前实际测试结果（不能相加）

| 实际运行 | 调用 | 通过 | failure/error | skip | 退出码 |
| --- | ---: | ---: | ---: | ---: | ---: |
| s4i完整verify | 2459 | 2429 | 0/0 | 30 | 0 |
| 同源s4l显式九类必测 | 103 | 102 | 1/0 | 0 | 1 |
| 同源s4h Python恢复/迁移单元 | 26 | 26 | 0/0 | 0 | 0 |
| 同源s4i原生schema演练 | 8检查 | 8 | 0 | 0 | 0 |

显式九类逐类：Deposit 25、ManualOrder 41、ManualControl 4、ChatArchive 10、Dedicated guard 4、SchemaPackageGuard 3+原生1、bucket SQL 1全部通过；Cache14调用13通过、C12失败。其中P01仅记录“未请求performance”，不是容量压测通过。

- 必测唯一失败：`C12_browserAndAuthenticatedHttp`，`Current-server UI evidence missing; mandatory C12 fails`。没有生成假的PASS收据、删除assert或延长生产客户端15秒timeout。实际C12认证HTTP测量先完成，但六分钟当前服务浏览器收据未闭环，所以整个C12仍失败。
- s3l/s4h/j/k/l浏览器未完成原始结果保留；焦点仿真前置、候选复测协调/六分钟窗口、15秒在途client timeout、300ms可见计数刷新时差导致部分检查没有可接受的当前服务证据。L最初键盘检查错误固定小时，改为读实际Home/Right相差一小时后验证；不改生产代码或数据制造通过。剩余刷新/重入/晚响应必须在同一活跃服务内完整复测。
- 完整verify的30个skip逐项：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-full-skip-cases.json`。其中ChatArchive 1和ManualControl 4在另次显式原生检查确实通过；不改原XML、不把30自动算作可选、不把两个运行调用相加。另25包括资金/行情隔离、真实外部catalog/exchange、资产权益MySQL、容量和TEXT事务原子性等，仍未齐。
- s4b实际Redis端口环境遗漏导致3个503失败；s4e没有短信sandbox sink环境导致7失败。后续只补真实隔离Redis/受限sink环境，原失败保留，未替换实际限流器为mock。当前七条SMS契约及实际Redis安全检查通过只限本地sink；D02真实供应商仍blocked，没有真实外发。
- 逐case/原XML/命令/日志hash：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-testcase-catalog.json`、`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3-command-index.json`。测试名称只用于检索，不证明159条需求的语义覆盖率。

## 5. 缓存前端QA（PARTIAL，不是C12 PASS）

**Environment：** Browser路径为可用的统一CUA/Chrome扩展；未使用外部Playwright/CDP driver或证书绕过。L实际URL `http://127.0.0.1:54069/__cache_test`，API `http://127.0.0.1:51395`；现均已关闭。候选真实Vue组件、生产Auth/JWT和实际MySQL/Redis，仅loopback cash-only fixture；不是公网DNS/TLS、四端全业务或真实OS窗口管理矩阵。390×844通过实际DOM尺寸及截图确认，不只相信设置调用。

| 检查 | 限定结果 |
| --- | --- |
| URL/标题/非空 | 实际`MT705 isolated cache QA`、真实图表及控制器，PASS |
| 首屏/截图 | 实际390×844绿色资产图，未见框架错误overlay；图表控件未裁切，PASS限定该视口 |
| 交互 | 初次1W一次、静置至少61秒无新增请求、同周期无fetch、隐私与日文/英文、Home/Right/Escape、鼠标/触摸长按拖动与恢复、resize，已有原始观察 |
| focus/visibility | 自建新标签产生实际blur/focus与hidden/visible事件，期间计数不变；使用支持的每tab Chrome开发仿真，不是脚本伪造事件。参见[Chrome官方接口](https://chromedevtools.github.io/devtools-protocol/tot/Emulation/#method-setFocusEmulationEnabled) |
| Console | 已观察部分无error/warn；未完成最终竞争/重入后的全程健康验收 |
| 刷新在途/重入/晚响应 | K有实际短于15秒的刷新去重子证据；L完整当前服务收据未齐，NOT ACCEPTED |

证据：`C:\workspace\fx\new\mt705-cont-20261001-r1\s4k-cache-evidence\browser-partial-results.json`、`C:\workspace\fx\new\mt705-cont-20261001-r1\s4l-cache-evidence\browser-final-partial-results.json`。K最初名为mobile的截图实际是桌面，另行保存并验证了actual-mobile，不把错误文件名当移动证据。旧visibility探针宽泛PASS另作PARTIAL重核：`C:\workspace\fx\new\mt705-cont-20261001-r1\visibility-probe-reconciliation.json`。

L实际认证HTTP每组100请求：off p50/p95=17.9313/24.3683ms，cold=20.1867/23.4861ms，warm=17.5738/21.3166ms。warm parent-body SQL为0，auth/revision/live等SQL仍执行，不能称无SQL缓存。`C:\workspace\fx\new\mt705-cont-20261001-r1\s4l-cache-evidence\performance-http.json`。**本轮未执行D03四档120秒容量或长稳验收。**

关键API：CUA标签/AX、grounded locator、真实CDP鼠标/触摸输入、临时focus/device开发仿真。所有临时观察器与尺寸/touch覆盖已清理，自建标签关闭；没有保留浏览器开发状态。

## 6. 最新全部本轮数据保全与资源收尾

- 停止业务测试写者后，盘点并只备份本轮新primary实例内15个`mt705_*`数据库，包括失败/部分DDL和金融增量库；14个有数据，1个初始化空baseline单独标记。共1161个表实例、4600行，不是1161张不同业务表。
- 每库最新完整dump含routine/trigger/event，在不同UUID/datadir的新restore库独立恢复；全部原列含current_token、CONTROL、审计、二进制/时间精度与完整schema一致，触发器模式/ACTION_ORDER也一致；源库再次检查不变。没有向源库恢复、删除部分库或丢掉新增量。证明：`C:\workspace\fx\new\mt705-cont-20261001-r1\s7a-newest-all-owned-restore.json`，每库before/restored state、raw dump、exact restore input、proof均保留。
- global read_only恢复为本轮此前的0。备份PASS后按UUID/datadir/PID/config及Redis精确ID/labels/image/绑定核验，仅正常shutdown本轮33418/33419并stop 33417，不rm容器、不删datadir。`C:\workspace\fx\new\mt705-cont-20261001-r1\s7c-owned-fixtures-stop.json`。
- Vite/watchers/visibility probe已按本轮session关闭，已知本轮Node PID均不再存在。原3306与其他写者33315前后仍监听；没有停止其他Java/MySQL/Docker。全部备份、jar、源码、失败证据、独立恢复库保留。
- 本轮未导入测试CA；旧thumbprint根库匹配0。没有重新导入、关闭TLS校验或绕过浏览器警告。

## 7. 下一步与未完成项

1. 必测C12在同一仍活跃的服务窗口内完成全部十项真实交互，尤其刷新在途、重入、晚响应数据不替换；保留现有失败，不把单个截图或HTTP测量替代C12 PASS。
2. 按30skip清单补其余真实MySQL资金/权益/行情、外部条件、容量与任务故障检查；再做本版本完整资金/模拟/任务/客服/文件/开通/域名/留存链。真实SMS沙箱仍需外部条件；不真实付款或外发。
3. 正式孤儿/文件批准入口、旧凭据隔离和不确定DDL人工前向处置仍是缺口；真实生产需明确目标、停写、最新独立恢复证明、实际批准及全部必要验收。release_approved=false、三blocker和activation=0均不自动放开。
4. 主工作区D04并发变化的合并审查保持待定；不要把本轮已验证的隔离源码覆盖其未审活动/手动订单变更。

当前机器checkpoint：`C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3.json`；矩阵：`C:\workspace\fx\705\docs\multitenant-requirement-matrix.json`。159需求、G01–G12、C01–C12、T01–T18范围保留；本轮只追加有明确来源的限定证据，未声称接口全集或全部需求通过。

## Screenshots

![同源L实际390×844图表：部分交互证据，不代表C12通过](C:\workspace\fx\new\mt705-cont-20261001-r1\s4l-cache-evidence\mobile-390x844.jpg)
