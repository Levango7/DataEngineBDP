@echo off
REM 用 JDK17 (Amazon Corretto) 启动 encaps-layer 后端（mock 模式，端口 8080）
set JAVA_HOME=F:\Program Files (x86)\Amazon Corretto\jdk17.0.20_8
set PATH=%JAVA_HOME%\bin;%PATH%
set K8S_MOCK_ENABLED=true
set OTEL_TRACES_EXPORTER=none
set OTEL_METRICS_EXPORTER=none
set OTEL_LOGS_EXPORTER=none

cd /d F:\Nexus\DataEngineBDP
echo 启动后端 (JDK17, mock mode, port 8080)...
java -jar platform\encaps-layer\target\encaps-layer-0.1.0-SNAPSHOT-exec.jar --server.port=8080
