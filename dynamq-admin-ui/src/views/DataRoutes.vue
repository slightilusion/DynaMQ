<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, Share } from '@element-plus/icons-vue'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const routes = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)

const routeForm = ref({
  id: null,
  mqttTopicPattern: '',
  kafkaTopic: '',
  transformType: 'JSON_WRAP',
  enabled: true,
  description: ''
})

const enabledCount = computed(() => routes.value.filter(r => r.enabled).length)

const fetchRoutes = async () => {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/api/admin/routes`)
    routes.value = res.data.routes || []
  } catch (err) {
    ElMessage.error('获取路由列表失败')
  } finally {
    loading.value = false
  }
}

const showAddDialog = () => {
  isEdit.value = false
  routeForm.value = {
    id: null,
    mqttTopicPattern: '',
    kafkaTopic: '',
    transformType: 'JSON_WRAP',
    enabled: true,
    description: ''
  }
  dialogVisible.value = true
}

const editRoute = (route) => {
  isEdit.value = true
  routeForm.value = { ...route }
  dialogVisible.value = true
}

const saveRoute = async () => {
  saving.value = true
  try {
    if (isEdit.value) {
      await axios.put(`${API_BASE}/api/admin/routes/${routeForm.value.id}`, routeForm.value)
      ElMessage.success('路由已更新')
    } else {
      await axios.post(`${API_BASE}/api/admin/routes`, routeForm.value)
      ElMessage.success('路由已添加')
    }
    dialogVisible.value = false
    fetchRoutes()
  } catch (err) {
    ElMessage.error('保存失败: ' + (err.response?.data?.message || err.message))
  } finally {
    saving.value = false
  }
}

const toggleRoute = async (route) => {
  try {
    await axios.put(`${API_BASE}/api/admin/routes/${route.id}/toggle`)
    ElMessage.success(route.enabled ? '路由已启用' : '路由已禁用')
  } catch (err) {
    route.enabled = !route.enabled
    ElMessage.error('切换状态失败')
  }
}

const deleteRoute = async (id) => {
  try {
    await axios.delete(`${API_BASE}/api/admin/routes/${id}`)
    ElMessage.success('路由已删除')
    fetchRoutes()
  } catch (err) {
    ElMessage.error('删除失败')
  }
}

onMounted(fetchRoutes)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">数据路由</h1>
        <p class="page-subtitle">配置 MQTT 到 Kafka 的消息路由规则</p>
      </div>
      <div class="header-actions">
        <button @click="showAddDialog" class="pill-btn">
          <el-icon><Plus /></el-icon>
          添加路由
        </button>
        <button @click="fetchRoutes" class="icon-btn" :disabled="loading">
          <el-icon :class="{ spin: loading }" :size="18"><Refresh /></el-icon>
        </button>
      </div>
    </header>

    <!-- Stats -->
    <div class="metrics">
      <div class="metric-card">
        <span class="metric-value">{{ routes.length }}</span>
        <span class="metric-label">总路由数</span>
      </div>
      <div class="metric-card">
        <span class="metric-value success">{{ enabledCount }}</span>
        <span class="metric-label">已启用</span>
      </div>
      <div class="metric-card">
        <span class="metric-value muted">{{ routes.length - enabledCount }}</span>
        <span class="metric-label">已禁用</span>
      </div>
    </div>

    <!-- Routes Table -->
    <div class="table-container">
      <table class="table">
        <thead>
          <tr>
            <th width="60">状态</th>
            <th>MQTT Topic</th>
            <th><el-icon><Share /></el-icon></th>
            <th>Kafka Topic</th>
            <th width="100">转换</th>
            <th>描述</th>
            <th width="100"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="route in routes" :key="route.id">
            <td class="center">
              <label class="toggle">
                <input type="checkbox" v-model="route.enabled" @change="toggleRoute(route)" />
                <span class="toggle-slider"></span>
              </label>
            </td>
            <td><code>{{ route.mqttTopicPattern }}</code></td>
            <td class="center arrow">→</td>
            <td><code class="kafka">{{ route.kafkaTopic }}</code></td>
            <td class="center">
              <span :class="['type-badge', route.transformType.toLowerCase()]">
                {{ route.transformType }}
              </span>
            </td>
            <td class="muted">{{ route.description || '-' }}</td>
            <td>
              <div class="row-actions">
                <button @click="editRoute(route)" class="icon-btn small">
                  <el-icon :size="14"><Edit /></el-icon>
                </button>
                <button @click="deleteRoute(route.id)" class="icon-btn small danger">
                  <el-icon :size="14"><Delete /></el-icon>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      
      <div v-if="loading" class="loading">
        <div class="spinner"></div>
      </div>
      <div v-else-if="routes.length === 0" class="empty">
        暂无数据路由规则
      </div>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑路由' : '添加路由'" width="500">
      <div class="form-grid">
        <div class="form-item full">
          <label>MQTT Topic 模式</label>
          <input v-model="routeForm.mqttTopicPattern" placeholder="例如: devices/+/telemetry" class="form-input" />
          <span class="form-hint">支持通配符: + (单级), # (多级)</span>
        </div>
        <div class="form-item full">
          <label>Kafka Topic</label>
          <input v-model="routeForm.kafkaTopic" placeholder="例如: iot-telemetry" class="form-input" />
        </div>
        <div class="form-item">
          <label>转换类型</label>
          <select v-model="routeForm.transformType" class="form-input">
            <option value="JSON_WRAP">JSON 封装 (带元数据)</option>
            <option value="RAW">原始数据</option>
          </select>
        </div>
        <div class="form-item">
          <label>启用状态</label>
          <label class="toggle large">
            <input type="checkbox" v-model="routeForm.enabled" />
            <span class="toggle-slider"></span>
            <span class="toggle-label">{{ routeForm.enabled ? '已启用' : '已禁用' }}</span>
          </label>
        </div>
        <div class="form-item full">
          <label>描述</label>
          <textarea v-model="routeForm.description" rows="2" placeholder="可选描述" class="form-input"></textarea>
        </div>
      </div>
      <template #footer>
        <button @click="dialogVisible = false" class="pill-btn secondary">取消</button>
        <button @click="saveRoute" class="pill-btn" :disabled="saving">
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  max-width: 1100px;
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

.header-actions {
  display: flex;
  gap: 12px;
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
  margin-bottom: 24px;
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

.metric-value.success { color: var(--success); }
.metric-value.muted { color: var(--text-tertiary); }

.metric-label {
  font-size: 13px;
  color: var(--text-secondary);
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
  padding: 14px 20px;
  font-size: 13px;
  border-top: 1px solid var(--border-default);
}

.table code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-hover);
  padding: 4px 8px;
  border-radius: 4px;
}

.table code.kafka {
  background: rgba(52, 168, 83, 0.1);
  color: var(--success);
}

.arrow {
  font-size: 16px;
  color: var(--text-tertiary);
}

.center { text-align: center; }
.muted { color: var(--text-secondary); }

.type-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 600;
}

.type-badge.json_wrap {
  background: rgba(26, 115, 232, 0.1);
  color: var(--accent);
}

.type-badge.raw {
  background: rgba(251, 188, 4, 0.15);
  color: var(--warning);
}

.row-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.icon-btn.small {
  width: 30px;
  height: 30px;
}

.icon-btn.danger {
  color: var(--danger);
}

.icon-btn.danger:hover {
  background: rgba(234, 67, 53, 0.1);
}

/* Toggle */
.toggle {
  position: relative;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
}

.toggle input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  width: 40px;
  height: 22px;
  background: var(--text-tertiary);
  border-radius: 11px;
  transition: all var(--transition);
}

.toggle-slider::after {
  content: '';
  position: absolute;
  width: 18px;
  height: 18px;
  left: 2px;
  top: 2px;
  background: white;
  border-radius: 50%;
  transition: transform var(--transition);
}

.toggle input:checked + .toggle-slider {
  background: var(--success);
}

.toggle input:checked + .toggle-slider::after {
  transform: translateX(18px);
}

.toggle.large {
  gap: 12px;
}

.toggle-label {
  font-size: 13px;
  color: var(--text-secondary);
}

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

/* Form */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-item.full {
  grid-column: 1 / -1;
}

.form-item label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.form-input {
  padding: 12px 14px;
  background: var(--bg-page);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--text-primary);
  outline: none;
  transition: border-color var(--transition);
  font-family: inherit;
}

.form-input:focus {
  border-color: var(--accent);
}

.form-hint {
  font-size: 11px;
  color: var(--text-tertiary);
}

textarea.form-input {
  resize: vertical;
  min-height: 60px;
}
</style>
