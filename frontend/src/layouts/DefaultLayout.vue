<template>
  <!-- 公共页（登录等）不显示布局框架：无侧边栏/顶栏 -->
  <div v-if="isPublicRoute" class="public-page">
    <router-view />
  </div>
  <!-- 常规业务页：完整布局 -->
  <div v-else class="app">
    <Sidebar />
    <section class="main">
      <TopBar />
      <div class="view">
        <ErrorBoundary>
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </ErrorBoundary>
      </div>
    </section>
    <Toast />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import Toast from '@/components/Toast.vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'

const route = useRoute()

/** 公共页（登录等）：meta.public=true 时不显示布局框架 */
const isPublicRoute = computed(() => !!route.meta.public)
</script>

<style>
/* 路由切换过渡：淡入滑动入场 + 淡出离场（非 scoped 以确保 transition 类名作用于子组件根元素） */
.page-enter-active {
  animation: fadeInSlide 0.25s var(--ease-smooth);
}
.page-leave-active {
  animation: fadeInSlide 0.15s var(--ease-smooth) reverse;
}
</style>
