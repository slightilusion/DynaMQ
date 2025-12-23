<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
    ElMessage.error('Failed to load ACL rules')
  } finally {
    loading.value = false
  }
}

const addRule = async () => {
  try {
    await api.addAclRule(newRule.value)
    ElMessage.success('Rule added successfully')
    dialogVisible.value = false
    resetNewRule()
    fetchRules()
  } catch (error) {
    ElMessage.error('Failed to add rule')
  }
}

const deleteRule = async (ruleId) => {
  try {
    await ElMessageBox.confirm(
      'Are you sure you want to delete this rule?',
      'Confirm Delete',
      { type: 'warning' }
    )
    await api.deleteAclRule(ruleId)
    ElMessage.success('Rule deleted')
    fetchRules()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('Failed to delete rule')
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

onMounted(fetchRules)
</script>

<template>
  <div class="acl-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>ACL Rules ({{ rules.length }})</span>
          <div>
            <el-button type="primary" @click="dialogVisible = true">
              Add Rule
            </el-button>
            <el-button @click="fetchRules" :loading="loading">
              Refresh
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="rules" v-loading="loading" stripe border>
        <el-table-column prop="priority" label="Priority" width="80" align="center" sortable />
        <el-table-column prop="clientIdPattern" label="Client ID Pattern" min-width="120" />
        <el-table-column prop="usernamePattern" label="Username Pattern" min-width="120" />
        <el-table-column prop="action" label="Action" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="topicPattern" label="Topic Pattern" min-width="150" />
        <el-table-column prop="allow" label="Permission" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.allow ? 'success' : 'danger'" size="small">
              {{ row.allow ? 'Allow' : 'Deny' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="100" align="center">
          <template #default="{ row }">
            <el-button type="danger" size="small" @click="deleteRule(row.id)">
              Delete
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add Rule Dialog -->
    <el-dialog v-model="dialogVisible" title="Add ACL Rule" width="500px">
      <el-form :model="newRule" label-width="140px">
        <el-form-item label="Client ID Pattern">
          <el-input v-model="newRule.clientIdPattern" placeholder="* for any" />
        </el-form-item>
        <el-form-item label="Username Pattern">
          <el-input v-model="newRule.usernamePattern" placeholder="* for any" />
        </el-form-item>
        <el-form-item label="Action">
          <el-select v-model="newRule.action" style="width: 100%">
            <el-option label="All (*)" value="*" />
            <el-option label="Connect" value="connect" />
            <el-option label="Publish" value="publish" />
            <el-option label="Subscribe" value="subscribe" />
          </el-select>
        </el-form-item>
        <el-form-item label="Topic Pattern">
          <el-input v-model="newRule.topicPattern" placeholder="# for all topics" />
        </el-form-item>
        <el-form-item label="Permission">
          <el-switch v-model="newRule.allow" active-text="Allow" inactive-text="Deny" />
        </el-form-item>
        <el-form-item label="Priority">
          <el-input-number v-model="newRule.priority" :min="0" :max="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="addRule">Add Rule</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
