param([string[]]$MavenArguments = @('verify'))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $root
try {
    python scripts/multitenant/isolation_gate.py --check
    if ($LASTEXITCODE -ne 0) { throw 'Tenant isolation source review gate failed; build stopped.' }
    & mvn -f exchange-backend/pom.xml @MavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
} finally { Pop-Location }
