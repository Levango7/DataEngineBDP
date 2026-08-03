<template>
  <Teleport to="body">
    <div v-if="visible" class="overlay show" @click.self="close"></div>
    <div v-if="visible" class="drawer show">
      <div class="dh">
        <slot name="header"></slot>
        <span class="x" @click="close">×</span>
      </div>
      <div class="db">
        <slot></slot>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()
function close() {
  emit('close')
}
</script>