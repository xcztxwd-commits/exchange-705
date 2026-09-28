param([switch]$NormalRules)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'exchange-705-manual-test-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$previous = $env:MANUAL_TEST_JDBC
$previousNormal = $env:QA_MINIMAL_MYSQL
$previousPassword = $env:QA_DB_PASSWORD
$created = $false
try {
    docker run -d --name $name --label exchange-manual-fixture=true --tmpfs /var/lib/mysql -p 127.0.0.1::3306 -e MYSQL_ROOT_PASSWORD=equity-fixture-only -e MYSQL_DATABASE=manual_order_test mysql:5.7 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create isolated MySQL 5.7 fixture' }
    $created = $true
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot manual_order_test -e "select 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'MySQL fixture not ready' }
    $binding = docker port $name 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Fixture must be loopback only' }
    $env:MANUAL_TEST_JDBC = "jdbc:mysql://127.0.0.1:$($Matches[1])/manual_order_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
    & mvn -B -f "$root/exchange-backend/pom.xml" '-Dtest=ManualOrderCalculationTest,ManualOrderMySqlIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Manual order tests failed' }
    foreach ($class in @('ManualOrderCalculationTest','ManualOrderMySqlIT')) {
        [xml]$report = Get-Content -Raw "$root/exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.trade.$class.xml"
        if ([int]$report.testsuite.tests -le 0 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.failures -ne 0) { throw "Invalid acceptance report: $class" }
    }
    if ($NormalRules) {
        docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "create database manual_regression_test character set utf8mb4 collate utf8mb4_unicode_ci"'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create separate ordinary-trading regression database' }
        $env:QA_MINIMAL_MYSQL = $env:MANUAL_TEST_JDBC.Replace('/manual_order_test?', '/manual_regression_test?')
        $env:QA_DB_PASSWORD = 'equity-fixture-only'
        & mvn -B -f "$root/exchange-backend/pom.xml" '-Dtest=MinimalFixRegressionTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Ordinary-trading MySQL regression failed' }
        [xml]$report = Get-Content -Raw "$root/exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.security.MinimalFixRegressionTest.xml"
        if ([int]$report.testsuite.tests -le 0 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.failures -ne 0) { throw 'Invalid ordinary-trading acceptance report' }
    }
} finally {
    $env:MANUAL_TEST_JDBC = $previous
    $env:QA_MINIMAL_MYSQL = $previousNormal
    $env:QA_DB_PASSWORD = $previousPassword
    if ($created) {
        $owned = docker inspect $name --format '{{ index .Config.Labels "exchange-manual-fixture" }}'
        if ($LASTEXITCODE -ne 0 -or $owned -ne 'true') { throw 'Refusing cleanup of unverified container' }
        docker rm -f -v $name | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot remove owned temporary container' }
    }
}
