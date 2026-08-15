@echo off
REM ============================================================
REM 本地开发前端启动（vite dev server，端口 5173）
REM 用法：双击运行，或 cmd 中执行 scripts\start-frontend.bat
REM 前置：encaps-layer 已在跑（scripts\start-encaps.bat）
REM ============================================================
cd /d "%~dp0..\frontend"

echo 启动前端 dev server（127.0.0.1:5173）...
echo 浏览器访问 http://127.0.0.1:5173 登录（demo / demo123）
echo Ctrl+C 停止
call npm run dev
