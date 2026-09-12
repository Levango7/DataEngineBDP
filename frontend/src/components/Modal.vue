<template>
  <Teleport to="body">
    <Transition name="ds-modal-fade">
      <div v-if="visible" class="overlay show" @click.self="close"></div>
    </Transition>
    <Transition name="ds-modal-zoom">
      <div
        v-if="visible"
        :id="modalId"
        :ref="trapRef"
        class="modal show"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
      >
        <div class="mh">
          <span :id="titleId">{{ title }}</span>
          <el-button text :icon="Close" class="x" :aria-label="t('common.close')" @click="close" />
        </div>
        <div class="mb">
          <slot></slot>
        </div>
        <div class="mf">
          <slot name="footer">
            <el-button @click="close">{{ t('common.cancel') }}</el-button>
          </slot>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { watch, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Close } from '@element-plus/icons-vue'
import { useFocusTrap } from '@/composables/useFocusTrap'

const props = defineProps<{ visible: boolean; title: string }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()

// 生成唯一 id 用于 aria-labelledby
const modalId = `modal-${useId()}`
const titleId = `modal-title-${useId()}`

function close() {
  emit('close')
}

// 焦点陷阱：ESC 关闭 + Tab 循环 + 滚动锁定 + 焦点恢复
const { trapRef, activate, deactivate } = useFocusTrap({ onEscape: close })

// visible 变化时激活/解除焦点陷阱
watch(
  () => props.visible,
  (v) => {
    if (v) activate()
    else deactivate()
  },
  { immediate: true }
)
</script>
