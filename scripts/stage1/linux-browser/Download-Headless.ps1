param([Parameter(Mandatory=$true)][string]$RunDir)
$ErrorActionPreference='Stop';$RunDir=(Resolve-Path -LiteralPath $RunDir).Path;$name=Split-Path -Leaf $RunDir
if($name -notmatch '^stage1-\d{8}-\d{6}$' -or (Get-Content (Join-Path $RunDir 'environment.json') -Raw|ConvertFrom-Json).run -cne $name){throw 'Owned run required'}
$assets=Join-Path $RunDir 'private/linux-fixture-assets';$plan=Get-Content (Join-Path $assets 'plan.json') -Raw|ConvertFrom-Json
$browser=$plan.browsers|Where-Object directory -eq 'chromium_headless_shell-1234'
if(@($browser).Count -ne 1 -or $browser.url -cne 'https://cdn.playwright.dev/builds/cft/151.0.7922.34/linux64/chrome-headless-shell-linux64.zip'){throw 'Exact official Playwright headless plan required'}
$stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ');$logDir=Join-Path $RunDir "logs/linux-headless-ranges-$stamp";$parts=Join-Path $assets 'headless-range-parts'
New-Item -ItemType Directory -Force -Path $logDir,$parts|Out-Null
$headers=Join-Path $logDir 'official-headers.txt'
& curl.exe --fail --head --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 $browser.url *> $headers
if($LASTEXITCODE -ne 0){throw 'Normal official TLS metadata request failed'}
$raw=Get-Content -LiteralPath $headers -Raw;$size=[long]([regex]::Matches($raw,'(?im)^Content-Length:\s*(\d+)')[ -1].Groups[1].Value)
$md5=[regex]::Match($raw,'(?im)^x-goog-hash:\s*md5=([^\r\n]+)').Groups[1].Value.Trim()
if($size -ne 120231126 -or $md5 -ne 'eSBHs8JiXX1LD8fE/GfXrQ=='){throw 'Exact official Chrome-for-Testing object metadata mismatch'}
$config=Join-Path $logDir 'curl-ranges.conf';$chunk=8MB;$lines=@();$expected=@()
for($index=0;$index*$chunk -lt $size;$index++){
  $start=[long]$index*$chunk
  $end=[Math]::Min($size-1,$start+$chunk-1);$file=Join-Path $parts "$index.bin";$expected+=[pscustomobject]@{file=$file;size=$end-$start+1}
  if((Test-Path -LiteralPath $file) -and (Get-Item -LiteralPath $file).Length -eq $end-$start+1){continue}
  if($lines.Count){$lines+='next'}
  $lines+=@(('url = "'+$browser.url+'"'),('output = "'+$file.Replace('\','\\')+'"'),('range = "'+$start+'-'+$end+'"'),'fail','location','proto = "=https"','proto-redir = "=https"','connect-timeout = 15','max-time = 240','retry = 1',('max-filesize = '+(-+1)))
}
if($lines.Count){[IO.File]::WriteAllLines($config,$lines,[Text.UTF8Encoding]::new($false));& curl.exe --parallel --parallel-max 8 --config $config *> (Join-Path $logDir 'curl.log');$downloadExit=$LASTEXITCODE;$downloadExit|Set-Content (Join-Path $logDir 'curl.exit')}else{$downloadExit=0}
$bad=@($expected|Where-Object {!(Test-Path -LiteralPath $_.file) -or (Get-Item -LiteralPath $_.file).Length -ne $_.size})
if($downloadExit -ne 0 -or $bad.Count){throw "Official ranged download incomplete; exit=$downloadExit invalidParts=$($bad.Count)"}
$temporary=Join-Path $assets 'headless-ranges-assembled.zip';$stream=[IO.File]::Create($temporary)
try{foreach($part in $expected){$input=[IO.File]::OpenRead($part.file);try{$input.CopyTo($stream)}finally{$input.Dispose()}}}finally{$stream.Dispose()}
$hash=[Security.Cryptography.MD5]::Create();$input=[IO.File]::OpenRead($temporary);try{$actual=[Convert]::ToBase64String($hash.ComputeHash($input))}finally{$input.Dispose();$hash.Dispose()}
if($actual -cne $md5){throw 'Assembled official artifact failed TLS-authenticated storage object checksum'}
$canonical=Join-Path $assets ($browser.directory+'.zip');if(Test-Path -LiteralPath $canonical){if((Get-FileHash $canonical -Algorithm SHA256).Hash -cne (Get-FileHash $temporary -Algorithm SHA256).Hash){throw 'Existing archive differs from official object'}}else{Move-Item -LiteralPath $temporary -Destination $canonical}
$target=Join-Path $assets ('browsers/'+$browser.directory);if(!(Test-Path -LiteralPath $target)){Expand-Archive -LiteralPath $canonical -DestinationPath $target;(Get-FileHash $canonical -Algorithm SHA256).Hash|Set-Content (Join-Path $target 'stage1-artifact-sha256')}
$marker=Join-Path $target 'stage1-artifact-sha256';if(!(Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw).Trim() -cne (Get-FileHash -LiteralPath $canonical -Algorithm SHA256).Hash){throw 'Existing extracted archive marker mismatch'}
Write-Output "Official headless TLS/object-checksum verified; ZIP extraction evidence=$logDir"
