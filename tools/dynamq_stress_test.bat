@echo off
chcp 65001 > nul
title DynaMQ MQTT 压力测试 (持续模式)

echo.
echo ╔══════════════════════════════════════════════════════════════╗
echo ║           DynaMQ MQTT 压力测试 - 持续模式                    ║
echo ╚══════════════════════════════════════════════════════════════╝
echo.

REM ========== 配置区域（根据需要修改）==========
set MQTT_HOST=138.2.108.254
set MQTT_PORT=1880
set NUM_CLIENTS=2000
set MESSAGES=20
set INTERVAL=200
set BATCH_SIZE=150
set BATCH_DELAY=500
set TOPIC_PREFIX=sys/test

REM ========== 持续模式配置 ==========
set CONTINUOUS=true
set DURATION=0

REM ========== 显示配置 ==========
echo   服务器: %MQTT_HOST%:%MQTT_PORT%
echo   客户端数: %NUM_CLIENTS%
echo   消息间隔: %INTERVAL% ms
echo   Topic: %TOPIC_PREFIX%/up
echo   模式: 持续连接 (Ctrl+C 停止)
if %DURATION% GTR 0 (
    echo   持续时间: %DURATION% 秒
) else (
    echo   持续时间: 无限制
)
echo.
echo ══════════════════════════════════════════════════════════════
echo.

REM 切换到脚本目录
cd /d "e:\DynaMQ\tools"

REM 检查 node
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Node.js，请先安装 Node.js
    pause
    exit /b 1
)

REM 检查依赖
if not exist "node_modules" (
    echo 正在安装依赖...
    call npm install mqtt
    echo.
)

echo 开始压测... (按 Ctrl+C 停止)
echo.

node stress_test.js

echo.
echo ══════════════════════════════════════════════════════════════
echo 压测结束！
echo.
pause
