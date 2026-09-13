<template>
  <div class="empty-state">
    <component :is="icon" v-if="icon" class="empty-state__icon" />
    <p class="empty-state__message">{{ message || t('common.empty') }}</p>
    <el-button v-if="actionLabel" type="primary" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps<{
  message?: string
  icon?: Component
  actionLabel?: string
}>()

defineEmits<{
  action: []
}>()
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--ds-spacing-12) var(--ds-spacing-4);
  text-align: center;
}

.empty-state__icon {
  width: 48px;
  height: 48px;
  margin-bottom: var(--ds-spacing-3);
  color: var(--ds-text-tertiary);
}

.empty-state__message {
  margin: 0 0 16px;
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-secondary);
}
</style>
