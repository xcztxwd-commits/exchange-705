param([string]$ReportDir, [switch]$LiveApi)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$ReportDir) { $ReportDir = Join-Path $root ('reports/manual-generation-full-test/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $ReportDir | Out-Null
$ReportDir = (Resolve-Path $ReportDir).Path
$oldApi = $env:VITE_API_BASE_URL
Push-Location $root
try {
    $tests = 'ManualOrderGenerationMatrixTest,ManualOrderGeneratorTest,ManualOrderCalculationTest,ManualOrderMinutesTest,YahooHistoryCalendarTest,AssetEquityValuationTest,AssetHistoryRollupTest,AssetHistoryTest,AssetEquityScheduleTest,AssetEquityCarryTest'
    & mvn -B -f exchange-backend/pom.xml "-Dtest=$tests" "-Dmanual.reportDir=$ReportDir" test *> "$ReportDir/final-unit-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'Unit regression failed; see final-unit-tests.log' }
    foreach ($class in $tests.Split(',')) {
        $file = Get-ChildItem exchange-backend/target/surefire-reports/TEST-*.$class.xml
        [xml]$xml = Get-Content -Raw $file.FullName
        if ([int]$xml.testsuite.tests -le 0 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) { throw "Invalid discovered tests: $class" }
    }
    & "$PSScriptRoot/Test-MySql.ps1" -NormalRules *> "$ReportDir/mysql-final.log"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL regression failed' }
    Copy-Item exchange-backend/target/manual-generation-matrix/service-matrix-128.csv $ReportDir
    foreach ($test in @('exchange-admin/tests/manualOrderGenerationMatrix.test.mjs','exchange-admin/tests/manualOrderLifecycle.test.mjs','exchange-admin/tests/manualOrderGeneration.test.mjs','exchange-admin/tests/manualOrderEstimate.test.mjs','exchange-admin/tests/marketMinuteHours.test.mjs','exchange-pc/tests/contract.test.mjs','exchange-frontend/tests/assetEquityHistory.test.mjs','exchange-frontend/tests/assetPixelWindow.test.mjs','exchange-frontend/tests/assetCarryForward.test.mjs','exchange-frontend/tests/orderView.test.mjs')) {
        "COMMAND node --experimental-strip-types $test" | Add-Content "$ReportDir/frontend-tests.log"
        & node --experimental-strip-types $test *>> "$ReportDir/frontend-tests.log"
        "$test exit=$LASTEXITCODE" | Add-Content "$ReportDir/frontend-exits.txt"
        if ($LASTEXITCODE -ne 0) { throw "Frontend check failed: $test" }
    }
    $env:VITE_API_BASE_URL = '/api'
    foreach ($project in @('exchange-admin','exchange-pc','exchange-frontend')) {
        & npm --prefix $project run build *> "$ReportDir/$project-build.log"
        "$project exit=$LASTEXITCODE" | Add-Content "$ReportDir/build-exits.txt"
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $project" }
    }
    if ($LiveApi) {
        # This explicitly writes three orders for dedicated user 9000070, never resets accounts or shared tables.
        $ReportDir | Set-Content reports/manual-generation-full-test/latest.txt
        & python "$PSScriptRoot/verify_generation_full.py" *> "$ReportDir/http.log"
        if ($LASTEXITCODE -ne 0) { throw 'HTTP/SQL acceptance failed' }
    }
    'PASS automated regression; browser acceptance is separate and is not implied.' | Set-Content "$ReportDir/runner-result.txt"
} catch {
    "FAIL: $($_.Exception.Message)" | Set-Content "$ReportDir/runner-result.txt"
    throw
} finally { $env:VITE_API_BASE_URL=$oldApi; Pop-Location }
