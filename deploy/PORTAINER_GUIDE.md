# DynaMQ Portainer 部署指南 (GitHub 直接部署)

## 方式一：GitHub 直接部署 (推荐)

### 步骤

1. **Portainer → Stacks → Add stack**
2. 名称输入：`dynamq-cluster`
3. **Build method** 选择 **Repository**
4. 填写 Git 信息：
   - **Repository URL**: `https://github.com/slightilusion/DynaMQ`
   - **Repository reference**: `main`（或您的分支名）
   - **Compose path**: `deploy/docker-compose.yml`
5. 点击 **Deploy the stack**

> **注意**：首次部署会自动 clone 仓库并构建镜像

---

## 方式二：Web editor (手动粘贴)

1. Portainer → Stacks → Add stack
2. 选择 **Web editor**
3. 粘贴 docker-compose.yml 内容
4. Deploy

---

## 访问地址

| 服务 | 端口 | 说明 |
|------|------|------|
| Admin UI | `:3000` | 管理界面 |
| Admin API | `:8000` | 负载均衡 |
| MQTT | `:1880` | 负载均衡 |

---

## 验证

```bash
# 查看集群状态
curl http://YOUR_SERVER:8000/api/cluster/status

# 查看日志
docker logs dynamq-1 | grep cluster
```
