param([Parameter(Mandatory=$true)][string]$RunDir)
$ErrorActionPreference='Stop'
$RunDir=(Resolve-Path -LiteralPath $RunDir).Path;$name=Split-Path -Leaf $RunDir
if($name -notmatch '^stage1-\d{8}-\d{6}$' -or (Get-Content (Join-Path $RunDir 'environment.json') -Raw|ConvertFrom-Json).run -cne $name){throw 'Owned run required'}
$assets=Join-Path $RunDir 'private/linux-fixture-assets';$plan=Get-Content (Join-Path $assets 'plan.json') -Raw|ConvertFrom-Json
if($plan.playwrightVersion -cne '1.62.1'){throw 'Exact Playwright plan version required'}
$stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ');$logs=Join-Path $RunDir "logs/linux-assets-$stamp"
New-Item -ItemType Directory -Force -Path $logs,(Join-Path $assets 'debs'),(Join-Path $assets 'browsers')|Out-Null
$results=@($plan.debs|ForEach-Object -Parallel {
  $ErrorActionPreference='Stop';$item=$_;$file=Join-Path $using:assets ('debs/'+$item.file);$log=Join-Path $using:logs ($item.file+'.log')
  try{
    $url=[uri]$item.url;if($url.Scheme -cne 'https' -or $url.Host -cne 'mirrors.tuna.tsinghua.edu.cn' -or $item.file -notmatch '^[^/\\]+\.deb$' -or $item.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'APT vendor URI or signed SHA mismatch'}
    if(!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ine $item.sha256){
      & curl.exe --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 180 --retry 1 --continue-at - --output ($file+'.part') $item.url *> $log
      $code=$LASTEXITCODE;if($code -ne 0){throw "Normal HTTPS download exit=$code"}
      if((Get-FileHash -LiteralPath ($file+'.part') -Algorithm SHA256).Hash -ine $item.sha256 -or (Get-Item -LiteralPath ($file+'.part')).Length -ne $item.bytes){throw 'Downloaded APT package does not match signed index SHA/length'}
      Move-Item -LiteralPath ($file+'.part') -Destination $file -Force
    }
    [pscustomobject]@{file=$item.file;exit=0;sha256=$item.sha256;status='PASS'}
  }catch{$_|Out-String|Add-Content -LiteralPath $log;[pscustomobject]@{file=$item.file;exit=1;status='FAIL';error=$_.Exception.Message}}
} -ThrottleLimit 6)
foreach($item in $plan.browsers){
  $file=Join-Path $assets ($item.directory+'.zip');$log=Join-Path $logs ($item.directory+'.log');$target=Join-Path $assets ('browsers/'+$item.directory)
  try{
    $url=[uri]$item.url;if($url.Scheme -cne 'https' -or $url.Host -notin @('cdn.playwright.dev','playwright.download.prss.microsoft.com') -or $item.directory -notmatch '^(chromium_headless_shell|ffmpeg)-\d+$'){throw 'Official artifact URL mismatch'}
    if($item.directory -eq 'chromium_headless_shell-1234' -and !(Test-Path -LiteralPath $file)){
      & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $PSScriptRoot 'Download-Headless.ps1') -RunDir $RunDir *> $log
      if($LASTEXITCODE -ne 0){throw 'Official headless download/checksum failed'}
    }
    if(!(Test-Path -LiteralPath $file)){
      & curl.exe --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 240 --retry 1 --continue-at - --output ($file+'.part') $item.url *> $log
      $code=$LASTEXITCODE;if($code -ne 0){throw "Normal official artifact HTTPS download exit=$code"};Move-Item -LiteralPath ($file+'.part') -Destination $file
    }
    $sha=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash;$complete=Join-Path $target 'stage1-artifact-sha256'
    if(!(Test-Path -LiteralPath $target)){Expand-Archive -LiteralPath $file -DestinationPath $target;$sha|Set-Content -LiteralPath $complete}
    if(!(Test-Path -LiteralPath $complete) -or (Get-Content -LiteralPath $complete -Raw).Trim() -cne $sha){throw 'Existing extracted artifact identity mismatch'}
    $results+=[pscustomobject]@{file=$item.directory;exit=0;sha256=$sha;source=$item.url;integrity='Normal official vendor TLS and ZIP extraction; no error suppression';status='PASS'}
  }catch{$_|Out-String|Add-Content -LiteralPath $log;$results+=[pscustomobject]@{file=$item.directory;exit=1;status='FAIL';error=$_.Exception.Message}}
}
$failed=@($results|Where-Object status -eq 'FAIL').Count
$results|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $logs 'result.json');[int]($failed -gt 0)|Set-Content -LiteralPath (Join-Path $logs 'exit')
Write-Output "Signed APT/official artifacts passed=$(@($results|Where-Object status -eq 'PASS').Count) failed=$failed logs=$logs"
if($failed){$results|Where-Object status -eq 'FAIL'|Format-List;exit 1};exit 0
