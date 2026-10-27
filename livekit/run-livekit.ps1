# Helper script to launch native Windows LiveKit Server for AEGIS with Dynamic LAN IP
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$serverExe = Join-Path $scriptDir "livekit-server.exe"
$configYaml = Join-Path $scriptDir "livekit.yaml"

if (-not (Test-Path $serverExe)) {
    Write-Error "livekit-server.exe not found in $scriptDir"
    exit 1
}

# Dynamically resolve active outbound LAN interface
$bestRoute = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1
$lanIp = "127.0.0.1"
if ($bestRoute) {
    $lanIp = (Get-NetIPAddress -InterfaceIndex $bestRoute.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Starting Self-Hosted AEGIS LiveKit Server " -ForegroundColor Cyan
Write-Host " Config:        $configYaml" -ForegroundColor Cyan
Write-Host " Active LAN IP: $lanIp" -ForegroundColor Green
Write-Host " Signaling:     http://${lanIp}:7880" -ForegroundColor Green
Write-Host " WebRTC Node:   $lanIp (ICE Candidates)" -ForegroundColor Green
Write-Host " Press Ctrl+C to stop the server" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan

# Passing --node-ip ensures WebRTC ICE candidates advertise the dynamic LAN IP on any router
& $serverExe --config $configYaml --node-ip $lanIp
