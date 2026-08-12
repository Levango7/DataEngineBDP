<template>
  <div class="finops-dashboard">
    <el-container>
      <el-header class="header">
        <h1 class="title">FinOps 看板</h1>
        <nav class="nav">
          <router-link to="/dashboard" class="nav-link">总览</router-link>
          <router-link to="/top10" class="nav-link">Top10</router-link>
          <router-link to="/trend" class="nav-link">趋势</router-link>
          <router-link to="/details" class="nav-link">明细</router-link>
          <router-link to="/idle" class="nav-link">闲置清单</router-link>
          <router-link to="/suggestions" class="nav-link">优化建议</router-link>
          <router-link to="/bill-export" class="nav-link">账单导出</router-link>
          <router-link to="/allocation" class="nav-link">分账配置</router-link>
        </nav>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
// FinOps 看板总览布局
</script>

<style scoped>
.finops-dashboard {
  min-height: 100vh;
}
.header {
  background-color: #ffffff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  padding: 0 24px;
  height: 60px;
}
.title {
  margin: 0;
  font-size: 18px;
  color: #303133;
  margin-right: 32px;
}
.nav {
  display: flex;
  gap: 16px;
}
.nav-link {
  color: #606266;
  text-decoration: none;
  font-size: 14px;
  padding: 8px 12px;
  border-radius: 4px;
  transition: all 0.2s;
}
.nav-link:hover {
  background-color: #f5f7fa;
  color: #409eff;
}
.nav-link.router-link-active {
  color: #409eff;
  background-color: #ecf5ff;
}
.main {
  padding: 24px;
}
</style>