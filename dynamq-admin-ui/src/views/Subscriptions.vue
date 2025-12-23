<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const loading = ref(true)
const subscriptions = ref([])

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

const getTotalTopics = () => {
  return subscriptions.value.reduce((sum, s) => sum + (s.topics?.length || 0), 0)
}

onMounted(fetchSubscriptions)
</script>

<template>
  <div class="subscriptions-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Subscriptions ({{ getTotalTopics() }} topics)</span>
          <el-button type="primary" @click="fetchSubscriptions" :loading="loading">
            Refresh
          </el-button>
        </div>
      </template>

      <el-collapse v-if="subscriptions.length > 0">
        <el-collapse-item 
          v-for="sub in subscriptions" 
          :key="sub.clientId"
          :title="`${sub.clientId} (${sub.topics?.length || 0} topics)`"
        >
          <el-table :data="sub.topics || []" size="small" stripe>
            <el-table-column prop="topic" label="Topic Filter" />
            <el-table-column prop="qos" label="QoS" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.qos === 2 ? 'danger' : row.qos === 1 ? 'warning' : 'info'">
                  QoS {{ row.qos }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>

      <el-empty v-else description="No subscriptions found" />
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
