@echo off
REM =====================================================================
REM  启动后端服务（Windows）
REM
REM  如果尚未构建，请先执行：cd backend ^&^& mvn -DskipTests package
REM  首次启动会自动导入本体、演示数据与构建检索索引，耗时约 30 秒。
REM =====================================================================
setlocal

REM 必须切到 backend 目录再启动：
REM 配置中的上传目录是相对路径 ..\storage，进程的工作目录决定了它指向哪里。
REM 若从项目根目录启动，附件会被写到项目外的上级目录，表现为
REM 「换个方式启动后之前上传的文件不见了」。固定工作目录可保证附件始终落在 kms\storage。
cd /d "%~dp0..\backend"

if not exist target\kms.jar (
    echo.
    echo [提示] 未找到 target\kms.jar，正在构建……
    call mvn -B -DskipTests package
    if errorlevel 1 (
        echo [失败] 构建失败，请检查 Maven 与网络。
        exit /b 1
    )
)

echo.
echo 后端启动中，服务地址 http://localhost:8080
echo 接口文档     http://localhost:8080/swagger-ui.html
echo 按 Ctrl+C 停止服务。
echo.

REM -Dstdout.encoding=UTF-8：让中文日志在 Windows 控制台下也能正常显示
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\kms.jar

endlocal
