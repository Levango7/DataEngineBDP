<template>
  <Teleport to="body">
    <div v-if="visible" class="overlay show" @click.self="close"></div>
    <div v-if="visible" class="modal show">
      <div class="mh">
        <span>{{ title }}</span>
        <span class="x" @click="close">×</span>
      </div>
      <div class="mb">
        <slot></slot>
      </div>
      <div class="mf">
        <slot name="footer">
          <button class="btn ghost" @click="close">取消</button>
        </slot>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean; title: string }>()
const emit = defineEmits<{ (e: 'close'): void }>()
function close() {
  emit('close')
}
</script>
