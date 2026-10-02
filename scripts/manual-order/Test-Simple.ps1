param([string]$MySqlBin = 'C:\Environment\MySQL\8.0.21\bin')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tempRoot = [IO.Path]::GetFullPath((Join-Path $root 'rollback')) + [IO.Path]::DirectorySeparatorChar
# MySQL 8.0 Windows cannot reliably parse a non-ASCII native datadir argument.
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$directory = Join-Path $tempRoot ('exchange-simple-fixture-' + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $directory 'data'
$server = $null
$previous = $env:SIMPLE_TEST_JDBC
try {
    New-Item -ItemType Directory -Path $directory | Out-Null
    $mysqld = Join-Path $MySqlBin 'mysqld.exe'
    $mysql = Join-Path $MySqlBin 'mysql.exe'
    if (!(Test-Path -LiteralPath $mysqld) -or !(Test-Path -LiteralPath $mysql)) { throw 'Specify an installed MySQL binary directory; never point this script at an application datadir.' }
    & $mysqld --no-defaults --initialize-insecure "--datadir=$data" --console 2>&1 | Out-File (Join-Path $directory 'initialize.log')
    if ($LASTEXITCODE -ne 0) { throw "Isolated initialization failed: $directory" }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    $server = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults', "--datadir=`"$data`"", '--bind-address=127.0.0.1', "--port=$port", '--mysqlx=OFF', '--console') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'server.log') -RedirectStandardError (Join-Path $directory 'server-error.log')
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        & $mysql --no-defaults '--host=127.0.0.1' "-P$port" -uroot -e 'select 1' *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        if ($server.HasExited) { throw "Isolated server exited ($($server.ExitCode)): $directory" }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw "Isolated server not ready: $directory" }
    & $mysql --no-defaults '--host=127.0.0.1' "-P$port" -uroot -e 'create database simple_manual_order_test character set utf8mb4 collate utf8mb4_unicode_ci'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable fixture schema' }
    $env:SIMPLE_TEST_JDBC = "jdbc:mysql://127.0.0.1:$port/simple_manual_order_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
    & mvn -B -f "$root/exchange-backend/pom.xml" '-Dtest=SimpleManualOrderMySqlIT,SimpleManualOrderGeneratorTest,SimpleManualOrderPricesTest,SimpleManualOrderAdminTest,ManualOrderGeneratorTest,ManualOrderCalculationTest,ManualOrderHashTest,ManualOrderToleranceTest,ManualOrderPricesLoadingTest,ManualOrderGenerationMatrixTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Simple manual order tests failed' }
    [xml]$report = Get-Content -Raw "$root/exchange-backend/target/surefire-reports/TEST-com.gtcfesk.exchange.trade.SimpleManualOrderMySqlIT.xml"
    if ([int]$report.testsuite.tests -le 0 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.failures -ne 0) { throw 'Missing or invalid fixture acceptance report' }
    Write-Output "Isolated MySQL acceptance passed ($($report.testsuite.tests) cases)."
} catch {
    foreach ($name in @('initialize.log','server-error.log','server.log')) { $log=Join-Path $directory $name; if(Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 12 } }
    throw
} finally {
    $env:SIMPLE_TEST_JDBC = $previous
    if ($server) {
        # Windows MySQL can launch a watchdog child. Match the unique datadir on every owned PID.
        $owned = @(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($directory) -and $_.ExecutablePath -eq $mysqld })
        if ($owned.Count) {
            & $mysql --no-defaults '--host=127.0.0.1' "-P$port" -uroot --connect-timeout=2 -e 'shutdown' *> $null
            $null = $server.WaitForExit(5000)
            foreach ($process in $owned) { $running=Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue; if($running) { Stop-Process -Id $process.ProcessId -Force; $null=$running.WaitForExit(5000) } }
        }
    }
    $resolved = [IO.Path]::GetFullPath($directory)
    if (!$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'exchange-simple-fixture-*') { throw 'Refusing cleanup outside owned temporary directory' }
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
}
