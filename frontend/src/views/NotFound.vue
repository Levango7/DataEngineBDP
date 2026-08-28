<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/dashboard')
}
</script>

<template>
  <div class="not-found">
    <el-icon :size="64" color="#909399"><WarningFilled /></el-icon>
    <h1 class="not-found__code">404</h1>
    <p class="not-found__text">页面不存在或已被移除</p>
    <el-button type="primary" @click="goHome">返回首页</el-button>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  gap: 12px;
  text-align: center;
}

.not-found__code {
  font-size: 56px;
  font-weight: 700;
  margin: 0;
  color: #303133;
}

.not-found__text {
  color: #909399;
  margin: 0 0 8px;
}
</style>
