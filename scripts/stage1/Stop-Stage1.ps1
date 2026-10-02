param([Parameter(Mandatory=$true)][string]$RunDir)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDir=(Resolve-Path -LiteralPath $RunDir).Path
if(!$RunDir.StartsWith($root+[IO.Path]::DirectorySeparatorChar+'reports'+[IO.Path]::DirectorySeparatorChar)){throw 'Workspace stage1 run directory required'}
$recordFile=Join-Path $RunDir 'processes.json'
$records=$null
if(Test-Path -LiteralPath $recordFile){
  $records=Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
}elseif(@(Get-ChildItem -LiteralPath $RunDir -Filter 'processes-stopped-*.json').Count -eq 0){
  throw 'No live or archived owned service record; refusing an unproven stop'
}
foreach($entry in @($records.PSObject.Properties)){
  if(!$entry){continue}
  $owned=$entry.Value
  $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($owned.pid)"
  if(!$process){continue}
  if(!$process.CommandLine.Contains($RunDir)){throw "PID ownership changed; refusing to stop $($owned.pid)"}
  $created=([DateTimeOffset]$process.CreationDate).ToUnixTimeSeconds()
  if([Math]::Abs($created-$owned.startedAt) -gt 10){throw "PID creation time changed; refusing to stop $($owned.pid)"}
  Stop-Process -Id $owned.pid
}
$environment=Get-Content -LiteralPath (Join-Path $RunDir 'environment.json') -Raw | ConvertFrom-Json
foreach($name in $environment.containers.PSObject.Properties.Value){
  $raw=docker inspect --type container $name
  if($LASTEXITCODE -ne 0){throw "Missing owned container: $name"}
  $data=($raw | ConvertFrom-Json)[0]
  if($data.Config.Labels.'com.gtcfesk.stage1.run' -ne $environment.run){throw "Container ownership changed: $name"}
}
docker compose --env-file (Join-Path $RunDir 'private/compose.env') -f (Join-Path $RunDir 'compose.json') stop
if($LASTEXITCODE -ne 0){throw 'Owned stack stop failed'}
if(Test-Path -LiteralPath $recordFile){
  Move-Item -LiteralPath $recordFile -Destination (Join-Path $RunDir ('processes-stopped-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.json'))
}
if(Test-Path -LiteralPath (Join-Path $RunDir 'fixture-certificate-trust.json')){
  $log=Join-Path $RunDir ('logs/certificate-stop-cleanup-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log')
  pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Fixture-Certificate.ps1') -RunDir $RunDir -Action Remove *> $log
  $code=$LASTEXITCODE
  $code | Set-Content -LiteralPath ($log -replace '\.log$','.exit')
  if($code -ne 0){throw "Owned services stopped, but exact fixture CA removal is pending; see $log"}
}
Write-Output 'Only ownership-checked stage1 processes stopped; databases, volumes and evidence retained.'
