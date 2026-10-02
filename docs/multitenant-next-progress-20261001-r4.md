# 多租户接续执行进度：2026-10-01，r4 限定验收

## 1. 结论与边界

本轮已完成原先失败的 `AssetHistoryCacheIT.C12_browserAndAuthenticatedHttp` 十项同一活跃服务浏览器验收；九类必测103项全部通过。同一冻结候选完整verify为2459项，2434通过、25跳过、0失败、0错误。另次执行其中一个留存opt-in，1项限定通过；其余24项尚未执行，不能把完整verify原25个skip改写为24或豁免。

这些结果只支持冻结r3源的本轮重验。共享工作区仍待逐份合并审查；新增Python元数据完整性防护另有28项便携测试，不在本轮Java候选包内。原三项发布blocker未关闭，release门禁不通过，`release_approved=false`、业务activation=0、生产未迁移或部署。项目与159条需求的完整业务链没有验收完成。

**名称区分：缓存测试方法C12不等于需求矩阵的C12迁移/发布整链。** 本轮没有将矩阵C01–C12、G01–G12或总体接受状态改为通过。

## 2. 可复核版本

- 唯一运行目录：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671`。根目录ACL仅当前Windows用户FullControl，禁止继承。
- 已验冻结候选：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\candidate-r3c`。2402份源与原r3基线逐文件SHA-256一致；基线与旧候选未修改。
- 源清单：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\baseline-source-manifest.json`，SHA-256 `cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed`。
- 完整verify后实际jar：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\candidate-r3c\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar`，SHA-256 `c6bfcb1c73c74d5686887b504c35d4e222292e264747e5d96f489572a6ff5aed`，实际包内 `META-INF/mt705-schema-epoch` 为 `2026100101`。
- 与原r3实际jar的559个生产class逐个hash一致。两次jar整体hash不同，不能用旧jar hash指代新包。
- 最终源/包核验：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\final-frozen-artifact-and-baseline.json`；class证明：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\final-artifact-production-class-comparison.json`。
- 最终候选source门禁退出0、release退出1，四项为批准缺失及 `ORM_DIRTY_WRITE_PREDICATE`、`PRODUCTION_LEGACY_ORPHANS`、`RUNTIME_ACCEPTANCE_PENDING`。共享工作区source/release分别退出1，观察132/136项，不是独立漏洞数。未自动重算registry或批准。
- 观察Git HEAD仍为 `1a2642e4e02033f27141a366376589b09c188a54`。共享状态另见 `C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\final-workspace-git-status.txt`，它不是原子冻结或接受证明。

## 3. C12实际结果

本次动态API为 `http://127.0.0.1:56746`，challenge为 `3a9a6293-981d-4bab-911c-998764b5a92b`；服务绑定UTC `02:39:25.621`，十项收据完成UTC `02:44:55.183`，329562毫秒，小于360000毫秒。Java在该服务仍活跃时消费收据并通过，随后正常结束服务。

十项都进行了实际操作和状态检查：默认1W；61秒空闲无新增请求；真实focus/visibility/blur、privacy、ja/en、390×844与1280×900、鼠标/模拟触摸/键盘；同周期无新增；切1M恰好一次；真实HTTP在途刷新去重与禁用按钮物理点击；重入恰好1W；延迟旧1W不能替换最新1M；页面错误检查；实际截图。

真实AuthService登录、JWT filter、Tomcat、MySQL、Redis以及生产Vue组件运行在本轮夹具上。代理只延迟真实上游响应，没有伪造行情/HTTP响应。六次range为 `1W,1M,1M,1W,1W,1M`，六个上游均200，响应正文逐个保存。

- 收据：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\cache-evidence-r3\browser-results.json`。
- 请求/响应：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\cache-evidence-r3\proxy-events.jsonl`及该目录下六个 `response-*.json`。
- DOM、console、network、截图：该目录的 `browser-evidence.json`、`browser-console.json`、`browser-network.json`、`browser-final.png`。
- 证据限制：CDP事件缓冲有截断，明确标为partial，不声称完整CDP抓包。完整本次传输依据是代理日志与六个真实正文。错误检查依据为累计window error/unhandledrejection计数0、console warnings/errors空、保留的Runtime exceptions空。触摸是CDP模拟，390×844是CSS视口，不是物理手机验收。
- 另有off/cold/warm各100次真实登录JWT HTTP测量，p95分别42.8235/45.7011/42.0804毫秒；现金夹具、可变墙钟，不代表D03混合容量或真实报价业务链。P01仍记录“Performance not requested”。

## 4. 命令及独立统计

1. 九类必测：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\mandatory-r3c-command.json`，退出0；103通过、0failure、0error、0skip。原始XML及独立审计副本保留在 `mandatory-r3c-surefire-reports`和 `mandatory-r3c-audit-xml`。
2. 完整verify：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\full-verify-command.json`，退出0；2459调用中2434通过、25skip，0failure/error。原始副本 `full-surefire-reports`和 `full-verify-audit-xml`。默认Surefire不会自动运行名称以IT结尾的集成类；九类显式必测结果另行保留。两个运行结果分别报告，不能相加为需求覆盖率。
3. 留存补测：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\retention-bounded-command.json`，退出0；`KlineRetentionRegressionTest.boundedSyntheticRetentionRun`实际1通过。两段各10秒合成before/after，墙钟42.6307023秒、观测机器CPU最高16%、每JVM heap768MiB、总墙钟上限120秒。真正Maven输出在 `retention-child.stdout.log`和 `.stderr.log`；外层空日志有真实空文件hash。不是D03的10/25/50/100四档各120秒全服务混合压测或长稳。
4. 共享Python防护：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\workspace-python-guard-command.json`，退出0，28项通过。冻结基线便携测试仍为26项；不能把28项套用为冻结Java源结果。
5. 完整逐case目录：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\checkpoint-r4-testcase-catalog.json`。命令、真实退出码与日志hash索引：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\checkpoint-r4-command-index.json`。

实际Redis安全/captcha/SMS测试没有关闭限流器。短信使用本轮受限本地sink；没有真实短信、邮件或付款，不代表真实供应商沙箱通过。

## 5. 本轮最小共享源码修改

只改两份源码，先备份原字节、校验并发变化，再原子替换：

- `C:\workspace\fx\705\scripts\multitenant\controlled_migration.py`：已知表的SHOW CREATE TABLE为空时立即拒绝，不再把缺失定义的 `[]` hash当作完整schema证据。这修复了完整性检查漏检，**没有证明原生Windows mysql客户端偶发空stdout的根因已解决**。
- `C:\workspace\fx\705\scripts\multitenant\test_controlled_migration.py`：两个最小回归检查，分别覆盖缺失定义拒绝和空表仍必须有schema定义。

最新hash、原字节及diff索引：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\metadata-guard-increment.json`、`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\edits.jsonl`、`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\backups`、两个 `metadata-guard-*.diff`。冻结candidate-r3c未修改；这个Python增量需要另次审查、冻结和迁移工具验收。没有Java、旧DDL、批准或release开关修改。

## 6. 所有失败保留，不能以重跑覆盖

- 首次九类：103调用，100通过、2failure、1error。C12只完成部分操作；ManualOrder大批量调用上限检查失败且根因未确认；deposit夹具名后缀导致历史FUND25种子未插入。后续只在新夹具写种子并做最新独立恢复；ManualOrder同源重跑通过不等于原不稳定根因已修复。
- 第二次九类：103调用，102通过、1failure（C12），0error/skip。点击、焦点/可见状态与截图协调未闭环；没有伪造PASS收据。
- 本次成功C12内的过期tab handle及“要求CDP缓冲未截断”的仪器失败也保留，最终说明真实证据范围，没有把旧失败文件改PASS。
- 初次原生迁移确实执行14份DDL，前后全状态独立恢复及已审查确定性转换保全成功；整体辅助脚本因目录冲突仍为FAIL。没有盲重放已执行DDL。
- 多轮原生夹具读取曾出现退出0但空stdout/缺结束标记，恢复校验如实失败。旧“lazy InnoDB”推测未证实；新诊断明确根因未知。超长 `--execute`还曾触发Windows命令行长度限制。结束标记与空定义防护只负责失败关闭，不降低完整性断言。
- candidate-r3/r3b的“缺9个中文文件”诊断已明确为Python默认cp936误读UTF-8清单的假缺失。实际原基线2402份hash未变；candidate-r3c使用显式UTF-8正确复制。错误诊断、失败构建日志保留；`manifest-decode-root-cause.json`给出更正。
- 最新备份第一次猜错数据库名失败；r2在10个新恢复已通过后遭遇原生读取缺结束标记，整体FAIL。随后采用已安装PyMySQL仅替代本轮夹具只读查询，消费全部协议结果，native dump/restore及所有字段/schema比较不变；多结果、40KB查询与既有完整指纹有实际self-check。r3在全新目录20库全部通过，未把r2改PASS。
- 收尾第一次错误地将空前置库按应用epoch2026100101检查，守卫正常拒绝，未停MySQL。r2仅对明确的无表 `mt705_empty_baseline`使用epoch0，其余应用库仍2026100101、activation=false；重新核验20库后才正常shutdown。原失败日志/收据保留。

## 7. 最新备份、不同物理实例恢复与资源收尾

`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\newest-primary-full-backups-r3\result.json`记录本轮primary上的全部20个 `mt705_*`库（含空前置、遗留迁移、失败尝试和四类测试运行后的新增资金/账户/审计/归档数据）。每库新全量mysqldump，全部列及数据、表定义、trigger SQL_MODE/ACTION_ORDER、routine/parameter/event均比较；在不同UUID和datadir的restore实例中新建库，完整状态与源一致。源数据库没有被旧dump覆盖，没有删除。临停前再次逐库核对最新源与备份/恢复证据相等。

- 源UUID `856a87c4-bd38-11f1-a170-e89c257ef640`，restore UUID `877478cb-bd38-11f1-aecf-e89c257ef640`。datadir各自位于本轮 `mysql57-isolated\primary\data`与 `restore\data`。
- 最后全状态证明：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\latest-source-before-shutdown-proof-r2.json`。MySQL正常shutdown：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\owned-mysql-cleanup-r2.json`。
- Redis最新1,142,011字节完整RDB、格式检查及不同volume/network-none容器实际装载成功，SHA-256 `46306a03bc93c5d919406f2360adc89659b72dadd1cb8f76181d63496775fb60`；见 `C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\newest-redis-rdb\result.json`。这是RDB二进制与隔离装载证明，TTL正常过期，不声称全部在线key/value/TTL一致。
- 三个实际Vite Node按精确PID、创建时间、executable和command line停止。原wrapper终止码4294967295原样保留，不伪造退出0。
- MySQL33418/33419、Redis33417、自建Vite及动态API均不再监听。两份MySQL datadir、全部恢复副本、dump、RDB、jar、容器/volume和失败证据保留；不递归重备份验证克隆，更不删除它们。
- 最终观察UTC03:16:51：原3306 PID7284仍在；33315现在由其他写者PID51152监听，本轮没有连接或停止它。此前“33315未监听”不能沿用到收尾。没有停止Docker Desktop/daemon或用户标签页。
- 浏览器临时touch、metrics、focus覆盖已撤销，自建tab关闭；见 `browser-cleanup.json`。资源总观察见 `C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\final-resource-observation.json`。

## 8. 未闭环工作与下一步

逐case处置在 `C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\checkpoint-r4-full-skip-dispositions.json`。余24项：BalancedControlPlanMySQL1、CatalogLive1、ContractCloseConcurrency9、ExchangeLive1、MarketIsolation2、ReceivedIndex1、SourceCandles MySQL严格TEXT1、AssetEquityMySQL8。旧测试的硬编码root口令、默认库/create-drop和缺tenant context需要专用当前schema夹具改造，不能直接打开开关指向3306或现有owned primary。真实外部行情条件未建立。

仍缺本版本充值/转账/开平仓/到期/结算/权益/提现/理财收益/借贷整链的真实报价HTTP、异步任务scope、幂等/并发/回滚/真实模拟隔离组合；新租户开通运营、域名DNS/HTTPS挑战及热切换、活动派发/领取、客服监管附件、101个超大队头归档与留存故障恢复、四端真实角色及D03混合容量/长稳没有完整验收。留存删除继续默认关闭。

正式孤儿/私有文件批准apply、旧凭据撤销轮换/旧包直接启动隔离、不确定DDL人工核对及新审查前向恢复仍是code_gap；现有schema工具不授权这些范围。共享并发代码必须逐份审查后重新冻结，不能只改registry hash。所有正式批准、发布、生产迁移部署仍禁止。

继续入口：`C:\workspace\fx\705\docs\multitenant-next-commands-20261001-r4.md`。机器checkpoint：`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\checkpoint-r4.json`。所有PASS均以各自限定scope解释，不是项目完成。

当前:多租户平台 / C12十项及103必测通过，完整verify仍25skip，未上线 / 继续改造余24项夹具及资金、迁移、运营和共享合并闭环
