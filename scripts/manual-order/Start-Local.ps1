# Rebuild/restart only the retained browser fixture. Does not touch original compose services.
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$names = @('mysql','redis','backend','admin','pc','mobile') | ForEach-Object { "exchange-705-manual-browser-$_" }
foreach ($name in $names) {
    $owned = docker inspect $name --format '{{ index .Config.Labels "exchange-manual-browser" }}'
    if ($LASTEXITCODE -ne 0 -or $owned -ne 'true') { throw "Missing or unowned fixture: $name" }
}
if ((docker inspect exchange-705-manual-browser-mysql --format '{{.State.Running}}') -ne 'true') {
    throw 'Fixture MySQL is stopped; tmpfs data needs restoring into isolation before this helper can run'
}
$previous = $env:VITE_API_BASE_URL
try {
    # Never use the repository's externally-targeted production API for local acceptance.
    $env:VITE_API_BASE_URL = '/api'
    foreach ($project in @('exchange-admin','exchange-pc','exchange-frontend')) {
        npm --prefix "$root/$project" run build
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $project" }
    }
    docker stop exchange-705-manual-browser-backend | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop fixture backend before replacing its mounted jar' }
    mvn -B -f "$root/exchange-backend/pom.xml" '-Dmaven.compiler.release=8' '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; fixture backend remains stopped' }
    docker start @names
    if ($LASTEXITCODE -ne 0) { throw 'Fixture start failed' }
    Write-Output 'Admin http://127.0.0.1:18151 | PC http://127.0.0.1:18150 | Mobile http://127.0.0.1:18152'
    Write-Output 'This is a rebuild helper, not a functional test. MySQL uses tmpfs: stopping MySQL loses fixture data.'
} finally { $env:VITE_API_BASE_URL = $previous }
