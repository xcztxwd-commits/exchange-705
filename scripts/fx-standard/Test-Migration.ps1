$ErrorActionPreference = 'Stop'
$name = 'fx-migration-test-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$created = $false
function Query([string]$sql) {
    $out = $sql | docker exec -i $name mysql -uroot -pfixture-only --batch --skip-column-names fx_fixture 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable migration SQL failed' }
    return $out
}
try {
    docker run -d --name $name --label fx-migration-fixture=true --tmpfs /var/lib/mysql -e MYSQL_ROOT_PASSWORD=fixture-only -e MYSQL_DATABASE=fx_fixture mysql:5.7 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable MySQL' }
    $created=$true
    for($i=0;$i -lt 60;$i++) {
        docker exec $name mysqladmin --host=127.0.0.1 -uroot -pfixture-only ping 2>$null | Out-Null
        if($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 2
    }
    Query @'
CREATE TABLE trading_symbol (id BIGINT PRIMARY KEY, symbol VARCHAR(32), source_category VARCHAR(16), lot_size DECIMAL(32,16), fee_multiplier DECIMAL(32,16), min_trade_amount DECIMAL(32,16), volume_precision INT, row_version BIGINT, updated_at DATETIME);
CREATE TABLE contract_order (id BIGINT PRIMARY KEY, lot_size DECIMAL(32,16), fee DECIMAL(32,16));
CREATE TABLE asset_account (id BIGINT PRIMARY KEY, available DECIMAL(32,16));
INSERT INTO trading_symbol VALUES (1,'JPY=X','Forex',1000,30,0,2,0,NOW()),(2,'AAPL','US',1000,30,0,2,0,NOW());
INSERT INTO contract_order VALUES(1,1000,30);
INSERT INTO asset_account VALUES(1,48463.71);
'@ | Out-Null
    $sql=Get-Content -Raw (Join-Path $PSScriptRoot 'migrate.sql')
    Query $sql | Out-Null
    Query $sql | Out-Null
    $valid=Query "SELECT COUNT(*) FROM trading_symbol WHERE (id=1 AND lot_size=100000 AND fee_multiplier=7 AND min_trade_amount=.01 AND row_version=1) OR (id=2 AND lot_size=1000 AND fee_multiplier=30 AND row_version=0);"
    if($valid -ne '2') { throw 'Migration is incorrect or not idempotent' }
    $unchanged=Query "SELECT COUNT(*) FROM contract_order o JOIN asset_account a ON a.id=o.id WHERE o.lot_size=1000 AND o.fee=30 AND o.fx_base_currency IS NULL AND a.available=48463.71;"
    if($unchanged -ne '1') { throw 'Migration changed legacy orders or funds' }
    Query (Get-Content -Raw (Join-Path $PSScriptRoot 'rollback.sql')) | Out-Null
    $restored=Query "SELECT COUNT(*) FROM trading_symbol WHERE lot_size=1000 AND fee_multiplier=30 AND min_trade_amount=0;"
    if($restored -ne '2') { throw 'Rollback failed' }
    Write-Output 'PASS: MySQL 5.7 migration, idempotency, non-FX isolation, legacy/funds preservation and rollback.'
} finally {
    if($created) {
        $owned=docker inspect $name --format '{{ index .Config.Labels "fx-migration-fixture" }}'
        if($LASTEXITCODE -ne 0 -or $owned -ne 'true') { throw 'Refusing cleanup of unverified container' }
        docker rm -f -v $name | Out-Null
    }
}
