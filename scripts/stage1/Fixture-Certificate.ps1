param(
  [Parameter(Mandatory=$true)][string]$RunDir,
  [Parameter(Mandatory=$true)][ValidateSet('Install','Prepare','Remove')][string]$Action
)
$ErrorActionPreference='Stop'
$lock=$null; $store=$null; $certificate=$null
try {
  $root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
  $RunDir=(Resolve-Path -LiteralPath $RunDir).Path
  $reports=[IO.Path]::GetFullPath((Join-Path $root 'reports'))+[IO.Path]::DirectorySeparatorChar
  $runName=Split-Path -Leaf $RunDir
  if(!$RunDir.StartsWith($reports,[StringComparison]::OrdinalIgnoreCase) -or $runName -notmatch '^stage1-\d{8}-\d{6}$'){throw 'Only an explicit owned stage1 report directory is allowed'}
  $spec=Get-Content -LiteralPath (Join-Path $RunDir 'environment.json') -Raw | ConvertFrom-Json
  if($spec.run -ne $runName){throw 'Run identity mismatch'}
  $file=Join-Path $RunDir 'private/ca.crt'
  foreach($item in @($RunDir,(Join-Path $RunDir 'private'),$file)){
    if((Get-Item -LiteralPath $item).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Certificate fixture paths must not be symbolic links'}
  }
  $lock=[IO.File]::Open((Join-Path $RunDir 'fixture-certificate.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
  $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
  $certificate=[Security.Cryptography.X509Certificates.X509Certificate2]::new($file)
  $thumbprint=$certificate.Thumbprint.ToUpperInvariant()
  $sha=[Security.Cryptography.SHA256]::Create()
  try {$derHash=([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace('-','')} finally {$sha.Dispose()}
  if($certificate.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName,$false) -cne ($runName+' local fixture CA')){throw 'CA common name does not belong to this run'}
  if([Convert]::ToBase64String($certificate.SubjectName.RawData) -cne [Convert]::ToBase64String($certificate.IssuerName.RawData)){throw 'Fixture CA must be self-issued'}
  $constraints=@($certificate.Extensions | Where-Object {$_.Oid.Value -eq '2.5.29.19'})
  $usage=@($certificate.Extensions | Where-Object {$_.Oid.Value -eq '2.5.29.15'})
  if($constraints.Count -ne 1 -or !$constraints[0].CertificateAuthority -or !$constraints[0].HasPathLengthConstraint -or $constraints[0].PathLengthConstraint -ne 0){throw 'Unexpected fixture CA constraints'}
  if($usage.Count -ne 1 -or !($usage[0].KeyUsages -band [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign)){throw 'Fixture certificate is not a signing CA'}
  if($certificate.NotAfter -le (Get-Date) -or $certificate.NotBefore -gt (Get-Date) -or ($certificate.NotAfter-$certificate.NotBefore).TotalDays -gt 31){throw 'Fixture CA validity is invalid'}
  $recordFile=Join-Path $RunDir 'fixture-certificate-trust.json'
  $record=$null
  if(Test-Path -LiteralPath $recordFile){
    $record=Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    if($record.run -cne $runName -or $record.runDir -ine $RunDir -or $record.caFileSha256 -cne $hash -or $record.certificateSha256 -cne $derHash -or $record.thumbprint -cne $thumbprint -or $record.store -cne 'CurrentUser/Root'){throw 'Trust record or certificate fingerprint mismatch'}
  }
  function Save-Record {
    $tmp=$recordFile+'.tmp'
    [IO.File]::WriteAllText($tmp,($record | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tmp -Destination $recordFile -Force
  }
  $store=[Security.Cryptography.X509Certificates.X509Store]::new('Root',[Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
  $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
  $present=@($store.Certificates.Find([Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,$thumbprint,$false))
  if($present.Count -gt 1){throw 'Unexpected duplicate certificate thumbprint'}
  if($present.Count -eq 1 -and [Convert]::ToBase64String($present[0].RawData) -cne [Convert]::ToBase64String($certificate.RawData)){throw 'Stored certificate bytes do not match this fixture'}
  if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $hash){throw 'Certificate changed concurrently'}
  if($Action -in @('Install','Prepare')){
    if($present.Count -eq 1 -and $record -and $record.importedByThisRun -and $record.state -in @('Installing','Installed')){
      if($Action -eq 'Install' -or $present.Count -eq 1){$record.state='Installed'}; Save-Record
      Write-Output "Already installed by this run: CurrentUser/Root $thumbprint"
    }else{
      $record=[pscustomobject]@{run=$runName;runDir=$RunDir;store='CurrentUser/Root';caFileSha256=$hash;certificateSha256=$derHash;thumbprint=$thumbprint;subject=$certificate.Subject;existedBefore=($present.Count -eq 1);importedByThisRun=($present.Count -eq 0);state='Installing';recordedAt=(Get-Date).ToUniversalTime().ToString('o');removedAt=$null}
      Save-Record
      if($present.Count -eq 0 -and $Action -eq 'Install'){
        # Explicit opt-in imports only this checked CA; never disable protected-root policy or TLS.
        $start=[Diagnostics.ProcessStartInfo]::new()
        $start.FileName="$env:SystemRoot/System32/certutil.exe"
        $start.Arguments='-f -user -addstore Root "'+$file+'"'
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true
        $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        $import=[Diagnostics.Process]::Start($start)
        try{
          if(!$import.WaitForExit(30000)){
            $import.Kill();$import.WaitForExit()
            throw 'OS certificate trust confirmation blocked unattended import; no trust policy was weakened'
          }
          if($import.ExitCode -ne 0){throw "Fixture CA import failed (exit $($import.ExitCode)); approve this run CA using OS trust controls"}
        }finally{$import.Dispose()}
        $installed=[Security.Cryptography.X509Certificates.X509Store]::new('Root',[Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
        try{
          $installed.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
          $matches=@($installed.Certificates.Find([Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,$thumbprint,$false))
          if($matches.Count -ne 1 -or [Convert]::ToBase64String($matches[0].RawData) -cne [Convert]::ToBase64String($certificate.RawData)){throw 'Imported CA fingerprint could not be confirmed'}
        }finally{$installed.Close();$installed.Dispose()}
      }
      if($Action -eq 'Install' -or $present.Count -eq 1){$record.state='Installed'}; Save-Record
      Write-Output "Fixture CA state=$($record.state): CurrentUser/Root $thumbprint; importedByThisRun=$($record.importedByThisRun)"
    }
  }else{
    if(!$record){throw 'No ownership record; refusing certificate removal'}
    if(!$record.importedByThisRun -or $record.existedBefore -or $record.state -eq 'Removed'){
      Write-Output "No owned certificate removed: CurrentUser/Root $thumbprint"
    }else{
      if($record.state -notin @('Installing','Installed')){throw 'Unknown trust record state; refusing removal'}
      if($present.Count -eq 1){
        # Normal exact-thumbprint deletion; bound OS/AV confirmation rather than hanging forever.
        $start=[Diagnostics.ProcessStartInfo]::new()
        $start.FileName="$env:SystemRoot/System32/certutil.exe"
        $start.Arguments='-f -user -delstore Root '+$thumbprint
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true
        $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        $remove=[Diagnostics.Process]::Start($start)
        try{
          if(!$remove.WaitForExit(30000)){
            $remove.Kill();$remove.WaitForExit()
            throw 'OS certificate removal confirmation blocked; only this run CA remains pending removal'
          }
          if($remove.ExitCode -ne 0){throw "Owned fixture CA removal failed (exit $($remove.ExitCode)); no other certificate was targeted"}
        }finally{$remove.Dispose()}
        $verified=[Security.Cryptography.X509Certificates.X509Store]::new('Root',[Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
        try{
          $verified.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
          if(@($verified.Certificates.Find([Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,$thumbprint,$false)).Count -ne 0){throw 'Owned CA deletion could not be confirmed'}
        }finally{$verified.Close();$verified.Dispose()}
      }
      $record.state='Removed';$record.removedAt=(Get-Date).ToUniversalTime().ToString('o');Save-Record
      Write-Output "Removed only this run's imported CA: CurrentUser/Root $thumbprint"
    }
  }
  exit 0
}catch{
  Write-Error $_.Exception.Message -ErrorAction Continue
  exit 1
}finally{
  if($store){$store.Close();$store.Dispose()}
  if($certificate){$certificate.Dispose()}
  if($lock){$lock.Dispose()}
}
