<script setup>
import { RouterView } from 'vue-router'
import { ref } from 'vue'
import {
  House,
  Monitor,
  Promotion,
  Lock,
  Connection,
  Setting,
  DataLine
} from '@element-plus/icons-vue'

const isCollapse = ref(false)
</script>

<template>
  <el-container class="app-container">
    <el-aside :width="isCollapse ? '64px' : '200px'" class="aside">
      <div class="logo">
        <img src="/vite.svg" alt="DynaMQ" width="32" />
        <span v-if="!isCollapse">DynaMQ</span>
      </div>
      <el-menu
        router
        :default-active="$route.path"
        :collapse="isCollapse"
        class="menu"
        background-color="#001529"
        text-color="#fff"
        active-text-color="#409eff"
      >
        <el-menu-item index="/">
          <el-icon><House /></el-icon>
          <span>Dashboard</span>
        </el-menu-item>
        <el-menu-item index="/clients">
          <el-icon><Monitor /></el-icon>
          <span>Clients</span>
        </el-menu-item>
        <el-menu-item index="/cluster">
          <el-icon><Promotion /></el-icon>
          <span>Cluster</span>
        </el-menu-item>
        <el-menu-item index="/acl">
          <el-icon><Lock /></el-icon>
          <span>ACL Rules</span>
        </el-menu-item>
        <el-menu-item index="/routes">
          <el-icon><Connection /></el-icon>
          <span>数据路由</span>
        </el-menu-item>
        <el-menu-item index="/kafka">
          <el-icon><Setting /></el-icon>
          <span>Kafka 配置</span>
        </el-menu-item>
        <el-menu-item index="/monitoring">
          <el-icon><DataLine /></el-icon>
          <span>监控面板</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-button @click="isCollapse = !isCollapse" text>
          <el-icon size="20">
            <component :is="isCollapse ? 'Expand' : 'Fold'" />
          </el-icon>
        </el-button>
        <span class="title">DynaMQ Admin Console</span>
      </el-header>
      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
}

.app-container {
  height: 100%;
}

.aside {
  background-color: #001529;
  transition: width 0.3s;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
}

.menu {
  border-right: none;
}

.header {
  background: #fff;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
}

.title {
  font-size: 16px;
  font-weight: 500;
}

.main {
  background: #f0f2f5;
  padding: 20px;
}
</style>
