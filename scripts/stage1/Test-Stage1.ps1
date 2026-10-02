param([Parameter(Mandatory=$true)][string]$RunDir,[ValidateSet('Linux','Windows')][string]$Browser='Windows',[switch]$TrustFixtureCertificate)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDir=(Resolve-Path -LiteralPath $RunDir).Path
if(!$RunDir.StartsWith($root+[IO.Path]::DirectorySeparatorChar+'reports'+[IO.Path]::DirectorySeparatorChar)){throw 'Workspace stage1 run directory required'}
$environment=Get-Content -LiteralPath (Join-Path $RunDir 'environment.json') -Raw | ConvertFrom-Json
if($TrustFixtureCertificate -and $Browser -ne 'Windows'){throw 'OS certificate opt-in applies only to the Windows browser mode'}
$trustAttempted=$false
Push-Location $root
try {
  $attempt=Get-Date -Format 'yyyyMMdd-HHmmss'
  $env:STAGE1_RUN=$RunDir
  $env:MT705_TEST_REDIS_PORT=[string]$environment.redisPort
  $env:MT705_SMS_TEST_DIRECTORY=Join-Path $RunDir 'private/sms-tests'
  New-Item -ItemType Directory -Path $env:MT705_SMS_TEST_DIRECTORY -Force | Out-Null
  $env:T05_DISPOSABLE_STACK='true'
  $env:T05_MYSQL_PASSWORD=(Get-Content -LiteralPath (Join-Path $RunDir 'private/compose.env') -Raw).Trim().Split('=',2)[1]
  $env:T05_REDIS_CONTAINER=$environment.containers.cache_redis
  function Run([string]$name,[scriptblock]$command) {
    $log=Join-Path $RunDir ('logs/'+$name+'-'+$attempt+'.log')
    if(Test-Path -LiteralPath $log){throw "Evidence already exists: $log"}
    $startedUtc=[DateTime]::UtcNow
    & $command *> $log
    $code=$LASTEXITCODE
    $code | Set-Content -LiteralPath ($log -replace '\.log$','.exit')
    if($name -eq 'backend-stage1'){
      $evidence=Join-Path $RunDir ('backend-tests-'+$attempt)
      New-Item -ItemType Directory -Path $evidence | Out-Null
      $suites=@()
      foreach($test in $tests.Split(',')){
        $class=$test.Split('#')[0]
        $xml=Get-ChildItem -LiteralPath (Join-Path $root 'exchange-backend/target/surefire-reports') -Filter "TEST-*.$class.xml"
        if($xml -and $xml.LastWriteTimeUtc -ge $startedUtc){
          Copy-Item -LiteralPath $xml.FullName -Destination $evidence
          $suite=([xml](Get-Content -LiteralPath $xml.FullName -Raw)).testsuite
          if($test.Contains('#') -and @($suite.testcase.name) -notcontains $test.Split('#')[1]){throw 'Explicit focused lifecycle method missing from current XML'}
          $suites+=@{name=$class;selector=$test;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped}
        }
      }
      $suites | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding UTF8
      if($code -eq 0 -and ($suites.Count -ne $tests.Split(',').Count -or ($suites | Measure-Object skipped -Sum).Sum -ne 0)){
        throw 'Mandatory selected tests missing or skipped; inspect current Maven evidence'
      }
    }
    if($code -ne 0){throw "$name failed (exit $code); see $log"}
  }
  Run 'certificate-helper-unit' { python scripts/stage1/test_fixture_certificate.py --run-dir $RunDir }
  Run 'source-gate' { python scripts/multitenant/isolation_gate.py }
  Run 'gate-unit' { python scripts/multitenant/test_isolation_gate.py }
  Run 'mysql-schema' { python scripts/multitenant/stage1_schema_checks.py --container $environment.containers.mysql --database $environment.database --output (Join-Path $RunDir ('schema/mysql-schema-'+$attempt+'.json')) }
  $tests='TenantBoundaryTest,TenantCorsBoundaryTest,TenantForceVersionTest,SchemaPackageGuardTest,SchemaPackageGuardMySqlIT,Stage1TenantRepositoryMySqlIT,DedicatedMysqlFixtureTest,OutboundEndpointPolicyTest,ControlSecurityTest,TenantDomainLifecycleTest,TenantReadinessTest,TenantPolicyLifecycleTest,ControlledExitIntegrationTest#disabledNewBusinessGuardDoesNotBlockHistoricalQueryOrExistingCancellation,BackendAccountIdentityTest,BackendAccountCollisionTest,ControlAccountSecurityTest,TenantJwtBoundaryTest,AgentProvisioningPolicyTest,RegistrationEmailBoundaryTest,RegistrationWithoutCodeTest,RegistrationIdentityCollisionTest,RegistrationFieldsTest,PermissionGrantRepairTest,AdminPermissionLoginAuditTest,ControlReadQueryTest,ControlReadOnlyMethodTest,SystemConfigControllerTest,EmailOutboundTest,EmailRateLimitTest,TenantSmsContractTest,AnnouncementRetentionTest,UploadStorageTest,ImageAuthorizationTest,AudioValidationTest,TenantSourceGateTest,ControlTablePreferenceTest,ControlSupportIsolationTest,HomeSparklineRedisIntegrationTest,HomeSparklineCacheTest'
  Run 'backend-stage1' { mvn -f exchange-backend/pom.xml "-Dsecurity.test.redis.port=$($environment.redisPort)" "-Dmanual.mysql.fixture=$RunDir/private/it-connection.json" "-Dstage1.mysql.fixture=$RunDir/private/it-connection.json" "-Dtest=$tests" test }
  Run 'admin-unit' { Push-Location exchange-admin; try { node --test tests/activityRecipients.test.mjs tests/controlSupervision.test.mjs tests/permissionCoverage.test.mjs tests/sharedRuntime.test.mjs tests/tenantSession.test.mjs } finally { Pop-Location } }
  Run 'pc-unit' { Push-Location exchange-pc; try { node --test tests/authEnter.test.mjs } finally { Pop-Location } }
  Run 'mobile-unit' { Push-Location exchange-frontend; try { node --test tests/registrationFields.test.mjs } finally { Pop-Location } }
  Run 'smtp-auth' { python scripts/stage1/mailbox.py $RunDir --verify-auth }
  Run 'api-smoke' { python scripts/stage1/api_smoke.py --run-dir $RunDir }
  if($Browser -eq 'Linux'){
    Run 'linux-browser-build' { pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/stage1/Linux-Browser.ps1 -RunDir $RunDir -Action Build }
    Run 'frontend-smoke' { pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/stage1/Linux-Browser.ps1 -RunDir $RunDir -Action Full }
  }else{
  if($TrustFixtureCertificate){
    $trustAttempted=$true
    Run 'certificate-trust' { pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/stage1/Fixture-Certificate.ps1 -RunDir $RunDir -Action Install }
  }
  Run 'frontend-smoke' { node scripts/stage1/frontend.browser.cjs }
  }
  Write-Output "Stage1 mandatory checks passed for current run: $RunDir (not whole-project completion)"
} finally {
  if($trustAttempted){
    $cleanupLog=Join-Path $RunDir ('logs/certificate-remove-'+$attempt+'.log')
    pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Fixture-Certificate.ps1') -RunDir $RunDir -Action Remove *> $cleanupLog
    $cleanupCode=$LASTEXITCODE
    $cleanupCode | Set-Content -LiteralPath ($cleanupLog -replace '\.log$','.exit')
    if($cleanupCode -ne 0){throw "Fixture certificate removal failed; see $cleanupLog"}
  }
  Remove-Item Env:T05_MYSQL_PASSWORD -ErrorAction SilentlyContinue
  Pop-Location
}
