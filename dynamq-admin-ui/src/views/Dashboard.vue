<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const loading = ref(true)
const dashboard = ref({
  connectedClients: 0,
  activeNodes: 0,
  timestamp: '',
  version: ''
})

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
  // Auto refresh every 5 seconds
  setInterval(fetchDashboard, 5000)
})
</script>

<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-card class="stat-card clients">
          <div class="stat-icon">
            <el-icon size="40"><Monitor /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ dashboard.connectedClients }}</div>
            <div class="stat-label">Connected Clients</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card nodes">
          <div class="stat-icon">
            <el-icon size="40"><Promotion /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ dashboard.activeNodes }}</div>
            <div class="stat-label">Active Nodes</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card version">
          <div class="stat-icon">
            <el-icon size="40"><InfoFilled /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ dashboard.version || 'N/A' }}</div>
            <div class="stat-label">Version</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card time">
          <div class="stat-icon">
            <el-icon size="40"><Clock /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value small">{{ dashboard.timestamp?.split('T')[1]?.split('.')[0] || '--' }}</div>
            <div class="stat-label">Last Updated</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>System Information</span>
              <el-button type="primary" @click="fetchDashboard" :loading="loading">
                Refresh
              </el-button>
            </div>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="MQTT Broker">DynaMQ</el-descriptions-item>
            <el-descriptions-item label="Version">{{ dashboard.version }}</el-descriptions-item>
            <el-descriptions-item label="Active Connections">{{ dashboard.connectedClients }}</el-descriptions-item>
            <el-descriptions-item label="Cluster Nodes">{{ dashboard.activeNodes }}</el-descriptions-item>
            <el-descriptions-item label="Status">
              <el-tag type="success">Running</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Last Heartbeat">{{ dashboard.timestamp }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 10px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
}

.stat-icon {
  margin-right: 20px;
  color: #fff;
  padding: 15px;
  border-radius: 8px;
}

.clients .stat-icon { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
.nodes .stat-icon { background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%); }
.version .stat-icon { background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); }
.time .stat-icon { background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%); }

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.stat-value.small {
  font-size: 20px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 5px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
