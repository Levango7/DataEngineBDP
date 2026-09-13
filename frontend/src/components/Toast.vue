<template>
  <div class="toasts" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="ds-toast">
      <div
        v-for="toast in store.toasts"
        :key="toast.id"
        class="toast"
        :class="`toast--${toast.type || 'info'}`"
        role="status"
      >
        <el-icon class="toast__icon"><component :is="iconOf(toast.type)" /></el-icon>
        <span class="toast__msg">{{ toast.msg }}</span>
        <el-button
          text
          size="small"
          :icon="Close"
          class="toast__close"
          :aria-label="t('common.close')"
          @click="store.dismissToast(toast.id)"
        />
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import {
  Close,
  CircleCheckFilled,
  CircleCloseFilled,
  WarningFilled,
  InfoFilled
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'

const { t } = useI18n()
const store = useAppStore()

/** 根据 toast type 返回对应图标组件 */
function iconOf(type?: string): Component {
  switch (type) {
    case 'success':
      return CircleCheckFilled
    case 'error':
      return CircleCloseFilled
    case 'warning':
      return WarningFilled
    case 'info':
    default:
      return InfoFilled
  }
}
</script>

<style scoped>
/* type 变体颜色：使用 design tokens */
.toast--success {
  border-left-color: var(--ds-color-success-500);
}
.toast--success .toast__icon {
  color: var(--ds-color-success-500);
}
.toast--error {
  border-left-color: var(--ds-color-error-500);
}
.toast--error .toast__icon {
  color: var(--ds-color-error-500);
}
.toast--warning {
  border-left-color: var(--ds-color-warning-500);
}
.toast--warning .toast__icon {
  color: var(--ds-color-warning-500);
}
.toast--info {
  border-left-color: var(--ds-color-primary-500);
}
.toast--info .toast__icon {
  color: var(--ds-color-primary-500);
}

/* 图标与消息布局 */
.toast__icon {
  margin-right: 6px;
  vertical-align: middle;
}
.toast__msg {
  vertical-align: middle;
}
.toast__close {
  margin-left: var(--ds-spacing-2);
  color: rgba(255, 255, 255, 0.7);
}
.toast__close:hover {
  color: var(--ds-text-inverse);
}

/* TransitionGroup 进入动画 */
.ds-toast-enter-active {
  transition:
    transform var(--ds-transition-moderate),
    opacity var(--ds-transition-moderate);
}
.ds-toast-leave-active {
  transition:
    transform var(--ds-transition-normal),
    opacity var(--ds-transition-normal);
}
.ds-toast-enter-from {
  transform: translateY(20px) scale(0.9);
  opacity: var(--ds-opacity-0);
}
.ds-toast-leave-to {
  transform: translateY(-10px) scale(0.95);
  opacity: var(--ds-opacity-0);
}
.ds-toast-move {
  transition: transform var(--ds-transition-moderate);
}
</style>
