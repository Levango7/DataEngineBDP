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
    <!-- v3：AI 助手全局快捷入口。此前它只是侧栏「智能数据」组里的一个链接，
         没有跨页面的可达入口；已经在该页时自动隐藏，避免"跳到当前页"的死按钮。 -->
    <router-link
      v-if="!isAiAssistant"
      class="ai-fab"
      to="/ai-assistant"
      :aria-label="t('app.aiFab')"
      :title="t('app.aiFab')"
    >
      <el-icon :size="18" aria-hidden="true">
        <MagicStick />
      </el-icon>
      <span class="ai-fab-label">{{ t('app.aiFabShort') }}</span>
    </router-link>
    <Toast />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { MagicStick } from '@element-plus/icons-vue'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import StatusBar from '@/components/StatusBar.vue'
import RightPanel from '@/components/RightPanel.vue'
import Toast from '@/components/Toast.vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'
import { useUiStore } from '@/stores/ui'

const { t } = useI18n()
const route = useRoute()
const ui = useUiStore()

/** 公共页（登录等）：meta.public=true 时不显示布局框架 */
const isPublicRoute = computed(() => !!route.meta.public)
/** 已在 AI 助手页时不显示快捷入口 */
const isAiAssistant = computed(() => route.path === '/ai-assistant')

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
  tabletMql.addEventListener('change', onTabletChange)
  // 移动端断点：离开 ≤640px 时关闭抽屉
  mobileMql = window.matchMedia('(max-width: 640px)')
  mobileMql.addEventListener('change', onMobileChange)
})

onUnmounted(() => {
  if (tabletMql) {
    tabletMql.removeEventListener('change', onTabletChange)
    tabletMql = null
  }
  if (mobileMql) {
    mobileMql.removeEventListener('change', onMobileChange)
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
/* 路由切换过渡：淡入滑动入场 + 淡出离场（非 scoped 以确保 transition 类名作用于子组件根元素）
 * 入场/离场时长对称统一为 normal (250ms)，保证过渡节奏一致可预测 */
.page-enter-active {
  animation: fadeInSlide var(--ds-transition-normal);
}
.page-leave-active {
  animation: fadeInSlide var(--ds-transition-normal) reverse;
}

/* ---------- v3：AI 助手快捷入口（右下角固定） ----------
 * 实心底 + 白字（--ds-accent / --ds-accent-on 是设计契约里已核算的 4.5:1 配对），
 * 不用玻璃——浮在内容上的小控件一旦半透明，背后的表格/图表会渗进文字。
 * bottom 让开状态栏，right 让开右侧通知面板。 */
.ai-fab {
  position: fixed;
  right: 24px;
  bottom: 56px;
  z-index: 40;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border-radius: 999px;
  background: var(--ds-accent);
  color: var(--ds-accent-on);
  font-size: var(--ds-font-size-sm);
  font-weight: var(--ds-font-weight-semibold);
  text-decoration: none;
  box-shadow: var(--ds-shadow-md);
  transition:
    background var(--ds-transition-quick),
    transform var(--ds-transition-quick);
}
.ai-fab:hover {
  background: var(--ds-accent-hover);
  transform: translateY(-1px);
}
.ai-fab:active {
  background: var(--ds-accent-active);
  transform: translateY(0);
}
.ai-fab:focus-visible {
  outline: 2px solid var(--ds-border-focus);
  outline-offset: 2px;
}
@media (max-width: 640px) {
  .ai-fab-label {
    display: none;
  }
  .ai-fab {
    padding: 10px;
  }
}
</style>
