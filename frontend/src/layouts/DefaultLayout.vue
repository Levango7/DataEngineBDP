<template>
  <!-- 公共页（登录等）不显示布局框架：无侧边栏/顶栏 -->
  <div v-if="isPublicRoute" class="public-page">
    <router-view />
  </div>
  <!-- 常规业务页：五区布局（左侧栏 | 顶栏+正文+状态栏 | 右侧面板） -->
  <div v-else class="app" :class="{ 'side-collapsed': ui.sidebarCollapsed }">
    <Sidebar />
    <!-- 移动端侧边栏抽屉遮罩层（≤640px，点击关闭抽屉） -->
    <div
      v-if="ui.sidebarOpen"
      class="side-overlay"
      aria-hidden="true"
      @click="ui.closeSidebarOpen"
    ></div>
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
      <StatusBar />
    </section>
    <RightPanel />
    <Toast />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import StatusBar from '@/components/StatusBar.vue'
import RightPanel from '@/components/RightPanel.vue'
import Toast from '@/components/Toast.vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

/** 公共页（登录等）：meta.public=true 时不显示布局框架 */
const isPublicRoute = computed(() => !!route.meta.public)

/** 平板断点 matchMedia 监听器（641–1440px 自动折叠侧边栏） */
let tabletMql: MediaQueryList | null = null
function onTabletChange(e: MediaQueryListEvent | MediaQueryList): void {
  ui.setSidebarAutoCollapsed(e.matches)
}

/** 移动端断点 matchMedia 监听器（≤640px 离开时关闭抽屉，避免桌面端残留状态） */
let mobileMql: MediaQueryList | null = null
function onMobileChange(e: MediaQueryListEvent | MediaQueryList): void {
  if (!e.matches && ui.sidebarOpen) {
    ui.closeSidebarOpen()
  }
}

onMounted(() => {
  // 平板断点：641px ≤ width ≤ 1440px 时自动折叠侧边栏
  tabletMql = window.matchMedia('(min-width: 641px) and (max-width: 1440px)')
  onTabletChange(tabletMql)
  if (typeof tabletMql.addEventListener === 'function') {
    tabletMql.addEventListener('change', onTabletChange)
  } else {
    // Safari < 14 兼容
    tabletMql.addListener(onTabletChange)
  }
  // 移动端断点：离开 ≤640px 时关闭抽屉
  mobileMql = window.matchMedia('(max-width: 640px)')
  if (typeof mobileMql.addEventListener === 'function') {
    mobileMql.addEventListener('change', onMobileChange)
  } else {
    mobileMql.addListener(onMobileChange)
  }
})

onUnmounted(() => {
  if (tabletMql) {
    if (typeof tabletMql.removeEventListener === 'function') {
      tabletMql.removeEventListener('change', onTabletChange)
    } else {
      tabletMql.removeListener(onTabletChange)
    }
    tabletMql = null
  }
  if (mobileMql) {
    if (typeof mobileMql.removeEventListener === 'function') {
      mobileMql.removeEventListener('change', onMobileChange)
    } else {
      mobileMql.removeListener(onMobileChange)
    }
    mobileMql = null
  }
})

// 路由变化时关闭移动端侧边栏抽屉
watch(
  () => route.path,
  () => {
    if (ui.sidebarOpen) {
      ui.closeSidebarOpen()
    }
  }
)
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
