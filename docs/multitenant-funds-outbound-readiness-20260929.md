# 资金事务、出站边界与配置就绪补充（2026-09-29）

## 资金变更

- 贷款批准/拒绝锁定当前租户贷款记录；批准还锁用户和 FUND，同一笔已批准重放不再次入账。还款仅接受已实际批准、未还款的贷款，拒绝原来允许的未放款 SIGNED 状态。实际还款重放不再次扣款。
- 理财赎回锁订单、用户、FUND，校验冻结金额及 0–100% 费率；重放返回既有结果。到期结算和收益发放使用同一锁定顺序；初始任务只取 ID，再逐条锁定读取，避免扫描缓存的旧实体覆盖并发赎回。代理不能执行全租户收益任务。
- 上述变更使用真实 `ControlIdentity.actorId/tenantId/accessSessionId`，资金、状态与 `control_audit_log` 写入同一事务。审计数据库拒绝写入时，业务一起回滚。关闭功能不阻止已有贷款还款、理财赎回、到期结算。
- 手工订单原生 JDBC 使用原物理连接提交资金、订单、历史修正、幂等证据和新增 CONTROL 审计。没有在 native 事务中调用另一个 JPA 事务。CONTROL 正 actor ID 写独立审计；旧 manual_record.operator_id 保留负数命名空间以兼容幂等，不创建隐藏管理员。
- 手工订单预览绑定 tenantId；请求哈希含租户；命名锁长度符合 MySQL 64 字符上限；资金不允许修正为负数；MANUAL_TEST 标记保留。
- 新提现申请现受 feature.withdraw 且锁 FUND；已有审核/退款未加新增业务开关。首次代理开通/重新启用受 feature.agent，角色转换及全局登录登记同事务并清旧登录状态；代理不能修改后台代理身份，员工须具有相应 set_agent/unset_agent 权限。既有代理修改登录名不会错误地当作新开通阻断。

## 出站配置边界

只允许平台操作员通过进程配置维护下列白名单；不是租户 system_config 键：

```yaml
platform:
  outbound:
    smtp-endpoints: "smtp.example.com:465,smtp.example.com:587"
    support-origins: "https://support.example.com"
    callback-origins: "https://callback.example.com:8443"
```

这些只是格式示例，不是已验证服务。三个默认值均为空；未显式授权时拒绝相应用途。

- SMTP 主机/端口精确匹配，拒绝 IP、localhost、域名后缀冒充、非法端口、私有/回环/链路本地/CGNAT/保留/映射/混合 DNS 结果。DNS 最多 16 地址、2 秒、2 工作线程+4 排队上限。
- 465 为隐式 TLS，587 强制 STARTTLS，必须验证服务器证书主机名；不允许明文降级。JavaMail 的 socket factory 固定连接本次已经检查的 IP，原始域名仍用于 TLS/SNI；DNS 后续变化不能改变 TCP 目标。
- 连接 3 秒、读/写 5 秒、socket 20 秒硬截止；每实例最多 4 个同步邮件发送。Redis 原子限流按租户 60 封/分钟、规范化收件人 SHA-256 1 封/分钟，故障拒绝。邮件只允许单一有效地址与固定短文本，验证码严格 6 位，无租户任意附件/正文入口。发送失败不打印凭据/服务商原始异常，API 返回安全可解释配置错误。
- 新验证码只在邮件成功后持久化；没有新增可靠邮件 outbox。外部投递与数据库仍不是一个分布式事务，不能保证邮件已发而验证码持久化失败时的自动补偿。
- 外部客服及命名为 callback/webhook/notify URL 的配置仅允许 HTTPS、无 userinfo/片段、精确用途 origin、全部公开 DNS；保存及公开客服读取均检查，旧不合规客服链接返回空，不输出可点击内网 URL。未授权外部客服可以先保存合规地址以准备依赖，但公开链接和切换外部渠道仍需授权；强制 internal 或显式锁定保持拒绝。当前工程没有实际租户 webhook/SMS 发送器；本次没有声称新增此能力。未来发送器必须复用固定 IP/证书校验及不跟随重定向的边界，不能仅依赖保存时 DNS 检查。
- 域名路由验证同步改为已验公开 IP 的固定 TLS 连接；原始域名做 Host/SNI/证书校验。固定 HTTP/1.0 GET，不跟随重定向，拒绝传输编码，头 8 KB、正文 256 B、连接/读取/硬截止 3/5/10 秒、并发最多 4。

## safe-v1 最小版本模板

`TenantManagementService.create` 原事务内调用 `TenantSafeTemplate.initialize`，锁租户行后幂等创建缺失项，不覆盖已有配置、角色或产品：

- 原两项无业务权限角色、站名占位/UTC/关闭客服配置保留。
- 新增 BTCUSD 禁用币种，USD 计价、零现价、控盘关闭、1 倍杠杆、零默认手续费；行情来源仅元数据，不导入密钥或请求行情。
- 新增 SAFE-V1-DISABLED 禁用产品，零收益/租金/赎回费率、7 天、100–1000 的待审金额示例；必须明确审核后才可启用。
- DRAFT、configReady=false、全 feature=false 保持。没有复制用户、账户余额、支付信息、SMTP 或第三方秘密。模板仍禁用时不能满足 financial/contract 的启用依赖。
- 同一 tenant 行锁串行化重试；所有 saveAndFlush 加入创建事务，异常一起回滚。所需列均来自既有实体；运行库若缺列将失败并回滚，不做自动 DDL 或字段忽略。

## 实际配置就绪

`GET /api/control/tenants/{id}/readiness` 返回：

```json
{"success":true,"data":{"tenantId":1,"ready":false,"missing":[{"key":"mail.endpoint","message":"SMTP 目标未获平台出站授权"}]}}
```

不返回密钥或配置值。`configReady=true`、切 ACTIVE、启用功能策略均做服务端实际依赖检查，不信任勾选值；每次真实新增业务还重新检查其依赖，防止开放后删除配置仍继续接受新增业务。域名/配置检查沿用当前事务，临时租户上下文不会跨租户或开启看不到未提交配置的独立事务。

- 基础：已验证域名、非安全模板占位的站名、合法时区、有效租户后台管理员和对应全局后台登录记录。
- 注册：邮件 endpoint、用户名、可解密密码和发件人，以支持账户密码恢复；不在检查过程中发送任何邮件。
- 合约/期权：启用且来源/分类/结算币种完整的品种；启用 Alltick 品种还须行情密钥。期权另外检查期限、收益/亏损比例和金额范围。
- 理财：完整启用产品、期限、收益、金额和赎回费率；贷款：启用期限、利率、免息天数、逾期费率和金额范围。
- 充值：至少一个完整启用的数字收款地址或银行收款账户。
- 外部客服：获平台授权的 HTTPS 公共外链。站内客服/站内信：合法客服配置文档。
- 提现、代理、活动、模拟没有额外租户供应商凭据；实际用户 KYC/收款地址、活动预算/时段、模拟服务独立数据库和实时身份检查仍由现有业务执行。就绪检查不是供应商可用性、余额充足或实际订单一定成交的保证。

## 验证记录

首轮资金窄测 45 项通过（0 failures/errors/skips），真实 MySQL native 4 项均实际运行，不是条件跳过。日志：`reports/identity-funds-tests.log`。隔离库由现有迁移脚本 clone，URL 严格限制 localhost:64029 的 mt705_probe_*；没有触及真实业务库。H2 测试覆盖审计失败回滚、重复/并发放款还款、到期与赎回竞争、用户/租户范围。MySQL 测试覆盖同连接审计拒绝后资金/订单/历史/幂等证据回滚、重试与并发重放。

本次串行 12 类 70 项全部通过（0 failures/errors/skips），包括 46 项资金/底座/控制安全、7 项客服、12 项出站/邮件/就绪、2 项代理、3 项提现；日志 `reports/identity-funds-config-tests.log`，机器摘要 `reports/identity-funds-config-summary.json`。随后新增两项 Redis 邮件限流单测、一项外部客服准备配置测试与两项 safe-v1 幂等/事务回滚测试，主任务 16:00 clean 窄跑报告已确认 TenantReadinessTest 7 项及 EmailRateLimitTest 2 项全部通过。所有邮件测试使用 mock sender 或 localhost socket，不给真实地址发邮件。域名路由原证书检查尚未进行真实公网部署验收。

当前全套测试曾受陈旧 target/test-classes 影响，主任务安排 clean 重跑；本报告窄测不等于全量通过。

首次备份目录：`rollback/multitenant-20260929/identity/`。新增文件无旧版备份，回滚应按文件清单处理，不能整目录覆盖其他代理工作。

## 独立 MySQL 5.7 资金验收（2026-09-29 补充）

新增 `scripts/multitenant/FundsMySqlProbe.java`，使用原服务、真实全部 Repository、JpaTransactionManager、租户底座和物理 SQL tenant 谓词；只 mock 配置/行情/已批准身份资料等非资金依赖，不启动 Spring Boot、定时器、行情或邮件。`hibernate.hbm2ddl.auto=validate`，不对数据库执行建表/自动升级。入口仅接受 localhost:64029 的 mt705_probe_* 隔离克隆。

在由已通过迁移备份新建的 `mt705_probe_075552_f3a6` 上连续两次 63 项检查通过，均使用不同新建租户、用户和 CONTROL actor，无真实业务库改动：

- 余额调整并发同键一次台账、一次 CONTROL 审计；相同幂等键不同参数拒绝。
- 充值审核并发只入账一次，一笔 credit ledger，真实 reviewed_by_type=CONTROL。
- 未放款贷款拒绝还款；并发放款/重复偿还分别只移动一次本金。
- 真实理财申购冻结、并发赎回；到期结算和提前退出竞态最多归还一次本金，冻结余额归零。
- 提现拒绝退款、提现完成分别并发只迁移一次，重复请求拒绝；不实际发送转账。
- 每一路均用真实 MySQL BEFORE INSERT trigger 拒绝对应 control_audit_log，核对钱包、冻结、业务状态以及适用的幂等台账全部回滚，去掉触发器后可重试。触发器在 finally 删除。
- 其他用户/其他租户不能操作原记录，原钱包保持不变。

证据：`reports/multitenant/funds-mysql-probe.log`、`funds-mysql-probe-repeat.log`、`funds-mysql-probe-summary.json`；两个结尾均为 `FUNDS_MYSQL_57_PASS checks=63`。使用独立 class-only 快照 `reports/multitenant/funds-app-classes-1601`，包含主任务新 TenantEntityPersister；没有将 application.yml 或数据库凭据写入结果摘要。预期审计拒绝产生 SQL 异常日志，不代表检查失败。

复跑（先确认主任务编译结束；依赖 classpath 为现有不含凭据的文件）：

```powershell
python scripts/multitenant/mysql_migration.py fixture-connection --output rollback/multitenant-20260929/schema/identity-funds-jpa/connection.json
$cp=(Get-Content reports/multitenant/identity-test-classpath.txt -Raw).Trim()
javac -proc:none -encoding UTF-8 -cp "exchange-backend/target/classes;$cp" -d reports/multitenant/funds-probe-classes scripts/multitenant/FundsMySqlProbe.java
java -cp "reports/multitenant/funds-probe-classes;exchange-backend/target/classes;$cp" com.gtcfesk.exchange.control.FundsMySqlProbe rollback/multitenant-20260929/schema/identity-funds-jpa/connection.json
```

这是服务级事务/并发验收，不代替浏览器、JWT 请求过滤器及真实第三方配置联调；读取接口权限已有独立测试，不能把本探针解释成所有金融流程和所有并发排列均已穷尽。