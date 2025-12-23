<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const clients = ref([])
const searchQuery = ref('')
const detailDialog = ref(false)
const detailLoading = ref(false)
const selectedClient = ref(null)
const statusFilter = ref('all')

const formatTime = (time) => {
  if (!time) return '-'
  try {
    const date = typeof time === 'number' ? new Date(time) : new Date(time)
    if (isNaN(date.getTime())) return time
    return date.toLocaleString('zh-CN')
  } catch {
    return time
  }
}

const fetchClients = async () => {
  try {
    loading.value = true
    const { data } = await api.getClients()
    clients.value = data.clients || []
  } catch (error) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const showClientDetail = async (clientId) => {
  try {
    detailLoading.value = true
    detailDialog.value = true
    const { data } = await api.getClientDetail(clientId)
    selectedClient.value = data
  } catch (error) {
    ElMessage.error('加载详情失败')
    detailDialog.value = false
  } finally {
    detailLoading.value = false
  }
}

const kickClient = async (clientId) => {
  try {
    await ElMessageBox.confirm(`断开 "${clientId}" ?`, '确认', { type: 'warning' })
    await api.kickClient(clientId)
    ElMessage.success('已断开')
    detailDialog.value = false
    fetchClients()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('操作失败')
  }
}

const onlineCount = computed(() => clients.value.filter(c => c.online).length)
const offlineCount = computed(() => clients.value.filter(c => !c.online).length)

const filteredClients = computed(() => {
  let result = clients.value
  if (statusFilter.value === 'online') result = result.filter(c => c.online)
  else if (statusFilter.value === 'offline') result = result.filter(c => !c.online)
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    result = result.filter(c => c.clientId?.toLowerCase().includes(q))
  }
  return result
})

onMounted(fetchClients)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">客户端</h1>
        <p class="page-subtitle">管理 MQTT 客户端连接</p>
      </div>
      <button @click="fetchClients" class="icon-btn" :disabled="loading">
        <el-icon :class="{ spin: loading }" :size="18"><Refresh /></el-icon>
      </button>
    </header>

    <!-- Filter Pills -->
    <div class="filter-bar">
      <button :class="['filter-pill', { active: statusFilter === 'all' }]" @click="statusFilter = 'all'">
        全部 <span class="count">{{ clients.length }}</span>
      </button>
      <button :class="['filter-pill', { active: statusFilter === 'online' }]" @click="statusFilter = 'online'">
        <span class="dot online"></span>在线 <span class="count">{{ onlineCount }}</span>
      </button>
      <button :class="['filter-pill', { active: statusFilter === 'offline' }]" @click="statusFilter = 'offline'">
        <span class="dot offline"></span>离线 <span class="count">{{ offlineCount }}</span>
      </button>
      
      <div class="search-box">
        <el-icon class="search-icon"><Search /></el-icon>
        <input v-model="searchQuery" placeholder="搜索..." class="search-input" />
      </div>
    </div>

    <!-- Table -->
    <div class="table-container">
      <table class="table">
        <thead>
          <tr>
            <th width="50"></th>
            <th>Client ID</th>
            <th width="100">节点</th>
            <th width="60">订阅</th>
            <th width="160">连接时间</th>
            <th width="80"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in filteredClients" :key="c.clientId" @click="showClientDetail(c.clientId)">
            <td><span :class="['dot', c.online ? 'online' : 'offline']"></span></td>
            <td><code>{{ c.clientId }}</code></td>
            <td class="muted">{{ c.nodeId || 'local' }}</td>
            <td class="center">{{ c.subscriptionCount || 0 }}</td>
            <td class="muted small">{{ formatTime(c.connectedAt) }}</td>
            <td>
              <button v-if="c.online" @click.stop="kickClient(c.clientId)" class="pill-btn danger small">
                断开
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      
      <div v-if="loading" class="loading">
        <div class="spinner"></div>
      </div>
      <div v-else-if="filteredClients.length === 0" class="empty">
        暂无客户端
      </div>
    </div>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailDialog" title="客户端详情" width="500">
      <div v-loading="detailLoading" class="detail">
        <template v-if="selectedClient">
          <div class="detail-status">
            <span :class="['dot', selectedClient.online ? 'online' : 'offline']"></span>
            {{ selectedClient.online ? '在线' : '离线' }}
          </div>
          
          <div class="detail-grid">
            <div class="detail-item">
              <label>Client ID</label>
              <code>{{ selectedClient.clientId }}</code>
            </div>
            <div class="detail-item">
              <label>节点</label>
              <span>{{ selectedClient.nodeId || 'local' }}</span>
            </div>
            <div class="detail-item">
              <label>Clean Session</label>
              <span>{{ selectedClient.cleanSession ? '是' : '否' }}</span>
            </div>
            <div class="detail-item">
              <label>Keep Alive</label>
              <span>{{ selectedClient.keepAliveSeconds }}s</span>
            </div>
          </div>
          
          <div class="subs">
            <label>订阅 ({{ selectedClient.subscriptionCount || 0 }})</label>
            <div v-if="selectedClient.subscriptions?.length" class="subs-list">
              <div v-for="s in selectedClient.subscriptions" :key="s.topic" class="sub-item">
                <code>{{ s.topic }}</code>
                <span class="qos">QoS {{ s.qos }}</span>
              </div>
            </div>
            <div v-else class="empty small">暂无订阅</div>
          </div>
        </template>
      </div>
      <template #footer>
        <button @click="detailDialog = false" class="pill-btn secondary">关闭</button>
        <button v-if="selectedClient?.online" @click="kickClient(selectedClient?.clientId)" class="pill-btn danger">
          断开连接
        </button>
      </template>
    </el-dialog>
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

/* Filter Bar */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 24px;
}

.filter-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  background: var(--bg-page);
  border: none;
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition);
}

.filter-pill:hover {
  background: var(--bg-hover);
}

.filter-pill.active {
  background: var(--text-primary);
  color: var(--bg-page);
}

.filter-pill .count {
  opacity: 0.6;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.dot.online {
  background: var(--success);
}

.dot.offline {
  background: var(--text-tertiary);
}

.search-box {
  margin-left: auto;
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 14px;
  color: var(--text-tertiary);
}

.search-input {
  width: 200px;
  padding: 10px 14px 10px 40px;
  background: var(--bg-page);
  border: none;
  border-radius: var(--radius-pill);
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-tertiary);
}

/* Table */
.table-container {
  background: var(--bg-page);
  border-radius: var(--radius-xl);
  overflow: hidden;
  min-height: 200px;
}

.table {
  width: 100%;
  border-collapse: collapse;
}

.table th {
  padding: 16px 20px;
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.table td {
  padding: 16px 20px;
  font-size: 13px;
  border-top: 1px solid var(--border-default);
}

.table tr {
  cursor: pointer;
  transition: background var(--transition);
}

.table tbody tr:hover {
  background: var(--bg-hover);
}

.table code {
  font-family: var(--font-mono);
  font-size: 12px;
}

.muted { color: var(--text-secondary); }
.small { font-size: 12px; }
.center { text-align: center; }

.loading, .empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px;
  color: var(--text-tertiary);
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-default);
  border-top-color: var(--text-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

/* Detail */
.detail {
  min-height: 150px;
}

.detail-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--bg-surface);
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 24px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
  margin-bottom: 24px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-item label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
}

.detail-item code {
  font-family: var(--font-mono);
  font-size: 13px;
}

.subs {
  border-top: 1px solid var(--border-default);
  padding-top: 20px;
}

.subs label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  margin-bottom: 12px;
}

.subs-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sub-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--bg-surface);
  border-radius: var(--radius-md);
}

.sub-item code {
  font-family: var(--font-mono);
  font-size: 12px;
}

.qos {
  font-size: 11px;
  font-weight: 600;
  color: var(--warning);
}
</style>
