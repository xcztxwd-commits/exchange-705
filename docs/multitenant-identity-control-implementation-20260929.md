# 身份入口、总控访问与客服边界实施记录（2026-09-29）

本记录只说明本次已修改的身份/控制面路径及其验证范围，不等同于整个平台上线验收。

## 已实现

- 前台域名由 `TenantHostService` 精确查表；未知域名、不可信转发来源、跨 origin、停用/未验证租户拒绝。`TenantRequestFilter` 在安全过滤器前建立上下文，异常后清理。没有默认租户或任意租户请求头回退。
- 普通 JWT 携带 `tenantId` / `tenantVersion`；旧无租户 JWT 拒绝。后台管理员/代理通过全局规范化 `backend_login` 唯一名称定位租户，随后读取租户内实体；每次请求重新校验当前账号、会话和凭据。停用租户增加会话版本。
- 总控账号独立表、密码哈希与加密 TOTP，不新增隐藏租户管理员。普通后台默认弱密码初始化已停用。新后台管理员必须首次改密。
- 总控票据仅保存哈希，绑定操作者、目标租户、浏览器校验值、60 秒有效期，锁行原子消费一次。访问会话 60 分钟绝对上限、15 分钟闲置；轮询不更新闲置时间，交互通过专用活动接口更新。
- 总控业务身份为 `ControlIdentity`，真实 actor 保留在详情与独立审计，业务访问 principal 负数仅用于避免租户管理员 ID 碰撞，不是一个隐藏管理员。
- 总控账号开通/停用/密码重置需当前操作者密码与 MFA 再验证；禁止自停用。修改增加账号版本，立即使其所有总控和访问会话失效。
- 租户创建为 DRAFT；基础角色无业务授权，客服关闭、功能默认关闭。域名验证检查 HTTPS 证书与服务端返回的目标租户 ID，旧域名切换后退役。
- 强制站内客服同时锁定站内授权/禁止外链。`SupportSettings`、公开客服链接、保存路径均读取有效策略，不能仅改前端显示绕过。
- 客服监督不创建客服在线状态、不改变已读标记；真实接待与站内信使用独立 `controlActorId`，消息类型 `CONTROL`。读附件、导出、接待、发信记录独立审计。
- 提现审核、KYC/贷款资料审核、角色授权、后台管理员修改、系统配置及代理授权已接事务内真实 CONTROL 审计。提现状态变更锁记录，退款锁 FUND 并检查冻结余额。请求级审计只是补充，不当作资金事务审计的替代。
- 真实到模拟环境的身份与目录校验绑定租户；原生同步、模拟开户、种子资金明确使用租户条件。模拟新增业务实时查询真实租户的功能授权和开放状态，不以模拟库的陈旧策略放行。退出和历史查询不经过新增业务开关。
- 公共图片只允许当前租户启用产品/币种的 staff 图片显式引用；支付二维码要求登录。用户材料不因被填入产品配置而公开。其他私有文件要求本人，或真实审核模块、当前租户用户归属与业务字段引用；`users` 菜单不能替代材料审核授权。代理上传使用 `staff/agent-{id}`，不会与管理员 `staff/{id}` 碰撞。客服附件仍使用独立已鉴权接口。CONTROL 文件交付前必须成功持久化访问授权审计。
- 音频上传限 MP3/WAV/OGG 后缀与文件头，响应媒体类型白名单；拒绝 HTML/SVG。符号链接解析后的文件必须仍在对应租户和所有者目录中。
- 旧用户邮箱查询与唯一约束均使用 `LOWER(TRIM(email))` 语义，保持租户内登录一致；全局后台账号名仍由独立注册表解析。
- 市场 WebSocket 握手记录服务端验证的 `tenantId` / `frontendHost`；处理器隔离由行情改造部分负责。

## 主要接口

所有路径均以 `/api` 开头。总控接口只允许配置的总控 origin，后台交换只允许配置的后台 origin。

| 接口 | 用途 |
|---|---|
| POST `/control/auth/login` | `{account,password,totp}` |
| GET `/control/auth/me`；POST `/control/auth/logout` | 总控当前身份/注销 |
| GET/POST `/control/accounts`；PUT `/control/accounts/{id}` | 总控账号管理；敏感写要求当前密码、TOTP、原因 |
| POST `/control/security/mfa` | `{password,totp,newSecret,newTotp}`，成功后所有会话失效 |
| GET/POST `/control/tenants`；PUT `/control/tenants/{id}` | 租户建档/修改，原因 3 至 512 字符 |
| POST `/control/tenants/{id}/verify-domain` | 实际 HTTPS 与租户路由检查 |
| GET/PUT `/control/tenants/{id}/policies` | PUT 单条 `{key,value,locked,reason}` |
| GET/POST `/control/tenants/{id}/backend-accounts` | 开通/列出租户后台登录名 |
| POST `/control/tenants/{id}/access-ticket` | `{browserBinding}`，返回票据、目标、截止时间与固定后台 origin |
| POST `/admin/auth/control-exchange` | `{ticket,browserBinding,expectedTenantId}` |
| POST `/admin/auth/control-activity`；POST `/admin/auth/control-exit` | 真实交互活动/退出 |
| GET `/control/access-sessions`；POST `/control/access-sessions/{id}/revoke` | 当前操作者访问会话与撤销 |
| GET `/control/audit` | 分页总控审计，可按 tenantId 筛选 |
| GET `/tenant/features` | 域名绑定的公开能力白名单，不返回私密配置；用户端从真实环境读取 |

控制台账号创建：`account,newPassword,newSecret,newTotp,password,totp,reason`。更新：`enabled` 或 `newPassword`，再加 `password,totp,reason`。总控密码长度 16 至 128 位。

## 验证

可重复命令：

```powershell
mvn -q -f exchange-backend/pom.xml '-Dtest=ControlSecurityTest,TenantJwtBoundaryTest,ControlAccountSecurityTest,SimulationTenantIsolationTest,ControlSupportIsolationTest,AudioValidationTest,ImageAuthorizationTest' test
```

包含 27 项测试：主机/转发来源、TOTP/加密、票据误绑定及重放、撤销/闲置/版本、登录名称、审计摘要与凭据脱敏、无租户/跨租户 JWT、异常上下文回收、失败请求在线计数、总控账号再认证、模拟原生 SQL 及种子资金隔离、模拟实时授权、客服监督/接待/站内信、跨租户对象拒绝和审计失败业务回滚，以及公开/审核文件引用、后台身份文件命名空间、未引用文件拒绝、文件授权审计失败拒绝交付、音频类型检查。

- `ControlSupportIsolationTest` 使用独立 H2 JPA 与真实事务；审计 repository 的测试适配器将日志写入同一 EntityManager，用异常验证回滚。
- `SimulationTenantIsolationTest` 使用独立 H2 原生 SQL；远端 HTTP 协议由 mock server 校验，未冒充真实双服务部署验收。
- 最后再次使用独立 localhost MySQL 测试库运行现有 `JpaTenantProbe`：54 实体严格 schema validate、全部 Repository 查询初始化与 19 项隔离/约束检查通过，包含邮箱规范化查询定义；日志 `reports/identity-control-jpa-probe.log`。未启动业务计划任务，探针写事务最终回滚。
- 最新运行日志：`reports/identity-control-full-tests.log`；机器报告：`exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.control.*.xml`。
- 所有本域变更前备份位于 `rollback/multitenant-20260929/identity/`，已有备份不覆盖。新文件需依变更清单删除，不应盲目覆盖整个工作目录。

## 尚未完成的验收/配置

1. 未修改实际业务数据库、未部署、未完成真实证书/反向代理/浏览器双标签票据交换的端到端验收。MySQL 迁移验证属于独立数据库报告。
2. safe-v1 已包含禁用 BTCUSD 币种与禁用零收益产品的最小默认，仍没有经运营确认的生产币种/产品清单；不会复制已有租户产品。`configReady` 现已由服务端实际依赖检查保护，详细配置契约见补充报告；仍不提供未经确认的业务模板。
3. `LoanReviewService`、还款、理财赎回/到期与收益发放、手工订单已补同事务 CONTROL 审计与锁定，见补充报告。不能据此宣称所有历史资金命令和全套业务回归已完成。
4. 生产必须明确配置 `platform.base-domain`、`platform.admin-origin`、`platform.control-origin`、可信代理网段、JWT 密钥、MFA 加密密钥及受控初始账号。不得使用测试示例作为生产凭据。
5. 独立模拟库需要经过迁移并配置对应租户域名；真实后端仅信任明确指定的模拟服务代理来源。未提供可自动猜测租户的回退。
6. 迁移前的平面上传路径及旧代理与管理员共用数字 staff 目录不能猜测所有者；必须通过迁移清单明确归属后重建引用。当前新上传身份隔离已修复，不会回退放开旧私有文件。
7. 旧测试上下文的运行时迁移和全套业务回归仍需单独执行；上述 27 项不等同于完整回归套件。

## 后续补充

资金与出站配置补充见 `docs/multitenant-funds-outbound-readiness-20260929.md`。原上文 27 项为第一阶段历史执行结果，不作为当前全套回归结论。
