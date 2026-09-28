param(
    [ValidateSet('Verify','Apply','Status','RollbackIndexes')][string]$Mode='Verify',
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Container,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_]+$')][string]$Database,
    [ValidatePattern('^[a-zA-Z0-9_]+$')][string]$User='root',
    [ValidateSet('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')][string]$PasswordVariable='MYSQL_ROOT_PASSWORD',
    [string]$ManifestPath
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
function Query([string]$sql) {
    $result=$sql | docker exec -i $Container sh -c ('MYSQL_PWD="$'+$PasswordVariable+'" mysql -u'+$User+' '+$Database+' -N -B --skip-column-names')
    if($LASTEXITCODE -ne 0){throw "Cache migration SQL failed ($Mode)"}
    return $result
}
if((Query 'SELECT VERSION()') -notlike '5.7.*'){throw 'Requires MySQL 5.7'}
if($Mode -in @('Apply','RollbackIndexes') -and !$ManifestPath){throw 'Write modes require an absolute ManifestPath'}
$objects=@()
if($ManifestPath){
    if(![IO.Path]::IsPathRooted($ManifestPath)){throw 'ManifestPath must be absolute'}
    if(Test-Path -LiteralPath $ManifestPath){
        $old=Get-Content -Raw -LiteralPath $ManifestPath|ConvertFrom-Json
        if($old.container -ne $Container -or $old.database -ne $Database){throw 'Manifest target mismatch'}
        $objects=@($old.objects)
    }
}
function Remember($kind,$table,$name){
    $script:objects+=@{kind=$kind;table=$table;name=$name}
    $data=@{container=$Container;database=$Database;objects=$script:objects}|ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText("$ManifestPath.tmp",$data);Move-Item -LiteralPath "$ManifestPath.tmp" -Destination $ManifestPath -Force
}
$indexes=@(
    @('contract_order','ix_ahc_contract_user_status_close','user_id,status,close_time'),
    @('option_order','ix_ahc_option_user_status_close','user_id,status,close_time'),
    @('financial_yield_record','ix_ahc_yield_user_status_paid','user_id,status,paid_at'),
    @('loan_record','ix_ahc_loan_user','user_id')
)
if($Mode -eq 'RollbackIndexes'){
    foreach($entry in $objects|Where-Object kind -eq 'index'){
        $known=@($indexes|Where-Object {$_[0] -eq $entry.table -and $_[1] -eq $entry.name})
        if(!$known.Count){throw 'Unknown manifest object'}
        $definition=Query "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$($entry.table)' AND index_name='$($entry.name)'"
        $expected=($indexes|Where-Object {$_[0] -eq $entry.table -and $_[1] -eq $entry.name})[2]
        if($definition -and $definition -ne $expected){throw 'Owned index definition changed; refusing rollback'}
        $exists=Query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$($entry.table)' AND index_name='$($entry.name)'"
        if([int]$exists -gt 0){Query "SET SESSION lock_wait_timeout=5; ALTER TABLE $($entry.table) DROP INDEX $($entry.name), ALGORITHM=INPLACE, LOCK=NONE;"}
    }
    Write-Output 'Only manifest-owned indexes removed. Revision table and triggers retained.';return
}
foreach($index in $indexes){
    $table,$name,$columns=$index
    $existing=@(Query "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$table' AND sub_part IS NULL GROUP BY index_name")
    $covered=@($existing|Where-Object {$_ -eq $columns -or $_.StartsWith($columns+',')}).Count -gt 0
    Write-Output "INDEX $table ($columns) covered=$covered"
    if(!$covered -and $Mode -eq 'Apply'){
        Query "SET SESSION lock_wait_timeout=5; ALTER TABLE $table ADD INDEX $name ($columns), ALGORITHM=INPLACE, LOCK=NONE;"
        Remember 'index' $table $name
    }
}
$sql=Get-Content -Raw "$root/exchange-backend/src/main/resources/db/asset-equity/V002__history_cache.sql"
$sql=$sql -replace '(?m)^--[^\r\n]*',''
foreach($statement in $sql.Split(';')){
    $statement=$statement.Trim();if(!$statement){continue}
    if($statement -match '^CREATE TABLE'){
        $exists=[int](Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='asset_history_revision'") -gt 0
        Write-Output "TABLE asset_history_revision exists=$exists"
        if(!$exists -and $Mode -eq 'Apply'){Query $statement;Remember 'table' 'asset_history_revision' 'asset_history_revision'}
        if($exists){
            $shape=Query "SELECT GROUP_CONCAT(CONCAT(column_name,':',data_type,':',is_nullable) ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='asset_history_revision'"
            if($shape -ne 'user_id:bigint:NO,basis_version:varchar:NO,level:tinyint:NO,revision:bigint:NO'){throw 'Unexpected revision table definition'}
            if((Query "SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='asset_history_revision'") -ne 'InnoDB'){throw 'Revision table must be transactional'}
            if((Query "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='asset_history_revision' AND index_name='PRIMARY'") -ne 'user_id,basis_version,level'){throw 'Unexpected revision primary key'}
        }
    } elseif($statement -match '^CREATE TRIGGER (\w+) AFTER (\w+) ON (\w+) FOR EACH ROW (.*)$'){
        $name,$event,$table,$body=$Matches[1],$Matches[2],$Matches[3],$Matches[4]
        $existing=@(Query "SELECT action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='$name'")
        if($existing.Count){
            # MySQL preserves the statement; normalize only whitespace and identifier quotes.
            if(($existing[0] -replace '[\s`]+','').ToLowerInvariant() -ne ($body -replace '[\s`]+','').ToLowerInvariant()){throw "Existing trigger differs: $name"}
        } elseif($Mode -eq 'Apply'){Query "SET SESSION lock_wait_timeout=5; $statement";Remember 'trigger' $table $name}
        Write-Output "TRIGGER $name exists=$($existing.Count -gt 0)"
    } else {throw 'Unexpected migration statement'}
}
Write-Output "$Mode complete. Verify/Status are read-only. Apply is additive, DDL commits separately; enable cache only after complete verification."
