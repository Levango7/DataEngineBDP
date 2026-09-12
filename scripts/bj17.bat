@echo off
set JAVA_HOME=F:\jdk17
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d F:\Nexus\DataEngineBDP
set K8S_MOCK_ENABLED=true
set OTEL_TRACES_EXPORTER=none
set OTEL_METRICS_EXPORTER=none
set OTEL_LOGS_EXPORTER=none
java -jar platform\encaps-layer\target\encaps-layer-0.1.0-SNAPSHOT-exec.jar --server.port=8080
