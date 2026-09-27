# 新会话执行流程及命令

本文件是供后续编码会话执行的操作清单。现在只交付命令包，业务实现、迁移脚本和新增测试尚未生成，不能跳过编码直接把以下命令当作功能安装器。

## A. 唯一工作目录与交付范围

工作目录固定为 `C:/workspace/fx/705`。先完整阅读同目录 `01-DESIGN.md`，按其定义实现，不另起设计讨论；仅真实缺失的期权估值/债务计提规则需要一个有针对性的问题。

已运行 Compose：`C:/workspace/fx/705/compose.yaml`，项目 `exchange-705`。部署服务仅 backend、mobile；前端本机入口 `http://localhost:17052/profile`。未经用户指定，不操作 `C:/workspace/fx/new` 或其他副本。

交付代码、增量 SQL/迁移与回退读取开关、必要测试、测试结果和本机部署状态。不得创建新的会话/工作树、提交或推送 Git、改行情源/交易规则/费率、真实下单、调账、发放奖励或安装新框架。

## B. 进入与只读检查

以下代码块适用于 PowerShell。每个外部命令检查退出码，不能因后续 echo 成功就忽略前面的失败。

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
git status --short
if ($LASTEXITCODE -ne 0) { throw '无法读取工作区状态' }
Get-Content -LiteralPath "$root\docs\asset-equity-execution-pack\01-DESIGN.md" -Raw
docker compose -f "$root\compose.yaml" ps
if ($LASTEXITCODE -ne 0) { throw '本机 Compose 状态检查失败' }
```

阅读 AGENTS.md 和已配置技能。保留既有未提交修改；禁止 git reset、git clean、git checkout 覆盖、stash 全项目等操作。不要输出 .env、JWT、连接口令或客户资料。每个文件修改前单独备份到本次 rollback 目录，不拷贝 node_modules、数据库卷和无关项目。

## C. 必要备份：发布前必须完成

开始改代码前确定同一个 `$stamp` 与 `$backup`，供本次全流程复用。

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $root "rollback\asset-equity-$stamp"
New-Item -ItemType Directory -Path $backup | Out-Null

# 保存正在运行的镜像标识并保留本地回退标签。
$oldBackend = docker inspect exchange-705-backend-1 --format '{{.Image}}'
if ($LASTEXITCODE -ne 0) { throw '无法获取原后端镜像' }
$oldMobile = docker inspect exchange-705-mobile-1 --format '{{.Image}}'
if ($LASTEXITCODE -ne 0) { throw '无法获取原移动端镜像' }
docker image tag $oldBackend "exchange-705-backend:equity-before-$stamp"
if ($LASTEXITCODE -ne 0) { throw '后端镜像备份失败' }
docker image tag $oldMobile "exchange-705-mobile:equity-before-$stamp"
if ($LASTEXITCODE -ne 0) { throw '移动端镜像备份失败' }
[ordered]@{ stamp=$stamp; backend=$oldBackend; mobile=$oldMobile } |
    ConvertTo-Json | Set-Content -LiteralPath "$backup\images.json" -Encoding utf8

# 旧历史记录备份，口令只在容器环境内使用，不回显。
$dumpName = "asset-snapshot-$stamp.sql"
docker compose -f "$root\compose.yaml" exec -T -e "EQUITY_DUMP_NAME=$dumpName" mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --quick --no-tablespaces --skip-lock-tables -u1090 1090 asset_snapshot > "/tmp/$EQUITY_DUMP_NAME"'
if ($LASTEXITCODE -ne 0) { throw '旧资产快照备份失败' }
docker compose -f "$root\compose.yaml" cp "mysql:/tmp/$dumpName" "$backup\$dumpName"
if ($LASTEXITCODE -ne 0) { throw '备份文件导出失败' }
if ((Get-Item -LiteralPath "$backup\$dumpName").Length -eq 0) { throw '快照备份为空' }
Get-FileHash -Algorithm SHA256 -LiteralPath "$backup\$dumpName" |
    Select-Object Hash,Path | ConvertTo-Json |
    Set-Content -LiteralPath "$backup\snapshot-sha256.json" -Encoding utf8
```

这不是全库恢复授权。此功能只新增历史结构，不应改动业务账户数据。若部署前检查发现新表已经存在，要把将被修改的历史表纳入备份并核对来源，不能覆盖另一个会话的数据。备份不上传第三方、不写入代码提交。

源码备份示例，按实际改动文件清单逐个执行：

```powershell
$relative = 'exchange-backend\src\main\java\com\gtcfesk\exchange\user\AssetHistoryService.java'
$destination = Join-Path $backup $relative
New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
Copy-Item -LiteralPath (Join-Path $root $relative) -Destination $destination
```

## D. 编码执行顺序与最小成果

1. 纯估值服务：账户、合约浮盈亏、产品估值、负债、费用、应收、报价与质量状态。不调用有副作用的交易/还款方法。
2. 测试验证后新增四级历史表、口径版本、baseline、job_state。实现需使用 MySQL 5.7 可执行的 SQL。
3. 分钟采集、当前桶草稿、小时/4 小时/日归集、水位、锁和补跑，保存负净权益并区分 NULL。
4. API 主周期表路由、首尾边界修正、实时点和净权益分量，保持认证与已有收益分子口径。
5. 修改现有移动端图表及顶部金额，保留原样式和交互；年图一天，支持负值、不可估值和数据版本切换。
6. 最低新增测试建议：`AssetEquityValuationTest`、`AssetHistoryRollupTest`、现有 `AssetHistoryTest` 扩展，以及前端新增 `assetEquityHistory.test.mjs`。命名可遵守仓库惯例，但实际测试覆盖不得缩水。
7. 为发布提供三个范围受限的脚本（此执行包尚未创建它们）：
   - `C:/workspace/fx/705/scripts/asset-equity/Test-MySql.ps1`：只在隔离临时 MySQL 5.7 环境测试 DDL/UPSERT/锁/数据版本/事务与聚合，不使用生产数据库做测试插入。新临时服务隐藏运行，完成后只清理该测试自建容器及卷。
   - `C:/workspace/fx/705/scripts/asset-equity/Migrate.ps1`：`-Mode Verify|Apply|Status`。默认 Verify 只读；Apply 仅应用已备份的增量 DDL及显式版本处理，禁止删业务表、覆盖旧 total 为净权益、执行不明来源 SQL。输出迁移编号及结果；不能只依赖 ddl-auto。
   - `C:/workspace/fx/705/scripts/asset-equity/Smoke.ps1`：只读验收接口结构、分表路由、认证隔离、任务新数据与净权益组成项。认证通过用户提供的本地环境变量令牌或已有授权测试会话，不输出令牌，不绕过认证、不自动创建真实账户/交易。
8. 补充接口所需的读取/采集切换配置并记录真实名字、默认值、启用步骤与回退步骤。本文不预造尚不存在的配置名；必须在交付运行命令前替换为实际实现值。

脚本是本功能的可重复验收/发布入口，不扩展为通用运维平台。所有新增文件只有与本功能直接相关才可创建。

## E. 测试与构建命令

实现完成后执行，不用全站升级依赖或重复安装已经存在的运行时。

```powershell
Set-Location -LiteralPath 'C:\workspace\fx\705'
node .\exchange-frontend\tests\assetPixelWindow.test.mjs
if ($LASTEXITCODE -ne 0) { throw '原图表数据测试失败' }
node .\exchange-frontend\tests\assetPixelScrub.test.mjs
if ($LASTEXITCODE -ne 0) { throw '原图表交互测试失败' }
node .\exchange-frontend\tests\assetEquityHistory.test.mjs
if ($LASTEXITCODE -ne 0) { throw '净权益前端测试失败' }

if (!(Test-Path -LiteralPath '.\scripts\asset-equity\Test-MySql.ps1')) {
    throw 'MySQL 5.7 集成测试入口尚未实现，不可跳过'
}
& .\scripts\asset-equity\Test-MySql.ps1
# 新脚本必须用终止错误报告失败，并检查自身所有外部命令的退出码。

New-Item -ItemType Directory -Force -Path '.\reports' | Out-Null
docker compose -f .\compose.yaml build backend mobile *> ".\reports\asset-equity-build-$stamp.log"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath ".\reports\asset-equity-build-$stamp.log" -Tail 35
    throw '构建或测试失败，禁止发布'
}
```

当前后端 Dockerfile 已执行 `mvn -B package`（包含后端测试），移动端构建执行 vue-tsc/Vite；不需要再重复完整构建一遍。检查日志中的测试数量、失败及跳过原因，不能只看镜像是否存在。若遇到无关分支的编译错误，报告确切文件和错误，不默默大规模修改其他功能。

## F. 发布前门槛

以下条件缺一不可：

- 合约、期限产品及负债估值规则已经明确；不存在把未知项按 0 处理后声称完整净权益的情况。
- MySQL 5.7 集成测试和前后端回归通过；备份可读且有哈希。
- 新旧口径已隔离，旧余额历史不会被错误接成净权益曲线；不可估值时不会显示账户归零。
- 已提供当前真实配置名和迁移切换步骤；旧 370 天清理已纳入安全停用计划。
- 若缺必要产品定价/逾期费用计提规则，仅保留开发成果并提出具体问题，不对真实账户启用不完整估值。

## G. 本机增量迁移与局部发布

仅在用户通过新会话指令明确授权本机部署、且 F 全部通过后执行。禁止外网生产发布或更新 admin/pc/mysql/redis 服务。

```powershell
& .\scripts\asset-equity\Migrate.ps1 -Mode Verify
& .\scripts\asset-equity\Migrate.ps1 -Mode Apply
& .\scripts\asset-equity\Migrate.ps1 -Mode Status

# 应在此之前完成实际采集/读取切换配置；不得假定不存在的环境变量已生效。
docker compose -f .\compose.yaml up -d --no-deps backend mobile
if ($LASTEXITCODE -ne 0) { throw '本机服务启动失败' }

$healthy = $false
for ($i = 0; $i -lt 24; $i++) {
    $state = docker inspect exchange-705-backend-1 --format '{{.State.Health.Status}}'
    if ($LASTEXITCODE -ne 0) { throw '无法读取后端状态' }
    if ($state -eq 'healthy') { $healthy = $true; break }
    if ($state -eq 'unhealthy') { break }
    Start-Sleep -Seconds 5
}
if (!$healthy) { throw '后端未通过健康检查，停止验收并执行已记录的应用回退方案' }

$page = Invoke-WebRequest -UseBasicParsing 'http://localhost:17052/profile'
if ($page.StatusCode -ne 200) { throw '移动端页面不可访问' }
& .\scripts\asset-equity\Smoke.ps1
```

HTTP 200 和 healthy 仅证明服务可达，不证明权益计算正确。Smoke 还必须验证新分钟记录的组件相加关系、对应归集表和估值版本、NULL 与负数处理；真实持仓账户验证必须经用户提供授权，不向真实用户发交易以制造测试数据。

## H. 回退限制

优先用实现时交付的读取开关回退查询，保留新分钟数据和新表。故障来源是新估值采集时仅关闭该采集并保持其已有数据，不删除/反向重算真实业务账户。

若必须切回备份镜像：先确认旧版本 prune 不会重新删除用户要求保留的历史、旧结构能读取兼容数据，且发布后的新记录仍可追平。旧镜像默认带 370 天清理，因此不能不检查就一键启动旧镜像。本包故意不提供会自动恢复整库或覆盖资金表的危险回滚命令。

数据库增量结构默认保留不回删；不通过导入旧资金表来“回滚页面功能”。所有回退只作用于本功能及这次修改文件，不能撤销其他会话已经提交的新工作。

## I. 最终验收报告

仅报告：

1. 实际修改文件与迁移编号、备份路径。
2. 净权益组成及各产品估值方法、负债确认规则。
3. 四张周期表、定时任务、年图一天是否已生效。
4. 运行过的测试、结果及未验证项。
5. 本机部署/健康检查状态及必要回退入口。

不要生成多余原型、重设计页面、研究无关平台、创建 PR、庆祝动画或自动化通知。不把参考方案描述成已部署功能，不隐瞒规则缺口。
