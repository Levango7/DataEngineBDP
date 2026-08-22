@echo off
REM P0冒烟验证后端启动脚本
set K8S_MOCK_ENABLED=true
set OTEL_TRACES_EXPORTER=none
set OTEL_METRICS_EXPORTER=none
set OTEL_LOGS_EXPORTER=none
set SERVER_PORT=18086
cd /d F:\nexus\DataEngineBDP
echo Starting backend on port 18086 with K8S_MOCK_ENABLED=true...
java -jar platform\encaps-layer\target\encaps-layer-0.1.0-SNAPSHOT.jar --server.port=18086