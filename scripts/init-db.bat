@echo off
REM =====================================================================
REM  知识管理系统 —— 数据库初始化（Windows）
REM
REM  作用：创建 kms 数据库与 20 张表。
REM  注意：脚本会先删除同名库再重建，请勿在有数据的库上执行。
REM
REM  用法：直接双击，或在命令行执行：
REM      scripts\init-db.bat
REM      scripts\init-db.bat root 你的密码
REM =====================================================================
setlocal

cd /d "%~dp0.."

set DB_USER=%~1
set DB_PASS=%~2
if "%DB_USER%"=="" set DB_USER=root

echo.
echo [1/2] 正在创建数据库与表结构……
if "%DB_PASS%"=="" (
    mysql -u%DB_USER% -p < backend\src\main\resources\db\schema.sql
) else (
    mysql -u%DB_USER% -p%DB_PASS% < backend\src\main\resources\db\schema.sql
)

if errorlevel 1 (
    echo.
    echo [失败] 数据库初始化失败。请确认：
    echo        1. MySQL 服务已启动；
    echo        2. 命令行可以执行 mysql 命令（MySQL 的 bin 目录已加入 PATH）；
    echo        3. 账号密码正确。
    exit /b 1
)

echo.
echo [2/2] 完成。已创建的表：
if "%DB_PASS%"=="" (
    mysql -u%DB_USER% -p -e "USE kms; SHOW TABLES;" 2>nul
) else (
    mysql -u%DB_USER% -p%DB_PASS% -e "USE kms; SHOW TABLES;" 2>nul
)

echo.
echo 接下来启动后端，首次启动会自动导入本体、演示数据与构建检索索引。
echo.
endlocal
