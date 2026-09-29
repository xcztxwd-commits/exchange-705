[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$BackupFile, [string]$ReportDirectory = (Join-Path $PSScriptRoot '../../reports/crypto-quantity-migration'))
$ErrorActionPreference='Stop'
$BackupFile=(Resolve-Path -LiteralPath $BackupFile).Path
New-Item -ItemType Directory -Force $ReportDirectory | Out-Null
$ReportDirectory=(Resolve-Path $ReportDirectory).Path
$name='cq-migration-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$created=$false
function Query([string]$sql) {
 $out=$sql | docker exec -i $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names cq_fixture' 2>&1
 if($LASTEXITCODE -ne 0){throw "Isolated SQL failed: $out"}
 return ($out -join "`n")
}
function Apply([string]$file) {Query ("SET @cq_apply=1; SET @cq_database='cq_fixture';`n"+(Get-Content -Raw (Join-Path $PSScriptRoot $file)))}
function ExpectFailure([string]$sql) { $failed=$false;try {Query $sql | Out-Null}catch{$failed=$true};if(!$failed){throw 'Expected SQL rejection did not occur'} }
try {
 docker run -d --name $name --label crypto-quantity-isolation=true --tmpfs /var/lib/mysql -e MYSQL_ROOT_PASSWORD=cq-isolated-only -e MYSQL_DATABASE=cq_fixture mysql:5.7 | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cannot create isolated MySQL'};$created=$true
 $ready=$false
 for($i=0;$i -lt 60;$i++) {docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin -h127.0.0.1 -uroot ping' 2>$null | Out-Null;if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep 2}
 if(!$ready){throw 'Isolated MySQL not ready'}
 docker cp $BackupFile "${name}:/tmp/restore.sql"
 if($LASTEXITCODE -ne 0){throw 'Copy backup failed'}
 docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot cq_fixture < /tmp/restore.sql'
 if($LASTEXITCODE -ne 0){throw 'Restore failed'}
 $counts=Query "SELECT 'symbols',COUNT(*) FROM trading_symbol UNION ALL SELECT 'orders',COUNT(*) FROM contract_order UNION ALL SELECT 'accounts',COUNT(*) FROM asset_account;"
 $counts | Set-Content "$ReportDirectory/restored-counts.txt"
 $cols=@{};$before=@{}
 foreach($table in @('contract_order','asset_account')) {
  $cols[$table]=Query "SELECT GROUP_CONCAT(CONCAT(CHAR(96),COLUMN_NAME,CHAR(96)) ORDER BY ORDINAL_POSITION SEPARATOR ',') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$table';"
  $before[$table]=Query "SELECT $($cols[$table]) FROM $table ORDER BY id;"
 }
 $nonCrypto=Query 'SELECT id,symbol,lot_size,fee_multiplier FROM trading_symbol WHERE id NOT IN(72,73,74) ORDER BY id;'
 Apply 'schema.sql' | Out-Null;Apply 'schema.sql' | Out-Null
 Apply 'migrate.sql' | Out-Null
 $after=Query 'SELECT id,symbol,lot_size,fee_multiplier,quantity_unit_type,spec_version,min_order_quantity,quantity_step,min_order_notional,row_version FROM trading_symbol WHERE id IN(72,73,74) ORDER BY id;'
 $after | Set-Content "$ReportDirectory/config-after.txt"
 if((Query "SELECT COUNT(*) FROM trading_symbol WHERE id IN(72,73,74) AND lot_size=1 AND fee_multiplier=.03 AND quantity_unit_type='BASE_ASSET' AND spec_version=1 AND min_order_notional=10;") -ne '3'){throw 'Incorrect conversion'}
 Apply 'migrate.sql' | Out-Null
 if((Query 'SELECT id,symbol,lot_size,fee_multiplier,quantity_unit_type,spec_version,min_order_quantity,quantity_step,min_order_notional,row_version FROM trading_symbol WHERE id IN(72,73,74) ORDER BY id;') -ne $after){throw 'Not idempotent'}
 foreach($table in @('contract_order','asset_account')) {if((Query "SELECT $($cols[$table]) FROM $table ORDER BY id;") -cne $before[$table]){throw "Historical $table changed"}}
 if((Query 'SELECT id,symbol,lot_size,fee_multiplier FROM trading_symbol WHERE id NOT IN(72,73,74) ORDER BY id;') -cne $nonCrypto){throw 'Unrelated instruments changed'}
 Apply 'rollback.sql' | Out-Null
 if((Query 'SELECT COUNT(*) FROM trading_symbol s JOIN trading_symbol_cq_backup_20260929 b ON b.id=s.id WHERE s.lot_size=b.lot_size AND s.fee_multiplier=b.fee_multiplier AND s.quantity_unit_type IS NULL;') -ne '3'){throw 'Rollback did not restore config'}
 Query 'UPDATE trading_symbol SET lot_size=3,fee_multiplier=1 WHERE id=72;' | Out-Null
 ExpectFailure ("SET @cq_apply=1; SET @cq_database='cq_fixture';`n"+(Get-Content -Raw "$PSScriptRoot/migrate.sql"))
 if((Query 'SELECT COUNT(*) FROM trading_symbol WHERE id IN(72,73,74) AND quantity_unit_type IS NULL;') -ne '3'){throw 'Failed migration partially applied'}
 Query 'UPDATE trading_symbol SET lot_size=1000,fee_multiplier=30 WHERE id=72;' | Out-Null
 Apply 'migrate.sql' | Out-Null
 # Only in this restored disposable database: simulate a new-spec order to prove rollback guard.
 Query "UPDATE contract_order SET symbol='BTCUSDT',quantity_unit_type='BASE_ASSET',spec_version=1 WHERE id=(SELECT n.id FROM (SELECT MIN(id) id FROM contract_order) n);" | Out-Null
 ExpectFailure ("SET @cq_apply=1; SET @cq_database='cq_fixture';`n"+(Get-Content -Raw "$PSScriptRoot/rollback.sql"))
 $evidence=[ordered]@{status='PASS';container=$name;image='mysql:5.7';backupSha256=(Get-FileHash $BackupFile).Hash;restoredCounts=$counts;historicalRowsUnchanged=$true;nonCryptoUnchanged=$true;idempotent=$true;inexactFeeRejected=$true;rollbackGuard=$true;businessDatabaseWrites=0}
 $evidence | ConvertTo-Json | Set-Content "$ReportDirectory/result.json"
 $evidence | ConvertTo-Json
} finally {
 if($created){$label=docker inspect $name --format '{{ index .Config.Labels "crypto-quantity-isolation" }}';if($LASTEXITCODE -ne 0 -or $label -ne 'true' -or $name -notmatch '^cq-migration-[a-f0-9]{12}$'){throw 'Refusing unverified cleanup'};docker rm -f $name | Out-Null}
}
