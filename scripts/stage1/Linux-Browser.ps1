param(
  [Parameter(Mandatory=$true)][string]$RunDir,
  [ValidateSet('Build','Preflight','Full')][string]$Action='Preflight'
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$RunDir=(Resolve-Path -LiteralPath $RunDir).Path
$runName=Split-Path -Leaf $RunDir
if(!$RunDir.StartsWith((Join-Path $root 'reports')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $runName -notmatch '^stage1-\d{8}-\d{6}$'){throw 'Only an owned stage1 run is allowed'}
$spec=Get-Content -LiteralPath (Join-Path $RunDir 'environment.json') -Raw | ConvertFrom-Json
if($spec.run -cne $runName){throw 'Run identity mismatch'}
$version='1.62.1';$image="mt705-$runName-browser-pw$version"
$stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')
$log=Join-Path $RunDir "logs/linux-browser-$($Action.ToLowerInvariant())-$stamp.log"
$exitFile=[IO.Path]::ChangeExtension($log,'.exit')
try{
  if($Action -eq 'Build'){
    $assets=Join-Path $RunDir 'private/linux-fixture-assets';New-Item -ItemType Directory -Force -Path $assets | Out-Null
    $tools="mt705-$runName-browser-tools";$dockerfile=Join-Path $PSScriptRoot 'linux-browser/Dockerfile';$source=Join-Path $PSScriptRoot 'linux-browser'
    & docker build --pull=false --target tools --label "com.gtcfesk.stage1.run=$runName" --label com.gtcfesk.stage1.role=browser-fixture -t $tools -f $dockerfile $source *> $log
    if($LASTEXITCODE -ne 0){throw 'Normally verified APT/Node/Playwright tools build failed'}
    & docker run --pull=never --name "mt705-$runName-browser-download-plan-$stamp" --label "com.gtcfesk.stage1.run=$runName" --label com.gtcfesk.stage1.role=browser-fixture --network none --mount "type=bind,source=$assets,target=/assets" --mount "type=bind,source=$source,target=/stage1,readonly" --entrypoint node $tools /stage1/download-plan.cjs *>> $log
    if($LASTEXITCODE -ne 0){throw 'APT signed download plan failed'}
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $source 'Download-Assets.ps1') -RunDir $RunDir *>> $log
    if($LASTEXITCODE -ne 0){throw 'Normal HTTPS signed assets download failed'}
    & docker build --pull=false --network none --build-context "fixtureassets=$assets" --label "com.gtcfesk.stage1.run=$runName" --label com.gtcfesk.stage1.role=browser-fixture -t $image -f $dockerfile $source *>> $log
    $code=$LASTEXITCODE
  }else{
    $imageRun=(& docker image inspect --format '{{index .Config.Labels "com.gtcfesk.stage1.run"}}' $image).Trim()
    if($LASTEXITCODE -ne 0 -or $imageRun -cne $runName){throw 'Build the owned browser fixture image first'}
    $imageId=(& docker image inspect --format '{{.Id}}' $image).Trim()
    $redis=$spec.containers.redis
    if($redis -cne "mt705-$runName-redis"){throw 'Redis name does not belong to this run'}
    $redisLabel=(& docker inspect --format '{{index .Config.Labels "com.gtcfesk.stage1.run"}}' $redis).Trim()
    $redisId=(& docker inspect --format '{{.Id}}' $redis).Trim()
    $running=(& docker inspect --format '{{.State.Running}}' $redis).Trim()
    $ports=(& docker inspect --format '{{json .HostConfig.PortBindings}}' $redis | ConvertFrom-Json).'6379/tcp'
    if($redisLabel -cne $runName -or $redisId -notmatch '^[a-f0-9]{64}$' -or $running -ne 'true' -or @($ports).Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or $ports[0].HostPort -ne [string]$spec.redisPort){throw 'Redis identity, run label, or loopback port ownership mismatch'}
    $processes=Get-Content -LiteralPath (Join-Path $RunDir 'processes.json') -Raw | ConvertFrom-Json
    foreach($item in @(@{role='gateway';port=443},@{role='backend';port=$spec.backendPort})){
      $saved=$processes.($item.role)
      if($saved.runDir -ine $RunDir){throw 'Service ownership record mismatch'}
      $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $item.port -ErrorAction Stop)
      if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or $listeners[0].OwningProcess -ne $saved.pid){throw 'Actual service listener does not match this run'}
      $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($saved.pid)"
      if(!$process.CommandLine.Contains($RunDir)){throw 'Service process arguments do not belong to this run'}
    }
    $hostIp=((& docker exec $redis getent hosts host.docker.internal) -split '\s+')[0]
    $address=$null;if(![Net.IPAddress]::TryParse($hostIp,[ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork){throw 'Docker host address could not be resolved'}
    $caFile=Join-Path $RunDir 'private/ca.crt';$ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new($caFile)
    try{if($ca.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName,$false) -cne "$runName local fixture CA" -or $ca.NotAfter -le (Get-Date)){throw 'This run CA identity is invalid'}}finally{$ca.Dispose()}
    $container="mt705-$runName-browser-$($Action.ToLowerInvariant())-$stamp"
    $proofName="linux-browser-ownership-$stamp.json";$linuxRun="/workspace/705/reports/$runName"
    $proof=[pscustomobject]@{run=$runName;imageId=$imageId;container=$container;hostIp=$hostIp;redisContainerId=$redisId;redisRunLabel=$redisLabel;redisPort=$spec.redisPort;caFileSha256=(Get-FileHash -LiteralPath $caFile -Algorithm SHA256).Hash;playwrightVersion=$version;createdAt=$stamp;serviceListeners='Verified owned 127.0.0.1 backend/gateway';dockerSocketMounted=$false}
    [IO.File]::WriteAllText((Join-Path $RunDir $proofName),($proof | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    $profile=Join-Path $RunDir 'linux-browser-seccomp.json'
    if(!(Test-Path -LiteralPath $profile)){Invoke-WebRequest -Uri "https://raw.githubusercontent.com/microsoft/playwright/v$version/utils/docker/seccomp_profile.json" -OutFile $profile}
    if((Get-FileHash -LiteralPath $profile -Algorithm SHA256).Hash -cne 'CC3E61CABDA6BBC1E53E54D27BA4D55A9D3BE829B6DD1A596F4A7B31B1CC7849'){throw 'Official browser seccomp profile fingerprint mismatch'}
    $arguments=@('run','--pull=never','--init','--name',$container,'--label',"com.gtcfesk.stage1.run=$runName",'--label','com.gtcfesk.stage1.role=frontend-browser','--shm-size=256m','--memory=1g','--cpus=2','--security-opt',"seccomp=$profile",
      '--mount',"type=bind,source=$PSScriptRoot,target=/workspace/705/scripts/stage1,readonly",
      '--mount',"type=bind,source=$RunDir,target=$linuxRun",
      '-e','STAGE1_LINUX_FIXTURE=1','-e',"STAGE1_RUN=$linuxRun",'-e',"STAGE1_LINUX_OWNER_PROOF=$linuxRun/$proofName",'-e',"STAGE1_HOST_IP=$hostIp",'-e','STAGE1_REDIS_HOST=host.docker.internal','-e',"STAGE1_REDIS_PORT=$($spec.redisPort)",'-e',"STAGE1_REDIS_CONTAINER_ID=$redisId")
    foreach($hostName in @('control','admin','default','a','a2','b')){$arguments+=@('--add-host',"$hostName.localhost`:$hostIp")}
    $script=if($Action -eq 'Preflight'){'/workspace/705/scripts/stage1/linux-browser/preflight.cjs'}else{'/workspace/705/scripts/stage1/frontend.browser.cjs'}
    $arguments+=@($imageId,$script)
    & docker @arguments *> $log
    $code=$LASTEXITCODE
    $details=Join-Path $RunDir "linux-browser-container-$stamp.json"
    & docker inspect --format '{{json .HostConfig}}' $container | Set-Content -LiteralPath $details
  }
  $code | Set-Content -LiteralPath $exitFile
  Get-Content -LiteralPath $log -Tail 18
  Write-Output "Actual Linux browser $Action exit=$code; log=$log"
  exit $code
}catch{
  $_.Exception.Message | Add-Content -LiteralPath $log
  1 | Set-Content -LiteralPath $exitFile
  Write-Error $_.Exception.Message -ErrorAction Continue
  exit 1
}
