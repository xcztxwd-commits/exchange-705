param([ValidateSet('Verify','Apply','Status')][string]$Mode='Verify',[string]$BackupPath)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sqlPath=Join-Path $root 'exchange-backend/src/main/resources/db/asset-equity/V001__net_equity_history.sql'
function Query([string]$sql) {
    $result=$sql | docker compose -f "$root/compose.yaml" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u1090 1090 -N -B'
    if($LASTEXITCODE -ne 0){throw 'Equity migration SQL failed'}
    return $result
}
$version=Query 'SELECT VERSION();'
if($version -notlike '5.7.*'){throw 'Expected MySQL 5.7'}
$tables=@(Query "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'asset_history_%';")
Write-Output "V001__net_equity_history mode=$Mode existingTables=$($tables.Count)"
if($Mode -eq 'Status') {
    if($tables -contains 'asset_history_migration'){Query 'SELECT migration_id,basis_version,applied_at FROM asset_history_migration;'}
    return
}
if($Mode -eq 'Apply') {
    if(!$BackupPath){throw 'Apply requires this run BackupPath'}
    $backup=(Resolve-Path -LiteralPath $BackupPath).Path
    if(!$backup.StartsWith((Join-Path $root 'rollback')+[IO.Path]::DirectorySeparatorChar)){throw 'Backup must belong to this workspace rollback directory'}
    $hash=Get-Content -LiteralPath (Join-Path $backup 'snapshot-sha256.json') -Raw | ConvertFrom-Json
    if((Get-FileHash -Algorithm SHA256 -LiteralPath $hash.Path).Hash -ne $hash.Hash){throw 'Legacy snapshot backup hash mismatch'}
    if($tables.Count -gt 0){
        if($tables -notcontains 'asset_history_migration'){throw 'Unrecognized existing equity tables; back up and inspect first'}
        $migration=@(Query "SELECT migration_id FROM asset_history_migration WHERE migration_id='V001__net_equity_history';")
        if($migration.Count -ne 1 -or $tables.Count -ne 8){throw 'Partial or foreign migration; do not overwrite'}
        Write-Output 'V001 already applied; no writes performed';return
    }
    Get-Content -LiteralPath $sqlPath -Raw | docker compose -f "$root/compose.yaml" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u1090 1090'
    if($LASTEXITCODE -ne 0){throw 'Incremental migration failed; existing structures retained'}
    Write-Output 'APPLIED V001__net_equity_history; legacy rows untouched'
} else {
    if($tables.Count -gt 0 -and $tables.Count -ne 8){throw 'Unexpected or partial equity schema'}
    Write-Output 'VERIFY: additive DDL only; no account, legacy history or order writes'
}
