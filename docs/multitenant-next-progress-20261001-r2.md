# 多租户接续实现与隔离候选验证：2026-10-01 r2

**状态：本轮实际实现并完成限定范围复测；整体验收未闭环，不能正式使用。生产从未部署。**
本报告替代r1作为当前交接状态；r1和各轮失败、XML、日志、备份均保留。其他写者继续；这里只接受独立冻结候选H的明确证据，主工作区合并验收待定。

## 1. 版本与判定口径

- 当前观察HEAD：`1a2642e4e02033f27141a366376589b09c188a54`。HEAD不是600余项未提交/并发变化的完整版本。
- 候选：`C:\workspace\fx\new\mt705-next-20260930-r1\s6h-owned-candidate`；2394份源文件逐份重新散列，无缺失/变化。source manifest SHA-256：`cc39f56fda84844112ebd546748aa6d4ac49d5af790229a79a17223a618be020`。
- 当前实际jar SHA-256：`17bcb810a1611b940f8cea5691ac8145b7d3e14e80d1f81652ae31266308d922`；实际库epoch `2026093006`。
- 四端使用本轮R真实构建产物，H的1171份前端输入/锁文件逐字相同；全部四端产物重新验hash。不是旧前端来源不明，也不是一次新构建或跨轮测试总数。
- 构建、输入复用、部署和冻结排除记录：C:\workspace\fx\new\mt705-next-20260930-r1\s5s-runtime-frontend-builds.json；C:\workspace\fx\new\mt705-next-20260930-r1\s6k-frontend-exact-input-reuse.json；C:\workspace\fx\new\mt705-next-20260930-r1\s6m-reconciled-current-deployment.json；C:\workspace\fx\new\mt705-next-20260930-r1\s6h-owned-candidate-provenance.json。
- 本次完整verify原始XML2453调用：2428通过、0failure、0error、25skipped，退出0。另次必测IT三类BeforeAll共3失败，退出1，不能相加或由verify退出0遮盖。
- 全部25个skip逐项：C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2-skips.json；实际测试目录索引：C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2-testcase-catalog.json。匹配测试名称仅用于检索，不证明接口/业务覆盖比例。
- 最新工作区门禁观察：source 134 issues，release 138 issues，均退出1；C:\workspace\fx\new\mt705-next-20260930-r1\s7e-current-worktree-source-gate-command.json、C:\workspace\fx\new\mt705-next-20260930-r1\s7e-current-worktree-release-gate-command.json。这不是134个独立漏洞，也不是候选H门禁失败。
- `release_approved=false`及三个未解决release blocker原样保留。已有forceVersionIncrement修复和历史MySQL七项不是“完全未实现”；也不足以自动放开新增源码/全部业务发布。

## 2. 实际实现差异与逐阶段出口

每个源文件的精确before/after/候选/当前散列与备份索引：C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2-implementation-ledger.json。每轮源/测试/DDL/锁文件完整散列：C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2-source-rounds.json。逐文件备份位于受限目录，保留他人改变，不做reset/clean/盲覆盖。

- S0：冻结独立ASCII候选、区分写者/端口/目标、重跑源及release门禁；需求159条、G12/C12/T18/S8均机器可读。主工作区持续漂移，不能当冻结版本。
- S1：活动素材关系/尺寸约束、表清单、前向DDL登记；保留活动动作与KYC检查。D01-A受控退出、原因/幂等/真实审计，普通用户仍严格拦截。新活动选择/派发/体验金等他人变更保持未审。
- S2：双身份表格偏好（CONTROL独立客户端，不借普通Pinia/token）、域名候选工作流、UTC监管过滤、开通readiness/设置定位、本地短信sink/契约和可见异常。实现持久有界归档任务、101分块恢复、附件授权下载与v2独立恢复工具。
- S3：修复隔离旧夹具、保留原安全/资金断言；完整H verify及真实JDBC/manual opt-in重新执行。显式三类IT失败及25skip保留，尚未修齐。
- S4：冻结H jar和四端，隔离真实/模拟实例、实际受信HTTPS代理、现有真实Chrome桌面/移动/多标签实际登录和归档交互。模拟启动不是模拟业务链验收。
- S5：10001消息/101块/100 PNG实际JVM中断、数据库持久游标继续、独立全字段恢复；批准的10/25/50/100并发有限接口递增压测。不替代所有故障、任务补偿或长稳。
- S6：仅fixture核查/恢复工具及备份保护可用；批准绑定正式plan/apply/resume未实现，Java启动防旧包与正式增量保全闭环未齐。盲重放一次性索引实际1061，不能标正式可续跑。
- S7：未执行生产迁移/部署；生产目标、停写、最新批准计划、必要验收和实际批准未齐。

## 3. 当前归档真实缺陷、修复和数据不变量

- 实际Connector/J返回`DATETIME`为`LocalDateTime`，旧JSON链规范化未处理，10001条任务在游标0失败。真实只读JDBC探针定位；共享`archiveValue`显式UTC映射，同时覆盖v1和v2，保留六位微秒，新增便携及真实JDBC测试。
- 原失败与根因：C:\workspace\fx\new\mt705-next-20260930-r1\s6e-current-runtime-archive-restart.json；C:\workspace\fx\new\mt705-next-20260930-r1\s6f-archive-jdbc-types.log；C:\workspace\fx\new\mt705-next-20260930-r1\s6g-archive-jdbc-root-cause.json。旧失败任务及证据不改成成功，不声称可无损原地迁移旧坏归档。
- H实际10001条任务：100条已提交后只停止本轮精确Java PID，重新启动同一jar且不覆盖备份/文件；最终10001条、101块、100 PNG；各块SYSTEM真实审计、操作者与发件者不伪造，授权/租户拒绝及所有下载SHA/chain/微秒校验。
- 原命令仍FAIL：C:\workspace\fx\new\mt705-next-20260930-r1\s6n-current-runtime-archive-restart-command.json。原因是整张`asset_snapshot`不变断言捕获到合法定时追加。C:\workspace\fx\new\mt705-next-20260930-r1\s6o-current-archive-invariant-reconciliation.json独立逐字段确认所有钱包/订单/收据、聊天/附件/read flags未变，11171条原快照全列未变、新快照父关系与真实钱包合计逐条正确。没有关闭快照任务、删断言或把失败收据改PASS；新压测保留更精确的完整核心不变量及快照追加校验。
- Java实际认证下载产生的101块归档由当前Python工具在独立MySQL恢复：C:\workspace\fx\new\mt705-next-20260930-r1\s6t-current-java-archive-native-interop.json。10001消息/100 PNG、DATETIME(6)、完整原业务字段精确恢复；重放拒绝已存在PK，未覆盖当前运行数据。只在新的合成克隆内删除已归档合成会话来验证恢复，运行实例聊天从未删除。
- 运行留存始终关闭。101个超大队头推进、全保全/删除/故障矩阵尚未验收；上述恢复不允许自动开启清理。

## 4. 后端必测、原始失败与可重跑入口

- 完整H verify：`pwsh -NoProfile -Command "& ./scripts/multitenant/verify-build.ps1 -MavenArguments verify"`是包装入口；本次还传真实fixture配置路径和隔离Redis端口，完整实际参数仅在受限C:\workspace\fx\new\mt705-next-20260930-r1\s6h-owned-full-verify-command.json，未复制密码/token。
- 必测显式IT：`verify-build.ps1 -MavenArguments @("test","-Dtest=DepositOrderMySqlIT,ManualOrderMySqlIT,AssetHistoryCacheIT")`；C:\workspace\fx\new\mt705-next-20260930-r1\s6v-required-it-summary.json。三个缺fixture BeforeAll失败不是skip/pass。旧硬编码单租户夹具需适配租户/真实操作者/审计，不得默认tenant=1制造通过；当前Redis6不满足该IT要求的Redis7/Docker故障夹具。
- 当前恢复工具单元：`python -m unittest discover -s scripts/multitenant -p test_retention_archive.py -v`，H实际13项退出0；C:\workspace\fx\new\mt705-next-20260930-r1\s6x-current-retention-python-tests-command.json。
- 完整新/旧DDL实际探针：C:\workspace\fx\new\mt705-next-20260930-r1\current_candidate_migration.py；C:\workspace\fx\new\mt705-next-20260930-r1\s6y-current-candidate-migration.json。旧数据独立恢复、13份DDL一次升级、原列保全、17项实际SQL检查通过子步骤；额外未经版本收据保护的索引重放1061，整次退出1。尚不证明正式apply/resume。
- 当前实证重跑入口：C:\workspace\fx\new\mt705-next-20260930-r1\runtime_archive_restart_h_no_pipe.py；C:\workspace\fx\new\mt705-next-20260930-r1\java_archive_native_interop_h.py；C:\workspace\fx\new\mt705-next-20260930-r1\runtime_capacity_h.py；C:\workspace\fx\new\mt705-next-20260930-r1\browser_archive_h_ok.cjs；C:\workspace\fx\new\mt705-next-20260930-r1\browser_identity_flows_h.cjs。
- 重跑必须先建新的唯一运行号/ASCII候选/target/XML/输出目录，并核对脚本里的本轮固定路径和报告名；不得直接覆盖本轮证据、恢复旧备份覆盖增量、重新初始化已有datadir、偷用他人端口或将fixture入口接生产。本轮服务现已停，测试CA已移除；前置不齐时应记blocked/not_run，不关闭TLS验证。
- 全命令/退出码/日志hash索引：C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2-commands.json。其中坏选择器、Java时间映射、背景进程pipe、原过宽不变量、清理脚本/缓存失败均保留原exit!=0，不包装为零。

## 5. 批准的有界容量结果

- 当前用户批准预算：每档120秒，p95≤1000ms、p99≤2000ms、非预期错误率≤1%、每实例JVM堆≤768MiB、持续CPU≤80%；连续30秒指标超限停压，任意越权/资金不变量失败立即停压。未改成历史文档建议预算。
| 并发 | 请求 | p95 ms | p99 ms | 非预期错误 | 最高主机CPU | 最高提交堆MiB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 5833 | 9.682 | 16.292 | 0 | 21.0% | 133.0 |
| 25 | 14536 | 8.954 | 16.342 | 0 | 11.0% | 133.0 |
| 50 | 28153 | 11.071 | 21.917 | 0 | 18.0% | 133.0 |
| 100 | 56445 | 16.481 | 27.563 | 0 | 18.0% | 133.0 |

- 合计104967请求；原始逐请求/指标hash：C:\workspace\fx\new\mt705-next-20260930-r1\s6p-approved-capacity.json。100个可丢弃A/B账号通过真实HTTPS登录，非注入token；新账号写入前最新完整备份已独立恢复。
- 仅assets/info/activity/跨Host token拒绝接口；全部钱包/订单/收据字段版本不变，原快照保全，新追加父关系与钱包合计正确。不代表交易/结算/导出/模拟业务容量，未执行长稳。

## 6. 前端QA

### Findings
- 实际归档弹窗按钮由ElementPlus显示`OK/Cancel`，旧脚本按中文“确定”定位超时，截图后修正实际可见选择器；原失败保留，不放宽HTTP/下载/资金检查。
- 原PC共享通知组件路由/provider输入已修，本H精确输入实际路由/Inbox通过；Inbox模块停用状态是刻意配置，不等于已测试客服完整链。
- 仍有旧后台图表legend与轴标签重叠、长数字ID在窄列换行、短时成功toast叠放；未宣称全端视觉精修通过。

### Summary / Environment
- 实际Chrome `154.0.8037.58`、既有Playwright `1.62.1`，headed；Browser路径两次`Unable to load browser request-header policy`后按D06获准回退。未安装浏览器、未使用ignoreHTTPSErrors或证书绕过。
- 实际地址：`https://control.localhost/`、`https://admin.localhost/dashboard`、`https://a.localhost/`及`https://b.localhost/mobile/home`。桌面1440×900/1000，移动390×844。现在测试服务已停、CA已移除，不再是可在线访问的生产地址。
- H实际Chrome身份/表格/多标签15检查通过，归档8检查通过；主工作区新并发UI不是本轮候选。

### Changes Verified / Checks
| 检查 | 限定结果 |
| --- | --- |
| URL/标题/身份 | PASS：真实总控/普通后台/A-B用户，记录每个screen URL/title |
| 非空首屏/错误overlay | PASS：有实际业务控件，无框架错误overlay |
| Console | PASS：无pageerror或捕获Vue异常；预期撤销401、favicon404单独解释 |
| Screenshot | PASS：原PNG/SHA/DOM文本hash留档且实际查看，不以截图代替API |
| Interaction | PASS：密码/MFA、弹窗票据、三身份偏好、撤销、PC路由、移动登录、真实创建/刷新/下载归档 |
| 全部业务/角色/视口 | NOT ACCEPTED：没有穷尽全部资金、任务、客服、文件及模拟路径 |

### Interaction Loop / Commands
- 总控登录后保存BackendAccounts偏好并重载；分别一键进入A/B，opener清空、固定origin和真实票据；与普通负责人并发修改，偏好不共享。总控退出撤销两Access页而普通负责人仍有效。
- 监管页成对UTC请求、留存默认OFF/delete禁用、运行异常真实API；PC A与移动B以同一fixture邮箱各自登录且租户身份不同。
- 监管查询201会话，首100条只读和认证PNG渲染；可见原因弹窗创建真实任务，刷新COMPLETE，实际下载manifest/3块/2 PNG并SHA/微秒校验。
- 实际入口：C:\workspace\fx\new\mt705-next-20260930-r1\browser_identity_flows_h.cjs与C:\workspace\fx\new\mt705-next-20260930-r1\browser_archive_h_ok.cjs，退出均0；DOM、network、console、下载、屏幕hash在两个result.json，不含认证值。

## 7. 当前资源收尾与增量保护

- 只停止本轮精确PID/exe/cmdline/启动时间/hash/loopback listener匹配的真实、模拟和代理；C:\workspace\fx\new\mt705-next-20260930-r1\s7ab-owned-runtime-stop.json。原清理保护误判先停止而非杀错进程，修正路径绑定/DateTime重复解析后才执行；原失败保留。
- 停业务写者后，最新真实86表34350行、模拟86表278行完整备份分别在不同UUID/datadir的MySQL独立恢复，每列/bytes/微秒/控制元数据精确相等，当前源未改变；C:\workspace\fx\new\mt705-next-20260930-r1\s7b-latest-runtime-backup-restore.json。不以空库恢复冒充、不用旧备份覆盖新账号/归档/活动增量。
- 本轮MySQL33318/33319和Redis33317按精确UUID/datadir/run_id正常关闭；完整datadir、dump、归档、jar、前端产物、私有证据均保留，未重新初始化或删除。Redis为明确的临时缓存，停止后缓存丢失已记录；C:\workspace\fx\new\mt705-next-20260930-r1\s7d-owned-database-fixtures-stop.json。其他写者的33315实例未动。
- D05测试CA已按thumbprint `333D16D6AC2631B0642BCB4704192266B30CF8D0`精确移除。原provider不能无UI删除，使用当前用户原生精确删除，独立新进程只读store确认0匹配；失败/缓存/布尔字面量修复原始记录完整保留。C:\workspace\fx\new\mt705-next-20260930-r1\s7cd-test-ca-cleanup-reconciliation.json。原生命令使用[Microsoft certutil文档](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/certutil#-delstore)的`-user -delstore`，没有elevation/force/silent/ACL改动或TLS校验绕过。

## 8. 必需未闭环项与交接

- 合并源码登记/新活动和手动订单审核；必测IT及25个opt-in/skip；完整本版本资金/模拟/任务/文件业务链；101超大队头/留存删除保全故障；公开域名链；新租户完整经营链；正式批准绑定迁移/恢复/增量/旧包隔离入口。
- 真实短信供应商/沙箱未提供属external_condition；正式目标/停写/批准缺失属production blocked；正式编排未实现属code_gap；缺fixture属fixture_defect；未测/未部署不混称代码未实现。
- 每条需求实现位置及支持证据/未验收范围见C:\workspace\fx\705\docs\multitenant-requirement-matrix.json，完整checkpoint见C:\workspace\fx\new\mt705-next-20260930-r1\checkpoint-r2.json。159条含历史上下文，不宣称接口全集已穷尽。
- 本环节按当前用户要求写handoff并开启指定gpt-6.1-sol/max新会话继续；交接引用这些持久证据，不复制秘密或整份设计。新会话创建结果另存，不能把“待创建”说成已启动。

## Screenshots

![独立总控偏好](C:\workspace\fx\new\mt705-next-20260930-r1\s6u-chrome-identity-flows\01-control-backend-preferences.png)
![真实201消息任务与完整下载](C:\workspace\fx\new\mt705-next-20260930-r1\s6r-chrome-archive\03-archive-downloads.png)
![移动B租户登录390×844](C:\workspace\fx\new\mt705-next-20260930-r1\s6u-chrome-identity-flows\11-mobile-user-B.png)
