# 三会话合并、本地验证与发布阻断（2026-10-07）

## 完成范围

在指定现有 `3d6b/705` 工作树整合三份实现，没有创建或重置工作树：

- 当前控盘与渐进恢复：基线 `d74367b747491e9321db55afcb71c27599f9bbfb`，包括此前 42 项中断扩展修复。
- **修复后台列设置刷新丢失**：`5e1a/705`，基线同上；合并全部 47 个改动文件。
- **排查登录后弹出 Forbidden**：`e09b/705`，基线 `f45276472ef223b03b2b631196f11b8be50ebd90`；合并全部 10 个未提交改动文件。该旧基线已经是目标的祖先，因此不以整树复制倒退较新的已提交功能。

逐文件使用源 HEAD、目标当前内容、源当前内容三方合并，核对目标修改前 SHA 与预览 SHA。没有文本冲突残留；AiControl 的 8 个稳定列键与现有队列、应急 SOURCE、取消凭证、pending、14 种中断恢复 UI 验证共同保留。额外仅修复已被全套权限回归指出的两处客服权限指令，并增加 AST 检查。

原始三树改动、补丁、合并预览与目标改前件位于忽略目录 `rollback/combined-three-chat-20261007/`。47/10 个来源文件已再次核对，原来源工作树未被修改。当前应用改动已加入 `codex/three-chat-hot-release-20261007`；GitHub 分支提交不代表生产发布批准。

## 合并后的实际验证

- 后端控盘回归：169 项，168 通过、1 跳过。
- 两列设置 controller：7/7；合计上述后端 176 项，175 通过、1 跳过。
- 独占真实 MySQL 5.7：42/42，0 失败、0 错误、0 跳过；真实终点租约过期、连接/查询切断、COMMIT ACK 丢失注入、未知键取消/晚到接受、JVM halt、源价失效与租户隔离全部保留。
- 新正式 0702 收据：真实 MySQL 中最低应用 epoch=0603、activation=false；旧 0603 收据、旧取消凭证、引擎 trigger 哈希保持。
- 综合入口 client：5/5；该数含 PC/mobile 共用恢复检查，不能与下列 PC 检查当作不同场景重复相加。
- admin 全部 `.mjs`：45/45；账户表 CJS：6/6；PC 恢复检查：4/4。
- Chrome：控盘 14 项、权限 6 组、权限故障恢复 7 组、列设置 10 组、客服权限 7 组，全部通过。是真实 Vue/Chrome，但 API 由本地 fixture 拦截，不是生产身份或数据库验收。
- 类型检查、admin/control 构建通过。最后串行 `-DskipTests package` 成功；该打包命令不再次执行测试。
- 受控迁移离线合同：198/198。只证明代码合同，不替代真实审批或生产迁移。
- 完整公开单 schema 结构快照检查通过：113 表、1338 列、452 索引、188 外键、161 trigger、1 routine。原生阶段保全、独立单 schema 恢复/重启的具体结果见 `control0702-release-contract-20261007.md`；失败证据保留，不能以离线 SQL 单测替代应用验收。

原始证据：
- `reports/three-chat-20261007-control-01/`（backend/client/MySQL、列 controller、最终 package）。
- `reports/combined-three-chat-20261007-frontend/frontend-summary.json`（前端源码及产物逐文件 SHA、各阶段退出码、最终浏览器与早期失败证据）。
- `reports/combined-three-chat-20261007-verification.json`（合并应用源集合、685 个测试后编译 class、两 frontend 构建与结果）。
- `reports/control0702-offline-20261007.log` 与独占 `control0702-schema-*` 目录。

唯一 JUnit skip 为 `S1PairedProbeTest.measureActualFreezeAndHoldOnRestoredMillionSnapshot`，缺明确百万行全应用快照。本轮未完成 full production auth/JPA、持续负载/p99、真实供应商互联网断流、长期网络分区或生产完整启动/回滚。此前 full security fixture 的 DOMAIN_VERSION=NULL 启动失败不能冒充本轮已解决。

## 可复用命令

在同一个现有工作树使用已存在 ASCII junction；所有 Maven 命令必须串行，证据目录必须全新：

```powershell
$root = 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705'
$junction = 'C:/workspace/fx/new/control-timeout-20261006-worktree'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$root/scripts/market/Test-ControlTimeout.ps1" `
  -MavenRoot $junction -OutputDirectory "$root/reports/combined-new-run" -WithMysql -WithBrowser
mvn.cmd -B -f "$junction/exchange-backend/pom.xml" '-Dtest=AdminTablePreferenceTest,ControlTablePreferenceTest' test
Set-Location "$root/exchange-admin"
node --experimental-strip-types --test (Get-ChildItem tests/*.test.mjs | ForEach-Object FullName)
npm run build
npm run build:control
# 列设置 Chrome 须先另启专属 tablePreferences.fixture.mjs，按汇总指定 COLUMN_QA_URL。
Set-Location $root
python -B scripts/database/check_snapshot.py
python -B scripts/multitenant/isolation_gate.py --check --release
```

前端所有单独可复用 Chrome/Node 命令及本地 fixture 使用方式记录在 `frontend-summary.json`。最新 source release 命令预期仍 FAIL，不应当删除门禁使其变绿。

## 不可变本地候选与生产发布

新增 `scripts/market/build_combined_candidate.py`，复用已有 Docker/SHA helper；只接受已提交、干净工作树和真实验证文件。核对完整源集合、685 个测试后 class、全部打包业务资源、0702 epoch 与正式 SQL 哈希、两套完整前端产物，使用基础 image digest、唯一 SHA tag、OCI commit revision。复用已验证的完整热修回滚 image，不复用旧 admin/control 业务 bundle；不覆盖旧 tag/证据目录。

```powershell
python -B scripts/market/build_combined_candidate.py `
  --verification reports/combined-three-chat-20261007-verification.json `
  --output rollback/combined-three-chat-20261007-candidate `
  --admin-nginx rollback/combined-release-20261007-live-readonly/admin.nginx.private.conf `
  --control-nginx rollback/combined-release-20261007-live-readonly/control.nginx.private.conf
```

该命令只本地构建/归档，每种新 image 独占、无生产网络重建两次，核对 JAR/全部前端文件/真实运行 nginx 配置字节以及 Java/nginx syntax。允许的额外 nginx 基底文件只有 `50x.html`。成功后的 image ID、归档 SHA 与 6 次重建结果写入新的 `manifest.json`，始终 `UNRELEASED / deploymentReady=false`。制品持久性不代表完整 Spring Boot/生产部署验收。

最新线上只读审计（2026-10-07 03:04:24 +08）：
- 6 应用 healthy，但 main-api/admin/mobile/pc 实际运行 image 与 Compose 候选均漂移。
- 实际主 JAR SHA 为 `8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`；它是当前容器可写层热修，不能用原 image f30 重建回滚。
- 目标物理库仍为 0603，0701/0702 的 5 个新列和收据全部缺失；新版不能先发布。
- 来源发布门禁 32 个错误：28 项指纹/敏感 inventory/登记差异，另外 4 项真正批准/发布阻断；structuralErrors=[] 不代表批准已完成。
- 双签审批 policy/ledger 缺失；本轮未伪造、刷新批准指纹或使用未授权例外。
- admin/control 当前 nginx 字节相同但不同于 tracked 配置；候选保留只读捕获的当前配置与真实 `backend:8080` 上游，不猜换成另一个 upstream。

因此按原要求停止覆盖发布，未上传新 image、未线上迁移、未重建生产容器。只准备本地候选及只读回滚材料。Git commit/push 不解除这些阻断。

## 仅针对有变化的目标库

遵守最新范围限制：不做全实例、所有物理数据库或无变化库的全量备份迁移。先比较拟发布服务实际连接的目标物理库和对应 schema/SQL 版本；只有存在差异的目标库进入审定流程。本次新增 DDL 只有 `market_control_command` 两列、`market_control_flow` 三列，以及 `tenant_schema_version` 追加收据。用户数据、行情历史、trigger、其他库和其他服务不改。

113 表结构保全/空库往返是独占本地单 schema 验证，不是生产所有数据库备份。对确实需要升级的单个目标库仍须保留审定的受影响数据/metadata/trigger 备份与独立恢复证明，不能以“仅比较差异”删除失败闭合的备份门禁。

## 发布/回滚待实施步骤

1. 协调四个漂移候选和真实审批，另存批准的配置基线；原候选、其他服务均不覆盖。
2. 仅为有变化的指定目标库生成 plan、备份及独立恢复 proof、真实双签 ledger；按实际维护要求 `read_only=1` 并排空写会话。
3. 受控顺序执行 0701/0702，保留全部历史/取消凭证/旧 metadata/161 trigger；package-check 绑定不可变 JAR。
4. 基于批准基线仅替换需要更新的完整 backend/admin/control image IDs，未变化 mobile/pc 固定当前运行 IDs。不能用宽泛 compose up 顺带更新漂移版本。
5. 实际核对制品 SHA、完整应用 healthy、租户/鉴权、202 队列、未知键取消、SOURCE、断源拒绝交易、实际水位和预算日志；未完成项仍报告待实施。
6. 回滚前用正式业务 API 查询/取消/排空新队列，不能直接 SQL 改任务状态。backend 使用完整当前查询热修 JAR/image，admin/control 使用核实的原运行 image IDs。保留 additive 列、收据和历史，不 DROP/TRUNCATE/全库回灌；最低应用 epoch 不兼容则审定前滚。

远端完整热修回滚 image 已再次确认存在：`sha256:3e5fcaaec6e0fe317df8a404dea2139a5fec2ac8066f469f0ccac9889b0200de`。其完整制品重建验证沿用先前本地备份，不假称本轮在线业务回滚演练。全部私有配置、容器 inspect、日志与 image-only pins 留在忽略目录，不提交 GitHub。
