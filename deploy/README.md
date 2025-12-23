# DynaMQ Cluster Deployment

## Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 17+ (for local build)
- Node.js 18+ (for UI development)

### Build & Deploy

```bash
# 1. Build the Java application
cd "d:\Program Files\demo\DynaMQ"
mvn clean package -DskipTests

# 2. Start the cluster
cd deploy
docker-compose up -d

# 3. Check status
docker-compose ps
```

### Ports

| Service | Port | Description |
|---------|------|-------------|
| MQTT (Node 1) | 1883 | MQTT Broker |
| MQTT (Node 2) | 1884 | MQTT Broker |
| MQTT (Node 3) | 1885 | MQTT Broker |
| MQTT (LB) | 1880 | Load Balanced MQTT |
| Admin API (Node 1) | 8080 | REST API |
| Admin API (Node 2) | 8081 | REST API |
| Admin API (Node 3) | 8082 | REST API |
| Admin API (LB) | 8000 | Load Balanced API |
| Admin UI | 3000 | Web Console |
| Redis | 6379 | State Store |

### Architecture

```
                   ┌─────────────────┐
                   │   Admin UI      │
                   │   :3000         │
                   └────────┬────────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
    ┌───────────┐    ┌───────────┐    ┌───────────┐
    │ DynaMQ-1  │    │ DynaMQ-2  │    │ DynaMQ-3  │
    │ :1883/8080│    │ :1884/8081│    │ :1885/8082│
    └─────┬─────┘    └─────┬─────┘    └─────┬─────┘
          └─────────────────┼─────────────────┘
                            │
                     ┌──────▼──────┐
                     │    Redis    │
                     │    :6379    │
                     └─────────────┘
```

### Commands

```bash
# View logs
docker-compose logs -f dynamq-1

# Stop cluster
docker-compose down

# Scale nodes (add more)
docker-compose up -d --scale dynamq-2=2
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| NODE_NAME | dynamq-1 | Unique node identifier |
| REDIS_HOST | redis | Redis hostname |
| REDIS_PORT | 6379 | Redis port |
| MQTT_PORT | 1883 | MQTT listener port |
| ADMIN_PORT | 8080 | Admin API port |
