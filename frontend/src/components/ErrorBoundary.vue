<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'

interface ErrorInfo {
  message: string
  stack?: string
  component?: string
}

const error = ref<ErrorInfo | null>(null)

onErrorCaptured((err: Error, _instance, info) => {
  error.value = { message: err.message, stack: err.stack, component: info }
  console.error('[ErrorBoundary]', err, info)
  return false
})

function reset() {
  error.value = null
}
</script>

<template>
  <div v-if="error" class="error-boundary">
    <div class="error-boundary__icon">⚠️</div>
    <h2 class="error-boundary__title">页面渲染出错</h2>
    <p class="error-boundary__message">{{ error.message }}</p>
    <details class="error-boundary__details">
      <summary>错误详情</summary>
      <pre>{{ error.stack }}</pre>
      <pre>组件: {{ error.component }}</pre>
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
  font-size: 48px;
  margin-bottom: 16px;
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