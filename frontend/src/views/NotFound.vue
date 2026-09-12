<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'

const router = useRouter()
const { t } = useI18n()

function goHome() {
  router.push('/dashboard')
}
</script>

<template>
  <div class="not-found" role="main" :aria-label="t('notFound.pageAria')">
    <el-icon
      :size="64"
      color="var(--ds-text-secondary)"
    >
      <WarningFilled />
    </el-icon>
    <h1 class="not-found__code">404</h1>
    <p class="not-found__text">{{ t('notFound.message') }}</p>
    <el-button type="primary" @click="goHome">{{ t('notFound.backHome') }}</el-button>
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
  font-size: var(--ds-font-size-6xl);
  font-weight: var(--ds-font-weight-bold);
  margin: 0;
  color: var(--ds-text-primary, var(--ds-text-primary));
}

.not-found__text {
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
</style>
