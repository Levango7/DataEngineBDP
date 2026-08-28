<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'

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
      <el-icon :size="40" color="#e74c3c"><WarningFilled /></el-icon>
    </div>
    <h2 class="error-boundary__title">页面渲染出错</h2>
    <p class="error-boundary__message">{{ error.message }}</p>
    <details class="error-boundary__details">
      <summary>错误详情</summary>
      <pre v-text="error.stack"></pre>
      <pre v-text="'组件: ' + error.component"></pre>
    </details>
    <button class="error-boundary__retry" @click="reset">重试</button>
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
  font-size: 20px;
  font-weight: 600;
  color: #e74c3c;
  margin: 0 0 8px;
}

.error-boundary__message {
  font-size: 14px;
  color: #666;
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
  font-size: 12px;
  color: #999;
  overflow-x: auto;
  background: #f5f5f5;
  padding: 12px;
  border-radius: 4px;
}

.error-boundary__retry {
  padding: 8px 24px;
  background: #409eff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.error-boundary__retry:hover {
  background: #66b1ff;
}
</style>
