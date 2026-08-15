@echo off
REM ============================================================
REM 本地开发一键启动 encaps-layer（OIDC 模式）
REM 用法：双击运行，或 cmd 中执行 scripts\start-encaps.bat
REM 前置：Docker Desktop 运行中（Keycloak 容器在 18040）
REM ============================================================
cd /d "%~dp0..\platform\encaps-layer"

REM 确保 jar 最新
echo [1/2] 构建 encaps-layer ...
call mvn -B -q clean package -DskipTests
if errorlevel 1 (
  echo 构建失败！
  pause
  exit /b 1
)

echo [2/2] 启动 encaps-layer（OIDC 模式，端口 8080）...
set OIDC_ENABLED=true
set OIDC_JWKS_URI=http://127.0.0.1:18040/realms/shuqing/protocol/openid-connect/certs
set OIDC_ISSUER_URI=http://127.0.0.1:18040/realms/shuqing
set KEYCLOAK_TOKEN_URI=http://127.0.0.1:18040/realms/shuqing/protocol/openid-connect/token

REM 前台运行（本窗口保持，Ctrl+C 停止）——进程不会被杀
java -jar target\encaps-layer-0.1.0-SNAPSHOT.jar
