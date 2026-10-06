param([string]$OutputDirectory, [switch]$WithMysql, [switch]$WithBrowser, [string]$MavenRoot)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ($MavenRoot) {
    $candidate = Get-Item -LiteralPath $MavenRoot
    $resolved = if ($candidate.LinkType) { [IO.Path]::GetFullPath([string]$candidate.Target) } else { $candidate.FullName }
    if ($resolved -ne [IO.Path]::GetFullPath($root)) { throw 'MavenRoot must point to this same existing working tree.' }
} else { $MavenRoot = $root }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $root ('reports\control-timeout-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Evidence directory already exists; choose a new directory.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$tests = 'LatestSourceLookupTest,S1HistoryTest,PriceControlTest,PriceControlV2Test,StabilizedControlPlanTest,TargetControlApiTest,ControlRecoveryFlowTest,ControlFlowMarketIntegrationTest,PersistentPriceControlTest,SimulationControlPathTest,S1PairedProbeTest,S1TransactionTest,MarketEngineFailureTest,MarketRuntimeCleanupTest,ControlRequestLoggingTest,BalancedControlPlanTest,HistoryOrderingTest,QuoteStateTest,ExecutionQuoteTest,TenantMarketIsolationTest'
$result = @()
Push-Location $MavenRoot
try {
    # Explicit isolated H2/mocked-source tests. Never use application datasource or live credentials.
    $started = [DateTimeOffset]::Now
    & mvn.cmd -B -f exchange-backend/pom.xml "-Dtest=$tests" test *> (Join-Path $OutputDirectory 'backend.log')
    $result += @{stage='backend'; exitCode=$LASTEXITCODE; startedAt=$started.ToString('o'); finishedAt=[DateTimeOffset]::Now.ToString('o')}
    $reports = Join-Path $OutputDirectory 'surefire'
    New-Item -ItemType Directory -Path $reports | Out-Null
    if (Test-Path 'exchange-backend/target/surefire-reports') {
        Get-ChildItem 'exchange-backend/target/surefire-reports' -File | Where-Object {$_.LastWriteTimeUtc -ge $started.UtcDateTime} | Copy-Item -Destination $reports
    }
    # Reuse this worktree's already installed TypeScript; do not require a hidden shell NODE_PATH.
    $previousNodePath = $env:NODE_PATH
    try {
        $env:NODE_PATH = (@((Join-Path $root 'exchange-admin/node_modules'), $previousNodePath) | Where-Object {$_}) -join [IO.Path]::PathSeparator
        & node --experimental-strip-types --test exchange-admin/tests/aiControlCommand.test.mjs exchange-pc/tests/controlRecovery.test.mjs *> (Join-Path $OutputDirectory 'client.log')
        $result += @{stage='client'; exitCode=$LASTEXITCODE; finishedAt=[DateTimeOffset]::Now.ToString('o')}
    } finally { $env:NODE_PATH = $previousNodePath }
    if ($WithBrowser) {
        & node scripts/market/run_control_recovery_browser.cjs *> (Join-Path $OutputDirectory 'browser.log')
        $result += @{stage='browser'; exitCode=$LASTEXITCODE; finishedAt=[DateTimeOffset]::Now.ToString('o'); productionAuthenticationAndMysqlAcceptance=$false}
    } else { $result += @{stage='browser'; skipped=$true; reason='Opt-in stage: pass -WithBrowser. Not counted as passed.'} }
    if ($WithMysql) {
        & python scripts/market/reproduce_control_lookup.py --output (Join-Path $OutputDirectory 'mysql') *> (Join-Path $OutputDirectory 'mysql.log')
        $result += @{stage='mysql-sql-mechanism'; exitCode=$LASTEXITCODE; finishedAt=[DateTimeOffset]::Now.ToString('o'); applicationAcceptance=$false}
        & python scripts/market/run_control_recovery_mysql.py --output (Join-Path $OutputDirectory 'mysql-application') --maven-root $MavenRoot *> (Join-Path $OutputDirectory 'mysql-application.log')
        $result += @{stage='mysql-application'; exitCode=$LASTEXITCODE; finishedAt=[DateTimeOffset]::Now.ToString('o'); applicationAcceptance=$true; productionSecurityAndLoadAcceptance=$false}
    }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8
    Get-Content -LiteralPath (Join-Path $OutputDirectory 'backend.log') -Tail 15
    Get-Content -LiteralPath (Join-Path $OutputDirectory 'client.log') -Tail 18
    if ($WithBrowser) { Get-Content -LiteralPath (Join-Path $OutputDirectory 'browser.log') -Tail 15 }
    if ($WithMysql) { Get-Content -LiteralPath (Join-Path $OutputDirectory 'mysql.log') -Tail 27; Get-Content -LiteralPath (Join-Path $OutputDirectory 'mysql-application.log') -Tail 27 }
    if (@($result | Where-Object {-not $_.skipped -and $_.exitCode -ne 0}).Count) { exit 1 }
} finally { Pop-Location }
