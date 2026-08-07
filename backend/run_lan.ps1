param(
    [int]$Port = 8765
)

$configuration = Get-NetIPConfiguration |
    Where-Object {
        $_.NetAdapter.Status -eq 'Up' -and
        $_.NetAdapter.HardwareInterface -and
        $_.IPv4Address -and
        $_.IPv4DefaultGateway
    } |
    Select-Object -First 1

if ($null -eq $configuration) {
    throw 'No active physical LAN/Wi-Fi interface with an IPv4 gateway was found.'
}

$lanAddress = $configuration.IPv4Address.IPAddress
$env:JUGGLUCO_ALLOWED_HOSTS = @(
    '127.0.0.1',
    'localhost',
    'testserver',
    '10.0.2.2',
    $lanAddress
) -join ','

Write-Host "Juggluco backend: http://${lanAddress}:$Port"
& "$env:LOCALAPPDATA\Microsoft\WindowsApps\python.exe" -m uvicorn app.main:app `
    --host 0.0.0.0 --port $Port
