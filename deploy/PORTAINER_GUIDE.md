# DynaMQ Portainer 部署指南

## 方式一：服务器 SSH 部署 (推荐)

在服务器上执行以下命令：

```bash
# 一键部署
curl -sSL https://raw.githubusercontent.com/slightilusion/DynaMQ/master/deploy/deploy.sh | bash
```

或手动执行：

```bash
git clone https://github.com/slightilusion/DynaMQ.git
cd DynaMQ

# 使用 Docker 构建 JAR (支持 ARM64)
docker run --rm -v "$(pwd)":/app -w /app maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests

# 启动集群
cd deploy
docker-compose up -d --build
```

---

## 方式二：Portainer Stacks 部署

1. SSH 到服务器，先构建镜像：
   ```bash
   git clone https://github.com/slightilusion/DynaMQ.git
   cd DynaMQ
   docker run --rm -v "$(pwd)":/app -w /app maven:3.9-eclipse-temurin-17-alpine mvn clean package -DskipTests
   cd deploy
   docker-compose build
   ```

2. Portainer → Stacks → Add stack → Web editor
3. 粘贴 `docker-compose.yml` 内容
4. Deploy

---

## 访问地址

| 服务 | 端口 |
|------|------|
| Admin UI | `:3000` |
| Admin API | `:8000` |
| MQTT | `:1880` |
