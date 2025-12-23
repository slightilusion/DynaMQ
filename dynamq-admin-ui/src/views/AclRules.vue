<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus, Key, Delete } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(true)
const rules = ref([])
const dialogVisible = ref(false)
const newRule = ref({
  clientIdPattern: '*',
  usernamePattern: '*',
  action: '*',
  topicPattern: '#',
  allow: true,
  priority: 0
})

const fetchRules = async () => {
  try {
    loading.value = true
    const { data } = await api.getAclRules()
    rules.value = data.rules || []
  } catch (error) {
    console.error('Failed to fetch rules:', error)
    ElMessage.error('加载ACL规则失败')
  } finally {
    loading.value = false
  }
}

const addRule = async () => {
  try {
    await api.addAclRule(newRule.value)
    ElMessage.success('规则添加成功')
    dialogVisible.value = false
    resetNewRule()
    fetchRules()
  } catch (error) {
    ElMessage.error('添加规则失败')
  }
}

const deleteRule = async (ruleId) => {
  try {
    await ElMessageBox.confirm('确认删除此规则?', '确认删除', { type: 'warning' })
    await api.deleteAclRule(ruleId)
    ElMessage.success('规则已删除')
    fetchRules()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除规则失败')
    }
  }
}

const resetNewRule = () => {
  newRule.value = {
    clientIdPattern: '*',
    usernamePattern: '*',
    action: '*',
    topicPattern: '#',
    allow: true,
    priority: 0
  }
}

const allowCount = computed(() => rules.value.filter(r => r.allow).length)
const denyCount = computed(() => rules.value.filter(r => !r.allow).length)

onMounted(fetchRules)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">访问控制</h1>
        <p class="page-subtitle">管理 MQTT 客户端访问权限规则</p>
      </div>
      <div class="header-actions">
        <button @click="dialogVisible = true" class="pill-btn">
          <el-icon><Plus /></el-icon>
          添加规则
        </button>
        <button @click="fetchRules" class="icon-btn" :disabled="loading">
          <el-icon :class="{ spin: loading }" :size="18"><Refresh /></el-icon>
        </button>
      </div>
    </header>

    <!-- Stats -->
    <div class="metrics">
      <div class="metric-card">
        <span class="metric-value">{{ rules.length }}</span>
        <span class="metric-label">总规则数</span>
      </div>
      <div class="metric-card">
        <span class="metric-value success">{{ allowCount }}</span>
        <span class="metric-label">允许规则</span>
      </div>
      <div class="metric-card">
        <span class="metric-value danger">{{ denyCount }}</span>
        <span class="metric-label">拒绝规则</span>
      </div>
    </div>

    <!-- Rules Table -->
    <div class="table-container">
      <table class="table">
        <thead>
          <tr>
            <th width="80">优先级</th>
            <th>Client ID</th>
            <th>用户名</th>
            <th width="100">动作</th>
            <th>Topic</th>
            <th width="100">权限</th>
            <th width="80"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rule in rules" :key="rule.id">
            <td class="center">
              <span class="priority-badge">{{ rule.priority }}</span>
            </td>
            <td><code>{{ rule.clientIdPattern }}</code></td>
            <td><code>{{ rule.usernamePattern }}</code></td>
            <td class="center">
              <span class="pill-badge small">{{ rule.action }}</span>
            </td>
            <td><code>{{ rule.topicPattern }}</code></td>
            <td class="center">
              <span :class="['pill-badge', 'small', rule.allow ? 'success' : 'danger']">
                {{ rule.allow ? '允许' : '拒绝' }}
              </span>
            </td>
            <td class="center">
              <button @click="deleteRule(rule.id)" class="icon-btn small danger">
                <el-icon :size="16"><Delete /></el-icon>
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      
      <div v-if="loading" class="loading">
        <div class="spinner"></div>
      </div>
      <div v-else-if="rules.length === 0" class="empty">
        暂无访问控制规则
      </div>
    </div>

    <!-- Add Rule Dialog -->
    <el-dialog v-model="dialogVisible" title="添加访问控制规则" width="500">
      <div class="form-grid">
        <div class="form-item">
          <label>Client ID 匹配模式</label>
          <input v-model="newRule.clientIdPattern" placeholder="* 表示匹配所有" class="form-input" />
        </div>
        <div class="form-item">
          <label>用户名匹配模式</label>
          <input v-model="newRule.usernamePattern" placeholder="* 表示匹配所有" class="form-input" />
        </div>
        <div class="form-item">
          <label>动作类型</label>
          <select v-model="newRule.action" class="form-input">
            <option value="*">全部 (*)</option>
            <option value="connect">连接 (connect)</option>
            <option value="publish">发布 (publish)</option>
            <option value="subscribe">订阅 (subscribe)</option>
          </select>
        </div>
        <div class="form-item">
          <label>Topic 匹配模式</label>
          <input v-model="newRule.topicPattern" placeholder="# 表示匹配所有" class="form-input" />
        </div>
        <div class="form-item">
          <label>权限</label>
          <div class="permission-toggle">
            <button 
              :class="['toggle-btn', { active: newRule.allow }]" 
              @click="newRule.allow = true"
            >允许</button>
            <button 
              :class="['toggle-btn', 'danger', { active: !newRule.allow }]" 
              @click="newRule.allow = false"
            >拒绝</button>
          </div>
        </div>
        <div class="form-item">
          <label>优先级</label>
          <input v-model.number="newRule.priority" type="number" min="0" max="1000" class="form-input" />
        </div>
      </div>
      <template #footer>
        <button @click="dialogVisible = false" class="pill-btn secondary">取消</button>
        <button @click="addRule" class="pill-btn">添加规则</button>
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
.metric-value.danger { color: var(--danger); }

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
  padding: 2px 6px;
  border-radius: 4px;
}

.center { text-align: center; }

.priority-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: var(--bg-hover);
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
}

.pill-badge.small {
  padding: 4px 10px;
  font-size: 11px;
}

.icon-btn.small {
  width: 32px;
  height: 32px;
}

.icon-btn.danger {
  color: var(--danger);
}

.icon-btn.danger:hover {
  background: rgba(234, 67, 53, 0.1);
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
}

.form-input:focus {
  border-color: var(--accent);
}

.permission-toggle {
  display: flex;
  gap: 8px;
}

.toggle-btn {
  flex: 1;
  padding: 10px;
  background: var(--bg-page);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition);
}

.toggle-btn:hover {
  background: var(--bg-hover);
}

.toggle-btn.active {
  background: var(--success);
  border-color: var(--success);
  color: white;
}

.toggle-btn.danger.active {
  background: var(--danger);
  border-color: var(--danger);
}
</style>
