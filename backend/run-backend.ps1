# Helper script to launch AEGIS FastAPI Backend with Automatic LAN Discovery
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$pythonExe = Join-Path $scriptDir "venv\Scripts\python.exe"

if (-not (Test-Path $pythonExe)) {
    $pythonExe = "python"
}

# Detect active LAN IP
$bestRoute = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1
$lanIp = "127.0.0.1"
if ($bestRoute) {
    $lanIp = (Get-NetIPAddress -InterfaceIndex $bestRoute.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Starting AEGIS Backend Server           " -ForegroundColor Cyan
Write-Host "  Active LAN IP: $lanIp                   " -ForegroundColor Green
Write-Host "  API URL:       http://${lanIp}:8000     " -ForegroundColor Green
Write-Host "  LiveKit URL:   ws://${lanIp}:7880       " -ForegroundColor Green
Write-Host "  Binding:       0.0.0.0:8000             " -ForegroundColor Yellow
Write-Host "  Discovery:     mDNS (_aegis._tcp.local) " -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan

Set-Location $scriptDir
& $pythonExe main.py
