#!/bin/bash
# DynaMQ 服务器端部署脚本
# 在服务器上执行此脚本进行构建和部署

set -e

echo "=== DynaMQ Cluster Deployment ==="

# 1. 克隆或更新仓库
if [ -d "DynaMQ" ]; then
    echo "Updating repository..."
    cd DynaMQ
    git pull origin master
else
    echo "Cloning repository..."
    git clone https://github.com/slightilusion/DynaMQ.git
    cd DynaMQ
fi

# 2. 构建 JAR 包 (使用支持 ARM64 的镜像)
echo "Building JAR..."
docker run --rm -v "$(pwd)":/app -w /app maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests

# 3. 构建和启动容器
echo "Building and starting containers..."
cd deploy
docker-compose up -d --build

echo "=== Deployment Complete ==="
echo "Admin UI:   http://$(hostname -I | awk '{print $1}'):3000"
echo "Admin API:  http://$(hostname -I | awk '{print $1}'):8000"
echo "MQTT:       $(hostname -I | awk '{print $1}'):1880"
