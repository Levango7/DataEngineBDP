<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/dashboard')
}
</script>

<template>
  <div class="not-found" role="main" aria-label="404 页面">
    <el-icon
      :size="64"
      color="var(--ds-text-muted, var(--ds-text-muted, var(--ds-text-secondary)))"
    >
      <WarningFilled />
    </el-icon>
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

/* 使用设计令牌（亮暗双主题适配：暗色下不硬编码深灰导致不可读） */
.not-found__code {
  font-size: 56px;
  font-weight: 700;
  margin: 0;
  color: var(--ds-text-primary, var(--ds-text-primary));
}

.not-found__text {
  color: var(--ds-text-secondary, var(--ds-text-muted, var(--ds-text-secondary)));
  margin: 0 0 8px;
}
</style>
