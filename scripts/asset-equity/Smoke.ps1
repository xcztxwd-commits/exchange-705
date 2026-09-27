param([string]$BaseUrl='http://localhost:17052',[string]$SessionFile)
$ErrorActionPreference='Stop'
if($BaseUrl -notmatch '^http://(localhost|127\.0\.0\.1):17052$'){throw 'Only local mobile proxy is allowed'}
if($SessionFile){
    $sessions=Import-Clixml -LiteralPath $SessionFile
    $env:EQUITY_SMOKE_TOKEN=$sessions[0].GetNetworkCredential().Password
    $env:EQUITY_SMOKE_OTHER_TOKEN=$sessions[1].GetNetworkCredential().Password
}
if(!$env:EQUITY_SMOKE_TOKEN -or !$env:EQUITY_SMOKE_OTHER_TOKEN){throw 'Set EQUITY_SMOKE_TOKEN and EQUITY_SMOKE_OTHER_TOKEN to authorized, distinct existing local sessions; tokens are never printed'}
$headers=@{Authorization="Bearer $env:EQUITY_SMOKE_TOKEN"}
$other=@{Authorization="Bearer $env:EQUITY_SMOKE_OTHER_TOKEN"}
function Get-History($range,$header){Invoke-RestMethod -Uri "$BaseUrl/api/user/asset-history?range=$range" -Headers $header}
$tables=@('asset_history_1m','asset_history_1h','asset_history_4h','asset_history_1d')
$ranges=@('1D','1W','1M','1Y');$intervals=@(60000,3600000,14400000,86400000)
for($i=0;$i -lt 4;$i++){
    $r=Get-History $ranges[$i] $headers
    if($r.schemaVersion -ne 2 -or $r.basisVersion -ne 'net_equity_v1' -or $r.sourceTable -ne $tables[$i] -or $r.intervalMs -ne $intervals[$i]){throw 'Version/table routing mismatch'}
    if($r.optionValuationMethod -ne 'PRINCIPAL_COST_NOT_FAIR_VALUE'){throw 'Option basis missing'}
    if($r.total -ne $r.live.value){throw 'Headline/live mismatch'}
    if($r.total -ne $null){
        $c=$r.components
        # Decimal parsing is confined to read-only verification; service remains DECIMAL(32,16).
        $debt=[decimal]$c.loan_principal+[decimal]$c.accrued_interest+[decimal]$c.overdue_fees+[decimal]$c.accrued_trading_fees+[decimal]$c.other_liabilities
        $net=[decimal]$c.wallet_balance+[decimal]$c.contract_unrealized_pnl+[decimal]$c.option_unrealized_pnl+[decimal]$c.receivables-$debt
        if($debt -ne [decimal]$c.liabilities_total -or $net -ne [decimal]$r.total){throw 'Component arithmetic mismatch'}
    } elseif($r.valuationStatus -ne 'INCOMPLETE'){throw 'NULL equity missing quality state'}
    if(@($r.points | Where-Object {$_.time -gt $r.asOf -or $_.time -lt $r.from}).Count){throw 'Out-of-window observation'}
}
# A supplied query userId must not change the authenticated identity; compare identity-scoped data, not volatile live prices.
$a=Get-History '1D' $headers;$b=Get-History '1D' $other
if($env:EQUITY_SMOKE_TOKEN -eq $env:EQUITY_SMOKE_OTHER_TOKEN){throw 'Distinct authorized sessions required'}
if(!$a.userId -or !$b.userId -or $a.userId -eq $b.userId){throw 'Sessions do not identify distinct accounts'}
$swapped=Invoke-RestMethod -Uri "$BaseUrl/api/user/asset-history?range=1D&userId=$($b.userId)" -Headers $headers
if($swapped.userId -ne $a.userId){throw 'Query parameter overrode authenticated identity'}
foreach($header in @($headers,$other)){
    $seen=$false
    for($i=0;$i -lt 18;$i++){
        $sample=Get-History '1D' $header
        if(@($sample.points).Count -gt 0){$seen=$true;break}
        Start-Sleep -Seconds 5
    }
    if(!$seen){throw 'No scheduled minute observations appeared'}
    foreach($range in @('1W','1M','1Y')){if(@((Get-History $range $header).points).Count -eq 0){throw 'Current rollup missing'}}
}
try{Invoke-WebRequest -UseBasicParsing "$BaseUrl/api/user/asset-history?range=1D" | Out-Null;throw 'Unauthenticated history unexpectedly allowed'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -notin @(401,403)){throw}}
Write-Output 'PASS: read-only components, versions, all ranges, new minute observations/current buckets, distinct-user identity isolation and unauthorized rejection'
