@echo off
REM =====================================================================
REM  启动前端开发服务器（Windows）
REM
REM  首次运行会自动安装依赖（npm install），需要几分钟。
REM  开发服务器会把 /api 请求代理到 http://localhost:8080，
REM  因此请先启动后端。
REM =====================================================================
setlocal

cd /d "%~dp0..\frontend"

if not exist node_modules (
    echo.
    echo [提示] 未找到 node_modules，正在安装依赖……
    call npm install
    if errorlevel 1 (
        echo [失败] 依赖安装失败，请检查 Node.js 与网络。
        exit /b 1
    )
)

echo.
echo 前端启动中，访问地址 http://localhost:5173
echo 按 Ctrl+C 停止服务。
echo.

call npm run dev

endlocal
