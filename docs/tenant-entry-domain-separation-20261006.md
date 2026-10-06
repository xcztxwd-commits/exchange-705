# 租户入口域名与前台域名分离交付报告

## 结论与范围

**本地代码、迁移文件、测试和网关配置已完成；专项校验通过；没有上线。** 发布门禁仍阻断，公开数据库结构快照仍旧，不得将本报告当作生产发布批准。

本次只在 `C:\Users\徐乾妖\.codex\worktrees\906d\705` 工作。未修改线上 DNS、CDN/代理、数据库或容器，未创建发布批准、未将业务激活回执改为已就绪，也未输出任何真实凭据或 MFA 密钥。线上观察仅是公开 DNS 查询和无凭据 HTTPS HEAD。

- 实际后端专项：18 个测试类，**114 项，0 失败、0 错误、0 跳过**。
- MySQL **5.7.44、8.4.12**：两次独立、任务自有本地临时容器执行实际增量 SQL；只读预检通过，各拒绝 7 类唯一性冲突；旧前台及状态、readiness 标记、会话版本保持不变。
- 总控/后台/PC/移动端：既有 `vue-tsc -b` 检查和 Vite 构建均退出 0；5 个前端行为脚本通过。
- 实际本地 Nginx：`nginx -t` 通过，**25 个 HTTP 检查**通过。上游是确定性测试夹具，不是完整应用或生产 TLS 验收。
- 受控迁移协议：31 项测试通过；旧 0404、0403 合同分别 9、8 项通过。源隔离门禁通过（462 个 Java 文件、2227 处敏感点），门禁变异检查 12 项通过。
- **未通过**：发布门禁退出 1，保留原有 4 个阻断；公开结构快照校验退出 1，原因与处置边界见下文。

## 基线与其他任务保护

阅读了实际 `C:/Users/徐乾妖/.codex/AGENTS.md`、ponytail 和 caveman 的 `SKILL.md`，均按 full 执行。请求目录与会话最初的 a099 工作树不同，本次命令显式切到 906d。

- Git HEAD：`bcb382e68b75f940a18af24731a682623c5207d1`。
- 分支：`codex/four-chat-control-20261006`。
- 开工时存在三项未提交改动：`ControlService.java`、`ControlSecurityTest.java`、`source_isolation_registry.json`。
- 前两项没有编辑，交付时逐字节 SHA-256 与开工基线一致；注册表保留前一任务的 ControlService 记录及全部发布批准/阻断字段，仅更新经本任务逐文件审查的 10 个 Java 文件指纹与敏感点。
- 未执行 reset、clean、提交、推送或线上发布。真实 Git index 未暂存任务改动。
- 每次源文件写入前保存原文件，并校验基线/上次写入哈希，采用同目录临时文件替换；保留原有混合换行的未改行。

开工基线树为 `bb16f3b9b01265d3639a62e2bda31824ba8fb539`，包含开工时其他任务的工作内容。交付补丁比较此树与仅加入本任务路径的临时 index 树，而不是直接导出 HEAD 的全部差异。因此不包含前两项其他任务改动，也不重复携带注册表的前一任务改动。

证据：[基线与哈希](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/baseline.json)、[开工时其他任务差异](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/pre-existing.patch)、[逐文件隔离审查](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/source-review.json)。补丁和备份按本地访问权限保管，不上传公共站点。

## 根因及已追踪链路

旧实现只有 `Tenant.frontendHost/domainVerified` 和单候选生命周期。准备新域名时按租户退役所有候选，无法让入口和前台独立存在；核验仅识别租户/nonce，缺少角色；Caddy 的固定 `.net` 跳转和别名前台跳转不读取租户映射；PC Nginx 的静态 `try_files` 可在入口/未知 Host 上展示 SPA。可信代理的转发 Host 以前可替换真实 Host。

已经从模型、绑定/历史仓库、总控创建与更新、prepare/verify/activate/release、出站固定核验、Host 与请求过滤、Spring Security/MFA/审计、readiness/JWT/CORS/WebSocket，追到总控 Vue 页面和 Caddy/Nginx、既有隔离测试。代码定位依据源代码与测试，不依据截图猜测。Chrome 预览只用于完成后的界面复核。

## 数据与生命周期实施

复用 `tenant_domain_binding` 的全局 hostname 主键及 PENDING/VERIFIED/ACTIVE/RETIRED/RELEASED 状态，新加 `domain_role`，旧记录默认 FRONTEND。每租户每角色最多一个现用和一个候选，用 MySQL 5.7 可用的生成列及唯一索引约束；Java 保留绑定乐观版本和租户悲观锁。

租户新增 `entryHost`、独立 `entryEnabled/entryVerified`、单调 `domainVersion`。旧 `.cc` 配置、原 `domainVerified`、状态及会话版本不动；入口默认 NULL、关闭、未核验；没有按前缀推测或批量启用 `.net`。

- ENTRY 只允许平台 `.net` 单层非保留子域；FRONTEND 只允许平台 `.cc` 单层非保留子域。根域是运维配置，普通租户不能修改。拒绝任意外部根域、端口、保留标签和跨角色名称。
- 准备/退役按角色处理，两个候选互不覆盖。全局占用和退役归属不得跨租户抢占；只有原归属总控显式审查、携带退役绑定版本释放后才可复用，历史归属不删除。
- 每角色独立 HTTPS/nonce 核验，挑战响应绑定 hostname、tenantId、nonce、role。复用原出站 DNS 全地址公网限制、IP 固定连接、TLS 链与主机名/SNI 验证、禁止跟随重定向和超时/响应大小限制。
- 网络核验不持数据库锁；响应回来重新检查绑定版本、配置版本、nonce、有效期和归属，过期或并发变更失败时不改现用配置。
- 同时改两个域名必须提交完整候选集合，全部 VERIFIED 且版本匹配后，在同一事务切换两个角色并写审计。旧前台单角色 API 不能绕过双候选完整激活。
- 独立入口开关带配置版本和审计。开启要求租户允许访问、两类现用绑定都已核验并属于同租户。失败不改变现用值。
- 入口目标只读取同一租户当前生效前台，未增加任意目标字段。前台成功切换自动改变入口映射；入口开关/换域不增加 sessionVersion，不撤销已进入前台的登录。新前台域名可能需要重新登录，不共享跨站令牌。

所有新管理 API 在原 `/api/control/**` 权限和精确总控 Origin 下，服务层还要求独立 ROLE_CONTROL、真实 ControlIdentity，禁止携带普通租户或目标后台访问上下文。保留已有总控登录、MFA、审计及业务隔离；一般租户编辑路径仍不能重新绑定平台域名。

## 总控与访问实施

列表按“入口域名、前台域名、入口状态”顺序展示；统一域名管理窗口同时展示两角色现用值、候选值、独立状态/有效期/版本及入口开关。提供两种地址复制和默认对外地址复制，入口关闭/未配置明确提示只能直访前台。页面使用请求代次丢弃晚到响应，输入变更不能套用旧核验；全部候选核验前不允许激活。

入口识别先于业务、后台/总控登录、健康检查、模拟监管和网关 SPA 兜底。仅放行 allowlist 中的 GET/HEAD 文档/HTML 导航，覆盖真实 PC 与移动端 `/mobile/...` 页面。目标由服务器构造为 HTTPS 302，`Cache-Control: no-store`、`Referrer-Policy: no-referrer`。仅保留限定 ASCII 白名单参数，拒绝跳转目标参数，丢弃 token、code、state 等非白名单信息；拒绝协议相对路径、编码路径穿越和 WebSocket Upgrade。

除原固定只读 nonce GET 挑战外，入口不提供业务 API、上传、WS、后台登录或支付回调。关闭、未知或不允许的入口均返回相同 403 JSON，不带 Location、不建立 TenantContext、不展示 SPA且不缓存。前台直访保持原状态/权限控制，不读取入口开关。没有把入口加入业务 Origin/CORS/WS 允许集。

Nginx 对真实 Host 先做内部 `auth_request` 分类，再决定转给入口过滤器或进入正常资源/API路径，保留原始移动端 URI；无效/重复 Host 也统一不可用。Upgrade 信息交给入口过滤器拒绝，不被网关丢弃后误当导航。转发 Host 必须与唯一真实 Host 一致，运维可信代理范围之外不得提交；边缘 SNI 如提供必须唯一、可信且与 Host 一致。Caddy 覆盖上游 SNI 头并保留 Host，不再按全局后缀或固定主机替换目标。

## 迁移步骤及禁止上线条件

本次实际执行的只有下列可复跑测试命令，它们创建自己带唯一标签的新容器，不使用现有 DB 或容器，完成后校验标签并删除自己的临时容器/网络。线上迁移尚未执行。

将来由运维审批后迁移时按顺序：

1. 先解决现有发布阻断，并刷新独立的公开数据库结构快照。确认物理目标、0601 完整回执/历史迁移校验和、独立审批、维护和已排空写入；备份并在新隔离实例恢复验证。不得用数据库对象名字推测迁移已经完成。
2. 只读执行 [域名迁移预检](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/tenant-domains/preflight.sql)：版本/最小应用 epoch 必须为 2026100601，其余结果集必须为空，包括多现用/多候选、当前前台归属、已核验标记、保留域及历史归属异常。异常由原归属审查，不能靠自动改 owner、删除或推断消除。
3. 经既有受控计划、恢复证据、审批绑定及维护门禁，执行 [0602 增量迁移](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/resources/db/migration/V2026100602__tenant_entry_frontend_roles.sql)，而不是裸 SQL 重放。本任务仅扩展“完整 0601 后的唯一 0602 尾迁移” inactive metadata 合同；旧列值保持指纹核验，仅排除新 0602 元数据行。未知/多尾迁移、元数据重放、擅自 business activation 仍拒绝。
4. 核查 epoch/minimum_application_epoch=2026100602、business_activation_ready=0、旧前台值不变、全部入口为空且关闭，绑定和历史默认 FRONTEND，唯一约束齐备。MySQL DDL 隐式提交，任何中途失败都必须继续维护并按证据前向修复，不盲目重跑。
5. 由运维完成网关、证书/信任和运行验收后，仍通过总控逐租户 prepare、独立 verify、完整 activate，最后显式开启入口；不能直接改表绕过总控核验。

新 epoch 会拒绝旧应用启动。此处没有运行生产 controlled_migration、没有申请或伪造生产批准，也没有将本地夹具审批检查当作真实审批证据。迁移不会自动把租户设为 ACTIVE。

## 实际命令 退出码与测试证据

以下命令的工作目录均为 `C:\Users\徐乾妖\.codex\worktrees\906d\705`。完整命令/日志在 [命令结果清单](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/all-command-results.json)。Maven protobuf 工具不能处理该中文路径，使用任务创建并确认指向同一目录的 ASCII junction `C:/workspace/fx/tenant-entry-906d`；不是另一份源码。构建输出放在本任务证据目录。

- `mvn.cmd -q -f C:/workspace/fx/tenant-entry-906d/exchange-backend/pom.xml test -Dtest=TenantDomainLifecycleTest,TenantEntryRequestTest,TenantReadinessTest,TenantJwtBoundaryTest,ControlSecurityTest,ControlExchangeHttpTest,OutboundEndpointPolicyTest,ControlPolicyDefinitionTest,TenantPolicyLifecycleTest,TenantSourceGateTest,TenantBoundaryTest,TenantCorsBoundaryTest,TenantForceVersionTest,SchemaPackageGuardTest,ControlAccountSecurityTest,ControlSupportIsolationTest,SimulationTenantIsolationTest,ControlReadOnlyMethodTest`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/backend-final.log)。
- `node --experimental-strip-types exchange-admin/tests/controlDomains.test.mjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/frontend-domains-final.log)。
- `node --experimental-strip-types exchange-admin/tests/controlPolicies.test.mjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/admin-policy-tests.log)。
- `node --experimental-strip-types exchange-admin/tests/tenantSession.test.mjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/admin-session-tests.log)。
- `node --experimental-strip-types exchange-admin/tests/controlAccounts.test.mjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/admin-control-tests.log)。
- `node --experimental-strip-types exchange-admin/tests/controlTenantEntry.test.mjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/admin-entry-tests.log)。
- `npm.cmd --prefix exchange-admin run build:control -- --outDir C:/workspace/fx/tenant-entry-906d/rollback/tenant-entry-20261006-01/build/control`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/control-build-final.log)。
- `npm.cmd --prefix exchange-admin run build -- --outDir C:/workspace/fx/tenant-entry-906d/rollback/tenant-entry-20261006-01/build/admin`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/admin-build.log)。
- `npm.cmd --prefix exchange-frontend run build -- --base=/mobile/ --outDir C:/workspace/fx/tenant-entry-906d/rollback/tenant-entry-20261006-01/build/mobile`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/mobile-build.log)。
- `npm.cmd --prefix exchange-pc run build -- --outDir C:/workspace/fx/tenant-entry-906d/rollback/tenant-entry-20261006-01/build/pc`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/pc-build-retest.log)。
- `python scripts/tenant-domains/test_migration.py --docker`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/mysql57-migration-final.log)。
- `python scripts/tenant-domains/test_migration.py --docker --image container-registry.oracle.com/mysql/community-server:8.4.12`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/mysql84-migration-final.log)。
- `python scripts/tenant-domains/test_gateway.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/nginx-final-retest.log)。
- `node docker/domain-redirect.test.cjs`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/gateway-source-final.log)。
- `docker run --rm --network none --mount type=bind,src=C:\Users\徐乾妖\.codex\worktrees\906d\705\docker\Caddyfile,dst=/etc/caddy/Caddyfile,readonly caddy:2-alpine caddy adapt --config /etc/caddy/Caddyfile`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/caddy-adapt-fixed.log)。
- `python scripts/multitenant/test_controlled_migration.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/migration-controller-verified.log)。
- `python scripts/multitenant/test_history0404_migration_contract.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/migration-history-contract.log)。
- `python scripts/multitenant/test_source0403_metadata_contract.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/migration-source-contract.log)。
- `python scripts/multitenant/build_manifest.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/manifest-final.log)。
- `python scripts/multitenant/isolation_gate.py --check`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/source-gate-delivery.log)。
- `python scripts/multitenant/test_isolation_gate.py`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/source-gate-mutations.log)。
- `python scripts/multitenant/isolation_gate.py --check --release`：退出码 **1**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/release-gate-final.log)。
- `python scripts/database/check_snapshot.py`：退出码 **1**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/snapshot-final.log)。
- `git -c core.whitespace=cr-at-eol diff --check`：退出码 **0**；[原始日志](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/logs/diff-check-delivery.log)。

后端 XML 与逐类数量：[114 项测试汇总](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/backend-final-reports/summary.json)、[Surefire XML](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/backend-final-reports)。覆盖独立候选、三种修改映射、全候选原子激活、过期/并发响应、双并发单赢家、TLS/nonce/审计/版本失败回滚（包括 prepare、verify、activate、开关审计失败）、退役释放/跨租户占用、Host/SNI/开放重定向/业务 API 拒绝、CORS/JWT/readiness/总控及模拟隔离。

前端新脚本运行实际 Vue 脚本处理器并编译模板；既有4个脚本覆盖总控 MFA、授权、目标租户访问与会话隔离。四套构建均包含 `vue-tsc -b`。构建有既有包大小/Sass/动态导入警告，未因此声称无警告。

Chrome 仅访问 127.0.0.1 的最终构建，只读合成租户夹具，使用非真实身份测试数据，不连接生产。确认列表顺序、入口关闭提示、两角色独立候选/核验和未全核验时激活禁用；测试页面和监听已关闭。[AX 观察](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/ui-preview.json)、[预览截图](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/ui-domain-management.jpg)。这是显示验证，不是真实登录、MFA或业务后端的浏览器端到端验收。

早期失败保留原始日志，不算通过：中文路径 protoc 失败；初始 mock 重设触发旧 Answer，修正后通过；SchemaPackageGuardTest 的旧固定 epoch 与资源不一致，改为0602后最终114项通过；PC 首次缺 vue-tsc，按现有 lockfile `npm ci --no-audit --no-fund` 后重建通过（没有改变依赖文件）；Caddy 首次把 HTTP 和 TLS 混在同一块导致 adapt 失败，拆开后通过；对脚本式门禁误用 unittest discover 返回5且0测试，改为运行它的实际 main 后12检查通过；新增迁移合同初次断言错误地把批处理 SQL 当单语句，修正测试解析后31项通过。最终成功结果不覆盖这些失败证据。

## 尚未通过的门禁与线上待核验

发布门禁仍为原来的四项，未改其 resolved 或 release_approved：缺生产发布批准、`ORM_DIRTY_WRITE_PREDICATE`、`PRODUCTION_LEGACY_ORPHANS`、`RUNTIME_ACCEPTANCE_PENDING`。专项源门禁通过不等于这些阻断已解决。

`check_snapshot.py` 的原始失败为 `Snapshot is behind the schema epoch`。公开快照仍是0404，开工 manifest 已是0601，当前因本迁移为0602；[基线比对](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/snapshot-baseline.json)证明快照文件开工即旧且本次没有改写。未仅抬快照版本或哈希假装通过；完整结构快照重建属于独立的整库结构生成/导入校验，未在本任务扩展。此检查确实未通过，后续受控发布前必须同步。

[线上只读观察](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/network-readonly.json)记录 2026-10-06 12:01:59 +08:00：源码中已知 `trade.forex-exchange.net` 和 `trade.forex-exchange.cc` 都解析为公网 Cloudflare 地址，最终 curl 退出0并通过其 TLS 检查；入口 HEAD 是302到同名前台并带no-store，前台HEAD是200。该既有302**不是本代码线上生效的证据**，也不证明全租户显式映射。早期 HEAD 用法导致单个 curl 超时28，换成 --head 后最终观察有效。

仍未验证，需运维只读核验/获准后另行处理：

- 两套根域下所有具体租户及 wildcard DNS A/AAAA/CNAME 路由、CA证书/SAN/有效期、origin及Java信任链；只检查了上述两个已知主机。
- 线上数据库是否具有角色绑定及显式映射、是否应用0602、是否运行本构建，真实关闭/切换/并发/MFA/审计闭环。
- 实际 Caddy/Nginx/CDN 规则、SNI与真实Host一致、外部伪造头在边缘被覆盖，Nginx/后端只能从审定内部网络访问；`security.trusted-proxies` 必须最小化，不能信任所有来源。
- wildcard TLS 在普通 Caddy 镜像中不能凭现配置自动签发。生产 compose新增只读证书/密钥挂载，需运维提供 `TENANT_DOMAIN_CERT_FILE` 和 `TENANT_DOMAIN_KEY_FILE`，覆盖两套wildcard并核查根入口所需证书；本次没有签发、读取或部署这些密钥。
- 真实 nonce 挑战公网直返、DNS全地址/连接IP固定、公网超时/TLS失败/回滚。生命周期测试用mock故障注入；本地Nginx测试是HTTP夹具，不能替代真实TLS与完整应用网关验收。
- 线上既有永久重定向/301/308、CDN规则、浏览器或边缘永久缓存是否仍存在。不得以当前一次302观测宣称永久缓存已清除。本次未清理任何线上缓存或规则。
- 新入口不会加入业务CORS/WS Origin，也不会跨域共享登录。前台新域需要正常登录，不能承诺自动迁移旧站登录。

## 本任务改动文件

- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/Tenant.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/Tenant.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantRepository.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantRepository.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainBinding.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainBinding.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainHistory.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainHistory.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainBindingRepository.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainBindingRepository.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantHostService.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantHostService.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainVerification.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantDomainVerification.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/ControlController.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/ControlController.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantManagementService.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantManagementService.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantDomainLifecycleTest.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantDomainLifecycleTest.java)
- [exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantRequestFilter.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/java/com/gtcfesk/exchange/control/TenantRequestFilter.java)
- [exchange-backend/src/main/resources/db/migration/V2026100602__tenant_entry_frontend_roles.sql](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/resources/db/migration/V2026100602__tenant_entry_frontend_roles.sql)
- [scripts/multitenant/table_manifest.json](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/multitenant/table_manifest.json)
- [exchange-backend/src/main/resources/META-INF/mt705-schema-epoch](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/main/resources/META-INF/mt705-schema-epoch)
- [.gitignore](C:/Users/徐乾妖/.codex/worktrees/906d/705/.gitignore)
- [docker/nginx.conf](C:/Users/徐乾妖/.codex/worktrees/906d/705/docker/nginx.conf)
- [docker/Caddyfile](C:/Users/徐乾妖/.codex/worktrees/906d/705/docker/Caddyfile)
- [compose.production.yaml](C:/Users/徐乾妖/.codex/worktrees/906d/705/compose.production.yaml)
- [docker/domain-redirect.test.cjs](C:/Users/徐乾妖/.codex/worktrees/906d/705/docker/domain-redirect.test.cjs)
- [exchange-admin/src/control/TenantManager.vue](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-admin/src/control/TenantManager.vue)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantEntryRequestTest.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantEntryRequestTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantReadinessTest.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/test/java/com/gtcfesk/exchange/control/TenantReadinessTest.java)
- [exchange-admin/tests/controlDomains.test.mjs](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-admin/tests/controlDomains.test.mjs)
- [scripts/multitenant/source_isolation_registry.json](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/multitenant/source_isolation_registry.json)
- [scripts/tenant-domains/test_migration.py](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/tenant-domains/test_migration.py)
- [scripts/tenant-domains/test_gateway.py](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/tenant-domains/test_gateway.py)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/tenant/TenantCorsBoundaryTest.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/test/java/com/gtcfesk/exchange/tenant/TenantCorsBoundaryTest.java)
- [exchange-backend/src/test/java/com/gtcfesk/exchange/tenant/SchemaPackageGuardTest.java](C:/Users/徐乾妖/.codex/worktrees/906d/705/exchange-backend/src/test/java/com/gtcfesk/exchange/tenant/SchemaPackageGuardTest.java)
- [scripts/multitenant/controlled_migration.py](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/multitenant/controlled_migration.py)
- [scripts/multitenant/test_controlled_migration.py](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/multitenant/test_controlled_migration.py)
- [scripts/tenant-domains/preflight.sql](C:/Users/徐乾妖/.codex/worktrees/906d/705/scripts/tenant-domains/preflight.sql)
- [docs/tenant-entry-domain-separation-20261006.md](C:/Users/徐乾妖/.codex/worktrees/906d/705/docs/tenant-entry-domain-separation-20261006.md)

## 备份 补丁与回滚

- 本任务补丁：[task.patch](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/task.patch)，基于开工含其他任务成果的树，仅本任务32个路径，生成后通过 `git apply --check --reverse` 及正向开工树校验；未真正应用反向补丁。
- 原始字节备份：[original 目录](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/original)；逐文件 before/after：[touched.json](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/touched.json)；补丁范围/哈希及保留证明：[交付完整性](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/delivery-integrity.json)。
- 安全回滚入口：[restore.py](C:/Users/徐乾妖/.codex/worktrees/906d/705/rollback/tenant-entry-20261006-01/restore.py)，默认为只校验；验证根目录、所有任务后哈希及备份哈希，发现后续改动则停止，不覆盖其他任务。--apply 前另存当前任务文件；只恢复本任务原文件、移除本任务创建且未被后续编辑的单文件，不操作Git或数据库。**本次只执行 --check，没有执行回滚。**

本地源码回滚命令（只检查是安全默认）：

```powershell
Set-Location -LiteralPath 'C:\Users\徐乾妖\.codex\worktrees\906d\705'
python 'C:\Users\徐乾妖\.codex\worktrees\906d\705\rollback\tenant-entry-20261006-01\restore.py' --check
# 仅在确认本任务需要回滚、检查通过且没有后续改动后执行：
python 'C:\Users\徐乾妖\.codex\worktrees\906d\705\rollback\tenant-entry-20261006-01\restore.py' --apply
```

线上数据库没有被本任务修改，所以不存在本次线上DDL回滚执行。将来应用0602后，不可只回退旧Jar或自动DROP新列/删域名历史：epoch会封锁旧应用，且入口归属和审计需保留。优先保持已核验前台并通过总控关闭入口、前向修复；确需旧应用/旧schema回退，必须另行审批、完整备份恢复证明、停写与新ENTRY数据/归属审查，不能用本地源码回滚脚本代替。

本地测试自有MySQL/Nginx容器和网络已清理；证据与构建保留。本报告严格区分本地实施、各项校验结果与未验证的线上生效。
