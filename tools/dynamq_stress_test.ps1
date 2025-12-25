# ╔══════════════════════════════════════════════════════════════╗
# ║           DynaMQ MQTT 压测启动脚本                             ║
# ╚══════════════════════════════════════════════════════════════╝

# ========== 配置区域（根据需要修改）==========

# 服务器地址和端口
$env:MQTT_HOST = "138.2.108.254"
$env:MQTT_PORT = "1880"

# 压测参数
$env:NUM_CLIENTS = "2000"        # 客户端数量
$env:MESSAGES = "20"             # 每个客户端发送消息数
$env:INTERVAL = "200"            # 消息发送间隔(ms)
$env:BATCH_SIZE = "150"          # 每批创建客户端数
$env:BATCH_DELAY = "500"         # 批次间隔(ms)
$env:TOPIC_PREFIX = "sys/test"   # Topic前缀 (消息发送到 sys/test/up)

# ========== 运行压测 ==========

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║           DynaMQ MQTT 压力测试                               ║" -ForegroundColor Cyan
Write-Host "╠══════════════════════════════════════════════════════════════╣" -ForegroundColor Cyan
Write-Host "║  服务器: $env:MQTT_HOST`:$env:MQTT_PORT" -ForegroundColor Yellow
Write-Host "║  客户端数: $env:NUM_CLIENTS" -ForegroundColor Yellow
Write-Host "║  每客户端消息数: $env:MESSAGES" -ForegroundColor Yellow
Write-Host "║  消息间隔: $env:INTERVAL ms" -ForegroundColor Yellow
Write-Host "║  Topic: $env:TOPIC_PREFIX/up" -ForegroundColor Yellow
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# 切换到脚本目录
$scriptPath = "e:\DynaMQ\tools"
Set-Location $scriptPath

# 检查 node_modules
if (-not (Test-Path "node_modules")) {
    Write-Host "安装依赖..." -ForegroundColor Green
    npm install mqtt
}

# 运行压测
Write-Host "开始压测..." -ForegroundColor Green
Write-Host ""
node stress_test.js

Write-Host ""
Write-Host "压测完成！按任意键退出..." -ForegroundColor Green
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
