$ErrorActionPreference = 'Stop'
foreach ($port in 17050, 17051, 17052) {
    $base = "http://127.0.0.1:$port"
    $page = Invoke-WebRequest $base -TimeoutSec 20
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'id="app"') {
        throw "Frontend failed: $base"
    }
    $null = Invoke-RestMethod "$base/api/uploads/images/test/ping" -TimeoutSec 20
    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $timeout = [System.Threading.CancellationTokenSource]::new(10000)
    try {
        $null = $socket.ConnectAsync([uri]"ws://127.0.0.1:$port/api/ws/market", $timeout.Token).GetAwaiter().GetResult()
        if ($socket.State -ne 'Open') { throw "WebSocket failed: $base" }
    } finally {
        $socket.Dispose()
        $timeout.Dispose()
    }
    Write-Output "$base HTTP/API/WebSocket OK"
}
