param(
    [Parameter(Mandatory=$true)][string]$RunDir,
    [Parameter(Mandatory=$true)][string]$Batch,
    [Parameter(Mandatory=$true)][string]$Tests,
    [string]$Fixture
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$RunDir=(Resolve-Path -LiteralPath $RunDir).Path
if(-not $RunDir.StartsWith((Join-Path $root 'reports\stage2-'),[StringComparison]::OrdinalIgnoreCase) -or $Batch -notmatch '^[a-z0-9-]+$' -or $Tests -notmatch '^[A-Za-z0-9_,]+$'){throw 'Explicit stage2 run, unique batch and test names required'}
$log=Join-Path $RunDir "logs\$Batch.log"
if(Test-Path -LiteralPath $log){throw 'Keep original evidence; choose a fresh batch name'}
if(-not $Fixture){
    $pointer=Get-Content -Raw -LiteralPath (Join-Path $RunDir 'money-fixture-current.json')|ConvertFrom-Json
    $Fixture=$pointer.connectionFile
    if(-not $Fixture){$Fixture=$pointer.connection}
}
if(-not(Test-Path -LiteralPath $Fixture)){throw 'Identified and independently restored fixture required'}
$Fixture=(Resolve-Path -LiteralPath $Fixture).Path
$inputs=@{}
Get-ChildItem -LiteralPath (Join-Path $root 'exchange-backend\src') -File -Recurse | ForEach-Object {$inputs[$_.FullName]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
$pom=Join-Path $root 'exchange-backend\pom.xml';$inputs[$pom]=(Get-FileHash -LiteralPath $pom -Algorithm SHA256).Hash
$args=@('-f',$pom,"-Dstage2.mysql.fixture=$Fixture","-Ddeposit.mysql.fixture=$Fixture","-Dmanual.mysql.fixture=$Fixture","-Dsimple.mysql.fixture=$Fixture","-Dequity.mysql.fixture=$Fixture","-Dtest=$Tests",'test')
# Trial localhost HTTP probe needs the fork JVM flag before JDK HttpURLConnection caches it.
if($Tests.Split(',') -contains 'TrialExpiryPendingOrderMySqlTest'){$args+='-DargLine=-Dsun.net.http.allowRestrictedHeaders=true'}
$destination=Join-Path $RunDir "tests\$Batch"
New-Item -ItemType Directory -Path $destination | Out-Null
$previousTrialEvidence=$env:T02_EXPIRY_EVIDENCE
if($Tests.Split(',') -contains 'TrialExpiryPendingOrderMySqlTest'){$env:T02_EXPIRY_EVIDENCE=$destination}
$started=[DateTimeOffset]::Now
@{tests=$Tests;fixture=$Fixture;arguments=$args;startedAt=$started.ToString('o');inputSha256=$inputs}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $RunDir "logs\$Batch-command.json") -Encoding utf8
Push-Location $root
try{
    & mvn.cmd @args *> $log
    $code=$LASTEXITCODE
    Set-Content -LiteralPath (Join-Path $RunDir "logs\$Batch.exit") -Value $code
    $names=$Tests.Split(',')
    # Copy only fresh reports for whole class names; suffix matches can inflate evidence.
    $reports=Get-ChildItem -LiteralPath (Join-Path $root 'exchange-backend\target\surefire-reports') -File | Where-Object {$name=$_.Name;$_.LastWriteTimeUtc -ge $started.UtcDateTime -and @($names|Where-Object {$name -match ('(^|\.)'+[regex]::Escape($_)+'\.(xml|txt)$')}).Count -gt 0}
    $reports|Copy-Item -Destination $destination
    $missing=@($names|Where-Object {$testName=$_;@($reports|Where-Object {$_.Name -match ('(^|\.)'+[regex]::Escape($testName)+'\.xml$')}).Count -eq 0})
    $changed=@($inputs.Keys|Where-Object {-not(Test-Path -LiteralPath $_) -or (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash -ne $inputs[$_]})
    @{exitCode=$code;finishedAt=[DateTimeOffset]::Now.ToString('o');inputChanged=$changed;missingFreshXml=$missing;testReportDirectory=$destination}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $RunDir "logs\$Batch-result.json") -Encoding utf8
    Get-Content -LiteralPath $log -Tail 16
    if($changed.Count -gt 0){throw 'Inputs changed during test command; evidence is not a same-version acceptance'}
    if($code -eq 0 -and $missing.Count -gt 0){throw 'Fresh test XML missing; evidence is not accepted'}
    exit $code
}finally{$env:T02_EXPIRY_EVIDENCE=$previousTrialEvidence;Pop-Location}
