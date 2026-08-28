<template>
  <div class="toasts">
    <div v-for="t in store.toasts" :key="t.id" class="toast">{{ t.msg }}</div>
  </div>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app'
const store = useAppStore()
</script>
