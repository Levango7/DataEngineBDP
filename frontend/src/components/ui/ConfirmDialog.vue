<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="480px"
    :close-on-click-modal="false"
    role="alertdialog"
    aria-modal="true"
    @update:model-value="$emit('update:visible', $event)"
    @closed="$emit('cancel')"
  >
    <p class="confirm-dialog__message">{{ message }}</p>
    <template #footer>
      <el-button @click="onCancel">{{ cancelText || 'Cancel' }}</el-button>
      <el-button
        :type="confirmType"
        :loading="loading"
        @click="onConfirm"
      >
        {{ confirmText || 'Confirm' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    visible: boolean
    title: string
    message: string
    confirmText?: string
    cancelText?: string
    type?: 'warning' | 'danger' | 'info'
    loading?: boolean
  }>(),
  {
    type: 'warning',
    loading: false
  }
)

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: []
  cancel: []
}>()

const confirmTypeMap: Record<string, 'warning' | 'danger' | 'primary'> = {
  warning: 'warning',
  danger: 'danger',
  info: 'primary'
}

const confirmType = computed(() => confirmTypeMap[props.type] ?? 'warning')

function onConfirm() {
  emit('confirm')
}

function onCancel() {
  emit('update:visible', false)
  emit('cancel')
}
</script>

<style scoped>
.confirm-dialog__message {
  margin: 0;
  font-size: 14px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
</style>
