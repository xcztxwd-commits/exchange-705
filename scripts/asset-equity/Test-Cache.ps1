param([Parameter(Mandatory)][string]$ReportPath,[switch]$Performance)
$ErrorActionPreference='Stop'
if(![IO.Path]::IsPathRooted($ReportPath)){throw 'ReportPath must be absolute'}
New-Item -ItemType Directory -Force -Path $ReportPath | Out-Null
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$id=[Guid]::NewGuid().ToString('N').Substring(0,12);$mysql="ahc-$id-mysql";$redis="ahc-$id-redis";$created=@()
$variables=@('CACHE_TEST_JDBC','CACHE_TEST_REPORT','CACHE_TEST_REDIS_PORT','CACHE_TEST_REDIS_CONTAINER','CACHE_TEST_PERFORMANCE')
$previous=@{};foreach($v in $variables){$previous[$v]=[Environment]::GetEnvironmentVariable($v)}
function Checked {param([string]$File,[string[]]$Arguments) & $File @Arguments; if($LASTEXITCODE -ne 0){throw "$File failed ($LASTEXITCODE)"}}
function SqlFile([string]$path){Get-Content -Raw -LiteralPath $path | docker exec -i $mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot asset_cache_test';if($LASTEXITCODE -ne 0){throw "Fixture SQL failed: $path"}}
function Sql([string]$sql){$sql | docker exec -i $mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot asset_cache_test';if($LASTEXITCODE -ne 0){throw 'Fixture SQL failed'}}
function Plans([string]$label){
    $sql='';foreach($t in @('contract_order','option_order','financial_yield_record','loan_record')){$sql+="ANALYZE TABLE $t; SHOW INDEX FROM $t;"}
    $sql+="EXPLAIN SELECT SUM(profit) FROM contract_order WHERE user_id=1 AND status='CLOSED' AND close_time>='2026-09-01'; EXPLAIN SELECT SUM(profit) FROM option_order WHERE user_id=1 AND status='CLOSED' AND close_time>='2026-09-01'; EXPLAIN SELECT SUM(daily_yield) FROM financial_yield_record WHERE user_id=1 AND status='PAID' AND paid_at>='2026-09-01'; EXPLAIN SELECT amount FROM loan_record WHERE user_id=1;"
    Sql $sql | Set-Content "$ReportPath/explain-$label.txt"
}
try {
    Checked docker @('run','-d','--name',$mysql,'--label','asset-history-cache-fixture=true','--tmpfs','/var/lib/mysql','-p','127.0.0.1::3306','-e','MYSQL_ROOT_PASSWORD=equity-fixture-only','-e','MYSQL_DATABASE=asset_cache_test','mysql:5.7');$created+=$mysql
    Checked docker @('run','-d','--name',$redis,'--label','asset-history-cache-fixture=true','--tmpfs','/data','-p','127.0.0.1::6379','redis:7-alpine','redis-server','--save','','--appendonly','no');$created+=$redis
    $ready=$false;for($i=0;$i -lt 60;$i++){
        docker exec $mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot asset_cache_test -e "select 1"' *> $null
        if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 2
    };if(!$ready){throw 'MySQL fixture readiness failed'}
    Checked docker @('exec',$redis,'redis-cli','ping')
    $binding=docker port $mysql 3306/tcp;if($binding -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'MySQL not loopback-only'};$port=$Matches[1]
    $binding=docker port $redis 6379/tcp;if($binding -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Redis not loopback-only'};$redisPort=$Matches[1]
    @{mysql=$mysql;redis=$redis;jdbcPort=$port;redisPort=$redisPort;database='asset_cache_test';label='asset-history-cache-fixture=true'}|ConvertTo-Json|Set-Content "$ReportPath/fixture.json"
    SqlFile "$root/exchange-backend/src/test/resources/manual-order-schema.sql"
    SqlFile "$root/exchange-backend/src/main/resources/db/asset-equity/V001__net_equity_history.sql"
    SqlFile "$root/exchange-backend/src/main/resources/db/manual/705-manual-history-order.sql"
    foreach($size in @(10000,100000)){
        Sql 'DELETE FROM contract_order; DELETE FROM option_order; DELETE FROM financial_yield_record; DELETE FROM loan_record;'
        Sql ("SET @fixture_rows=$size;"+(Get-Content -Raw "$root/exchange-backend/src/test/resources/asset-cache-performance.sql"))
        Plans "before-$size"
    }
    & "$PSScriptRoot/Migrate-Cache.ps1" -Mode Verify -Container $mysql -Database asset_cache_test *> "$ReportPath/migration-verify-before.log"
    & "$PSScriptRoot/Migrate-Cache.ps1" -Mode Apply -Container $mysql -Database asset_cache_test -ManifestPath "$ReportPath/migration-objects.json" *> "$ReportPath/migration-apply.log"
    & "$PSScriptRoot/Migrate-Cache.ps1" -Mode Apply -Container $mysql -Database asset_cache_test -ManifestPath "$ReportPath/migration-objects.json" *> "$ReportPath/migration-repeat.log"
    & "$PSScriptRoot/Migrate-Cache.ps1" -Mode Status -Container $mysql -Database asset_cache_test *> "$ReportPath/migration-status.log"
    Plans 'after-100000'
    $env:CACHE_TEST_JDBC="jdbc:mysql://127.0.0.1:$port/asset_cache_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    $env:CACHE_TEST_REPORT=$ReportPath;$env:CACHE_TEST_REDIS_PORT=$redisPort;$env:CACHE_TEST_REDIS_CONTAINER=$redis;$env:CACHE_TEST_PERFORMANCE=$Performance.IsPresent.ToString().ToLowerInvariant()
    & mvn -B -f "$root/exchange-backend/pom.xml" '-Dtest=AssetHistoryCacheIT' test *> "$ReportPath/cache-tests.log"
    $exit=$LASTEXITCODE
    Copy-Item "$root/exchange-backend/target/surefire-reports/*AssetHistoryCacheIT*" $ReportPath -ErrorAction SilentlyContinue
    if($exit -ne 0){throw "Real cache tests failed. See $ReportPath/cache-tests.log"}
    [xml]$xml=Get-Content -Raw "$ReportPath/TEST-com.gtcfesk.exchange.user.AssetHistoryCacheIT.xml"
    if([int]$xml.testsuite.tests -lt 12 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0){throw 'Incomplete or failing real cache acceptance'}
    foreach($i in 1..12){$prefix='C{0:D2}_' -f $i;if(@($xml.testsuite.testcase|Where-Object {$_.name.StartsWith($prefix)}).Count -ne 1){throw "Missing acceptance case $prefix"}}
    if(!(Test-Path "$ReportPath/browser-results.json")){throw 'Missing actual browser evidence'}
    & "$PSScriptRoot/Migrate-Cache.ps1" -Mode RollbackIndexes -Container $mysql -Database asset_cache_test -ManifestPath "$ReportPath/migration-objects.json" *> "$ReportPath/migration-rollback.log"
    Write-Output "PASS real cache service tests: $ReportPath"
} finally {
    foreach($v in $variables){[Environment]::SetEnvironmentVariable($v,$previous[$v])}
    foreach($name in $created){
        $owned=docker inspect $name --format '{{ index .Config.Labels "asset-history-cache-fixture" }}'
        if($LASTEXITCODE -ne 0 -or $owned -ne 'true' -or $name -notmatch "^ahc-$id-(mysql|redis)$"){throw 'Refusing cleanup of unverified container'}
        # Redis may still be paused if a test JVM was killed.
        $paused=docker inspect $name --format '{{ .State.Paused }}';if($paused -eq 'true'){Checked docker @('unpause',$name)}
        Checked docker @('rm','-f','-v',$name)
        "Removed owned container $name" | Add-Content "$ReportPath/cleanup.log"
    }
}
