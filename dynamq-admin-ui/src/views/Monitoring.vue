<template>
  <div class="monitoring">
    <!-- Top Stats Row -->
    <el-row :gutter="16">
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="运行时间" :value="formatUptime(metrics.uptime)" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="活跃连接" :value="metrics.connections?.active || 0" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="消息接收" :value="metrics.messages?.received || 0" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="消息发送" :value="metrics.messages?.sent || 0" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="接收速率" :value="metrics.messages?.receiveRate || '0'" suffix="/s" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="stat-card">
          <el-statistic title="订阅数" :value="metrics.subscriptions || 0" />
        </el-card>
      </el-col>
    </el-row>

    <!-- Second Row: Health + Memory + Kafka -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="8">
        <el-card class="status-card">
          <template #header><span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><FirstAidKit /></el-icon>组件健康</span></template>
          <div class="health-items" v-loading="loadingHealth">
            <div class="health-item" v-for="(comp, key) in health.components" :key="key">
              <span>{{ key.toUpperCase() }}</span>
              <el-tag :type="comp.status === 'UP' ? 'success' : comp.status === 'DOWN' ? 'danger' : 'info'" size="small">
                {{ comp.status }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="status-card">
          <template #header><span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><Cpu /></el-icon>节点内存使用</span></template>
          <div class="nodes-list" v-loading="loadingNodes">
            <div class="node-item" v-for="node in clusterNodes" :key="node.nodeId" @click="showNodeDetail(node)">
              <span>{{ node.nodeId }}</span>
              <el-tag :type="node.status === 'online' ? 'success' : 'danger'" size="small">{{ node.status }}</el-tag>
            </div>
            <div v-if="clusterNodes.length === 0" class="no-nodes">暂无节点数据</div>
          </div>
        </el-card>
      </el-col>

      <!-- Node Detail Dialog -->
      <el-dialog v-model="nodeDialogVisible" :title="selectedNode?.nodeId + ' 详情'" width="400px">
        <div v-if="selectedNode" class="node-detail-dialog">
          <div class="detail-row">
            <span class="label">状态:</span>
            <el-tag :type="selectedNode.status === 'online' ? 'success' : 'danger'">{{ selectedNode.status }}</el-tag>
          </div>
          <div class="progress-row" v-if="selectedNode.memory">
            <span class="label">内存使用:</span>
            <div class="progress-wrapper">
              <el-progress 
                :percentage="selectedNode.memory.usedPercent || 0" 
                :color="getMemoryColor(selectedNode.memory.usedPercent)"
                :stroke-width="16"
              />
            </div>
          </div>
          <div class="detail-row" v-if="selectedNode.memory">
            <span class="label">已用:</span>
            <span>{{ formatBytes(selectedNode.memory.used) }}</span>
          </div>
          <div class="detail-row" v-if="selectedNode.memory">
            <span class="label">最大:</span>
            <span>{{ formatBytes(selectedNode.memory.max) }}</span>
          </div>
          <div class="detail-row" v-if="selectedNode.lastHeartbeat">
            <span class="label">最后心跳:</span>
            <span>{{ formatHeartbeat(selectedNode.lastHeartbeat) }}</span>
          </div>
        </div>
      </el-dialog>
      <el-col :span="8">
        <el-card class="status-card">
          <template #header><span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><Box /></el-icon>Kafka 统计</span></template>
          <div class="kafka-stats">
            <div class="kafka-item">
              <span>发送成功</span>
              <el-tag type="success">{{ metrics.kafka?.publishSuccess || 0 }}</el-tag>
            </div>
            <div class="kafka-item">
              <span>发送失败</span>
              <el-tag type="danger">{{ metrics.kafka?.publishFailed || 0 }}</el-tag>
            </div>
            <div class="kafka-item">
              <span>成功率</span>
              <el-tag type="info">{{ kafkaSuccessRate }}%</el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Charts Row -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="16">
        <el-card>
          <template #header>
            <div class="card-header">
              <span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><TrendCharts /></el-icon>连接数趋势</span>
              <el-button type="primary" link size="small" @click="clearHistory">清除</el-button>
            </div>
          </template>
          <v-chart class="chart" :option="connectionChartOption" autoresize />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><PieChartIcon /></el-icon>QoS 分布</span></template>
          <v-chart class="chart" :option="qosChartOption" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <!-- Prometheus Info -->
    <el-card style="margin-top: 16px">
      <template #header><span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><Link /></el-icon>Prometheus</span></template>
      <el-alert type="info" :closable="false" show-icon>
        <template #title>指标端点: <code>http://localhost:9090/metrics</code></template>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { FirstAidKit, Cpu, Box, TrendCharts, PieChart as PieChartIcon, Link } from '@element-plus/icons-vue'
import axios from 'axios'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const API_BASE = import.meta.env.VITE_API_BASE || ''

const metrics = ref({})
const health = ref({ components: {} })
const clusterNodes = ref([])
const loadingHealth = ref(false)
const loadingNodes = ref(false)
const connectionHistory = ref([])
const nodeDialogVisible = ref(false)
const selectedNode = ref(null)
const MAX_HISTORY = 60
let pollingInterval = null

const showNodeDetail = (node) => {
  selectedNode.value = node
  nodeDialogVisible.value = true
}

const formatHeartbeat = (timestamp) => {
  if (!timestamp) return 'N/A'
  try {
    return new Date(timestamp).toLocaleString()
  } catch {
    return timestamp
  }
}

const fetchMetrics = async () => {
  try {
    const res = await axios.get(`${API_BASE}/api/admin/metrics/realtime`)
    metrics.value = res.data
    const time = new Date().toLocaleTimeString()
    connectionHistory.value.push({ time, value: res.data.connections?.active || 0 })
    if (connectionHistory.value.length > MAX_HISTORY) connectionHistory.value.shift()
  } catch (err) {
    console.error('Failed to fetch metrics:', err)
  }
}

const fetchHealth = async () => {
  loadingHealth.value = true
  try {
    const res = await axios.get(`${API_BASE}/api/admin/health`)
    health.value = res.data
  } catch (err) {
    ElMessage.error('获取健康状态失败')
  } finally {
    loadingHealth.value = false
  }
}

const fetchClusterNodes = async () => {
  loadingNodes.value = true
  try {
    const res = await axios.get(`${API_BASE}/api/admin/cluster/nodes`)
    clusterNodes.value = res.data.nodes || []
  } catch (err) {
    console.error('Failed to fetch cluster nodes:', err)
  } finally {
    loadingNodes.value = false
  }
}

const clearHistory = () => { connectionHistory.value = [] }

const formatBytes = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024, sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

const formatUptime = (seconds) => {
  if (!seconds) return '0s'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

const getMemoryColor = (percent) => percent < 60 ? '#67c23a' : percent < 80 ? '#e6a23c' : '#f56c6c'

const kafkaSuccessRate = computed(() => {
  const s = metrics.value.kafka?.publishSuccess || 0
  const f = metrics.value.kafka?.publishFailed || 0
  return s + f > 0 ? ((s / (s + f)) * 100).toFixed(1) : '100.0'
})

const connectionChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: '3%', right: '4%', bottom: '10%', containLabel: true },
  xAxis: { type: 'category', data: connectionHistory.value.map(d => d.time), axisLabel: { rotate: 45 } },
  yAxis: { type: 'value', name: '连接数' },
  series: [{ name: '活跃连接', type: 'line', smooth: true, areaStyle: { opacity: 0.3 }, itemStyle: { color: '#409eff' }, data: connectionHistory.value.map(d => d.value) }]
}))

const qosChartOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie', radius: ['40%', '70%'], center: ['50%', '45%'],
    label: { show: false },
    data: [
      { value: metrics.value.qos?.qos0 || 0, name: 'QoS 0', itemStyle: { color: '#67c23a' } },
      { value: metrics.value.qos?.qos1 || 0, name: 'QoS 1', itemStyle: { color: '#409eff' } },
      { value: metrics.value.qos?.qos2 || 0, name: 'QoS 2', itemStyle: { color: '#e6a23c' } }
    ]
  }]
}))

onMounted(() => {
  fetchMetrics()
  fetchHealth()
  fetchClusterNodes()
  pollingInterval = setInterval(() => {
    fetchMetrics()
    fetchClusterNodes()
  }, 3000)
})

onUnmounted(() => { if (pollingInterval) clearInterval(pollingInterval) })
</script>

<style scoped>
.monitoring { padding: 16px; }
.stat-card { text-align: center; }
.status-card { min-height: 200px; }
.health-items { display: flex; flex-direction: column; gap: 10px; }
.health-item { 
  display: flex; 
  justify-content: space-between; 
  padding: 6px 10px; 
  background: var(--bg-hover); 
  border-radius: 4px; 
  color: var(--text-primary);
}
.nodes-list { display: flex; flex-direction: column; gap: 10px; }
.node-item { 
  display: flex; 
  justify-content: space-between; 
  padding: 8px 12px; 
  background: var(--bg-hover); 
  border-radius: 6px; 
  cursor: pointer;
  transition: background 0.2s;
  color: var(--text-primary);
}
.node-item:hover { background: var(--border-color); }
.no-nodes { text-align: center; color: var(--text-secondary); padding: 20px; }
.node-detail-dialog { display: flex; flex-direction: column; gap: 16px; }
.detail-row { display: flex; justify-content: space-between; align-items: center; }
.detail-row .label { color: var(--text-secondary); font-weight: 500; }
.progress-row { display: flex; flex-direction: column; gap: 8px; }
.progress-row .label { color: var(--text-secondary); font-weight: 500; }
.progress-wrapper { width: 100%; }
.kafka-stats { display: flex; flex-direction: column; gap: 10px; }
.kafka-item { 
  display: flex; 
  justify-content: space-between; 
  padding: 6px 10px; 
  background: var(--bg-hover); 
  border-radius: 4px;
  color: var(--text-primary);
}
.card-header { display: flex; justify-content: space-between; align-items: center; }
.chart { height: 250px; }
code { background: var(--bg-hover); padding: 2px 6px; border-radius: 4px; color: var(--text-primary); }
</style>
