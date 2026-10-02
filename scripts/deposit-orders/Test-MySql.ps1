$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = [Guid]::NewGuid().ToString('N')
$name = "deposit-order-test-$run"
$db = "deposit_order_test_$run"
$password = [Guid]::NewGuid().ToString('N')
$reports = Join-Path $root "reports/deposit-orders/mysql-$run"
New-Item -ItemType Directory -Path $reports -Force | Out-Null
$created = $false
try {
    & docker version *> (Join-Path $reports 'docker-version.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Docker unavailable' }
    & docker run -d --name $name --tmpfs /var/lib/mysql --label "deposit-test-run=$run" -e "MYSQL_ROOT_PASSWORD=$password" -e "MYSQL_DATABASE=$db" -p '127.0.0.1::3306' mysql:5.7 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci > (Join-Path $reports 'container.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL startup failed' }
    $created = $true
    $ready = $false
    for ($i=0; $i -lt 90; $i++) {
        & docker exec -e "MYSQL_PWD=$password" $name mysql -uroot -N -e 'SELECT 1' $db 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Isolated MySQL did not become ready' }
    $port = & docker port $name 3306
    if ($port -notmatch '^127\.0\.0\.1:\d+$') { throw 'Unsafe published address' }
    "container=$name`ndatabase=$db`naddress=$port" | Set-Content (Join-Path $reports 'isolation.txt')
    & docker exec -e "MYSQL_PWD=$password" $name mysql -uroot -N -e 'SELECT VERSION(), DATABASE()' $db > (Join-Path $reports 'mysql-version.txt')
    function SqlFile($path, $log) {
        Get-Content -Raw -Encoding UTF8 -LiteralPath $path | & docker exec -i -e "MYSQL_PWD=$password" $name mysql -uroot --default-character-set=utf8mb4 $db *> $log
        if ($LASTEXITCODE -ne 0) { throw "SQL failed: $path" }
    }
    SqlFile (Join-Path $PSScriptRoot 'legacy-fixture.sql') (Join-Path $reports 'legacy-fixture.log')
    $snapshot = 'SELECT id,amount,status FROM deposit_record ORDER BY id; SELECT user_id,coin,available,frozen FROM asset_account ORDER BY id;'
    $before = & docker exec -e "MYSQL_PWD=$password" $name mysql -uroot -NB -e $snapshot $db
    $structureQuery = "SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record'),(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record'),(SELECT COUNT(*) FROM admin_menu WHERE menu_code IN ('deposit_orders','view_deposit_orders','manual_deposit','export_deposit_orders')),(SELECT COUNT(*) FROM menu_action WHERE action_code IN ('view_deposit_orders','manual_deposit','export_deposit_orders')),(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='deposit_credit_record')"
    for($i=1;$i -le 2;$i++) {
        SqlFile (Join-Path $root 'exchange-backend/src/main/resources/db/migration/add_deposit_order_details.sql') (Join-Path $reports "migration-$i.log")
        $after = & docker exec -e "MYSQL_PWD=$password" $name mysql -uroot -NB -e $snapshot $db
        if (($before -join "`n") -cne ($after -join "`n")) { throw 'Migration changed historical money/status' }
        $structure = & docker exec -e "MYSQL_PWD=$password" $name mysql -uroot -NB -e $structureQuery $db
        if ($LASTEXITCODE -ne 0) { throw 'Migration metadata check failed' }
        if ($i -eq 1) { $firstStructure = $structure -join "`n" }
        elseif ($firstStructure -cne ($structure -join "`n")) { throw 'Migration duplicated schema or menu metadata' }
    }
    "Two migrations: existing amounts, status and balances unchanged; schema and menu counts unchanged on rerun.`nmetadata_counts=$firstStructure" | Set-Content (Join-Path $reports 'migration-result.txt')
    # Maven runs in the network namespace of THIS disposable container only.
    $jdbc = "jdbc:mysql://127.0.0.1:3306/${db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    & docker run --rm --network "container:$name" -e "DEPOSIT_TEST_JDBC=$jdbc" -e "DEPOSIT_TEST_PASSWORD=$password" --mount "type=bind,source=$root/exchange-backend,target=/app" --mount "type=bind,source=$env:USERPROFILE/.m2,target=/root/.m2" -w /app maven:3.9.9-eclipse-temurin-8 mvn '-Dtest=DepositOrderMySqlIT' test *> (Join-Path $reports 'maven.log')
    $code=$LASTEXITCODE
    "maven_exit_code=$code" | Set-Content (Join-Path $reports 'exit-code.txt')
    Copy-Item (Join-Path $root 'exchange-backend/target/surefire-reports/*DepositOrderMySqlIT*') $reports -ErrorAction SilentlyContinue
    if($code -ne 0){throw "MySQL integration failed; reports: $reports"}
    [xml]$testResult = Get-Content -Raw (Join-Path $root 'exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.user.DepositOrderMySqlIT.xml')
    if([int]$testResult.testsuite.tests -lt 1 -or [int]$testResult.testsuite.errors -ne 0 -or [int]$testResult.testsuite.failures -ne 0 -or [int]$testResult.testsuite.skipped -ne 0) { throw 'Invalid MySQL acceptance report' }
    Write-Output "PASS: $reports"
} finally {
    if($created) {
        $label = & docker inspect --format '{{index .Config.Labels "deposit-test-run"}}' $name
        if($label -eq $run) { & docker rm -f $name | Out-Null }
        else { throw 'Container ownership mismatch: refusing cleanup' }
    }
}
