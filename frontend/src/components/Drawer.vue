<template>
  <Teleport to="body">
    <Transition name="ds-drawer-fade">
      <div v-if="visible" class="overlay show" @click.self="close"></div>
    </Transition>
    <Transition name="ds-drawer-slide">
      <div
        v-if="visible"
        :id="drawerId"
        :ref="setTrapRef"
        class="drawer show"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
      >
        <div class="dh">
          <slot name="header"></slot>
          <el-button text :icon="Close" class="x" :aria-label="t('common.close')" @click="close" />
        </div>
        <div class="db">
          <slot></slot>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { watch, useId } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useFocusTrap } from '@/composables/useFocusTrap'

const { t } = useI18n()

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

// 生成唯一 id
const drawerId = `drawer-${useId()}`

function close() {
  emit('close')
}

// 焦点陷阱：ESC 关闭 + Tab 循环 + 滚动锁定 + 焦点恢复
const { setTrapRef, activate, deactivate } = useFocusTrap({ onEscape: close })

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
