[CmdletBinding()]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..'),
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path

# This runner does not migrate, deploy, log in or submit orders.
# New test files are deliberately required: missing implementation cannot pass.
$required = @(
    'exchange-backend/pom.xml',
    'exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityRulesTest.java',
    'exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityLifecycleTest.java',
    'exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityCompatibilityTest.java',
    'scripts/crypto-quantity/test.mjs',
    'scripts/crypto-quantity/Test-Migration.ps1',
    'scripts/crypto-quantity/schema.sql',
    'scripts/crypto-quantity/preview.sql',
    'scripts/crypto-quantity/migrate.sql',
    'scripts/crypto-quantity/rollback.sql',
    'exchange-backend/src/test/java/com/gtcfesk/exchange/trade/CryptoQuantityPersistenceTest.java',
    'scripts/fx-standard/test.mjs',
    'scripts/all-instruments/audit.mjs',
    'exchange-admin/package.json',
    'exchange-pc/package.json',
    'exchange-frontend/package.json'
)
$missing = @()
foreach ($command in @('git','node','mvn.cmd','npm.cmd')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        $missing += "Missing command: $command"
    }
}
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf)) {
        $missing += "Missing file: $relative"
    }
}
if ($CheckOnly) {
    Write-Output "Workspace: $Root"
    if ($missing.Count) {
        $missing | ForEach-Object { Write-Output $_ }
        Write-Output 'NOT READY: implement the required tests first. No tests or migrations were run.'
        exit 2
    }
    Write-Output 'Prerequisite paths/tools exist. This does not confirm test coverage or passing results.'
    exit 0
}
if ($missing.Count) { throw ($missing -join [Environment]::NewLine) }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$report = Join-Path $Root "reports/crypto-quantity-$stamp"
New-Item -ItemType Directory -Path $report -ErrorAction Stop | Out-Null
$script:results = @()
function Invoke-Checked {
    param([string]$Name, [string]$Directory, [string]$Program, [string[]]$Arguments)
    Push-Location -LiteralPath $Directory
    try {
        $log = Join-Path $report "$Name.log"
        & $Program @Arguments 2>&1 | Tee-Object -FilePath $log
        $code = $LASTEXITCODE
        $script:results += [pscustomobject]@{ name=$Name; exitCode=$code; log=$log }
        $script:results | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath (Join-Path $report 'commands.json') -Encoding utf8
        if ($code -ne 0) { throw "$Name failed with exit code $code. See $log" }
    } finally { Pop-Location }
}

$tests = @(
    'CryptoQuantityRulesTest','CryptoQuantityLifecycleTest','CryptoQuantityCompatibilityTest','CryptoQuantityPersistenceTest',
    'AllInstrumentCalculationTest','ContractConversionTest','ContractEquityRateTest',
    'FxStandardContractTest','FeeCalculationAuditTest','QuoteCurrencyConversionTest',
    'MinimalFixRegressionTest','ManualOrderCalculationTest','ManualOrderGeneratorTest',
    'ManualOrderGenerationMatrixTest','AssetEquityValuationTest',
    'FiatDepositTest','FiatWithdrawTest','DepositOrderServiceTest'
) -join ','
Invoke-Checked -Name 'backend-focused' -Directory $Root -Program 'mvn.cmd' -Arguments @(
    '-B','-f','exchange-backend/pom.xml',"-Dtest=$tests",'test'
)
Invoke-Checked -Name 'crypto-frontend' -Directory $Root -Program 'node' -Arguments @('scripts/crypto-quantity/test.mjs')
Invoke-Checked -Name 'fx-regression' -Directory $Root -Program 'node' -Arguments @('scripts/fx-standard/test.mjs')
Invoke-Checked -Name 'all-instruments' -Directory $Root -Program 'node' -Arguments @('scripts/all-instruments/audit.mjs')
foreach ($app in @('exchange-admin','exchange-pc','exchange-frontend')) {
    Invoke-Checked -Name "$app-build" -Directory (Join-Path $Root $app) -Program 'npm.cmd' -Arguments @('run','build')
}
Invoke-Checked -Name 'diff-check' -Directory $Root -Program 'git' -Arguments @('diff','--check')
Write-Output "PASS: selected commands completed. Evidence: $report"
Write-Output 'Database migration, full backend suite and browser/business acceptance remain separate required gates.'
