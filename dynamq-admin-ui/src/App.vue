<script setup>
import { RouterView } from 'vue-router'
import { ref, onMounted } from 'vue'
import { useTheme } from './composables/useTheme'
import {
  HomeFilled,
  Monitor,
  Grid,
  Key,
  Share,
  Cpu,
  DataLine,
  Sunny,
  Moon,
  Fold,
  Expand
} from '@element-plus/icons-vue'

const { isDark, toggleTheme } = useTheme()

// 侧边栏收缩状态
const sidebarCollapsed = ref(false)

// 从localStorage读取状态
onMounted(() => {
  const saved = localStorage.getItem('sidebar-collapsed')
  if (saved !== null) {
    sidebarCollapsed.value = saved === 'true'
  }
})

// 切换收缩状态
const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem('sidebar-collapsed', sidebarCollapsed.value)
}

// 导航菜单配置
const navItems = [
  { path: '/', icon: HomeFilled, label: '仪表盘' },
  { path: '/clients', icon: Monitor, label: '客户端' },
  { path: '/cluster', icon: Grid, label: '集群' },
  { path: '/acl', icon: Key, label: '访问控制' },
  { path: '/routes', icon: Share, label: '数据路由' },
  { path: '/kafka', icon: Cpu, label: 'Kafka' },
  { path: '/monitoring', icon: DataLine, label: '监控' }
]
</script>

<template>
  <div class="app">
    <!-- Floating Sidebar -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-inner">
        <div class="logo">
          <div class="logo-icon">
            <span>D</span>
          </div>
          <span v-if="!sidebarCollapsed" class="logo-text">DynaMQ</span>
        </div>
        
        <nav class="nav">
          <router-link 
            v-for="item in navItems" 
            :key="item.path"
            :to="item.path" 
            class="nav-item" 
            active-class="active" 
            :title="sidebarCollapsed ? item.label : ''"
          >
            <el-icon :size="20"><component :is="item.icon" /></el-icon>
            <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
          </router-link>
        </nav>
        
        <div class="sidebar-bottom">
          <button @click="toggleTheme" class="nav-item" :title="isDark ? '亮色模式' : '暗色模式'">
            <el-icon :size="20">
              <Sunny v-if="isDark" />
              <Moon v-else />
            </el-icon>
            <span v-if="!sidebarCollapsed" class="nav-label">{{ isDark ? '亮色模式' : '暗色模式' }}</span>
          </button>
          <button @click="toggleSidebar" class="nav-item collapse-btn" :title="sidebarCollapsed ? '展开' : '收起'">
            <el-icon :size="20">
              <Expand v-if="sidebarCollapsed" />
              <Fold v-else />
            </el-icon>
            <span v-if="!sidebarCollapsed" class="nav-label">收起</span>
          </button>
        </div>
      </div>
    </aside>
    
    <!-- Main Content -->
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  height: 100vh;
  padding: 16px;
  gap: 16px;
  background: var(--bg-page);
}

/* Floating Sidebar */
.sidebar {
  width: 220px;
  flex-shrink: 0;
  transition: width 0.3s ease;
}

.sidebar.collapsed {
  width: 72px;
}

.sidebar-inner {
  height: 100%;
  background: var(--bg-surface);
  border-radius: var(--radius-xl);
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  overflow: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  padding: 0 6px;
}

.logo-icon {
  width: 40px;
  height: 40px;
  min-width: 40px;
  background: var(--text-primary);
  color: var(--bg-page);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
}

.logo-text {
  font-size: 18px;
  font-weight: 700;
  white-space: nowrap;
  opacity: 1;
  transition: opacity 0.2s ease;
}

/* Navigation */
.nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-decoration: none;
  cursor: pointer;
  transition: all var(--transition);
  white-space: nowrap;
  overflow: hidden;
}

.collapsed .nav-item {
  justify-content: center;
  padding: 12px;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--text-primary);
  color: var(--bg-page);
}

.nav-label {
  font-size: 14px;
  font-weight: 500;
  opacity: 1;
  transition: opacity 0.2s ease;
}

.sidebar-bottom {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--border-default);
  margin-top: auto;
}

.collapse-btn {
  margin-top: 4px;
}

/* Main Content */
.main {
  flex: 1;
  background: var(--bg-surface);
  border-radius: var(--radius-xl);
  padding: 32px;
  overflow-y: auto;
  min-width: 0;
}
</style>
