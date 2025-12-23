<template>
  <div class="kafka-config">
    <!-- Page Header -->
    <header class="page-header">
      <div>
        <h1 class="page-title">Kafka 配置</h1>
        <p class="page-subtitle">MQTT 到 Kafka 数据桥接配置</p>
      </div>
      <button @click="refreshAll" class="pill-btn secondary small" :disabled="loadingStatus || loadingConfig">
        <el-icon :class="{ spin: loadingStatus || loadingConfig }"><Refresh /></el-icon>
        刷新
      </button>
    </header>

    <!-- Status Cards Row -->
    <div class="metrics">
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-icon" :class="status.connected ? 'online' : 'offline'">
            <el-icon :size="20"><Connection /></el-icon>
          </span>
        </div>
        <span class="metric-value">{{ status.connected ? '在线' : '离线' }}</span>
        <span class="metric-label">连接状态</span>
      </div>
      <div class="metric-card">
        <span class="metric-value">{{ status.routeCount }}</span>
        <span class="metric-label">总路由数</span>
      </div>
      <div class="metric-card">
        <span class="metric-value success">{{ status.enabledRoutes }}</span>
        <span class="metric-label">已启用</span>
      </div>
      <div class="metric-card">
        <span class="metric-value muted">{{ status.routeCount - status.enabledRoutes }}</span>
        <span class="metric-label">已禁用</span>
      </div>
    </div>

    <!-- Main Content -->
    <div class="content-grid">
      <!-- Connection Info -->
      <section class="section-card">
        <div class="section-header">
          <el-icon :size="18"><Connection /></el-icon>
          <h2>连接信息</h2>
        </div>
        <div class="info-grid" v-loading="loadingStatus">
          <div class="info-item">
            <span class="info-label">状态</span>
            <span class="pill-badge" :class="status.connected ? 'success' : 'danger'">
              <span class="status-dot" :class="status.connected ? 'online' : 'offline'"></span>
              {{ status.connected ? '已连接' : '未连接' }}
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">服务器地址</span>
            <code class="mono-text">{{ status.bootstrapServers || '-' }}</code>
          </div>
          <div class="info-item full-width">
            <span class="info-label">路由统计</span>
            <span class="info-value">{{ status.routeCount }} 条路由，{{ status.enabledRoutes }} 条已启用</span>
          </div>
        </div>
        <div class="section-actions">
          <button @click="$router.push('/routes')" class="pill-btn small">
            管理路由 →
          </button>
        </div>
      </section>

      <!-- Producer Config -->
      <section class="section-card">
        <div class="section-header">
          <el-icon :size="18"><Setting /></el-icon>
          <h2>生产者配置</h2>
        </div>
        <div class="info-grid" v-loading="loadingConfig">
          <div class="info-item">
            <span class="info-label">启用状态</span>
            <span class="pill-badge" :class="config.enabled ? 'success' : 'muted'">
              {{ config.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">Topic 前缀</span>
            <code class="mono-text">{{ config.topicPrefix || '-' }}</code>
          </div>
          <div class="info-item">
            <span class="info-label">Acks</span>
            <span class="info-value">{{ config.producer?.acks || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">重试次数</span>
            <span class="info-value">{{ config.producer?.retries || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">批量大小</span>
            <span class="info-value">{{ formatBytes(config.producer?.batchSize) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">Linger</span>
            <span class="info-value">{{ config.producer?.lingerMs || 0 }} ms</span>
          </div>
        </div>
      </section>
    </div>

    <!-- Footer Note -->
    <div class="footer-note">
      <el-icon :size="16"><InfoFilled /></el-icon>
      <span>Kafka 配置存储在 <code>application.yml</code> 文件中，如需修改请编辑配置文件并重启服务。</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Setting, Refresh, InfoFilled } from '@element-plus/icons-vue'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'

const config = ref({
  enabled: false,
  bootstrapServers: '',
  topicPrefix: '',
  producer: {}
})

const status = ref({
  connected: false,
  bootstrapServers: '',
  routeCount: 0,
  enabledRoutes: 0
})

const loadingConfig = ref(false)
const loadingStatus = ref(false)

const fetchConfig = async () => {
  loadingConfig.value = true
  try {
    const res = await axios.get(`${API_BASE}/api/admin/kafka/config`)
    config.value = res.data
  } catch (err) {
    ElMessage.error('获取 Kafka 配置失败')
  } finally {
    loadingConfig.value = false
  }
}

const fetchStatus = async () => {
  loadingStatus.value = true
  try {
    const res = await axios.get(`${API_BASE}/api/admin/kafka/status`)
    status.value = res.data
  } catch (err) {
    ElMessage.error('获取 Kafka 状态失败')
  } finally {
    loadingStatus.value = false
  }
}

const refreshAll = () => {
  fetchConfig()
  fetchStatus()
}

const formatBytes = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

onMounted(() => {
  fetchConfig()
  fetchStatus()
})
</script>

<style scoped>
.kafka-config {
  max-width: 1000px;
}

/* Page Header */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 32px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.5px;
  margin-bottom: 4px;
}

.page-subtitle {
  color: var(--text-secondary);
  font-size: 14px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Metrics Row */
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}

.metric-card {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metric-header {
  margin-bottom: 8px;
}

.metric-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.metric-icon.online {
  background: var(--success);
  box-shadow: 0 0 20px rgba(52, 168, 83, 0.4);
}

.metric-icon.offline {
  background: var(--text-tertiary);
}

.metric-value {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -1px;
  line-height: 1;
}

.metric-value.success {
  color: var(--success);
}

.metric-value.muted {
  color: var(--text-tertiary);
}

.metric-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* Content Grid */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-bottom: 24px;
}

/* Section Card */
.section-card {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  padding: 24px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  color: var(--text-primary);
}

.section-header h2 {
  font-size: 16px;
  font-weight: 600;
}

/* Info Grid */
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-item.full-width {
  grid-column: 1 / -1;
}

.info-label {
  font-size: 12px;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.info-value {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.mono-text {
  font-family: var(--font-mono);
  font-size: 13px;
  background: var(--bg-hover);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-primary);
}

/* Section Actions */
.section-actions {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-default);
}

/* Footer Note */
.footer-note {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  background: var(--bg-page);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
  font-size: 13px;
}

.footer-note code {
  background: var(--bg-hover);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  color: var(--text-primary);
}

/* Responsive */
@media (max-width: 800px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .content-grid {
    grid-template-columns: 1fr;
  }
}
</style>
