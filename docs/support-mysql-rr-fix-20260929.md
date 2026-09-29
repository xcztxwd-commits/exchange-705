# 客服 MySQL RR 并发缺陷修复与定向验收

日期：2026-09-29，04:28–04:40，Asia/Singapore。

## 结论与范围

已修复第二轮验收的 R2-D01、R2-D02：同客户并发发起返回相同已提交会话；容量 1 时并发接待严格只有一成功、一 409。并检查同源路径，修复转接竞争、后台图片重试、站内信批次重试及客服在线状态首次创建的过早 RR 快照问题。

真实 MySQL 5.7.44 / REPEATABLE-READ，三轮独立新库各 **47/47** 通过；第二轮原始额外夹具 **3/3** 通过；H2 对照 **50/50** 通过。执行次数包含重复，不代表同等数量的独立业务场景。没有部署、业务库操作、提交或推送；没有修改公共 SystemConfigService、隔离级别、数据库约束、容量阈值或权限规则。

这里只给上述缺陷的修复证据，不将第二轮 S01/S03 整体改判为通过；迁移、重启持久化、完整生产联调、真机通知等未验证项仍保持原状态。

## 1. 根因与修复

旧代码在 UserAccount/AdminUser 序列化行锁前调用 enabled()/permission()。这些普通 SELECT 在 InnoDB RR 事务内提前建立一致性读视图。随后 FOR UPDATE 即使等到了前事务提交，后面的普通会话/容量查询仍读取旧视图。

修复采用当前调用结构所需的最小变化：

1. start：仅从 SecurityContext 取身份，先 FOR UPDATE 锁用户，再读配置、活动会话与限速计数。
2. claim：先锁目标客服 AdminUser，再读配置/权限/在线状态及容量，最后锁并修改待接会话。
3. transfer：先锁接收客服 AdminUser，再执行相同容量检查，最后锁被转接会话。两次转接以及接待/转接竞争共享同一个接收客服互斥点。
4. presence、后台发图、sendLetters：同样将数据库权限/配置读取放在各自序列化锁之后，避免重复重试仍看不到已提交记录。
5. export：保持只允许超管，并统一先锁会话再读取导出相关数据。不是声称旧超管导出已经单独复现了新缺陷。

关键点不是“行锁刷新了旧快照”，而是 **首次普通读取发生在拿到序列化锁之后**。因此已经完成的同用户/同目标客服事务可见；接待和转入都不能越过容量检查。转出/关闭只能减少占用，可能使当次检查保守拒绝，不会造成超额接待。

没有把 count 查询简单换成对所有已分配会话 FOR UPDATE：那会扩大锁集合，可能使反向转接互相等待。本次保留“目标客服行，再单个会话行”的锁顺序；反向转接也有并发回归。

### 事务边界

已核对 UserSupportController、AdminSupportController、BackendAccess 没有包裹服务调用的外层 @Transactional。现有 REST 调用进入 SupportService 的新 REQUIRED 事务，拦截器/控制器先前的仓库读取不共享该事务的 RR 读视图。代码注释和锁屏障测试固定这一顺序。

本方案不能被解释为“允许未来调用方先在同一个外层 RR 事务内任意查询，再加入本服务仍一定安全”。若以后增加这种编排，必须重新设计事务边界或当前读方案；本次没有用 REQUIRES_NEW 偷改外层回滚语义，也没有降低到 READ COMMITTED。

## 2. 原缺陷和同源问题的负向对照

基线生产代码与第二轮报告一致，SHA-256：
`1a79be18f0eeeaa2b29702a1b902b2e926f2079e54fb13f81a8b0e0491cf28bd`

测试不是靠 sleep 碰概率：第三个真实数据库事务先持有相同用户/客服/会话行锁；两个服务事务都准备发出锁 SQL 后才释放。Hibernate StatementInspector 只观察 SQL 准备，不替换查询、事务或数据库。旧代码此时已做普通读，修复代码尚未做。

- baseline：四场景各重复三次，XML 共 12 次，9 个断言失败、3 个异常。并发开户三次均唯一键异常；接待容量、双转接容量、接待与转接竞争三类各三次均错误放行两次。
- baseline-extra：四场景各重复三次，共 12 个异常。原代码出现发图重试唯一键冲突、站内信重试唯一键冲突、在线状态初始化主键冲突；等待行锁时角色已撤销的严格权限预期也失败。
- 以上基线失败全部留档，没有放宽预期、捕获错误伪报成功、自动重试覆盖失败，或删除数据库约束。

原 SupportServiceTest 的字段/初始化和 12 个原测试主体逐字保留；仅扩展可显式启用的一次性 MySQL 数据源及 SQL 观察器，并追加 10 个并发方法。每个新方法 @RepeatedTest(3)。

## 3. 实测结果

| 轮次 | 环境 | 实际执行与结果 |
|---|---|---|
| fixed-1 | MySQL 5.7.44，RR，127.0.0.1:58221，独立新库 | 服务 42 + HTTP 4 + 日志 1 = 47，全通过，exit 0 |
| fixed-2 | MySQL 5.7.44，RR，127.0.0.1:56830，独立新库 | 同样 47，全通过，exit 0 |
| fixed-3 | MySQL 5.7.44，RR，127.0.0.1:51549，独立新库 | 同样 47，全通过，exit 0 |
| round2-original-fixture | 另一个 MySQL 5.7.44/RR 新容器、新库 | 原 concurrentMessagesRetainHashChain、concurrentClaimExistingSession、concurrentCapacityCannotOverbook，3/3，exit 0 |
| h2 | 随机名 H2 内存库 | 上述 47 + 原额外夹具 3 = 50/50，exit 0 |

服务 42 项的构成：原 12 项；新增 10 个并发场景各三次。新增场景包括同客户幂等开户、两个会话争抢容量 1、双转接争抢目标容量、接待与转接争抢目标容量、反向转接、并发图片重试、并发站内信重试、在线状态首次创建、不同用户同时开户、等待行锁期间角色撤销。

保留并重跑的权限回归覆盖跨用户/客服访问、转接后的原客服拒绝访问、监督不消费客服未读、普通客服不能监督/导出、图片隔离、真实 JWT/过滤链/拦截器、开关、配置写旁路及操作日志隐私。哈希链在竞争后的会话上重新校验。

测试 fixture 对 MySQL create-drop 设置三层护栏：仅 127.0.0.1、库名必须 support_qa_*、显式 SUPPORT_TEST_MYSQL_DISPOSABLE=true。密码随机且只用于一次性容器。没有使用业务 .env、客户数据或生产数据库连接。

## 4. 文件与复现

本轮生产变更仅：
- `C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/support/SupportService.java`

测试变更仅：
- `C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/support/SupportServiceTest.java`

另更新客服交付文档及本报告。第二轮独立验收报告未改动；其原始 Round2SupportExtraTest 只复制到独立测试快照，没有写回共享测试树。

修复后生产文件 SHA-256：
`0b997dc692caa921caacc023667bba8d38d92fd5781a8d6cd458432ca7842ba2`

修复后测试文件 SHA-256：
`fc39a9aaa06fb94deca8ca4a9a1a424b0aea5836b6e13de136378d4ae6bf447c`

独立目录：`C:/workspace/fx/new/support-rr-fix-20260929/`

- `before/`、`after/`：原文件与修复文件副本。
- `SupportService.java.diff`、`SupportServiceTest.java.diff`：最小差异。
- `source/exchange-backend/`：冻结测试源码、独立 target，不使用共享 target。
- `changed-source-hashes.json`：修改前后两文件 SHA-256。
- `tested-snapshot-sha256.json`：最终测试快照逐文件哈希；捕获于全部测试结束后，不冒充开始前的全树取证。
- `results-summary.json`：逐轮退出码、XML 统计、测试数据库及端口。
- 各轮 `maven.log`、`mysql-environment.txt`、`reports/`、`command.txt`、`result.json`。
- `h2.log`、`h2-reports/`：H2 对照。
- `evidence-sha256.json`：日志、XML、命令、运行器和快照清单的证据哈希。

在本机重跑一轮（轮次名称必须未使用）：

```powershell
& 'C:/workspace/fx/new/support-rr-fix-20260929/run-mysql.ps1' -Stage recheck-1
```

运行器使用已有 mysql:5.7 镜像（--pull=never），CPU/内存限额，tmpfs 数据目录，随机 localhost 端口。每轮新建独占容器，最终只清理 qa705-support-rr=true 且名称为本次生成的容器。Maven -B -o，未下载依赖，重型步骤串行；不测试 Redis，未声称已完成真实 Redis 集成。

baseline-extra 的目录里曾复制到前一轮的无关 HTTP/日志 XML；已移入 `unselected-stale-reports/` 并明确不计入该轮。后续运行器只复制所选测试套件，避免把陈旧报告算作本轮执行。

## 5. 保留的未验证项与回滚

- 本轮没有改 UI，也没有重跑浏览器、真机提示音或完整用户密码登录流程。
- 没有迁移/重启持久化恢复、生产压力测试或实际客服大规模容量验收。
- 三轮定向回归覆盖明确的交易序列，不是对所有事务交错的形式化证明。
- 未部署。应用回滚可逐文件使用 before 副本，仅限没有后续编辑的文件；不要覆盖共享工作树其他任务，不删除已有客服数据。
- 不建议回滚本修复后继续开启多客服并发服务，因为原容量缺陷已有可重复证据。
