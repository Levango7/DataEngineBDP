<template>
  <div class="app">
    <Sidebar />
    <section class="main">
      <TopBar />
      <div class="view">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </div>
    </section>
    <Toast />
  </div>
</template>

<script setup lang="ts">
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import Toast from '@/components/Toast.vue'
</script>