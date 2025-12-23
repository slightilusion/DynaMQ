<template>
  <div class="kafka-config">
    <el-row :gutter="20">
      <!-- Connection Status Card -->
      <el-col :span="8">
        <el-card class="status-card">
          <template #header>
            <span>⚡ 连接状态</span>
          </template>
          <div class="status-content" v-loading="loadingStatus">
            <div class="status-item">
              <span class="label">状态</span>
              <el-tag :type="status.connected ? 'success' : 'danger'">
                {{ status.connected ? '已连接' : '未连接' }}
              </el-tag>
            </div>
            <div class="status-item">
              <span class="label">服务器</span>
              <span class="value">{{ status.bootstrapServers }}</span>
            </div>
            <div class="status-item">
              <span class="label">路由数量</span>
              <span class="value">{{ status.routeCount }} ({{ status.enabledRoutes }} 已启用)</span>
            </div>
          </div>
          <el-button type="primary" @click="fetchStatus" :loading="loadingStatus" style="width: 100%; margin-top: 16px">
            刷新状态
          </el-button>
        </el-card>
      </el-col>

      <!-- Kafka Configuration Card -->
      <el-col :span="16">
        <el-card>
          <template #header>
            <span>⚙️ Kafka 配置</span>
          </template>
          <div v-loading="loadingConfig">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="启用状态">
                <el-tag :type="config.enabled ? 'success' : 'info'">
                  {{ config.enabled ? '已启用' : '已禁用' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="Bootstrap Servers">
                {{ config.bootstrapServers }}
              </el-descriptions-item>
              <el-descriptions-item label="Topic 前缀">
                {{ config.topicPrefix }}
              </el-descriptions-item>
              <el-descriptions-item label="Acks">
                {{ config.producer?.acks }}
              </el-descriptions-item>
              <el-descriptions-item label="重试次数">
                {{ config.producer?.retries }}
              </el-descriptions-item>
              <el-descriptions-item label="批量大小">
                {{ config.producer?.batchSize }} bytes
              </el-descriptions-item>
              <el-descriptions-item label="Linger Ms">
                {{ config.producer?.lingerMs }} ms
              </el-descriptions-item>
            </el-descriptions>

            <el-alert 
              type="info" 
              :closable="false"
              style="margin-top: 16px"
            >
              <template #title>
                配置文件说明
              </template>
              Kafka 配置存储在 <code>application.yml</code> 文件中。
              如需修改配置，请编辑配置文件并重启服务。
            </el-alert>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Route Statistics Card -->
    <el-card style="margin-top: 20px">
      <template #header>
        <div class="card-header">
          <span>📊 路由统计</span>
          <el-button type="primary" link @click="$router.push('/routes')">
            管理路由 →
          </el-button>
        </div>
      </template>
      <el-row :gutter="20">
        <el-col :span="6">
          <el-statistic title="总路由数" :value="status.routeCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="已启用" :value="status.enabledRoutes">
            <template #suffix>
              <span style="color: #67c23a">✓</span>
            </template>
          </el-statistic>
        </el-col>
        <el-col :span="6">
          <el-statistic title="已禁用" :value="status.routeCount - status.enabledRoutes">
            <template #suffix>
              <span style="color: #909399">○</span>
            </template>
          </el-statistic>
        </el-col>
        <el-col :span="6">
          <el-statistic title="Kafka 状态" :value="config.enabled ? '运行中' : '已禁用'" />
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
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

onMounted(() => {
  fetchConfig()
  fetchStatus()
})
</script>

<style scoped>
.kafka-config {
  padding: 20px;
}

.status-card {
  height: 100%;
}

.status-content {
  min-height: 120px;
}

.status-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}

.status-item:last-child {
  border-bottom: none;
}

.status-item .label {
  color: #909399;
}

.status-item .value {
  font-weight: 500;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

code {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
}
</style>
