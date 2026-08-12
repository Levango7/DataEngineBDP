<template>
  <Icons />
  <ErrorBoundary>
    <DefaultLayout />
  </ErrorBoundary>
</template>

<script setup lang="ts">
import Icons from '@/components/Icons.vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'
</script>
