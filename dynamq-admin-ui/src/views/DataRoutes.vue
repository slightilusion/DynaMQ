<template>
  <div class="data-routes">
    <el-card>
      <template #header>
        <div class="card-header">
          <span><el-icon :size="16" style="vertical-align: middle; margin-right: 6px;"><Share /></el-icon>数据路由配置</span>
          <el-button type="primary" @click="showAddDialog">
            <el-icon><Plus /></el-icon> 添加路由
          </el-button>
        </div>
      </template>

      <el-table :data="routes" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="mqttTopicPattern" label="MQTT Topic 模式">
          <template #default="{ row }">
            <el-tag type="info">{{ row.mqttTopicPattern }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="kafkaTopic" label="Kafka Topic">
          <template #default="{ row }">
            <el-tag type="success">{{ row.kafkaTopic }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="transformType" label="转换类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.transformType === 'RAW' ? 'warning' : 'primary'" size="small">
              {{ row.transformType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleRoute(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="editRoute(row)">
              编辑
            </el-button>
            <el-popconfirm title="确认删除此路由?" @confirm="deleteRoute(row.id)">
              <template #reference>
                <el-button type="danger" size="small" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div class="stats-footer">
        <span>共 {{ routes.length }} 条路由，{{ enabledCount }} 条已启用</span>
      </div>
    </el-card>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑路由' : '添加路由'" width="500px">
      <el-form :model="routeForm" label-width="120px" :rules="rules" ref="formRef">
        <el-form-item label="MQTT Topic" prop="mqttTopicPattern">
          <el-input v-model="routeForm.mqttTopicPattern" placeholder="例如: devices/+/telemetry" />
          <div class="form-tip">支持通配符: + (单级), # (多级)</div>
        </el-form-item>
        <el-form-item label="Kafka Topic" prop="kafkaTopic">
          <el-input v-model="routeForm.kafkaTopic" placeholder="例如: iot-telemetry" />
        </el-form-item>
        <el-form-item label="转换类型" prop="transformType">
          <el-select v-model="routeForm.transformType" style="width: 100%">
            <el-option label="JSON 封装 (带元数据)" value="JSON_WRAP" />
            <el-option label="原始数据" value="RAW" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="routeForm.enabled" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="routeForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoute" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Share } from '@element-plus/icons-vue'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'

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

const rules = {
  mqttTopicPattern: [{ required: true, message: '请输入 MQTT Topic 模式', trigger: 'blur' }],
  kafkaTopic: [{ required: true, message: '请输入 Kafka Topic', trigger: 'blur' }]
}

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
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  
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
    route.enabled = !route.enabled // Revert
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

<style scoped>
.data-routes {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stats-footer {
  margin-top: 16px;
  color: #909399;
  font-size: 14px;
}

.form-tip {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}
</style>
