<script setup>
import { ref, onMounted, computed } from 'vue'
import { Refresh, Connection } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const subscriptions = ref([])
const expandedClients = ref([])

const fetchSubscriptions = async () => {
  try {
    loading.value = true
    const { data } = await api.getSubscriptions()
    subscriptions.value = data.subscriptions || []
  } catch (error) {
    console.error('Failed to fetch subscriptions:', error)
  } finally {
    loading.value = false
  }
}

const totalTopics = computed(() => {
  return subscriptions.value.reduce((sum, s) => sum + (s.topics?.length || 0), 0)
})

const totalClients = computed(() => subscriptions.value.length)

const toggleClient = (clientId) => {
  const index = expandedClients.value.indexOf(clientId)
  if (index > -1) {
    expandedClients.value.splice(index, 1)
  } else {
    expandedClients.value.push(clientId)
  }
}

const isExpanded = (clientId) => expandedClients.value.includes(clientId)

onMounted(fetchSubscriptions)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">订阅管理</h1>
        <p class="page-subtitle">查看客户端 Topic 订阅详情</p>
      </div>
      <button @click="fetchSubscriptions" class="icon-btn" :disabled="loading">
        <el-icon :class="{ spin: loading }" :size="18"><Refresh /></el-icon>
      </button>
    </header>

    <!-- Stats -->
    <div class="metrics">
      <div class="metric-card">
        <span class="metric-value">{{ totalClients }}</span>
        <span class="metric-label">订阅客户端</span>
      </div>
      <div class="metric-card">
        <span class="metric-value">{{ totalTopics }}</span>
        <span class="metric-label">订阅主题数</span>
      </div>
    </div>

    <!-- Subscriptions List -->
    <div class="subs-list" v-if="subscriptions.length > 0">
      <div 
        v-for="sub in subscriptions" 
        :key="sub.clientId" 
        class="sub-card"
      >
        <div class="sub-header" @click="toggleClient(sub.clientId)">
          <div class="sub-info">
            <span class="expand-icon">{{ isExpanded(sub.clientId) ? '▼' : '▶' }}</span>
            <code class="client-id">{{ sub.clientId }}</code>
          </div>
          <span class="topic-count">{{ sub.topics?.length || 0 }} 个主题</span>
        </div>
        
        <div v-if="isExpanded(sub.clientId)" class="topics-list">
          <div v-for="topic in sub.topics" :key="topic.topic" class="topic-item">
            <code class="topic-name">{{ topic.topic }}</code>
            <span :class="['qos-badge', `qos-${topic.qos}`]">QoS {{ topic.qos }}</span>
          </div>
          <div v-if="!sub.topics?.length" class="no-topics">暂无订阅主题</div>
        </div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading" class="empty-state">
      <el-icon :size="48" class="empty-icon"><Connection /></el-icon>
      <p>暂无订阅数据</p>
      <span class="empty-hint">当客户端订阅主题后将在此显示</span>
    </div>

    <!-- Loading -->
    <div v-if="loading && subscriptions.length === 0" class="loading-state">
      <div class="spinner"></div>
      <span>加载中...</span>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 900px;
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
  grid-template-columns: repeat(2, 1fr);
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

.metric-value {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -1px;
}

.metric-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* Subscriptions List */
.subs-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sub-card {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  overflow: hidden;
}

.sub-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 24px;
  cursor: pointer;
  transition: background var(--transition);
}

.sub-header:hover {
  background: var(--bg-hover);
}

.sub-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.expand-icon {
  font-size: 10px;
  color: var(--text-tertiary);
  transition: transform var(--transition);
}

.client-id {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 500;
}

.topic-count {
  font-size: 13px;
  color: var(--text-secondary);
  background: var(--bg-hover);
  padding: 4px 12px;
  border-radius: var(--radius-pill);
}

.topics-list {
  padding: 0 24px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.topic-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--bg-hover);
  border-radius: var(--radius-md);
}

.topic-name {
  font-family: var(--font-mono);
  font-size: 13px;
}

.qos-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
}

.qos-badge.qos-0 {
  background: rgba(52, 168, 83, 0.15);
  color: var(--success);
}

.qos-badge.qos-1 {
  background: rgba(251, 188, 4, 0.15);
  color: var(--warning);
}

.qos-badge.qos-2 {
  background: rgba(234, 67, 53, 0.15);
  color: var(--danger);
}

.no-topics {
  text-align: center;
  padding: 20px;
  color: var(--text-tertiary);
  font-size: 13px;
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
