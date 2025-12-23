<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const loading = ref(true)
const nodes = ref([])

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

onMounted(() => {
  fetchNodes()
  setInterval(fetchNodes, 5000)
})
</script>

<template>
  <div class="cluster-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Cluster Nodes ({{ nodes.length }})</span>
          <el-button type="primary" @click="fetchNodes" :loading="loading">
            Refresh
          </el-button>
        </div>
      </template>

      <el-row :gutter="20" v-if="nodes.length > 0">
        <el-col :span="8" v-for="node in nodes" :key="node.nodeId">
          <el-card class="node-card" :class="{ online: node.status === 'online' }">
            <div class="node-header">
              <el-icon size="30" :color="node.status === 'online' ? '#67c23a' : '#f56c6c'">
                <component :is="node.status === 'online' ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
              </el-icon>
              <span class="node-id">{{ node.nodeId }}</span>
            </div>
            <el-divider />
            <div class="node-info">
              <div class="info-row">
                <span class="label">Status:</span>
                <el-tag :type="node.status === 'online' ? 'success' : 'danger'" size="small">
                  {{ node.status }}
                </el-tag>
              </div>
              <div class="info-row">
                <span class="label">Last Heartbeat:</span>
                <span class="value">{{ node.lastHeartbeat || 'N/A' }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-empty v-else description="No cluster nodes found" />
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.node-card {
  margin-bottom: 20px;
  transition: all 0.3s;
}

.node-card.online {
  border-left: 3px solid #67c23a;
}

.node-card:not(.online) {
  border-left: 3px solid #f56c6c;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.node-id {
  font-size: 16px;
  font-weight: bold;
}

.info-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.label {
  color: #909399;
}

.value {
  font-size: 12px;
}
</style>
