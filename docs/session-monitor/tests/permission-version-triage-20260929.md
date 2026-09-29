# 后台权限：运行版发现与最新源码归因表

核查时间：2026-09-29 04:56，Asia/Singapore。读取工作区 HEAD：`a3818cb44d854dff76e1a66770d9772d8c0f5d5b`；工作区存在未提交修改，下述“最新源码”指本轮读取的文件内容，不代表该 HEAD 的提交内容或任何已部署制品。

## 核查限制与证据等级

先读了 [原始权限审计报告](C:/workspace/fx/705/reports/permission-audit-20260929.md)，再核对源码、已有测试与日志。本轮没有访问运行数据库、创建账号、部署、修改业务代码、启动测试或浏览器。唯一新增产物为本文。

- **旧运行版已证实**：来自此前真实 HTTP、数据库结果和界面记录；本轮未重新验证当前运行状态。
- **源码有针对性处理且已有测试证据**：只证明相应输入和隔离测试路径，不能扩大为部署验收通过。
- **最新源码仍存在（静态确认）**：能从当前分支与调用顺序确定的问题；未动态复现，不冒充新增运行漏洞。
- **未验证**：缺少准确覆盖，或需要旧库迁移、真实部署、并发或有效业务操作验证。

已有后端摘要为 11 测试、0 失败、0 错误；其中 7 个继承自基础权限测试，新增 4 个，不能加总成 18 个唯一测试。前端已有日志为 3 个结构测试，检查 310 个控件及 127 个显式端点权限声明；不是浏览器行为测试。本轮没有重跑，也没有将既有日志当作全部当前文件的不可变构建证明。

## 逐条映射

| 原发现 | 旧运行版证据 | 最新源码处理点 | 已有测试证据及不足 | 归因结论 |
| --- | --- | --- | --- | --- |
| P1-01 菜单授权变成期限写权限 | 只读角色新增/编辑返回 200；补充删除实际记录返回 200，DB 数量变 0 | [AdminDurationController](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminDurationController.java):37/94/150 分别要求 create/edit/delete；[BackendAccess](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/config/BackendAccess.java):114–126 读取注解并校验；[AdminPermissionService](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminPermissionService.java):78–85 检查明确动作 | 基础测试 `roleReadOnlyCannotCreateEditOrDeleteAndGrantEnablesOnlyOneButton` 实际 POST/PUT/DELETE，先拒绝后逐项授予；`everyCatalogButtonRequiresExplicitGrantAndRevokesImmediately` 测动作显式授权及撤销。新增真实登录测试也验证只读 POST 拒绝 | **旧运行版缺陷；期限这条路径源码有针对性处理及已有回归。** 不能推定全部模块写入口均修复；部署后仍未验收 |
| P2-02 自定义角色无法创建管理员 | 6 个自定义角色通过创建角色接口后，创建管理员均 400，“无效的角色类型”；随后 DB 建账号才继续测试 | [AdminManagementController](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminManagementController.java):32–41 查角色表、active 状态、可授予权限；189 调用，192–200 保存账号 | 现有 `admin(...)` 辅助方法直接 repository 保存；新增 `login(...)` 同样先经 repository 准备账号。现有 POST `/admins` 是提权拒绝测试，**没有已执行成功创建自定义角色管理员的完整正向流程证据** | **旧运行版已证实；源码已移除该硬编码限制，但 API 正向流程未验证。** 不标“已修复验收” |
| P2-03 接受不存在菜单 ID | 负数 ID 授权返回 success=true；旧版无按钮，所谓 orphan 分支实为不存在 ID 兜底 | [AdminPermissionService](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminPermissionService.java):94–109 校验存在、active、父菜单；[AdminRoleService](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminRoleService.java):121–125 在删除旧授权前校验 | 新增 `assignedRolesProtectBoundDeletionAndUnknownOrOrphanGrants` 提交真实孤立按钮 ID 和不存在整数 ID，断言失败且原授权数仍为 1；该测试已有通过摘要。未测字符串、浮点、缺字段 | **不存在整数/真实孤立按钮路径已有针对性回归；输入类型变体仍有源码问题 C1。** 不能概括为授权输入全部修复 |
| P2-04 删除绑定账号的角色 | 对已有测试管理员绑定的零权限角色 DELETE 返回 success=true | [AdminRoleService](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminRoleService.java):82–95，在删除前扫描管理员 roleCode 绑定并拒绝 | 新增 `assignedRolesProtectBoundDeletionAndUnknownOrOrphanGrants` 断言删除失败、角色存在。没有并发“创建绑定与删除角色”测试 | **旧运行版缺陷；串行绑定删除路径源码与已有测试支持修复。** 并发约束未验证；旧授权停用后管理死锁见 C2 |
| P2-05 代理动作授权成功但本人备注操作 403 | 两个代理分配成功，但自己下级修改备注失败；当时 menu_action=0 | [AdminPermissionCatalog](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminPermissionCatalog.java):24–49 创建按钮与 MenuAction；[AdminUserController](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminUserController.java):378–433 事务内校验动作登记及所属菜单，未知动作抛错；589 明确 modify_remark。`AgentActionService.assignActions` 仍会忽略未登记动作，但当前源码唯一调用方先做登记校验 | `agentActionRevocationClearsOmittedActionsAndPreservesTenantScope` 验证动作实际存表及省略动作后清除，之后仅测试拒绝路径。**未在保留授权时，对本人下级执行备注修改并验证落库**；目录测试为隔离 H2，不是旧 MySQL 库升级 | **旧运行版已证实；源码补了目录和入口校验，完整正向操作与旧库升级未验证。** 不将推断的旧内部根因写成反编译事实 |
| P2-06 普通管理员全部菜单/越权页/写按钮可见 | 只读浏览器显示大量未授权导航、写按钮，角色页能进入但列表失败 | [Layout.vue](C:/workspace/fx/705/exchange-admin/src/views/Layout.vue):20–21/155–162 按快照构建分组并刷新；[router/index.ts](C:/workspace/fx/705/exchange-admin/src/router/index.ts):78–88 加载权限、检查路由，失败进入 forbidden；[access.ts](C:/workspace/fx/705/exchange-admin/src/utils/access.ts) 检查主体、菜单动作、指令显示及点击；[Durations.vue](C:/workspace/fx/705/exchange-admin/src/views/Durations.vue):5/42/43/122 分离动作；[main.ts](C:/workspace/fx/705/exchange-admin/src/main.ts):19 注册指令 | 已通过的仅是 `permissionCoverage.test.mjs` 结构检查。`permissions.browser.cjs` 包含模拟接口下只读/深链接等场景，但本报告引用的日志中没有本轮执行通过证明，且即使通过也不是实后端浏览器联调 | **旧运行版缺陷；源码有完整设计处理，但最新前端实际行为未验证。** 不以指令存在代替点击验证 |

## 版本差异的独立归因

| 差异 | 静态依据 | 不能得出的结论 |
| --- | --- | --- |
| 旧 `/api/admin/menus/current` 返回 404 | 最新 [AdminMenuController](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminMenuController.java):35 定义接口；基础/新增测试读过快照 | 不能由接口存在推断正在运行的 jar 已含它，也不能保证部署路由正确 |
| 旧 button、menu_action 均为 0 | Catalog 会建立按钮和动作；基础 `catalogIsIdempotentAndContainsTwoMenuLevels` 验证幂等及菜单层级 | 隔离 H2 初始化不证明旧数据库别名冲突、历史孤立授权、事务迁移全部正确 |
| 旧菜单平铺且代码含 legacy 名称 | Catalog 52–59 对下划线/连字符、announcements、system-config 做旧记录复用 | 如果新旧同名语义记录同时存在，当前优先查新代码的行为是否迁移全部旧引用，未验证 |

原审计的容器创建时间和镜像 ID 仅是当时快照。版本差异有接口、目录和行为多项证据，但**不能仅凭版本不同认定所有发现都已解决**。

## 最新源码仍存在：交原负责人最小修复/回归

### C1 / P2：权限 ID 类型被静默丢弃或截断，可能清空或误写授权

**静态确认位置：**

- [AdminRoleController.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminRoleController.java):135–147：只保留 `instanceof Number`，非数值元素不报错；对任意 Number 使用 `longValue()`。
- [AdminRoleService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminRoleService.java):122–125：空列表合法，随后删除全部旧授权。
- 同型路径：[AdminUserController.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminUserController.java):390–405，代理菜单非数值静默丢弃，随后删除动作并替换菜单。

最小复现建议（只供后续隔离测试，本轮未执行）：

1. 给普通测试角色分配一个 active 菜单；以 super 身份提交 `POST /api/admin/roles/{id}/menus`，请求体 `{"menuIds":["not-a-menu"]}`。
2. 当前代码将其转换成空列表，校验通过，删除旧授权并返回 success=true；期望应拒绝畸形输入且保留原授权。
3. 对代理请求 `{"menuIds":["not-a-menu"],"actions":{}}` 验证同型问题。
4. 对真实菜单整数 ID `N` 提交数值 `N+0.5`：`longValue()` 会截断到 N，而不是拒绝非整数。另测缺字段 `{}`；需要明确区分“显式清空 []”与“输入遗漏”。

影响是错误授权数据处理/意外撤权，不是已证明低权限用户绕过授权。现有权限校验仍限制调用者能授予哪些实际 ID。

建议最小处理：解析阶段拒绝非整数、非数值及缺失必填字段，保留显式空数组的清空语义；两个入口使用一致规则。不要把字符串静默过滤掉再执行替换。

### C2 / P2：已有授权停用后，角色无法经正常接口移除该授权

**静态确认位置：**

- [AdminRoleService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminRoleService.java):121 首先用 `validateGrant` 检查已有授权，而不是仅验证新集合。
- [AdminPermissionService.java](C:/workspace/fx/705/exchange-backend/src/main/java/com/gtcfesk/exchange/admin/AdminPermissionService.java):97–101 对不存在/已停用菜单直接抛异常；该检查早于 `require` 中的超级管理员放行。
- 同样影响角色更新 :70、角色删除 :91。`AdminPermissionCatalog.upsert` 不主动重置既有 status，因此不能假定重启会消除停用状态。

最小复现建议（隔离 repository fixture）：

1. 创建普通角色并授权 active 菜单 M。
2. 在隔离数据库把 M 状态设为 disabled，保留原绑定。
3. 以 super 对该角色提交 `{"menuIds":[]}`。
4. 当前代码在校验旧集合时抛“不能分配已停用的权限”，还没检查空新集合；结果无法移除旧停用授权。还可验证修改角色名称和删除无账号绑定角色受阻。

现有 `everyCatalogButtonRequiresExplicitGrantAndRevokesImmediately` 只验证停用后权限失效，再恢复 active；**没有在 disabled 状态测试角色授权修复**。

建议最小处理：区分“是否有权管理旧角色”与“新授权是否有效”；移除既有失效授权应可修复，但必须保留非超管不能管理超出自身权限角色的边界。交原负责人设计最小变更，本轮不改源码。

## 调度建议与待验证列表

1. C1、C2 派权限模块原负责人处理，先新增上述小型回归，不要求本轮执行。
2. P2-02 补真实 HTTP 创建角色、授权、创建管理员、密码登录的正向闭环，不能用 repository 建档代替。
3. P2-05 补代理有效授权期间本人下级备注写入成功、跨代理拒绝、同令牌撤销后拒绝；补旧库动作目录迁移。
4. P2-06 待其他验收释放浏览器后，验证最新构建+真实后端的只读按钮、深链接与失败关闭，不复用旧 UI 结果。
5. P1-01、P2-03 数值非法 ID、P2-04 串行绑定删除已有针对性源码回归，可作为后续发布回归基线，不直接宣布运行版修复。

## 已有证据文件

- [11 项后端摘要](C:/workspace/fx/705/reports/permission-password-integration-summary-20260929.txt)
- [后端完整日志](C:/workspace/fx/705/reports/permission-password-integration-20260929.log)
- [3 项前端结构日志](C:/workspace/fx/705/reports/permission-source-coverage-20260929.log)
- [基础权限测试](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/security/AdminPermissionIntegrationTest.java)
- [真实登录扩展测试](C:/workspace/fx/705/exchange-backend/src/test/java/com/gtcfesk/exchange/security/AdminPermissionLoginAuditTest.java)
- [此前运行矩阵](C:/workspace/fx/705/reports/permission-live-audit-20260929.json)
- [此前补充验证](C:/workspace/fx/705/reports/permission-live-supplement-20260929.json)

结论：6 条原发现不能统一归为“只需部署就全部解决”。3 条特定路径有源码回归支持，3 条仍缺完整正向/联调证据；授权输入解析和停用旧授权修复另有 2 个静态确认问题。所有部署后效果均未在本轮验证。
