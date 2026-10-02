# 全分支源码合并记录

日期：2026-10-03（Asia/Singapore）。范围：exchange-705 当前仓库的全部本地分支、已刷新远端引用、注册工作树及原工作区最新未提交源码。不跨仓库强行合入旧 CBFI 工程、设计检材或运行数据。

## 统一结果

- 集成分支：`codex/consolidate-all-20261003`，验证后以 fast-forward 合入 `main`，保留原分支与工作树。
- 原 `main`：`29ba08a`；工作区源码快照：`6c5dd76`；阶段3/4/5整合：`f08405f`；旧还原分支历史整合：`7bd6216`。
- `codex/tenant-stage34-integration-20261003` 的 `4bc5a90` 已包含阶段3、阶段4及共同基线，采用真实 Git 合并保留父提交；更早构建修复、启动迁移及非租户发布分支均为祖先，无须重复 cherry-pick。
- 保留高级版全套页面、版本偏好、登录反馈与注册路由，以及管理员高级版入口开关。
- 所有注册工作树均检查了未提交修改。旧 mobile-transition 工作树移除路由事件的修改已在当前实现中体现；Yahoo 工作树批量报价、优先订阅和相关测试已被当前实现包含或由租户隔离版本承接，未用旧文件覆盖新实现。
- 源码之外的 `.env`、凭据、上传文件、数据库、依赖、构建输出及私有报告不纳入新提交。没有执行生产迁移、部署或推送。

## 冲突决策

共处理15个阶段整合冲突文件和1个历史分支 `.gitignore` 冲突：

1. `SystemConfigService` 保留最新高级版布尔开关校验；移动端 App、Profile、请求封装和 Vite 配置保留高级版、登录错误分类及本地代理支持。
2. `ChatRetentionService` 采用阶段整合的 Timestamp/LocalDateTime 严格类型检查与共享 UTC 微秒格式化，避免消息链时间串不一致。
3. `controlled_migration` 保留恢复批准绑定；`orphan_quarantine` 和 `private_files` 保留完整容器身份核验与独立恢复约束。
4. `mysql_migration` 同时保留最新工作区的旧触发器精确审核、活动字段完整匹配，以及阶段4新增 `require_fixture`；没有整文件二选一丢弃另一侧功能。
5. 表清单保留旧触发器哈希和已有引入表，并增加阶段4的迁移历史控制表；源码隔离清单保留高级版变更审核和阶段5审核结论，未解除生产门禁。
6. 阶段交接文档保留原证据口径，并链接本文。2024还原分支的旧 AGENTS 约束归档为 `docs/restore-2024/AGENTS-historical.md`，避免把旧工作区只读/调度限制重新激活为当前指令；其他历史交接资料保留。
7. `.gitignore` 合并两侧隐私排除项，补充 stage/control 构建输出排除，不删除本地输出。

## 验证发现与修复

- 补齐权限目录缺失的 `loan_review:controlled_exit`，与已有前后端受控还款入口一致；权限检查没有放宽。
- 更新已审核的表格清单，纳入总控聊天归档任务表；没有删除清单一致性断言。
- 更新倒计时静态/运行时测试以识别高级版纯展示计算，保留禁止时钟触发网络请求的检查。
- 页面上报测试允许唯一展示参数 `route.query.edition`，仍禁止整段 query/fullPath；原查询参数隐私及网络行为断言保留。
- 价格测试保留普通订单三位小数规则，同时实际执行 AI 页面按品种精度的显示函数，覆盖0、3、5、8位精度及无效输入，不把价格显示倒退为统一三位。

## 本轮实际验证

| 检查 | 结果 |
| --- | --- |
| 手机端 `npm run build` | PASS |
| PC端 `npm run build` | PASS |
| 管理端 `npm run build` | PASS |
| 总控 `npm run build:control` | PASS |
| 后端 `mvn -DskipTests package` | PASS |
| 手机端 `tests/*.test.mjs` | 44通过 |
| PC端 `tests/*.test.mjs` | 65通过 |
| 管理端 `tests/*.test.mjs` | 27通过；首轮6失败修复后全部通过 |
| 迁移工具 `unittest discover` | 49通过 |
| 后端12个相关测试类 | 68项，67通过，1跳过，0失败/错误 |
| 租户源码审核门禁 | PASS，406文件、1700处登记 |
| 冲突标记与已跟踪构建产物扫描 | 均为0 |

后端测试类：UiEditionSettingsTest、ChatRetentionTimestampTest、ChatArchiveTest、TenantSourceGateTest、TenantBoundaryTest、TenantForceVersionTest、ExchangeQuoteTest、YahooQuoteStreamTest、YahooConnectionTest、RegistrationFieldsTest、PermissionGrantRepairTest、ControlledExitIntegrationTest。

跳过项为 ChatArchiveTest 的真实 MySQL DATETIME JDBC 类型探针；本轮未提供专用 MySQL 夹具，没有把跳过写成通过。未重跑全部阶段1—5浏览器、真实资金联调和生产容量验收。构建通过不代表生产发布批准，`release_approved=false` 等既有门禁不变。

## 回滚与证据

- 合并前全部引用 bundle、工作区源码 ZIP、暂存/未暂存补丁及旧工作树补丁：`C:\workspace\fx\705\rollback\all-branches-20261003-044410`。
- 本轮验证日志：`C:\workspace\fx\705\rollback\consolidate-*.log`；初次失败日志保留。
- 不使用 reset/clean 回退。需要恢复时从上述 bundle/快照创建独立恢复分支，先比较再选取文件；不要覆盖当前数据库、上传文件或其他会话工作树。
