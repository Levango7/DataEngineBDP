﻿# P0冒烟验证后端启动脚本
$env:K8S_MOCK_ENABLED = "true"
$env:OTEL_TRACES_EXPORTER = "none"
$env:OTEL_METRICS_EXPORTER = "none"
$env:OTEL_LOGS_EXPORTER = "none"
$env:SERVER_PORT = "18086"

Set-Location "F:\nexus\DataEngineBDP"

$proc = Start-Process -FilePath "java" `
    -ArgumentList "-jar","platform\encaps-layer\target\encaps-layer-0.1.0-SNAPSHOT.jar","--server.port=18086" `
    -WorkingDirectory "F:\nexus\DataEngineBDP" `
    -WindowStyle Hidden `
    -RedirectStandardOutput "F:\nexus\DataEngineBDP\smoke-backend-stdout.log" `
    -RedirectStandardError "F:\nexus\DataEngineBDP\smoke-backend-stderr.log" `
    -PassThru

"PID=$($proc.Id)" | Out-File -FilePath "F:\nexus\DataEngineBDP\smoke-backend-pid.txt" -Encoding ascii
Write-Host "Started backend PID=$($proc.Id)"