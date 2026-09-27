param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'exchange-705-equity-test-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$previous = $env:EQUITY_TEST_JDBC
$created = $false
try {
    docker run -d --name $name --label exchange-equity-fixture=true --tmpfs /var/lib/mysql -p 127.0.0.1::3306 -e MYSQL_ROOT_PASSWORD=equity-fixture-only -e MYSQL_DATABASE=equity_test mysql:5.7 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create isolated MySQL 5.7 fixture' }
    $created = $true
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot equity_test -e "select 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'MySQL fixture did not become ready' }
    $binding = docker port $name 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Fixture is not loopback-only' }
    $env:EQUITY_TEST_JDBC = "jdbc:mysql://127.0.0.1:$($Matches[1])/equity_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
    & mvn -B -f "$root/exchange-backend/pom.xml" '-Dtest=AssetEquityMySqlTest,AssetEquityValuationTest,AssetHistoryRollupTest,AssetHistoryTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Equity MySQL/valuation/history tests failed' }
    Write-Output 'PASS: isolated MySQL 5.7 DDL, UPSERT, locks, transaction, hierarchy and version tests'
} finally {
    $env:EQUITY_TEST_JDBC = $previous
    if ($created) {
        $owned = docker inspect $name --format '{{ index .Config.Labels "exchange-equity-fixture" }}'
        if ($LASTEXITCODE -ne 0 -or $owned -ne 'true') { throw 'Refusing cleanup of an unverified container' }
        docker rm -f -v $name | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to remove this test-owned container' }
    }
}
