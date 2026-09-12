<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'
import { useI18n } from 'vue-i18n'
import { WarningFilled } from '@element-plus/icons-vue'

const { t } = useI18n()

interface ErrorInfo {
  message: string
  stack?: string
  component?: string
}

const error = ref<ErrorInfo | null>(null)

onErrorCaptured((err: Error, _instance, info) => {
  error.value = { message: err.message, stack: err.stack, component: info }
  // 生产环境不打印 stack trace，仅记录错误消息
  if (import.meta.env.DEV) {
    console.error('[ErrorBoundary]', err, info)
  } else {
    console.warn('[ErrorBoundary]', err.message, '| component:', info)
  }
  return false
})

function reset() {
  error.value = null
}
</script>

<template>
  <div v-if="error" class="error-boundary">
    <div class="error-boundary__icon">
      <el-icon :size="40" color="var(--ds-color-error-500)"><WarningFilled /></el-icon>
    </div>
    <h2 class="error-boundary__title">{{ t('app.renderErrorTitle') }}</h2>
    <p class="error-boundary__message">{{ error.message }}</p>
    <details class="error-boundary__details">
      <summary>{{ t('app.errorDetails') }}</summary>
      <pre v-text="error.stack"></pre>
      <pre v-text="t('app.errorComponent') + ': ' + error.component"></pre>
    </details>
    <button class="error-boundary__retry" @click="reset">{{ t('common.retry') }}</button>
  </div>
  <slot v-else />
</template>

<style scoped>
.error-boundary {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: 32px;
  text-align: center;
}

.error-boundary__icon {
  margin-bottom: 16px;
  display: flex;
  justify-content: center;
}

.error-boundary__title {
  font-size: var(--ds-font-size-2xl);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-color-error-500);
  margin: 0 0 8px;
}

.error-boundary__message {
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-tertiary);
  margin: 0 0 16px;
  max-width: 600px;
  word-break: break-word;
}

.error-boundary__details {
  margin: 16px 0;
  max-width: 800px;
  width: 100%;
  text-align: left;
}

.error-boundary__details pre {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  overflow-x: auto;
  background: var(--ds-bg-subtle);
  padding: 12px;
  border-radius: var(--ds-radius-sm);
}

.error-boundary__retry {
  padding: 8px 24px;
  background: var(--ds-color-primary-500);
  color: var(--ds-text-inverse);
  border: none;
  border-radius: var(--ds-radius-sm);
  cursor: pointer;
  font-size: var(--ds-font-size-base);
}

.error-boundary__retry:hover {
  background: var(--ds-color-primary-400);
}
</style>
