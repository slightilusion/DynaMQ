<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Timer, User } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const clients = ref([])
const searchQuery = ref('')
const detailDialog = ref(false)
const detailLoading = ref(false)
const selectedClient = ref(null)

const formatTime = (time) => {
  if (!time) return '-'
  try {
    const date = typeof time === 'number' ? new Date(time) : new Date(time)
    if (isNaN(date.getTime())) return time
    return date.toLocaleString('zh-CN', { 
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
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
    console.error('Failed to fetch clients:', error)
    ElMessage.error('加载客户端列表失败')
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
    ElMessage.error('加载客户端详情失败')
    detailDialog.value = false
  } finally {
    detailLoading.value = false
  }
}

const kickClient = async (clientId) => {
  try {
    await ElMessageBox.confirm(
      `确定要断开客户端 "${clientId}" 的连接吗？`,
      '确认断开',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
    await api.kickClient(clientId)
    ElMessage.success(`客户端 ${clientId} 已断开`)
    detailDialog.value = false
    fetchClients()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('断开失败')
    }
  }
}

const filteredClients = computed(() => {
  if (!searchQuery.value) return clients.value
  return clients.value.filter(c => 
    c.clientId?.toLowerCase().includes(searchQuery.value.toLowerCase()) ||
    c.username?.toLowerCase().includes(searchQuery.value.toLowerCase())
  )
})

onMounted(fetchClients)
</script>

<template>
  <div class="clients-page">
    <!-- 顶部统计卡片 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%)">
          <el-icon :size="24"><Connection /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ clients.length }}</div>
          <div class="stat-label">在线客户端</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%)">
          <el-icon :size="24"><User /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ clients.filter(c => c.subscriptionCount > 0).length }}</div>
          <div class="stat-label">有订阅的客户端</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%)">
          <el-icon :size="24"><Timer /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ clients.reduce((sum, c) => sum + (c.subscriptionCount || 0), 0) }}</div>
          <div class="stat-label">总订阅数</div>
        </div>
      </div>
    </div>

    <!-- 客户端列表 -->
    <el-card class="clients-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="header-title">
            <span class="title-text">客户端列表</span>
            <el-tag type="info" size="small" effect="dark">{{ filteredClients.length }} 个</el-tag>
          </div>
          <div class="header-actions">
            <el-input
              v-model="searchQuery"
              placeholder="搜索 Client ID 或用户名..."
              style="width: 240px"
              clearable
              :prefix-icon="'Search'"
            />
            <el-button type="primary" @click="fetchClients" :loading="loading" :icon="'Refresh'">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table 
        :data="filteredClients" 
        v-loading="loading" 
        stripe 
        @row-click="(row) => showClientDetail(row.clientId)"
        :row-style="{ cursor: 'pointer' }"
        :header-cell-style="{ background: '#f5f7fa', fontWeight: '600' }"
      >
        <el-table-column prop="clientId" label="Client ID" min-width="200">
          <template #default="{ row }">
            <span class="client-id">{{ row.clientId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" min-width="100">
          <template #default="{ row }">
            <span v-if="row.username" class="username">{{ row.username }}</span>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="nodeId" label="节点" min-width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info">{{ row.nodeId || 'local' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="subscriptionCount" label="订阅数" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.subscriptionCount > 0 ? 'success' : 'info'" size="small" effect="dark" round>
              {{ row.subscriptionCount || 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="connectedAt" label="连接时间" min-width="160">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.connectedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" size="small" plain @click.stop="kickClient(row.clientId)">
              断开
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 客户端详情弹窗 -->
    <el-dialog v-model="detailDialog" title="客户端详情" width="640" :close-on-click-modal="false">
      <div v-loading="detailLoading" class="detail-content">
        <el-descriptions v-if="selectedClient" :column="2" border class="client-desc">
          <el-descriptions-item label="Client ID" :span="2">
            <span class="client-id">{{ selectedClient.clientId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户名">{{ selectedClient.username || '-' }}</el-descriptions-item>
          <el-descriptions-item label="节点">
            <el-tag size="small" effect="plain">{{ selectedClient.nodeId || 'local' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Clean Session">
            <el-tag :type="selectedClient.cleanSession ? 'info' : 'warning'" size="small">
              {{ selectedClient.cleanSession ? '是' : '否' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Keep Alive">{{ selectedClient.keepAliveSeconds }}s</el-descriptions-item>
          <el-descriptions-item label="连接时间" :span="2">
            <span class="time-text">{{ formatTime(selectedClient.connectedAt) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        
        <div class="subscriptions-section" v-if="selectedClient">
          <div class="section-title">
            <span>订阅列表</span>
            <el-tag type="success" size="small" effect="dark" round>{{ selectedClient.subscriptionCount || 0 }}</el-tag>
          </div>
          <el-table 
            :data="selectedClient.subscriptions || []" 
            size="small" 
            max-height="220"
            :header-cell-style="{ background: '#f5f7fa' }"
          >
            <el-table-column prop="topic" label="Topic">
              <template #default="{ row }">
                <code class="topic-code">{{ row.topic }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="qos" label="QoS" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.qos === 0 ? 'info' : row.qos === 1 ? 'success' : 'warning'" size="small" effect="dark">
                  QoS {{ row.qos }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!selectedClient.subscriptions?.length" description="暂无订阅" :image-size="60" />
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detailDialog = false">关闭</el-button>
          <el-button type="danger" @click="kickClient(selectedClient?.clientId)">断开连接</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.clients-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 统计卡片行 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.stat-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s, box-shadow 0.2s;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

/* 客户端列表卡片 */
.clients-card {
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-text {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.client-id {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  color: #409eff;
}

.username {
  color: #303133;
}

.no-data {
  color: #c0c4cc;
}

.time-text {
  color: #606266;
  font-size: 13px;
}

/* 详情弹窗 */
.detail-content {
  min-height: 200px;
}

.client-desc {
  margin-bottom: 0;
}

.subscriptions-section {
  margin-top: 24px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.topic-code {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  color: #606266;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
