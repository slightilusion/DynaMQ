# DynaMQ

<p align="center">
  <strong>🚀 High-Performance MQTT Broker for Million-Scale IoT Devices</strong>
</p>

<p align="center">
  <a href="https://github.com/slightilusion/DynaMQ/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  </a>
  <a href="https://github.com/slightilusion/DynaMQ">
    <img src="https://img.shields.io/badge/Java-17+-orange.svg" alt="Java Version">
  </a>
  <a href="https://vertx.io/">
    <img src="https://img.shields.io/badge/Vert.x-4.5.11-purple.svg" alt="Vert.x Version">
  </a>
</p>

---

## ✨ Features

- **High Performance** - Built on Eclipse Vert.x reactive toolkit for non-blocking I/O
- **Horizontal Scaling** - Hazelcast-based clustering supports millions of concurrent connections
- **Distributed State** - Redis-backed session and subscription management
- **Kafka Integration** - Seamless message bridging to Apache Kafka
- **Prometheus Metrics** - Built-in metrics endpoint for monitoring
- **Modern Admin UI** - Vue.js-based web console for cluster management

## 🏗️ Architecture

```
                   ┌─────────────────┐
                   │   Admin UI      │
                   │   (Vue.js)      │
                   └────────┬────────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
    ┌───────────┐    ┌───────────┐    ┌───────────┐
    │ DynaMQ-1  │    │ DynaMQ-2  │    │ DynaMQ-3  │
    │  (Node)   │◄──►│  (Node)   │◄──►│  (Node)   │
    └─────┬─────┘    └─────┬─────┘    └─────┬─────┘
          │      Hazelcast │ Cluster        │
          └─────────────────┼─────────────────┘
                            │
                     ┌──────▼──────┐
                     │    Redis    │
                     │ (State Store)│
                     └──────┬──────┘
                            │
                     ┌──────▼──────┐
                     │    Kafka    │
                     │ (Optional)  │
                     └─────────────┘
```

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose (for cluster deployment)

### Build

```bash
git clone https://github.com/slightilusion/DynaMQ.git
cd DynaMQ
mvn clean package -DskipTests
```

### Run Standalone

```bash
java -jar target/DynaMQ-1.0-SNAPSHOT.jar
```

### Run Cluster (Docker Compose)

```bash
cd deploy
docker-compose up -d
```

## 📡 Ports

| Service | Port | Description |
|---------|------|-------------|
| MQTT | 1883 | MQTT Protocol |
| Admin API | 8080 | REST API |
| Admin UI | 3000 | Web Console |
| Metrics | 8080/metrics | Prometheus Endpoint |

## ⚙️ Configuration

DynaMQ uses YAML configuration. Key settings:

```yaml
mqtt:
  port: 1883
  maxMessageSize: 65535

cluster:
  enabled: true
  
redis:
  host: localhost
  port: 6379

kafka:
  enabled: false
  bootstrapServers: localhost:9092
```

## 📊 Monitoring

Access Prometheus metrics at `http://localhost:8080/metrics`

Key metrics:
- `dynamq_connections_total` - Total active connections
- `dynamq_messages_received_total` - Messages received
- `dynamq_messages_sent_total` - Messages sent
- `dynamq_subscriptions_total` - Active subscriptions

## 🛠️ Tech Stack

| Component | Technology |
|-----------|------------|
| Core | Java 17, Vert.x 4.5.11 |
| Clustering | Hazelcast 5.3.6 |
| State Store | Redis |
| Messaging | Apache Kafka |
| Metrics | Micrometer + Prometheus |
| Admin UI | Vue.js 3, Element Plus |

## 📖 Documentation

- [Deployment Guide](deploy/README.md)
- [API Documentation](docs/API.md) *(coming soon)*

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 👤 Maintainer

**XioPhan**
- Email: slightilusion@gmail.com
- GitHub: [@slightilusion](https://github.com/slightilusion)

---

<p align="center">
  Made with ❤️ for the IoT community
</p>
