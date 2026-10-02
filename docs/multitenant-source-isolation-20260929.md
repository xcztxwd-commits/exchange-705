# 多租户源码隔离清单与变更门禁

## 门禁含义

`source_isolation_registry.json` 逐文件保存分类、审查说明、完整生产 Java 源 SHA-256，以及每个检测到的原生 SQL、JPQL、缓存/锁、异步、文件、实体、Repository、身份边界和启动面的位置/语句摘要。即便正则未识别新 API，任何生产 Java 文件新增、删除或内容变化也必须重新审查，不能无声绕过。

**它是变更审查门禁，不是完整 SQL 解析器，不是运行时通用过滤/SQL 改写器，也不是业务安全性的数学证明。** 检测命中可能包含注释/固定字符串。指纹批准只代表该源版本已登记；未覆盖项和发布阻塞仍必须保留。当前不存在自动“全部接受变更”命令。

```powershell
python scripts/multitenant/isolation_gate.py --check
python scripts/multitenant/test_isolation_gate.py
powershell -File scripts/multitenant/verify-build.ps1
# 发布必须另查：当前应失败，不能因源码检查通过而跳过。
python scripts/multitenant/isolation_gate.py --release
```

`verify-build.ps1` 先执行源码检查，再运行 Maven（默认 verify）。完整 Maven test 也有 `TenantSourceGateTest`；手工跳过测试或直接 compile 不能替代受控构建入口。Python 不可用时失败，不静默跳过。

候选生成：`python scripts/multitenant/isolation_gate.py --propose REVIEWED_NEW_CANDIDATE.json`。候选始终 `approved=false`，不得覆盖登记文件。逐项核对新原生 SQL 的每个私表/子查询/连接、缓存键的租户及主体类型、异步捕获与清理、文件路径/业务引用、实体/Repository 分类后，才更新对应指纹和审查原因；保留首份备份。构建期间仍有其他代理编辑时，应失败并等待定稿，而非盲目刷新。

结构门禁还拒绝：未分类 @Entity 表、私有实体未继承 TenantOwnedEntity、私有 Repository 直接暴露 JpaRepository/CrudRepository/PagingAndSortingRepository、旧 MAX+1 User ID 生成器被重新引用。纯工具和 DTO 同样有全文件指纹，新增未知面失败。

10 项独立变异测试在临时合成树执行，覆盖未知普通类、新敏感语句、即便改过指纹仍错误的私有实体/Repository、未批准候选、缺少原因、重新启用旧 ID 生成器，以及源码通过不等于发布批准。不修改生产树。

## 明确未覆盖和发布阻塞

1. `ORM_DIRTY_WRITE_PREDICATE`：现有 TenantRepository 查找/存在性检查带 tenant，但 Hibernate 默认 dirty UPDATE/DELETE 可能只带全局 ID（及 version）。owner 回调不能作为每条 SQL tenant+ID 的证明。独立 JpaTenantProbe 的 StatementInspector **只观察不改写**，输出 asset_account 实际 WHERE 形态；不得把跨租户行为测试通过写成所有生成 SQL 已验证。后续实体写边界方案必须额外实际验证后才能解除。
2. 真实旧库孤儿仍未清理；只读盘点和合成隔离演练不是正式迁移。新的测试数据清理授权不等于跳过备份、精确归档和停写。
3. 应用/浏览器/E2E/并发/恢复证据需要完整汇总。组件测试、schema validate 和源指纹不能替代该验收。源码 `--release` 与数据库 activation 门禁都保持未批准。

## 关键审查位置

- 市场：ControlHistoryStore/PersistentPriceControl/ControlHoldService/ControlRecoveryFlow/ControlledKlineMerger 显式 tenant SQL；市场工作队列/Redis/WS 实例状态需租户键和服务器验证域名。SimulationControlPath 使用 tenant+JSON 缓存键、返回 Event 防御副本及独立 plan，不共享可变对象。
- 文件：FileUploadController/ImageController/PublishedTenantFiles 明确租户、主体类型与实际发布引用；agent-N 与 admin 数字命名不碰撞。不能用 users 菜单读取任意他人未公开 staff 或用户材料。历史未知归属保持拒绝，参见文件迁移文档。
- 监管：ControlReadQueryService 固定投影、显式 tenant 和有界附件/证据导出，无读回执副作用；真正 CONTROL 身份和成功审计在返回数据之前。
- 自动任务：TenantJobRunner 显式逐租户独立上下文；ChatRetentionJob 是有意的控制面策略枚举，私有删除均显式 tenant，SYSTEM 审计不伪造管理员。
- 出站/配置：新 ReadinessService 只查固定实体条件和当前租户配置，邮件每次构造 sender；DNS/SMTP 线程只捕获目的连接，不从不明 ThreadLocal 获取租户。平台 allowlist 和租户配置相互独立。

## 全文件登记索引

以下为已登记版本的索引；若源文件随后变化，门禁会拒绝。权威详细说明、逐语句行号和摘要在 JSON 清单，不以本表的检测命中数量代替审查。

| 文件（生产包内相对路径） | 分类 | 敏感面命中 |
| --- | --- | --- |
| `ExchangeBackendApplication.java` | stateless | 无正则命中；仍有完整源指纹 |
| `activity/ActivityCampaign.java` | private_scoped | native_sql:1, tenant_entity:1 |
| `activity/ActivityCampaignRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `activity/ActivityController.java` | private_scoped | identity_boundary:1 |
| `activity/ActivityDelivery.java` | private_scoped | tenant_entity:1 |
| `activity/ActivityDeliveryRepository.java` | private_scoped | repository:1 |
| `activity/ActivityPrivacy.java` | stateless | 无正则命中；仍有完整源指纹 |
| `activity/ActivityService.java` | private_scoped | cache_lock:1, entity_manager_jpql:1, identity_boundary:8 |
| `activity/TrialAccount.java` | private_scoped | tenant_entity:1 |
| `activity/TrialAccountRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `activity/TrialFunds.java` | private_scoped | identity_boundary:2 |
| `activity/TrialLedger.java` | private_scoped | tenant_entity:1 |
| `activity/TrialLedgerRepository.java` | private_scoped | repository:1 |
| `admin/AdminAccountInspectionController.java` | private_scoped | identity_boundary:3 |
| `admin/AdminAccountQueryController.java` | private_scoped | identity_boundary:3 |
| `admin/AdminActivityController.java` | private_scoped | identity_boundary:3 |
| `admin/AdminAiControlController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AdminAnnouncementController.java` | private_scoped | identity_boundary:5 |
| `admin/AdminAuthController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AdminAuthService.java` | private_scoped | identity_boundary:7 |
| `admin/AdminDataInitializer.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AdminDurationController.java` | private_scoped | identity_boundary:5 |
| `admin/AdminFinancialController.java` | private_scoped | identity_boundary:1 |
| `admin/AdminFinancialService.java` | private_scoped | identity_boundary:8 |
| `admin/AdminFinancialYieldController.java` | private_scoped | identity_boundary:1 |
| `admin/AdminFinancialYieldService.java` | private_scoped | identity_boundary:2 |
| `admin/AdminManagementController.java` | private_scoped | identity_boundary:14 |
| `admin/AdminMenuController.java` | private_scoped | identity_boundary:1 |
| `admin/AdminMenuService.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AdminOrderController.java` | private_scoped | cache_lock:2, identity_boundary:15, native_sql:5 |
| `admin/AdminPermissionCatalog.java` | shared_public | startup:2 |
| `admin/AdminPermissionService.java` | private_scoped | cache_lock:1, identity_boundary:8 |
| `admin/AdminRoleController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AdminRoleService.java` | private_scoped | entity_manager_jpql:1, identity_boundary:9 |
| `admin/AdminSymbolController.java` | stateless | native_sql:1 |
| `admin/AdminSymbolService.java` | private_scoped | entity_manager_jpql:1, identity_boundary:12 |
| `admin/AdminTablePreferenceController.java` | private_scoped | identity_boundary:7 |
| `admin/AdminTenantPolicyController.java` | private_scoped | identity_boundary:2 |
| `admin/AdminUser.java` | private_scoped | tenant_entity:1 |
| `admin/AdminUserController.java` | stateless | entity_manager_jpql:1, native_sql:3 |
| `admin/AdminUserRepository.java` | private_scoped | repository:1 |
| `admin/AdminUserService.java` | private_scoped | entity_manager_jpql:2, identity_boundary:29, native_sql:2 |
| `admin/AdminWalletController.java` | private_scoped | identity_boundary:8 |
| `admin/AgentActionService.java` | private_scoped | entity_manager_jpql:2, identity_boundary:7 |
| `admin/AgentMenuController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/AgentMenuService.java` | private_scoped | entity_manager_jpql:2, identity_boundary:6 |
| `admin/AgentPerformanceController.java` | private_scoped | cache_lock:1, identity_boundary:6 |
| `admin/DashboardController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/DashboardService.java` | private_scoped | identity_boundary:24 |
| `admin/DepositOrderController.java` | private_scoped | cache_lock:1, entity_manager_jpql:6, identity_boundary:14, native_sql:1 |
| `admin/DepositReviewController.java` | private_scoped | identity_boundary:1 |
| `admin/DepositReviewService.java` | private_scoped | identity_boundary:6 |
| `admin/DepositSettingController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/DepositSettingService.java` | private_scoped | identity_boundary:6 |
| `admin/KycReviewController.java` | private_scoped | entity_manager_jpql:1, identity_boundary:11 |
| `admin/LoanPersonalInfoReviewController.java` | private_scoped | entity_manager_jpql:1, identity_boundary:9 |
| `admin/LoanReviewController.java` | private_scoped | identity_boundary:1 |
| `admin/LoanReviewService.java` | private_scoped | identity_boundary:6 |
| `admin/LoanSettingController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/LoanSettingService.java` | private_scoped | identity_boundary:3 |
| `admin/ManualOrderController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/NotificationController.java` | private_scoped | identity_boundary:11 |
| `admin/OperationLogController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/OperationLogService.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/PermissionGrantInput.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/StatisticsController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/SystemConfigController.java` | private_scoped | identity_boundary:1 |
| `admin/SystemConfigService.java` | private_scoped | identity_boundary:3 |
| `admin/WebsiteSecurityController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/WithdrawReviewController.java` | private_scoped | entity_manager_jpql:1, identity_boundary:10 |
| `admin/dto/ResetPasswordRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/dto/SymbolQueryRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/dto/UpdateUserBalanceRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/dto/UpdateUserStatusRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/dto/UpdateUserTypeRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `admin/dto/UserQueryRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/AuthController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/AuthService.java` | private_scoped | identity_boundary:10 |
| `auth/dto/LoginRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/dto/RegisterRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/dto/ResetPasswordRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/dto/SendCodeRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `auth/vo/AuthResponse.java` | stateless | 无正则命中；仍有完整源指纹 |
| `common/Base64MultipartFile.java` | stateless | file_io:3 |
| `common/BusinessException.java` | stateless | 无正则命中；仍有完整源指纹 |
| `common/FileUploadController.java` | private_scoped | file_io:12 |
| `common/ForexDisplayName.java` | shared_public | cache_lock:1 |
| `common/ImageController.java` | private_scoped | file_io:6, identity_boundary:2 |
| `common/ImageFiles.java` | boundary | file_io:3 |
| `common/JwtUtil.java` | private_scoped | identity_boundary:1 |
| `common/KycRequiredException.java` | stateless | 无正则命中；仍有完整源指纹 |
| `common/LogRedaction.java` | stateless | 无正则命中；仍有完整源指纹 |
| `common/PublishedTenantFiles.java` | private_scoped | entity_manager_jpql:3, identity_boundary:3, native_sql:7 |
| `common/SafeErrors.java` | stateless | 无正则命中；仍有完整源指纹 |
| `common/SecurityUtils.java` | boundary | identity_boundary:3 |
| `common/TradeValidation.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/AdminPermission.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/AssetHistoryRedisConfig.java` | boundary | cache_lock:3 |
| `config/BackendAccess.java` | private_scoped | identity_boundary:17 |
| `config/ContentCachingRequestFilter.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/CorsConfig.java` | stateless | identity_boundary:1 |
| `config/CustomUserIdGenerator.java` | disabled_legacy | native_sql:1 |
| `config/GlobalExceptionHandler.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/JwtFilter.java` | private_scoped | identity_boundary:11 |
| `config/OperationLogInterceptor.java` | private_scoped | identity_boundary:3, native_sql:2 |
| `config/PasswordConfig.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/RedisConfig.java` | boundary | cache_lock:3 |
| `config/RequestLoggingFilter.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/RestTemplateConfig.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/SecurityConfig.java` | stateless | 无正则命中；仍有完整源指纹 |
| `config/WebConfig.java` | boundary | file_io:4 |
| `config/WebSocketConfig.java` | private_scoped | identity_boundary:2 |
| `control/BackendAccountController.java` | control_plane | identity_boundary:5 |
| `control/BackendAccountService.java` | control_plane | identity_boundary:4 |
| `control/BackendLogin.java` | control_plane | tenant_entity:1 |
| `control/BackendLoginRegistry.java` | control_plane | identity_boundary:4 |
| `control/BackendLoginRepository.java` | control_plane | repository:1 |
| `control/ChatRetentionController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `control/ChatRetentionJob.java` | control_plane | async_job:1, identity_boundary:1, native_sql:3 |
| `control/ChatRetentionService.java` | control_plane | file_io:7, identity_boundary:4, native_sql:14 |
| `control/ControlAccessSession.java` | control_plane | tenant_entity:1 |
| `control/ControlAccessSessionRepository.java` | control_plane | entity_manager_jpql:2, native_sql:2, repository:1 |
| `control/ControlAccountController.java` | control_plane | native_sql:1 |
| `control/ControlAccountService.java` | control_plane | identity_boundary:3, native_sql:1 |
| `control/ControlAdmin.java` | control_plane | tenant_entity:1 |
| `control/ControlAdminRepository.java` | control_plane | entity_manager_jpql:1, native_sql:1, repository:1 |
| `control/ControlAuditLog.java` | control_plane | tenant_entity:1 |
| `control/ControlAuditLogRepository.java` | control_plane | repository:1 |
| `control/ControlAuditService.java` | control_plane | identity_boundary:1 |
| `control/ControlBootstrap.java` | control_plane | startup:2 |
| `control/ControlController.java` | boundary | identity_boundary:5, native_sql:1 |
| `control/ControlExchangeController.java` | control_plane | identity_boundary:1 |
| `control/ControlIdentity.java` | control_plane | identity_boundary:4 |
| `control/ControlLoginRateLimit.java` | control_plane | cache_lock:2 |
| `control/ControlMfa.java` | control_plane | 无正则命中；仍有完整源指纹 |
| `control/ControlReadController.java` | boundary | identity_boundary:5, native_sql:5 |
| `control/ControlReadQueryService.java` | control_plane | entity_manager_jpql:2, identity_boundary:3, native_sql:13 |
| `control/ControlSecurityController.java` | control_plane | 无正则命中；仍有完整源指纹 |
| `control/ControlSecurityService.java` | control_plane | identity_boundary:1 |
| `control/ControlService.java` | control_plane | identity_boundary:8 |
| `control/Tenant.java` | control_plane | tenant_entity:1 |
| `control/TenantDomainHistory.java` | control_plane | tenant_entity:1 |
| `control/TenantDomainHistoryRepository.java` | control_plane | repository:1 |
| `control/TenantDomainVerification.java` | control_plane | identity_boundary:1 |
| `control/TenantFeatureController.java` | control_plane | 无正则命中；仍有完整源指纹 |
| `control/TenantHostService.java` | control_plane | identity_boundary:2 |
| `control/TenantManagementService.java` | control_plane | cache_lock:1, identity_boundary:6, native_sql:1 |
| `control/TenantPolicy.java` | control_plane | tenant_entity:1 |
| `control/TenantPolicyRepository.java` | control_plane | repository:1 |
| `control/TenantPolicyService.java` | control_plane | identity_boundary:5 |
| `control/TenantReadinessService.java` | control_plane | entity_manager_jpql:3, identity_boundary:4, native_sql:2 |
| `control/TenantRepository.java` | control_plane | entity_manager_jpql:1, native_sql:1, repository:1 |
| `control/TenantRequestFilter.java` | control_plane | identity_boundary:5 |
| `control/TenantSafeTemplate.java` | control_plane | identity_boundary:2 |
| `demo/DemoAccount.java` | private_scoped | tenant_entity:1 |
| `demo/DemoAccountRepository.java` | private_scoped | repository:1 |
| `demo/DemoLedger.java` | private_scoped | tenant_entity:1 |
| `demo/DemoLedgerRepository.java` | private_scoped | repository:1 |
| `demo/DemoModeBoundary.java` | stateless | 无正则命中；仍有完整源指纹 |
| `demo/DemoOrder.java` | private_scoped | tenant_entity:1 |
| `demo/DemoOrderRepository.java` | private_scoped | repository:1 |
| `demo/DemoTradingController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `demo/DemoTradingService.java` | private_scoped | cache_lock:1, identity_boundary:11 |
| `entity/AdminMenu.java` | shared_public | tenant_entity:1 |
| `entity/AdminRole.java` | private_scoped | tenant_entity:1 |
| `entity/AdminRoleMenu.java` | private_scoped | tenant_entity:1 |
| `entity/AdminTablePreference.java` | private_scoped | tenant_entity:1 |
| `entity/Announcement.java` | private_scoped | tenant_entity:1 |
| `entity/AssetAccount.java` | private_scoped | tenant_entity:1 |
| `entity/AssetSnapshot.java` | private_scoped | tenant_entity:1 |
| `entity/BalanceAdjustment.java` | private_scoped | tenant_entity:1 |
| `entity/ContractOrder.java` | private_scoped | tenant_entity:1 |
| `entity/DepositCreditRecord.java` | private_scoped | tenant_entity:1 |
| `entity/DepositRecord.java` | private_scoped | tenant_entity:1 |
| `entity/DepositSetting.java` | private_scoped | tenant_entity:1 |
| `entity/FinancialOrder.java` | private_scoped | tenant_entity:1 |
| `entity/FinancialProduct.java` | private_scoped | tenant_entity:1 |
| `entity/FinancialYieldRecord.java` | private_scoped | tenant_entity:1 |
| `entity/KycRecord.java` | private_scoped | tenant_entity:1 |
| `entity/LoanPersonalInfo.java` | private_scoped | tenant_entity:1 |
| `entity/LoanRecord.java` | private_scoped | tenant_entity:1 |
| `entity/LoanSetting.java` | private_scoped | tenant_entity:1 |
| `entity/MenuAction.java` | shared_public | tenant_entity:1 |
| `entity/OperationLog.java` | private_scoped | tenant_entity:1 |
| `entity/OptionDuration.java` | private_scoped | tenant_entity:1 |
| `entity/OptionOrder.java` | private_scoped | tenant_entity:1 |
| `entity/SystemConfig.java` | private_scoped | tenant_entity:1 |
| `entity/TradingSymbol.java` | private_scoped | tenant_entity:1 |
| `entity/TransferRecord.java` | private_scoped | tenant_entity:1 |
| `entity/UserAccount.java` | private_scoped | tenant_entity:1 |
| `entity/UserAction.java` | private_scoped | tenant_entity:1 |
| `entity/UserBankCard.java` | private_scoped | tenant_entity:1 |
| `entity/UserDigitalAddress.java` | private_scoped | tenant_entity:1 |
| `entity/UserMenu.java` | private_scoped | tenant_entity:1 |
| `entity/VerifyCode.java` | private_scoped | tenant_entity:1 |
| `entity/WithdrawRecord.java` | private_scoped | tenant_entity:1 |
| `market/BalancedControlPlan.java` | stateless | cache_lock:1, native_sql:2 |
| `market/ControlHistoryStore.java` | private_scoped | cache_lock:1, identity_boundary:2, native_sql:38, startup:1 |
| `market/ControlHoldService.java` | private_scoped | native_sql:7 |
| `market/ControlRecoveryFlow.java` | private_scoped | native_sql:11 |
| `market/ControlledKlineMerger.java` | private_scoped | native_sql:4 |
| `market/ExchangeQuoteSource.java` | shared_public | cache_lock:2, startup:1 |
| `market/ExchangeQuoteStream.java` | shared_public | async_job:3, cache_lock:2, startup:1 |
| `market/FiatCurrencyController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/ForexQuoteMarketService.java` | private_scoped | async_job:2, cache_lock:9, identity_boundary:14, native_sql:2, startup:1 |
| `market/MarketCategoryService.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/MarketConfigController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/MarketController.java` | private_scoped | identity_boundary:4 |
| `market/MarketHttp.java` | shared_public | async_job:2, cache_lock:1 |
| `market/MarketIconController.java` | shared_public | cache_lock:3 |
| `market/MarketInstrumentCatalog.java` | private_scoped | cache_lock:3, identity_boundary:1 |
| `market/MarketKlineController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/MarketOrderProcessor.java` | private_scoped | async_job:1, startup:1 |
| `market/MarketPriceController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/MarketQuoteSource.java` | shared_public | cache_lock:5 |
| `market/MarketRedisController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/MarketWebSocketHandler.java` | private_scoped | async_job:2, cache_lock:4, identity_boundary:3, startup:1 |
| `market/PersistentPriceControl.java` | private_scoped | cache_lock:1, identity_boundary:13, native_sql:23 |
| `market/PriceControlPath.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/QuoteCurrencyConversion.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/QuoteState.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/RandomMarketPath.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/RateLimitService.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/RecoveryOptions.java` | stateless | 无正则命中；仍有完整源指纹 |
| `market/RedisMarketService.java` | private_scoped | async_job:1, cache_lock:3, identity_boundary:10, startup:1 |
| `market/SimulationControlPath.java` | boundary | cache_lock:1, identity_boundary:4 |
| `market/YahooQuoteStream.java` | shared_public | async_job:2, cache_lock:2, startup:1 |
| `repository/AdminMenuRepository.java` | shared_public | repository:1 |
| `repository/AdminRoleMenuRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `repository/AdminRoleRepository.java` | private_scoped | repository:1 |
| `repository/AdminTablePreferenceRepository.java` | private_scoped | repository:1 |
| `repository/AnnouncementRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:4, native_sql:2, repository:1 |
| `repository/AssetAccountRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `repository/AssetSnapshotRepository.java` | private_scoped | repository:1 |
| `repository/BalanceAdjustmentRepository.java` | private_scoped | repository:1 |
| `repository/ContractOrderRepository.java` | private_scoped | entity_manager_jpql:3, identity_boundary:3, native_sql:3, repository:1 |
| `repository/DepositCreditRecordRepository.java` | private_scoped | repository:1 |
| `repository/DepositRecordRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `repository/DepositSettingRepository.java` | private_scoped | repository:1 |
| `repository/FinancialOrderRepository.java` | private_scoped | entity_manager_jpql:3, identity_boundary:3, native_sql:3, repository:1 |
| `repository/FinancialProductRepository.java` | private_scoped | repository:1 |
| `repository/FinancialYieldRecordRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:2, native_sql:2, repository:1 |
| `repository/KycRecordRepository.java` | private_scoped | repository:1 |
| `repository/LoanPersonalInfoRepository.java` | private_scoped | repository:1 |
| `repository/LoanRecordRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `repository/LoanSettingRepository.java` | private_scoped | repository:1 |
| `repository/MenuActionRepository.java` | shared_public | repository:1 |
| `repository/OperationLogRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:2, native_sql:2, repository:1 |
| `repository/OptionDurationRepository.java` | private_scoped | repository:1 |
| `repository/OptionOrderRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:2, native_sql:2, repository:1 |
| `repository/SystemConfigRepository.java` | private_scoped | repository:1 |
| `repository/TradingSymbolRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:2, native_sql:2, repository:1 |
| `repository/TransferRecordRepository.java` | private_scoped | repository:1 |
| `repository/UserAccountRepository.java` | private_scoped | entity_manager_jpql:8, identity_boundary:6, native_sql:8, repository:1 |
| `repository/UserActionRepository.java` | private_scoped | entity_manager_jpql:2, identity_boundary:2, native_sql:2, repository:1 |
| `repository/UserBankCardRepository.java` | private_scoped | repository:1 |
| `repository/UserDigitalAddressRepository.java` | private_scoped | repository:1 |
| `repository/UserMenuRepository.java` | private_scoped | repository:1 |
| `repository/VerifyCodeRepository.java` | private_scoped | repository:1 |
| `repository/WithdrawRecordRepository.java` | private_scoped | entity_manager_jpql:1, identity_boundary:1, native_sql:1, repository:1 |
| `security/CombinedCaptcha.java` | stateless | 无正则命中；仍有完整源指纹 |
| `security/OutboundEndpointPolicy.java` | boundary | async_job:5, cache_lock:1 |
| `security/RegistrationSecurity.java` | boundary | cache_lock:2, identity_boundary:1 |
| `security/RegistrationSecurityFilter.java` | stateless | 无正则命中；仍有完整源指纹 |
| `security/SecurityFailure.java` | stateless | 无正则命中；仍有完整源指纹 |
| `security/WebsiteSecuritySettings.java` | stateless | 无正则命中；仍有完整源指纹 |
| `service/EmailService.java` | private_scoped | async_job:2, identity_boundary:2 |
| `simulation/AccountInspection.java` | private_scoped | identity_boundary:1, native_sql:4 |
| `simulation/AdminReadRoutes.java` | stateless | 无正则命中；仍有完整源指纹 |
| `simulation/SimulationAdminQueryBoundary.java` | private_scoped | identity_boundary:7 |
| `simulation/SimulationCatalogController.java` | private_scoped | identity_boundary:4, native_sql:4 |
| `simulation/SimulationEnvironment.java` | boundary | startup:1 |
| `simulation/SimulationGateway.java` | private_scoped | identity_boundary:1 |
| `simulation/SimulationIdentityBoundary.java` | stateless | 无正则命中；仍有完整源指纹 |
| `simulation/SimulationInspectionBoundary.java` | private_scoped | identity_boundary:1 |
| `simulation/SimulationProvisioner.java` | private_scoped | cache_lock:1, identity_boundary:2, native_sql:18 |
| `simulation/SimulationSeed.java` | private_scoped | tenant_entity:1 |
| `support/AdminSupportController.java` | boundary | file_io:2 |
| `support/InboxLetter.java` | private_scoped | tenant_entity:1 |
| `support/SupportAttachment.java` | private_scoped | tenant_entity:1 |
| `support/SupportConversation.java` | private_scoped | tenant_entity:1 |
| `support/SupportMessage.java` | private_scoped | tenant_entity:1 |
| `support/SupportPermissionCatalog.java` | shared_public | startup:2 |
| `support/SupportPresence.java` | private_scoped | tenant_entity:1 |
| `support/SupportService.java` | private_scoped | entity_manager_jpql:3, file_io:3, identity_boundary:7, native_sql:15 |
| `support/SupportSettings.java` | private_scoped | 无正则命中；仍有完整源指纹 |
| `support/UserSupportController.java` | boundary | file_io:2 |
| `tenant/TenantContext.java` | private_scoped | identity_boundary:2 |
| `tenant/TenantEntities.java` | private_scoped | entity_manager_jpql:5, identity_boundary:1, native_sql:1 |
| `tenant/TenantFiles.java` | private_scoped | file_io:1, identity_boundary:3 |
| `tenant/TenantJobRunner.java` | private_scoped | identity_boundary:3, native_sql:4 |
| `tenant/TenantOwnedEntity.java` | private_scoped | identity_boundary:4 |
| `tenant/TenantRepository.java` | private_scoped | repository:1 |
| `tenant/TenantRepositoryFactoryBean.java` | shared_public | entity_manager_jpql:2, identity_boundary:2, native_sql:1, repository:2 |
| `tenant/TenantRepositoryImpl.java` | private_scoped | entity_manager_jpql:3, identity_boundary:7, native_sql:1, repository:1 |
| `tenant/TenantSecrets.java` | private_scoped | identity_boundary:1 |
| `trade/ContractOrderController.java` | boundary | identity_boundary:2, native_sql:1 |
| `trade/ContractOrderService.java` | private_scoped | entity_manager_jpql:1, identity_boundary:21, native_sql:1 |
| `trade/ContractValuation.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/FxContractRules.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/ManualOrderCalculation.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/ManualOrderGenerator.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/ManualOrderPrices.java` | stateless | cache_lock:1 |
| `trade/ManualOrderService.java` | private_scoped | cache_lock:5, identity_boundary:26, native_sql:23 |
| `trade/OptionDurationController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/OptionDurationService.java` | private_scoped | identity_boundary:2 |
| `trade/OptionOrderController.java` | private_scoped | identity_boundary:5 |
| `trade/OptionOrderService.java` | private_scoped | identity_boundary:11 |
| `trade/QuantityRules.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/YahooHistoryCalendar.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/dto/CreateContractOrderRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `trade/dto/CreateOptionOrderRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/AnnouncementController.java` | private_scoped | identity_boundary:1 |
| `user/AssetEquityHistoryService.java` | private_scoped | identity_boundary:4, native_sql:7 |
| `user/AssetEquityJobs.java` | private_scoped | async_job:8, identity_boundary:4, native_sql:7 |
| `user/AssetEquityStore.java` | private_scoped | identity_boundary:10, native_sql:25 |
| `user/AssetHistoryBucket.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/AssetHistoryCache.java` | private_scoped | cache_lock:3, identity_boundary:2, native_sql:3 |
| `user/AssetHistoryController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/AssetHistoryService.java` | private_scoped | async_job:1, cache_lock:1, identity_boundary:14, native_sql:15 |
| `user/BalanceAdjustmentService.java` | private_scoped | identity_boundary:5 |
| `user/ChangePasswordController.java` | private_scoped | identity_boundary:2 |
| `user/CustomerServiceController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/DepositController.java` | private_scoped | async_job:1, identity_boundary:6 |
| `user/DepositOrderRequest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/DepositOrderService.java` | private_scoped | identity_boundary:8 |
| `user/EquityValuationService.java` | private_scoped | identity_boundary:6, native_sql:9 |
| `user/FiatCurrencyService.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/FileUploadService.java` | private_scoped | file_io:10 |
| `user/FinancialController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/FinancialService.java` | private_scoped | identity_boundary:5 |
| `user/FinancialYieldController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/FinancialYieldService.java` | private_scoped | async_job:1, identity_boundary:5 |
| `user/InviteController.java` | private_scoped | identity_boundary:3 |
| `user/KycController.java` | private_scoped | file_io:3, identity_boundary:3 |
| `user/KycIdentityService.java` | private_scoped | identity_boundary:1 |
| `user/LoanController.java` | boundary | file_io:1 |
| `user/LoanInterest.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/LoanPersonalInfoController.java` | boundary | file_io:2 |
| `user/LoanPersonalInfoService.java` | private_scoped | identity_boundary:3 |
| `user/LoanService.java` | private_scoped | identity_boundary:9 |
| `user/ManualOrderHistory.java` | private_scoped | identity_boundary:9, native_sql:12 |
| `user/TransferController.java` | private_scoped | identity_boundary:4 |
| `user/UserActivityController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/UserActivityService.java` | private_scoped | identity_boundary:3 |
| `user/UserDashboardController.java` | stateless | 无正则命中；仍有完整源指纹 |
| `user/UserDashboardService.java` | private_scoped | identity_boundary:2 |
| `user/UserInfoController.java` | private_scoped | identity_boundary:4 |
| `user/WalletController.java` | private_scoped | identity_boundary:8 |
| `user/WithdrawController.java` | private_scoped | identity_boundary:3 |
| `utils/DomainUtils.java` | stateless | 无正则命中；仍有完整源指纹 |
| `utils/IpUtils.java` | shared_public | cache_lock:2 |
