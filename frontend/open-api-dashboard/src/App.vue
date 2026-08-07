<template>
  <el-container class="app-container">
    <el-aside width="220px" class="app-aside">
      <div class="logo">
        <h2>开放 API 目录</h2>
        <p class="logo-sub">数擎大数据平台 L5.5</p>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="app-menu"
      >
        <el-menu-item index="/catalog">
          <el-icon><Grid /></el-icon>
          <span>API 目录</span>
        </el-menu-item>
        <el-menu-item index="/generate">
          <el-icon><MagicStick /></el-icon>
          <span>一键生成</span>
        </el-menu-item>
        <el-menu-item index="/subscriptions">
          <el-icon><Key /></el-icon>
          <span>订阅管理</span>
        </el-menu-item>
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>用量看板</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/catalog' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentPageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag type="success" effect="plain">服务正常</el-tag>
          <el-avatar :size="32">SQ</el-avatar>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Grid, MagicStick, Key, DataLine } from '@element-plus/icons-vue'

const route = useRoute()

const activeMenu = computed(() => route.path)

const titleMap = {
  '/catalog': 'API 目录',
  '/generate': '一键生成',
  '/subscriptions': '订阅管理',
  '/dashboard': '用量看板',
}
const currentPageTitle = computed(() => titleMap[route.path] || '页面')
</script>

<style scoped>
.app-container {
  height: 100vh;
}
.app-aside {
  background: #fff;
  border-right: 1px solid #e6e8eb;
}
.logo {
  padding: 20px 16px;
  border-bottom: 1px solid #e6e8eb;
}
.logo h2 {
  margin: 0;
  font-size: 18px;
  color: #1f2329;
}
.logo-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: #646a73;
}
.app-menu {
  border-right: none;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e6e8eb;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.app-main {
  background: #f5f6f7;
  padding: 24px;
}
</style>
