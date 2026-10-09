# 本地回填功能和模块回归命令

在 Windows PowerShell 中执行一条命令即可。脚本根据自身位置找到授权工作树，不依赖终端当前目录：

```powershell
python "C:\Users\徐乾妖\.codex\worktrees\72e5\705\scripts\market\test_kline_gap_local.py"
```

脚本复用本次已有测试，不启动业务 Spring Boot 应用、不连接现有业务数据库，不执行生产恢复、发布或部署。测试使用内存 H2、新建独立 MySQL 5.7 容器、localhost Vite 和隔离 HTTP/WebSocket 夹具。MySQL 使用随机数据库名、随机密码和 owner label，仅删除完整 ID 和 label 核对后的自有容器。

## 测试内容

- 回填功能：逐时间槽检查、原生周期独立、队列去重/容量/限速、insert-only、冲突校验、historyOnly、源修订/dirty 区间、提交后缓存与审计。
- 控盘和历史：运行中的任务、采样、hold、非空 publication、mixed minute、同周同月及七周期 OHLCV、权威报价和启动依据，SOURCE 投影、模拟冻结前缀、恢复/撤销与封存。
- 相关模块回归：报价执行与资金报价权威、换汇、手工订单价格/计算/历史图、资金写入检查点和锁顺序、租户/权限、WebSocket 行情、深度推送、首页图缓存、原行情采集调度。
- 客户端：PC/移动端中间插入、自动补齐、pending 历史分页、绘图/指标/缩放/视窗、HTTP/WS 和来源/品种/周期/租户/模拟/恢复/发布竞态、受保护稀疏历史。
- 后台：只读检查、安全补齐、保护原因、有/无提交权限、迟到响应；已有“定位最新”、选区、恢复工具回归。
- 前端工具和构建：三端相关单元测试、三个前端真实 build、后端定向回归及真实 package。

## 环境与报告

需要 Python 3、Maven/JDK、Node 24 或更新版本、npm、Docker、Chrome，以及已经安装的三个前端 node_modules 和 Playwright。默认使用本机 PATH 中的 Maven/Node/npm；若 PATH 缺少工具，尝试本机既有 `C:/Environment/Maven/3.9.9`、`C:/Environment/Node.js/24.18.0`。Playwright 默认使用 Codex 已安装 runtime，不下载新框架。

必要时设置 `KLINE_GAP_MAVEN`、`KLINE_GAP_NODE`、`KLINE_GAP_NPM`、`PLAYWRIGHT_PATH` 指向工具。依赖未安装时先在对应项目执行安装；脚本不会自动覆盖 lockfile 或停止占用依赖的现有进程。

每次在 `C:/workspace/fx/new/kline-local-qa-<新运行标识>/` 创建独立报告，不复用旧通过结果。报告包含：

- `README.md`、`local-summary.json`：逐项 PASS / FAIL / NOT_RUN 和本次范围。
- 各 `*.command.json`、`*.log`：实际命令、cwd、时间及退出码。
- `backend-junit/`、`physical-junit/`：本次实际测试报告；缺报告、旧报告或任何 skip 都不能算通过。
- `mysql-protected-before.json`、`mysql-protected-after.json`：安全补采前后保护事实/权威响应/启动依据/七周期 OHLCV；要求非空且字节相等。
- 浏览器结果、截图和 PC/移动端视窗证据。
- `mysql-cleanup.json`、`local-cleanup.json`：本次自有数据库、前端服务、临时 ASCII junction 的清理记录。

退出码 0 表示脚本列出的所有检查通过；退出码 1 表示失败或存在未运行项目。报告为 PASS 才能认定本次回填功能及覆盖模块的回归通过。脚本会继续其他独立检查，避免一个环境问题隐藏其余结果。

## 结论边界

这条命令检查列出的相关模块，不能证明项目所有模块都没有影响。浏览器 HTTP/WS 为隔离夹具，数据库写入边界为真实 MySQL；不将其称为生产端到端验收。旧 S2 固定身份 MySQL/Redis 全套、performance 专用 MySQL 方法、长时间 synthetic retention soak、真实供应商全历史和生产订单结算不在该命令内，不计作通过。

执行结束会清理该运行新建的资源，保留报告、构建产物和原有源码修改。不自动提交、推送或部署。

## 本次命令试跑

2026-10-09 的最终试跑报告在 `C:/workspace/fx/new/kline-local-qa-20261009-161741-79488f/README.md`，一键脚本退出码为 0，汇总为 PASS：后端 31 个类、176 项实际执行的检查无失败/错误/跳过，真实 MySQL 5.7 两项测试、三个前端单测组/构建以及四组浏览器脚本通过。源码与报告对应本次未提交工作区，不代表线上状态。

筹备试跑发现资金权威 H2 夹具缺少 `history_restore_revision` 字段、两个图表单测夹具缺少新增状态/API；仅同步三个测试夹具，保留原业务断言，没有为通过修改业务源码。早期失败报告保留在 `kline-local-qa-20261009-160350-245c05` 和 `kline-local-qa-20261009-161102-750f4d`，不计为通过。

脚本后续补上 Docker `rm -f -v` 的自有匿名卷清理；使用单独新建、未启动的容器真实验证完整 ID/owner 校验、容器移除以及匿名卷已不存在，记录在最终报告目录的 `volume-cleanup-verification.json`。该清理增强不改变功能测试路径。
