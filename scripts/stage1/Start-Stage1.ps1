param([string]$RunDir)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$RunDir) { $RunDir=Join-Path $root ('reports/stage1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')) }
$RunDir=[IO.Path]::GetFullPath($RunDir)
Push-Location $root
try {
  New-Item -ItemType Directory -Path (Join-Path $RunDir 'logs') -Force | Out-Null
  function Run([string]$name,[scriptblock]$command) {
    $log=Join-Path $RunDir ('logs/'+$name+'.log')
    if(Test-Path -LiteralPath $log){throw "Evidence already exists: $name"}
    & $command *> $log
    $code=$LASTEXITCODE
    $code | Set-Content -LiteralPath (Join-Path $RunDir ('logs/'+$name+'.exit'))
    if($code -ne 0){throw "$name failed (exit $code); see $log"}
  }
  Run 'prepare' { python scripts/stage1/environment.py prepare --run-dir $RunDir }
  Run 'initialize' { python scripts/stage1/environment.py initialize --run-dir $RunDir }
  Run 'it-fixture' { python scripts/stage1/environment.py integration-fixture --run-dir $RunDir }
  Run 'backend-build' { mvn -f exchange-backend/pom.xml -DskipTests package }
  $env:VITE_ADMIN_ORIGIN='https://admin.localhost';$env:VITE_CONTROL_ORIGIN='https://control.localhost'
  Run 'admin-build' { npm.cmd --prefix exchange-admin run build -- --outDir (Join-Path $RunDir 'frontend/admin') }
  Run 'control-build' { npm.cmd --prefix exchange-admin run build:control -- --outDir (Join-Path $RunDir 'frontend/control') }
  Run 'pc-build' { npm.cmd --prefix exchange-pc run build -- --outDir (Join-Path $RunDir 'frontend/pc') }
  Run 'mobile-build' { npm.cmd --prefix exchange-frontend run build -- --base /mobile/ --outDir (Join-Path $RunDir 'frontend/mobile') }
  Run 'services-start' { python scripts/stage1/services.py --run-dir $RunDir }
  Write-Output "Local stage1 environment: $RunDir"
} finally { Pop-Location }
