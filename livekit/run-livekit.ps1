# Helper script to launch native Windows LiveKit Server for AEGIS
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$serverExe = Join-Path $scriptDir "livekit-server.exe"
$configYaml = Join-Path $scriptDir "livekit.yaml"

if (-not (Test-Path $serverExe)) {
    Write-Error "livekit-server.exe not found in $scriptDir"
    exit 1
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Starting Self-Hosted AEGIS LiveKit Server " -ForegroundColor Cyan
Write-Host " Config: $configYaml" -ForegroundColor Cyan
Write-Host " Signaling: http://192.168.1.4:7880" -ForegroundColor Green
Write-Host " Press Ctrl+C to stop the server" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan

& $serverExe --config $configYaml
