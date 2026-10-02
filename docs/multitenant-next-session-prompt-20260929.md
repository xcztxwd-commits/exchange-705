# 可直接复制到新会话的执行指令

请在 `C:\workspace\fx\705` 接续完成多租户平台，不要从零重写，不要只给计划。先核查当前源码和以下设计，再按阶段实际实现、复测、留证。使用新会话当前配置的模型，不被历史文档的模型/推理强度要求覆盖。默认简体中文，按适用AGENTS.md读取并应用ponytail full及caveman full。

## 必读顺序

1. `C:\workspace\fx\705\docs\multitenant-next-gap-analysis-20260929.md`
2. `C:\workspace\fx\705\docs\multitenant-next-implementation-plan-20260929.md`
3. `C:\workspace\fx\705\docs\multi-tenant-platform-design-20260929.md`
4. `C:\workspace\fx\705\docs\multi-tenant-execution-order-20260929.md`（功能要求继续有效；旧授权不能替代当前工具要求和正式发布闸门）
5. `C:\workspace\fx\705\docs\multitenant-astra-verification-20260929.md` 及同名JSON
6. `C:\workspace\fx\705\docs\multitenant-identity-control-implementation-20260929.md`
7. `C:\workspace\fx\705\docs\multitenant-funds-outbound-readiness-20260929.md`
8. `C:\workspace\fx\705\docs\multitenant-migration-runbook-20260929.md`、`C:\workspace\fx\705\docs\multitenant-private-files-20260929.md`、`C:\workspace\fx\705\docs\multitenant-orphan-quarantine-20260929.md`
9. `C:\workspace\fx\705\docs\trade-kyc-switch-20260929.md` 和 `C:\workspace\fx\705\docs\activity-template-editor.md`；这两项在上次全量之后又有改动，不能忽略。
10. 当前源码登记、表清单、测试脚本和原18类测试要求。归档的18类指令位于 `C:\Users\徐乾妖\AppData\Local\Temp\codex-handoff-01a0eba5-20260929-155929\TEST-ORDER.md`；若临时文件已不存在，使用下一阶段设计中的T01至T18恢复矩阵，不凭空声称读过。

## 已知基线，必须重新验证

- 工作区大量未提交变更，HEAD `668edcbc72ebda33d0e50172d119bc92ccebb479`不代表完整版本。禁止reset/clean/整目录覆盖、盲恢复或争抢其他会话target。
- 历史隔离全量2393项：2141通过、8 failures、213 errors、31 skipped。之后已有14个既有后端src变化、7个新增src，以上不是最新代码通过证明。
- 最近源码门禁失败20 issues；release失败24 issues（含既存4个发布阻断）。ActivityMaterial新表未纳入清单，不能盲刷登记。
- ORM `forceVersionIncrement`已经修复且MySQL七项通过；计算1061项和定向52项是历史已通过范围。不要重复把它们称为完全没实现，也不要扩大为当前全功能通过。
- 表格偏好仍在BackendAccounts.vue失败。独立总控和普通后台身份不同，不能直接替换标签共用普通Pinia/token。
- 当前详细证据：`C:\workspace\fx\new\mt705-next-design-20260929`；上一轮原始证据：`C:\Windows\Temp\mt705-astra-20260929`。读取凭据只限实际测试所需，禁止回显或复制到报告。

## 工作范围与边界

本会话先在隔离本地环境实现和验收全部已确认需求。允许为此修改源码/测试/设计，使用可丢弃测试账号、数据库、文件根目录和测试服务；数据库写入前识别目标、备份并验证独立恢复。不要根据旧文档自动操作生产数据，不真实付款、不向真实用户外发邮件短信，不启用真实聊天删除。

保留已有他人改动；修改已有文件前备份并原子写入。ASCII路径编译Java，不在Windows Temp编译。源/测试/DDL/前端lockfile及产物逐轮hash，原始XML只汇总本轮输出。适用工具限制优先，不绕过证书或权限边界。

不要创建重复新会话、向旧会话派任务或擅自启动多个代理。确实需要额外协作时先获得明确授权；本任务可串行执行。

## 执行任务

按设计S0至S7推进，并维护G01至G12、C01至C12、T01至T18的机器可读矩阵。每条需求都需实现位置与验收证据，不能把本提示当作穷尽全部接口的清单。

1. 冻结基线、确认写者/端口/隔离目标，建立需求矩阵并实际重跑门禁。
2. 审核新增活动素材、动作、KYC；补表清单、约束和新旧库迁移路径，再实审更新登记。
3. 处理KYC与存量退出规则：保留当前安全检查；若历史确认无法确定普通未实名用户退出策略，只问这一个有实质影响的决策，同时继续独立工作。不得用删KYC/改旧断言来制造通过。
4. 实现双身份表格偏好、域名prepare/verify/activate、客服时间筛选、有界大会话导出和留存推进、隔离恢复、可见异常处理。
5. 完成新租户开通配置流程。明确短信是否含实际发送及供应商；没有外部参数先完成本地sink和契约，真实供应商标blocked，不冒充已验证。
6. 修剩余测试夹具及新规则测试，完整运行后端test/verify；检查IT、MySQL opt-in和skip，逐项运行必测集成。不能跳过失败、删断言或默认租户兜底。
7. 冻结并部署本轮jar、四端资源和DDL到隔离真实/模拟实例，通过实际代理完成API及Chrome真实交互，包括移动视口、多标签、资金/审计、文件、任务和故障恢复。旧jar或mock浏览器不能替代。
8. 有界容量递增至约100并发，记录尾延迟/错误率/资源/资金不变量；先确定容量预算和停止阈值。任何越权/资金不变量失败立即停压。
9. 实现受控正式迁移编排，但生产apply必须等明确目标、停写、最新计划、备份恢复、实际批准和全部必要验收齐备。保留release blocker，不能自动改批准字段。

## 不可改变的核心要求

共享数据库tenant_id强制隔离；未知/缺失租户拒绝；前台域名一租户一个；后台名称全局唯一；总控独立身份、一键进入具有目标租户业务写权限但不跨租户；不创建隐藏员工、不伪造操作者；资金流水与审计真实；模块关闭不丢弃存量责任；safe-v1不复制业务数据秘密；客服默认清理关闭；真实和模拟隔离；新写入后不能覆盖恢复旧备份丢增量。

## 完成与报告

每阶段交付实际实现差异、命令/退出码、源码hash、用例结果、数据不变量、原始证据和可重跑入口。状态必须区分代码缺口、夹具缺陷、规则决策、外部条件、未验收、未部署。

只有全部必需功能和业务链有同版本验收证据，才能写“隔离验收通过”。只要必需fail/blocked/not_run存在，就不得说“全部完成，可以正式使用”。生产未部署必须明确写未部署。

现在从S0开始实际执行，不复述整份计划，也不要就已经明确的普通本地操作反复请示。
