# 2026-10-06 控盘卡住、恢复原始行情与本地复现

## 结论与线上恢复

本次已恢复租户 1、品种 61（USD/JPY，`JPY=X`）的实时原始行情。恢复后连续 3 次状态核对成功，另作约 30 秒的 10 次只读核对：全部 HTTP 200，`controlState=SOURCE`、`running=false`、`restoring=false`、`holding=false`、`enabled=false`、`offset=0`，显示价等于原始价。观察中原始价由 158.119 更新到 158.114，版本与提交时间继续增长，不是把旧价格静态填回页面。

只热修并重启当前主后端容器 `exchange-705-tenanted-main-api-1`。主后端及其他原有服务健康检查正常；重启以来的日志中没有新增 `ENGINE_FENCED` 或 `Persistent control sampling failed`。没有重启数据库、Redis、演示后端、前端或代理，没有执行数据库迁移、人工改订单/资金或批量回写历史。

恢复通过正式后台接口 `POST /api/admin/ai-control/61/manual`、参数 `{"enabled":false,"offset":0}` 完成。修复前一次调用耗时 41.595 秒、HTTP 500，随后只读核对仍卡在最后一秒；修复并重新核对状态后，一次恢复成功，耗时 0.661 秒、HTTP 200。这里的耗时为同一 SSH/curl 运维调用链的端到端计时，不是纯数据库或浏览器计时；没有盲目自动重放失败请求。

本次“恢复原始行情”指恢复当前实时 SOURCE 流。正式接口按已有配置保存任务历史，未删除已发布的控盘历史段，也未声称历史 K 线已全部还原。

## 已确认的主根因

线上任务 `3349a6d6-b084-4b97-aa51-70299cae622e` 的目标价 157.900、时长 25 秒、强度 2、随机波动开启。计划有 26 个点，任务已推进到 `sampled_until=1791295760370`，结束时间为 `1791295761370`，相差恰好 1 秒。显示价 157.919 是已提交的倒数第二个点，不是目标价计算停止。

调用链如下：

1. `PersistentPriceControl.advance` 采样到计划终点，准备提交完成状态。
2. `ControlHoldService.activate` 查询任务截止时刻的最新源行情，建立持有参考价。
3. `ControlHistoryStore.sourceEvents(predicate, true)` 同时查事件账本和兼容的旧 tick 账本。
4. 旧 tick 分支先执行 `NOT EXISTS`，排除已被事件账本镜像的 tick，再排序、取一条。
5. 大部分 tick 已被镜像，因此数据库为了找到一条“没有镜像的最新 tick”，反向扫描大量历史并反复执行相关子查询。tick 分支还出现 `Using filesort`。
6. `MarketRuntime.locked` 的真实写入租约只有 15,000 毫秒。线上该查询反复超过租约，持有记录更新被原有数据库触发器拒绝，抛出 `ENGINE_FENCED`。
7. 最终采样、任务完成、持有状态处于同一事务，整体回滚。下一轮从同一旧水位重试，再次回滚，于是长期剩余 1 秒。

线上 EXPLAIN 的事件和 tick 分支估算分别约 66 万、62 万行，并有相关子查询；运行快照中的查询持续 16–40 秒。数据库 CPU 曾达约 489%，当时未发现活动行锁等待。因此本次有直接证据的是慢查询造成写入租约失效，不是已证实的数据库死锁。前端 10 秒 Axios 期限会先表现为超时；单纯把它调大不能让过期写入事务提交。

手动恢复也需要推进/终止原任务，因此会进入同一完成路径。这解释了“任务卡住”和“恢复按钮请求也卡住”同时出现。配置偏移为 0 也不代表已经 SOURCE：持久化目标任务与手动偏移字段是不同状态来源。

## 最小修复与正确性

修改位置：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/exchange-backend/src/main/java/com/gtcfesk/exchange/market/ControlHistoryStore.java:559`。

- 仅最新值查询移除 tick 分支的反连接。
- 在固定租户、固定品种的最新值查询中，tick 主键 `(tenant_id,symbol_id,source_time)` 已保证源时间唯一，因此 tick 分支只需 `ORDER BY t.source_time DESC LIMIT 1`。
- 事件分支仍按 `source_time,received_at,event_sequence` 排序；外层仍按相同规则比较最多两条候选。完全镜像的 tick 序号为 0，匹配事件的序号为正，事件会胜出，不需要先搜索所有非镜像旧 tick。
- 非最新值的完整历史查询仍保留 `NOT EXISTS` 去重，没有更改时间截止条件、租户过滤或历史合并规则。
- 未扩大 15 秒租约、移除写入隔离触发器、增大前端超时，或直接修改任务状态绕过错误。

已检查全部调用方：生产代码的 latest 分支仅用于固定品种的持有激活；历史冻结使用非 latest 分支。修复不保证任意数据分布或其他请求永不超时，但消除了本次已确认的镜像账本退化路径。线上同一截止条件的优化 SQL 返回相同价格/源时间，实测 0.943 毫秒。

## 独立的回执协议缺口：仍需修复

页面把目标启动和渐进恢复都保存为待确认命令，并在超时后只查询 `/commands?requestKey=...`。但是后端：

- `/start` 已走持久化命令队列，返回 HTTP 202，可按原键查询。
- `/restore` 仍同步执行、返回 HTTP 200，不写 `market_control_command`。

因此渐进恢复丢失响应时，页面可能长期等待一个服务端根本没有创建的命令。截图请求键 `99a50e0c-530e-4ea7-91bf-ae54f38b61e9` 在本次核对中既无命令也无任务；它不是当前运行任务对应的原始启动键。尚未获得该浏览器保存的 action/payload，不能断言截图键一定来自渐进恢复。

此外，页面 `busy` 包含未确认命令，一键恢复原始行情按钮也因此被禁用，会放大故障观感。当前客户端测试证明超时/刷新不重复启动，但不能把“服务端无恢复回执”报告成已修好。

本次线上只发布查询热修，没有改恢复 API 协议或删除未知浏览器请求。后续应让恢复走已有持久化队列，或提供严格按原请求键核对的兼容查询；不能仅凭一次“命令不存在”就清掉待确认键并再发启动。若页面提示会话失效，请重新登录并核对 SOURCE，不要重复提交截图中的旧启动请求。未完成真实浏览器验收，本次线上恢复结论来自正式 API、数据库及日志。

## 本地测试与机制复现

完整执行入口：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/Test-ControlTimeout.ps1`。`-WithMysql` 同时执行后端、客户端与独立 MySQL 5.7 复现；不会连接线上应用数据库或读取线上凭据。

```powershell
$env:NODE_PATH = 'C:/workspace/fx/705/exchange-pc/node_modules'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:/workspace/fx/new/control-timeout-20261006-worktree/scripts/market/Test-ControlTimeout.ps1' -WithMysql
```

已存在的 ASCII junction `C:/workspace/fx/new/control-timeout-20261006-worktree` 指向当前工作树，不是另一份源码。直接从含中文的原路径编译时，当前 protoc 环境报路径乱码；客户端最初也缺少 TypeScript 包。失败日志保留，未报告为通过。最终用 ASCII 入口及已有安装的 TypeScript 运行，没有另装依赖。

最终结果：

- 后端共 103 项，102 项通过、1 项跳过、0 失败、0 错误。覆盖目标计划、API、动态源渐进恢复、断源/重启连续性、停止恢复、历史发布、事务及最新行情查询。
- 跳过项是 `S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot`，要求另外显式标识的百万行完整应用 MySQL 配对夹具；未启用，不能算通过。
- 客户端 5 项通过，包括管理端回执/超时不重发以及 PC、移动端恢复与缓存版本切换。
- 新增 `LatestSourceLookupTest`：原实现 2 项中 1 项失败；修复后全部通过。差分覆盖截止时间、同时间事件、晚到行情、legacy-only tick、替换 tick 与租户/品种过滤。原实现失败证据已保留。

独立 MySQL 复现脚本：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/scripts/market/reproduce_control_lookup.py`。新建本轮专属、无外部网络的容器，两张账本各 100,000 行完全镜像，原 SQL 与修复 SQL 查询同一个最新值：

- 原 SQL：183.009 毫秒，`Handler_read*` 合计 200,005 次。
- 修复 SQL：0.483 毫秒，`Handler_read*` 合计 6 次。
- 两者返回价格、源时间完全一致；原 tick 分支有 filesort 和相关子查询，修复后的 tick 分支没有。

另外直接复用了生产迁移中未更改的 `s2_control_hold_u` 触发器，进行最小 SQL 事务机制复现：保留 15 秒租约，显式注入 15.1 秒延迟；连续两次 `ENGINE_FENCED` 后，最后采样、完成状态和持有激活全部回滚，水位仍在终点前一秒。使用优化查询且不注入额外延迟时完成提交。

必须区分：十万行夹具自然复现的是退化扫描，单次约 183 毫秒，不是线上 16–40 秒整机负载；租约回滚测试使用明确的延迟注入，也不是全应用端到端复刻。它与线上真实堆栈、水位、SQL 计划和恢复前后结果共同支持根因结论。测试结束按完整容器 ID 和本轮 owner 标签验证后删除专属容器，不清理其他容器或卷。最初夹具的 AIO 启动失败也保留；仅对这个新夹具禁用 native AIO，未更改线上 MySQL 设置。

## 发布范围、备份与后续

实际热修只替换运行中 JAR 的一个 entry：`BOOT-INF/classes/com/gtcfesk/exchange/market/ControlHistoryStore.class`；其余 entry 已逐项比对相同。原运行版本与本地改动前该源码一致。当前容器 ID、环境、挂载和网络保持原样。

- 原 JAR SHA-256：`1b9e8fe8356d8dbd7a10643519093aba0568f8cd62a1a6fb3d01f9778f0e3f56`。
- 热修 JAR SHA-256：`8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`。
- 服务端原件：`/opt/exchange-705-backups/control-lookup-20261006T143735Z/original-app.jar`。
- 服务端热修材料：`/opt/exchange-705-releases/control-lookup-20261006T143735Z`。
- 已准备热修镜像：`exchange-705-backend:control-lookup-20261006t143735z`，尚未替换正式 Compose 的镜像选择。

**这是当前容器内单类热修，不是完整正式发布。容器普通重启保留热修，按原镜像重新创建容器则可能丢失。** 检查发现现存 Compose 指向另一个待发布镜像，与实际运行镜像不同，故没有覆盖候选发布配置。需将本工作树修复合入正式构建/发布，同时补齐渐进恢复回执协议；未提交或推送 Git。不能把这一限制隐藏为“永久修复完成”。

若热修出现回归，先核对当前容器、现有 JAR 哈希及备份哈希，再把备份复制到同一容器临时路径、原子替换 `/app/app.jar`，只重启主后端并验健康。回退无需恢复数据库，但会重新引入原慢查询缺陷；成功恢复后未执行回退。

证据目录：

- 全量测试：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-timeout-20261006-all/`，含新鲜 Surefire 报告、客户端日志、MySQL SQL 计划/计时、回滚错误及逐阶段退出码。
- 原实现失败与修复差分：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control-timeout-20261006-baseline-ascii/` 及本轮其他保留的测试目录。
- 恢复、部署和只读观察：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/rollback/control-timeout-20261006/`，包含备份、脱敏回执、单类差分与健康核对。目录中私有会话和容器配置不应分享或入库。

历史上每次超时是否同因仍须按具体接口和发生时间关联，不能由本次故障推断所有超时。当前实时恢复、已确认主根因及本地测试已经完成；正式发布持久化与恢复回执兼容仍是后续项。
