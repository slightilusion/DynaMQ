<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { Refresh, Grid } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const nodes = ref([])
let refreshInterval = null

const fetchNodes = async () => {
  try {
    loading.value = true
    const { data } = await api.getClusterNodes()
    nodes.value = data.nodes || []
  } catch (error) {
    console.error('Failed to fetch nodes:', error)
  } finally {
    loading.value = false
  }
}

const onlineCount = computed(() => nodes.value.filter(n => n.status === 'online').length)
const offlineCount = computed(() => nodes.value.filter(n => n.status !== 'online').length)

const formatTime = (time) => {
  if (!time) return '-'
  try {
    const date = new Date(time)
    return date.toLocaleString('zh-CN')
  } catch {
    return time
  }
}

onMounted(() => {
  fetchNodes()
  refreshInterval = setInterval(fetchNodes, 5000)
})

onUnmounted(() => {
  if (refreshInterval) clearInterval(refreshInterval)
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">集群管理</h1>
        <p class="page-subtitle">DynaMQ 分布式集群节点状态</p>
      </div>
      <button @click="fetchNodes" class="icon-btn" :disabled="loading">
        <el-icon :class="{ spin: loading }" :size="18"><Refresh /></el-icon>
      </button>
    </header>

    <!-- Stats -->
    <div class="metrics">
      <div class="metric-card">
        <span class="metric-value">{{ nodes.length }}</span>
        <span class="metric-label">总节点数</span>
      </div>
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-icon online">
            <el-icon :size="20"><Grid /></el-icon>
          </span>
        </div>
        <span class="metric-value">{{ onlineCount }}</span>
        <span class="metric-label">在线节点</span>
      </div>
      <div class="metric-card">
        <span class="metric-value muted">{{ offlineCount }}</span>
        <span class="metric-label">离线节点</span>
      </div>
    </div>

    <!-- Nodes Grid -->
    <div class="nodes-grid" v-if="nodes.length > 0">
      <div 
        v-for="node in nodes" 
        :key="node.nodeId" 
        :class="['node-card', { online: node.status === 'online' }]"
      >
        <div class="node-header">
          <span :class="['status-indicator', node.status === 'online' ? 'online' : 'offline']"></span>
          <span class="node-id">{{ node.nodeId }}</span>
          <span :class="['pill-badge', 'small', node.status === 'online' ? 'success' : 'danger']">
            {{ node.status === 'online' ? '在线' : '离线' }}
          </span>
        </div>
        <div class="node-info">
          <div class="info-item">
            <span class="info-label">状态</span>
            <span class="info-value">{{ node.status }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">最后心跳</span>
            <span class="info-value">{{ formatTime(node.lastHeartbeat) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading" class="empty-state">
      <el-icon :size="48" class="empty-icon"><Grid /></el-icon>
      <p>暂无集群节点</p>
      <span class="empty-hint">单节点模式运行中</span>
    </div>

    <!-- Loading -->
    <div v-if="loading && nodes.length === 0" class="loading-state">
      <div class="spinner"></div>
      <span>加载中...</span>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1000px;
}

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

/* Metrics */
.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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

.metric-value {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -1px;
}

.metric-value.muted {
  color: var(--text-tertiary);
}

.metric-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* Nodes Grid */
.nodes-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.node-card {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  padding: 24px;
  border-left: 4px solid var(--text-tertiary);
  transition: all var(--transition);
}

.node-card.online {
  border-left-color: var(--success);
}

.node-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-float);
}

.node-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.status-indicator.online {
  background: var(--success);
  box-shadow: 0 0 8px rgba(52, 168, 83, 0.5);
  animation: pulse 2s infinite;
}

.status-indicator.offline {
  background: var(--text-tertiary);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.node-id {
  font-size: 16px;
  font-weight: 600;
  flex: 1;
}

.pill-badge.small {
  padding: 4px 10px;
  font-size: 11px;
}

.node-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label {
  font-size: 12px;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.info-value {
  font-size: 13px;
  color: var(--text-primary);
}

/* Empty State */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  text-align: center;
}

.empty-icon {
  color: var(--text-tertiary);
  margin-bottom: 16px;
}

.empty-state p {
  font-size: 16px;
  font-weight: 500;
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 13px;
  color: var(--text-tertiary);
}

/* Loading */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  gap: 16px;
  color: var(--text-tertiary);
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-default);
  border-top-color: var(--text-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
</style>
