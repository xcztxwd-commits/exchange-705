# 多租户后续命令：2026-10-01，r3基线

配套交接：`C:\workspace\fx\705\docs\multitenant-handoff-20261001-r3.md`。

这些命令区分只读检查、创建新候选、便携单元测试和具备前置条件后的真实集成测试。不要将整页不加判断地执行。旧候选与证据不得改写；生产迁移/部署没有得到批准。

## 1. 立即执行：只读校验和盘点

在PowerShell 7中执行。Hash不匹配时记录变化并停止沿用旧验收；不要自动更新预期值。

```powershell
$ErrorActionPreference = 'Stop'
$Workspace = 'C:\workspace\fx\705'
$Evidence = 'C:\workspace\fx\new\mt705-cont-20261001-r1'
$Baseline = Join-Path $Evidence 's4i-owned-candidate'
$Expected = @{
  'C:\workspace\fx\705\docs\multitenant-next-progress-20261001-r3.md' = '5a30d4d59502b99b0be04ea89ddf10042133e97e8a3e6bed96dad51392ca5332'
  'C:\workspace\fx\705\docs\multitenant-requirement-matrix.json' = '89e0b9af48cb64f0895c54ba1eaa33c168453a2c4a3d3466b9ddbddd5b050c6f'
  'C:\workspace\fx\new\mt705-cont-20261001-r1\checkpoint-r3.json' = 'b1940ef18068b7307220f7edf11ea32443ea3fc26b4df29e410fa99f653995b2'
  'C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate-source-manifest.json' = 'cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed'
  'C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar' = '37e675bb2965db65e43da0c66f485890bff91b0d305a16fc7fbe97c0c2411758'
}
foreach ($Entry in $Expected.GetEnumerator()) {
  $Actual = (Get-FileHash -LiteralPath $Entry.Key -Algorithm SHA256).Hash
  if ($Actual -ne $Entry.Value) { throw "Baseline changed: $($Entry.Key)" }
  Write-Output "HASH_OK $($Entry.Key)"
}
Get-Content -LiteralPath 'C:\workspace\fx\705\docs\multitenant-handoff-20261001-r3.md'
Get-Content -LiteralPath (Join-Path $Evidence 'checkpoint-r3.json')
Get-Content -LiteralPath (Join-Path $Evidence 'checkpoint-r3-full-skip-cases.json')
git -C $Workspace rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Git HEAD' }
git -C $Workspace status --short
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect working tree' }
Get-Command python,mvn,git,robocopy -ErrorAction Stop | Select-Object Name,Source
Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in @(3306,33315,33317,33318,33319,33417,33418,33419,54069,56142,57226,65269) } |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

此步不连接数据库、不停进程、不改代码。端口盘点只用于识别现状；不能据端口号决定kill或复用。

## 2. 创建新ASCII运行目录与候选，不复制旧target或依赖目录

继续上一PowerShell会话；若会话重启，先重新定义并校验第1节的变量和基线。

```powershell
$RunId = 'mt705-next-20261001-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$RunRoot = Join-Path 'C:\workspace\fx\new' $RunId
$Candidate = Join-Path $RunRoot 'candidate'
if (Test-Path -LiteralPath $RunRoot) { throw 'New run directory already exists' }
New-Item -ItemType Directory -Path $RunRoot,$Candidate -ErrorAction Stop | Out-Null
# No /MIR or /PURGE: copy to a new directory only, without deleting anything.
robocopy $Baseline $Candidate /E /XJ /XD target node_modules .git __pycache__ /R:0 /W:0 /NFL /NDL /NP "/LOG:$(Join-Path $RunRoot 'candidate-copy.log')"
$CopyExit = $LASTEXITCODE
if ($CopyExit -ge 8) { throw "Candidate copy failed: $CopyExit" }
$Manifest = Get-Content -LiteralPath (Join-Path $Evidence 's4i-owned-candidate-source-manifest.json') -Raw | ConvertFrom-Json -AsHashtable
if ($Manifest.Count -ne 2402) { throw 'Unexpected baseline manifest count' }
foreach ($Entry in $Manifest.GetEnumerator()) {
  $Path = [IO.Path]::GetFullPath((Join-Path $Candidate $Entry.Key))
  if (-not $Path.StartsWith($Candidate + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path outside new candidate' }
  if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Entry.Value) { throw "Source copy mismatch: $($Entry.Key)" }
}
Copy-Item -LiteralPath (Join-Path $Evidence 's4i-owned-candidate-source-manifest.json') -Destination (Join-Path $RunRoot 'baseline-source-manifest.json')
@{
  run_id=$RunId; baseline=$Baseline; candidate=$Candidate;
  source_manifest_sha256='cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed';
  created_utc=[DateTimeOffset]::UtcNow.ToString('o'); production_deployed=$false
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunRoot 'start.json') -Encoding utf8NoBOM
Write-Output "RUN_ROOT=$RunRoot"
```

这只是从冻结r3派生的新候选，不表示已合并共享工作区。前端依赖应核对lockfile和已有实际来源后复用或在新候选中安装；不得修改旧候选的依赖、源码或输出。

## 3. Source/release门禁观察

```powershell
$Gate = Join-Path $Candidate 'scripts\multitenant\isolation_gate.py'
python -B $Gate --check *> (Join-Path $RunRoot 'candidate-source-gate.log')
$SourceExit = $LASTEXITCODE
python -B $Gate --check --release *> (Join-Path $RunRoot 'candidate-release-gate.log')
$ReleaseExit = $LASTEXITCODE
python -B 'C:\workspace\fx\705\scripts\multitenant\isolation_gate.py' --check *> (Join-Path $RunRoot 'workspace-source-gate.log')
$WorkspaceExit = $LASTEXITCODE
@{ candidate_source=$SourceExit; candidate_release=$ReleaseExit; workspace_source=$WorkspaceExit } |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunRoot 'gate-exit-codes.json') -Encoding utf8NoBOM
Get-Content -LiteralPath (Join-Path $RunRoot 'candidate-release-gate.log')
if ($SourceExit -ne 0) { throw 'Candidate source review gate failed; inspect before building' }
```

r3候选release预期退出1，代表四项发布阻塞，不是允许忽略的发布结果。共享工作区门禁另行记录，不覆盖候选结果。不要运行 `C:\workspace\fx\705\scripts\multitenant\build_manifest.py` 来重生成旧表清单/DDL；它是有写入的历史authoring工具，不是只读检查或迁移入口。

## 4. 可先执行的便携单元测试

这些测试使用临时目录和合成数据，不是MySQL/Redis或浏览器验收。旧r3为26项通过，新运行以真实结果为准。

```powershell
Push-Location (Join-Path $Candidate 'scripts\multitenant')
try {
  python -B -m unittest -v test_retention_archive test_controlled_migration *> (Join-Path $RunRoot 'python-unit.log')
  $PythonExit = $LASTEXITCODE
  Get-Content -LiteralPath (Join-Path $RunRoot 'python-unit.log') -Tail 12
  if ($PythonExit -ne 0) { throw "Python tests failed: $PythonExit" }
} finally { Pop-Location }
```

## 5. 实际夹具前置，不满足时不能执行集成测试

本节需要新会话核验和建立本轮独立资源，不提供会误用旧实例的一键启动命令：

1. 核对DedicatedMysqlFixture实际允许的端口，再选择当前空闲、可识别的loopback测试实例；新datadir、新UUID、新数据库，不初始化旧目录或复用3306/33315。
2. 新运行的最新全备份必须在不同UUID/datadir独立恢复，完整原列、触发器SQL_MODE/ACTION_ORDER、routine/event一致；证明绑定实际物理目标和dump hash。
3. 为deposit、manual、cache和manual-control分别写本轮受限fixture配置，符合测试读取的真实结构；不要把密码写进这份文档或消息。
4. 实际Redis7容器名字须匹配 `ahc-[a-f0-9]+-redis`，核对身份、label、镜像和端口后，才允许本轮pause/unpause。
5. 核验SMS本地sink及实际Redis安全测试环境，保持外部短信、邮件和付款关闭。不得为“全绿”mock掉实际限流器。
6. 预先准备CUA、当前Vue/Vite harness及收据流程；测试开始后动态读取本次browser-request，全部十项在同一API/challenge六分钟窗口内完成。
7. 将前置核验写为本轮 `$RunRoot\fixture-ready.json`，包含实际配置路径及证明的hash/物理身份，不含认证值。下方脚本只是消费准备记录，记录本身不能代替Java守卫的实际验证。

`fixture-ready.json`需要的键：`deposit_fixture`、`manual_fixture`、`cache_fixture`、`manual_control_fixture`、`redis_port`、`redis_container`。路径必须属于本轮目录；文件内容由本轮实际准备过程产生，不能复制上轮配置冒充新证明。

## 6. C12和九类必测：前置完成后执行

测试命令会等待浏览器操作。应通过现有exec会话管理保留进程，并及时用CUA完成操作；不能阻塞六分钟后才打开浏览器。后台Windows辅助进程使用Hidden窗口。

```powershell
$ReadyPath = Join-Path $RunRoot 'fixture-ready.json'
if (-not (Test-Path -LiteralPath $ReadyPath)) { throw 'Identified/restored fixture readiness missing' }
$Ready = Get-Content -LiteralPath $ReadyPath -Raw | ConvertFrom-Json
foreach ($Key in @('deposit_fixture','manual_fixture','cache_fixture','manual_control_fixture')) {
  $File = [IO.Path]::GetFullPath([string]$Ready.$Key)
  if (-not $File.StartsWith($RunRoot + '\',[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $File)) {
    throw "Missing or non-owned fixture file: $Key"
  }
}
$RedisPort = [int]$Ready.redis_port
if ($RedisPort -lt 1024 -or $RedisPort -gt 65535) { throw 'Invalid owned Redis port' }
if ([string]$Ready.redis_container -notmatch '^ahc-[a-f0-9]+-redis$') { throw 'Invalid owned Redis container' }
$CacheReport = Join-Path $RunRoot 'cache-evidence'
New-Item -ItemType Directory -Path $CacheReport -ErrorAction Stop | Out-Null
$BrowserReceipt = Join-Path $CacheReport 'browser-results.json'
if (Test-Path -LiteralPath $BrowserReceipt) { throw 'Browser receipt must be fresh for this service' }
$env:CACHE_TEST_REDIS_PORT = [string]$RedisPort
$env:CACHE_TEST_REDIS_CONTAINER = [string]$Ready.redis_container
$env:CACHE_TEST_REPORT = $CacheReport
$env:CACHE_TEST_PERFORMANCE = 'false'
$Required = 'DepositOrderMySqlIT,ManualOrderMySqlIT,ManualControlJdbcMySqlTest,AssetHistoryCacheIT,ChatArchiveTest,DedicatedMysqlFixtureTest,SchemaPackageGuardTest,SchemaPackageGuardMySqlIT,AssetEquityBucketSqlTest'
$FixtureArgs = @(
  "-Ddeposit.mysql.fixture=$($Ready.deposit_fixture)",
  "-Dmanual.mysql.fixture=$($Ready.manual_fixture)",
  "-Dcache.mysql.fixture=$($Ready.cache_fixture)",
  "-Dmanual.control.fixture=$($Ready.manual_control_fixture)",
  "-Dcache.browser.proof=$BrowserReceipt"
)
$MavenArgs = @('test',"-Dtest=$Required") + $FixtureArgs
$MandatoryExit = 0
try {
  & (Join-Path $Candidate 'scripts\multitenant\verify-build.ps1') -MavenArguments $MavenArgs *> (Join-Path $RunRoot 'mandatory.log')
} catch {
  $MandatoryExit = 1
  $_ | Out-File -LiteralPath (Join-Path $RunRoot 'mandatory.log') -Append -Encoding utf8
}
$LastNativeExit = $LASTEXITCODE
foreach ($ReportName in @('surefire-reports','failsafe-reports')) {
  $Reports = Join-Path $Candidate "exchange-backend\target\$ReportName"
  if (Test-Path -LiteralPath $Reports) {
    Copy-Item -LiteralPath $Reports -Destination (Join-Path $RunRoot "mandatory-$ReportName") -Recurse -ErrorAction Stop
  }
}
@{ wrapper_exit=$MandatoryExit; last_native_exit=$LastNativeExit } |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunRoot 'mandatory-exit.json') -Encoding utf8NoBOM
if ($MandatoryExit -ne 0) { throw 'Mandatory test wrapper failed; original log and XML retained' }
```

PowerShell包装器非零会throw，应在调用该命令的exec记录实际退出码和日志，不包装为成功。收据只在真实十项操作完成后写入；真实API/challenge来自本次运行，不预填、不使用旧PARTIAL记录。

如果本次准备/尝试失败，后续尝试新建不同输出目录和候选，保留原日志/XML和未完成收据。不能重跑覆盖本轮失败文件。

## 7. 同一候选完整verify及其余opt-in

只有实际夹具和受限sink环境仍有效时运行。独立保存每次XML/日志；完整verify退出0不能遮盖另一必测退出1。

```powershell
$FullCacheReport = Join-Path $RunRoot 'full-cache-evidence'
New-Item -ItemType Directory -Path $FullCacheReport -ErrorAction Stop | Out-Null
$FullBrowserReceipt = Join-Path $FullCacheReport 'browser-results.json'
$env:CACHE_TEST_REPORT = $FullCacheReport
$FullArgs = @(
  'verify',
  "-Ddeposit.mysql.fixture=$($Ready.deposit_fixture)",
  "-Dmanual.mysql.fixture=$($Ready.manual_fixture)",
  "-Dcache.mysql.fixture=$($Ready.cache_fixture)",
  "-Dmanual.control.fixture=$($Ready.manual_control_fixture)",
  "-Dcache.browser.proof=$FullBrowserReceipt"
)
$FullExit = 0
try {
  & (Join-Path $Candidate 'scripts\multitenant\verify-build.ps1') -MavenArguments $FullArgs *> (Join-Path $RunRoot 'full-verify.log')
} catch {
  $FullExit = 1
  $_ | Out-File -LiteralPath (Join-Path $RunRoot 'full-verify.log') -Append -Encoding utf8
}
$LastNativeExit = $LASTEXITCODE
foreach ($ReportName in @('surefire-reports','failsafe-reports')) {
  $Reports = Join-Path $Candidate "exchange-backend\target\$ReportName"
  if (Test-Path -LiteralPath $Reports) {
    Copy-Item -LiteralPath $Reports -Destination (Join-Path $RunRoot "full-$ReportName") -Recurse -ErrorAction Stop
  }
}
@{ wrapper_exit=$FullExit; last_native_exit=$LastNativeExit } |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunRoot 'full-verify-exit.json') -Encoding utf8NoBOM
if ($FullExit -ne 0) { throw 'Full verification wrapper failed; original log and XML retained' }
```

其余25项逐项读取测试真实property/fixture前置后补命令，不以猜测参数或缺省tenant运行。完整verify可能需要新的cache报告/服务收据目录；先给每次运行分配唯一输出并重新绑定本次API/challenge，不能沿用第6节收据。

## 8. 迁移后续入口与边界

先只看帮助和代码，不对未识别目标执行plan/apply/resume：

```powershell
python -B (Join-Path $Candidate 'scripts\multitenant\controlled_migration.py') --help
```

正式入口真实支持：`plan`、`verify-backup`、`apply`、`resume`、`package-check`；参数包含物理container/database、plan/proof/output、独立restore目标、approval/ledger和实际artifact。这里不填生产目标，也不生成假生产批准。

原生迁移参考：`C:\workspace\fx\new\mt705-cont-20261001-r1\rehearse_controlled_native.py` 和 `C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-controlled-native-result.json`。先审查并派生绑定新路径的运行脚本，不直接重跑历史脚本覆盖旧计划/备份/收据。

旧 `C:\workspace\fx\705\scripts\multitenant\mysql_migration.py` 的非test直接迁移保持拒绝；orphan/private_files工具原fixture限制保持不变。先实现正式批准范围和旧凭据隔离/不确定DDL处置，再讨论正式目标。没有真实停写、最新独立恢复、真实批准和release通过，不迁移或部署生产。

## 9. 给新会话的执行命令（自然语言工单）

> 在项目 C:\workspace\fx\705 继续多租户，不重新设计。先完整读取 C:\workspace\fx\705\docs\multitenant-handoff-20261001-r3.md 和 C:\workspace\fx\705\docs\multitenant-next-commands-20261001-r3.md，核验r3 checkpoint、报告、矩阵、冻结候选及实际jar hash。直接执行只读前检，建立新的唯一ASCII候选/证据目录并运行便携单元测试。优先准备真实MySQL/Redis/Auth/Vue/CUA链，完成C12的十项同一活跃服务证据并重跑九类必测，再逐项补25个未闭环测试、完整资金/模拟/任务/运营链、正式孤儿/文件迁移和旧凭据/不确定DDL处置，审查并发源码后重新冻结验收。保留全部失败和增量，分别报告实现、限定验证、外部阻塞和未部署。只在本轮可丢弃隔离夹具执行受控测试，不动3306/33315等其他写者，不覆盖旧候选或备份，不真实付款/外发，不开启留存删除，不伪造PASS、批准或release。每阶段留下命令/退出码/XML/截图/hash/备份恢复和新版进度checkpoint；必要外部条件不齐时记录并继续能独立完成的部分，不把项目宣称完成。

当前:多租户后续命令 / 前检和执行顺序已给出，真实夹具需重新绑定 / 新会话直接从第1节开始