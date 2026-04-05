# CloudBox - Start full 5-node cluster + frontend
# Run from the CloudBox root directory: .\start.ps1

$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
$backend = Join-Path $root "backend"
$frontend = Join-Path $root "frontend"

Write-Host "Starting CloudBox cluster..." -ForegroundColor Cyan

# Backend nodes (ports 8080-8084)
for ($i = 1; $i -le 5; $i++) {
    $port = 8079 + $i
    $cmd = "Set-Location '$backend'; .\mvnw.cmd spring-boot:run `"-Dspring-boot.run.arguments=--server.port=$port --cloudbox.node-id=$i`""
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    Start-Process powershell -ArgumentList "-NoExit", "-EncodedCommand", $encoded -WindowStyle Normal
    Write-Host "  Node $i started on port $port"
    Start-Sleep -Seconds 1
}

# Frontend
$cmd = "Set-Location '$frontend'; npm install; npm run dev"
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
Start-Process powershell -ArgumentList "-NoExit", "-EncodedCommand", $encoded -WindowStyle Normal
Write-Host "  Frontend started (http://localhost:5173)"

Write-Host ""
Write-Host "All processes launched. Close individual windows to stop nodes." -ForegroundColor Green
