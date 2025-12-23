<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Refresh, Monitor, Share, DataLine, Key } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const dashboard = ref({
  connectedClients: 0,
  activeNodes: 0,
  timestamp: '',
  version: ''
})

let refreshInterval = null

const fetchDashboard = async () => {
  try {
    loading.value = true
    const { data } = await api.getDashboard()
    dashboard.value = data
  } catch (error) {
    console.error('Failed to fetch dashboard:', error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchDashboard()
  refreshInterval = setInterval(fetchDashboard, 5000)
})

onUnmounted(() => {
  if (refreshInterval) clearInterval(refreshInterval)
})
</script>

<template>
  <div class="dashboard">
    <header class="page-header">
      <div>
        <h1 class="page-title">仪表盘</h1>
        <p class="page-subtitle">DynaMQ MQTT Broker 概览</p>
      </div>
      <button @click="fetchDashboard" class="pill-btn secondary small" :disabled="loading">
        <el-icon :class="{ spin: loading }"><Refresh /></el-icon>
        刷新
      </button>
    </header>

    <div class="metrics">
      <div class="metric-card">
        <span class="metric-value">{{ dashboard.connectedClients }}</span>
        <span class="metric-label">在线客户端</span>
      </div>
      <div class="metric-card">
        <span class="metric-value">{{ dashboard.activeNodes }}</span>
        <span class="metric-label">活跃节点</span>
      </div>
      <div class="metric-card">
        <span class="metric-value">{{ dashboard.version || '-' }}</span>
        <span class="metric-label">版本</span>
      </div>
      <div class="metric-card">
        <span class="pill-badge success">
          <span class="status-dot online"></span>
          运行中
        </span>
        <span class="metric-label">系统状态</span>
      </div>
    </div>

    <section class="section">
      <h2 class="section-title">快速导航</h2>
      <div class="quick-nav">
        <router-link to="/clients" class="quick-item">
          <div class="quick-icon"><el-icon :size="24"><Monitor /></el-icon></div>
          <span>客户端管理</span>
        </router-link>
        <router-link to="/routes" class="quick-item">
          <div class="quick-icon"><el-icon :size="24"><Share /></el-icon></div>
          <span>数据路由</span>
        </router-link>
        <router-link to="/monitoring" class="quick-item">
          <div class="quick-icon"><el-icon :size="24"><DataLine /></el-icon></div>
          <span>实时监控</span>
        </router-link>
        <router-link to="/acl" class="quick-item">
          <div class="quick-icon"><el-icon :size="24"><Key /></el-icon></div>
          <span>访问控制</span>
        </router-link>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 900px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 40px;
}

.page-title {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.5px;
  margin-bottom: 4px;
}

.page-subtitle {
  color: var(--text-secondary);
  font-size: 15px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Metrics */
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 48px;
}

.metric-card {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-value {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: -1px;
  line-height: 1;
}

.metric-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* Section */
.section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 16px;
}

/* Quick Nav */
.quick-nav {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 32px 20px;
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  text-decoration: none;
  color: var(--text-primary);
  transition: all var(--transition);
}

.quick-item:hover {
  background: var(--bg-hover);
  transform: translateY(-2px);
}

.quick-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-hover);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  transition: all var(--transition);
}

.quick-item:hover .quick-icon {
  background: var(--text-primary);
  color: var(--bg-page);
}

.quick-item span:last-child {
  font-size: 13px;
  font-weight: 500;
}

@media (max-width: 900px) {
  .metrics, .quick-nav {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
