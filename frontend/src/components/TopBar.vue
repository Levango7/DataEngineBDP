<template>
  <div class="topbar" role="banner" aria-label="Top Bar">
    <!-- 移动端汉堡菜单（≤640px 显示，打开侧边栏抽屉） -->
    <button
      class="tb-icon-btn hamburger"
      :class="{ on: ui.sidebarOpen }"
      :aria-label="ui.sidebarOpen ? t('app.closeMenu') : t('app.openMenu')"
      :title="ui.sidebarOpen ? t('app.closeMenu') : t('app.openMenu')"
      :aria-expanded="ui.sidebarOpen"
      @click="ui.toggleSidebarOpen"
    >
      <el-icon class="tb-ic"><Fold v-if="ui.sidebarOpen" /><Expand v-else /></el-icon>
    </button>

    <!-- 工作空间切换 -->
    <div
      class="ws-switch"
      role="button"
      aria-haspopup="true"
      :aria-expanded="wsMenuOpen"
      :aria-label="t('app.workspaceSwitch')"
      tabindex="0"
      @click="toggleWsMenu"
      @keyup.enter="toggleWsMenu"
    >
      <el-icon class="ws-ic"><Folder /></el-icon>
      {{ store.workspace }}
      <span class="ws-arrow" aria-hidden="true">▾</span>
      <div
        v-if="wsMenuOpen"
        class="ws-menu"
        role="menu"
        :aria-label="t('app.workspaceList')"
        @click.stop
      >
        <div
          v-for="ws in wsList"
          :key="ws"
          class="ws-item"
          role="menuitem"
          :class="{ on: ws === store.workspace }"
          :aria-current="ws === store.workspace ? 'true' : undefined"
          tabindex="0"
          @click="chooseWs(ws)"
          @keyup.enter="chooseWs(ws)"
        >
          {{ ws }}
        </div>
      </div>
    </div>

    <!-- 面包屑 -->
    <nav v-if="crumb" class="crumb" :aria-label="t('app.breadcrumbNav')">
      <span class="crumb-sep" aria-hidden="true">/</span>
      <span class="crumb-group">{{ crumb.group }}</span>
      <span class="crumb-sep" aria-hidden="true">/</span>
      <span class="crumb-label">{{ crumb.label }}</span>
    </nav>

    <div class="spacer"></div>

    <!-- 全局搜索（数据平台质感） -->
    <div class="global-search" role="search">
      <el-icon class="gs-ic"><Search /></el-icon>
      <input
        v-model="searchKw"
        class="gs-input"
        type="search"
        :placeholder="t('app.globalSearchPlaceholder')"
        :aria-label="t('app.globalSearchPlaceholder')"
        @focus="searchFocus = true"
        @blur="searchFocus = false"
      />
      <kbd class="gs-kbd" :class="{ dim: searchFocus }">⌘K</kbd>
    </div>

    <div class="spacer"></div>

    <span class="env-tag" :aria-label="t('app.envBadge')">● {{ store.envTag }}</span>

    <!-- 通知铃铛（开合右侧信息面板） -->
    <button
      class="tb-icon-btn bell"
      :class="{ on: ui.rightPanelOpen }"
      :aria-label="ui.rightPanelOpen ? t('app.closePanel') : t('app.notificationPanel')"
      :title="ui.rightPanelOpen ? t('app.closePanel') : t('app.notificationPanel')"
      @click="ui.toggleRightPanel"
    >
      <el-icon class="tb-ic"><Bell /></el-icon>
      <span v-if="noticeCount > 0" class="bell-dot" aria-hidden="true">{{ noticeCount }}</span>
    </button>

    <!-- 语言切换（中文态显 EN / 英文态显 中） -->
    <button
      class="tb-locale"
      :aria-label="t('app.localeLabel')"
      :title="t('app.localeLabel')"
      @click="toggleLocale"
    >
      {{ locale === 'zh-CN' ? 'EN' : '中' }}
    </button>

    <!-- 昼夜模式切换（贴近头像，全屏即时生效操作放最右避免误触） -->
    <button
      class="tb-icon-btn"
      :aria-label="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
      :title="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
      @click="theme.toggle"
    >
      <el-icon class="tb-ic" aria-hidden="true">
        <Sunny v-if="theme.isDark" />
        <Moon v-else />
      </el-icon>
    </button>

    <!-- 用户菜单（登录用户 + 退出登录） -->
    <div class="user-menu" @click.stop>
      <div
        class="avatar"
        role="button"
        aria-haspopup="true"
        :aria-expanded="userMenuOpen"
        :aria-label="
          auth.user ? t('app.userMenu', { name: auth.user.username }) : t('app.userMenuAnonymous')
        "
        tabindex="0"
        @click="toggleUserMenu"
        @keyup.enter="toggleUserMenu"
      >
        {{ avatarText }}
      </div>
      <div v-if="userMenuOpen" class="user-pop" role="menu" :aria-label="t('app.userActions')">
        <div class="user-info" :aria-label="t('app.userInfo')">
          <div class="user-name">{{ auth.user?.username || t('app.notLoggedIn') }}</div>
          <div class="user-email">{{ auth.user?.email || '—' }}</div>
        </div>
        <div class="user-actions" role="group" :aria-label="t('app.userActions')">
          <router-link
            to="/account"
            class="user-action"
            role="menuitem"
            @click="userMenuOpen = false"
          >
            {{ t('nav.items.account') }}
          </router-link>
          <button
            class="user-action"
            role="menuitem"
            :aria-label="t('app.logout')"
            @click="handleLogout"
          >
            {{ t('app.logout') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Folder, Search, Bell, Sunny, Moon, Expand, Fold } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useUiStore } from '@/stores/ui'
import { persistLocale, type SupportedLocale } from '@/i18n'
import { useBreadcrumb } from '@/composables/useNavGroups'
import * as tenantApi from '@/api/tenant'

const { t, locale } = useI18n()
const store = useAppStore()
const auth = useAuthStore()
const theme = useThemeStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const crumbOf = useBreadcrumb()

const wsMenuOpen = ref(false)
const userMenuOpen = ref(false)
const searchKw = ref('')
const searchFocus = ref(false)
// 工作空间列表：从 API 加载租户名称，失败时 fallback 到 i18n 默认值
const wsList = ref<string[]>([])

/** 从 API 加载工作空间列表（租户名称） */
async function loadWsList() {
  try {
    const tenants = await tenantApi.listAllTenants()
    wsList.value = tenants.map((t) => t.name)
    if (wsList.value.length === 0) wsList.value = [t('app.defaultWorkspace')]
  } catch {
    wsList.value = [t('app.defaultWorkspace')]
  }
}

const crumb = computed(() => crumbOf(route.path))

/** 通知数（预留：接通知中心后替换为真实未读数） */
const noticeCount = ref(2)

const avatarText = computed(() => {
  const name = auth.user?.username
  return name ? name.charAt(0).toUpperCase() : t('app.tenantInitial')
})

/** Ctrl+B 切换侧边栏（与主流 IDE/B/S 应用一致） */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'b' || e.key === 'B')) {
    e.preventDefault()
    ui.toggleSidebar()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 加载工作空间列表和租户信息（替代原硬编码业务参数）
  void loadWsList()
  void store.fetchTenantInfo()
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

function toggleWsMenu() {
  wsMenuOpen.value = !wsMenuOpen.value
}

function chooseWs(ws: string) {
  store.setWorkspace(ws)
  wsMenuOpen.value = false
}

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
}

/** 顶栏语言切换：中文态显 EN / 英文态显 中（点击即切换并持久化） */
function toggleLocale() {
  const current = locale.value
  const next: SupportedLocale = current === 'zh-CN' ? 'en-US' : 'zh-CN'
  locale.value = next
  persistLocale(next)
}

function handleLogout() {
  auth.logout()
  userMenuOpen.value = false
  router.replace('/login')
}
</script>

<style scoped>
/* === 工作空间切换 === */
.ws-switch {
  position: relative;
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--gradient-primary-soft);
  color: var(--ds-color-primary-500);
  padding: 6px 12px;
  border-radius: var(--ds-radius-md);
  font-weight: var(--ds-font-weight-semibold);
  font-size: var(--ds-font-size-sm);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  transition:
    transform var(--ds-transition-quick),
    box-shadow var(--ds-transition-quick);
}
.ws-switch:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.2);
}
.ws-ic {
  width: 14px;
  height: 14px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.ws-arrow {
  font-size: var(--ds-font-size-xs);
  opacity: var(--ds-opacity-7);
  transition: transform var(--ds-transition-quick);
}
.ws-switch[aria-expanded='true'] .ws-arrow {
  transform: rotate(180deg);
}

/* === 图标按钮（汉堡/铃铛通用） === */
.tb-icon-btn {
  width: 32px;
  height: 32px;
  flex: none;
  border: 1px solid transparent;
  border-radius: var(--ds-radius-md);
  background: transparent;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--ds-text-secondary);
  position: relative;
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast),
    border-color var(--ds-transition-fast),
    transform var(--ds-transition-fast);
}
.tb-icon-btn:hover {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
  border-color: rgba(59, 130, 246, 0.35);
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(59, 130, 246, 0.18);
}
.tb-icon-btn.on {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
  border-color: rgba(59, 130, 246, 0.35);
}
.tb-ic {
  width: 17px;
  height: 17px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 铃铛红点 */
.bell-dot {
  position: absolute;
  top: 3px;
  right: 3px;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: var(--ds-radius-md);
  background: var(--ds-color-error-500);
  color: var(--ds-text-inverse);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-extrabold);
  line-height: var(--ds-line-height-none);
  text-align: center;
  box-shadow: 0 0 0 2px var(--ds-bg-surface);
  animation: bellPulse var(--ds-animation-pulse) var(--ease-smooth) infinite;
}

/* 顶栏语言切换（中文态显 EN / 英文态显 中，醒目胶囊） */
.tb-locale {
  height: 28px;
  min-width: 32px;
  padding: 0 9px;
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-surface);
  color: var(--ds-color-primary-700);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-extrabold);
  letter-spacing: 0.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast),
    border-color var(--ds-transition-fast),
    box-shadow var(--ds-transition-fast),
    transform var(--ds-transition-fast);
}
.tb-locale:hover {
  background: var(--ds-color-primary-50);
  border-color: var(--ds-color-primary-500);
  color: var(--ds-color-primary-700);
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.22);
  transform: translateY(-1px);
}
.tb-locale:active {
  transform: translateY(0);
}

/* === 面包屑 === */
.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--ds-font-size-sm);
  min-width: 0;
  white-space: nowrap;
}
.crumb-group {
  color: var(--ds-text-tertiary);
  font-weight: var(--ds-font-weight-medium);
}
.crumb-sep {
  color: var(--ds-border-default);
}
.crumb-label {
  color: var(--ds-text-primary);
  font-weight: var(--ds-font-weight-extrabold);
}

/* === 全局搜索 === */
.global-search {
  position: relative;
  display: flex;
  align-items: center;
  width: min(340px, 32vw);
}
.gs-ic {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  stroke: var(--ds-color-gray-400);
  fill: none;
  stroke-width: 1.8;
  stroke-linecap: round;
  pointer-events: none;
}
.gs-input {
  width: 100%;
  padding: 7px 44px 7px 30px;
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md-plus);
  background: var(--ds-bg-surface);
  font-size: var(--ds-font-size-sm);
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.04);
  transition:
    border-color var(--ds-transition-quick),
    box-shadow var(--ds-transition-quick),
    background var(--ds-transition-quick);
}
.gs-input:hover {
  border-color: var(--ds-color-gray-400);
}
.gs-input:focus {
  outline: none;
  border-color: var(--ds-color-primary-500);
  background: var(--ds-bg-surface);
  box-shadow:
    0 0 0 3px rgba(99, 102, 241, 0.15),
    0 2px 8px rgba(59, 130, 246, 0.12);
}
.gs-kbd {
  position: absolute;
  right: 8px;
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  color: var(--ds-color-gray-400);
  background: var(--ds-bg-base);
  border: 1px solid var(--ds-border-subtle);
  border-bottom-width: 2px;
  border-radius: var(--ds-radius-sm);
  padding: 1px 5px;
  pointer-events: none;
  transition: opacity var(--ds-transition-quick);
}
.gs-kbd.dim {
  opacity: var(--ds-opacity-0);
}

/* 铃铛呼吸 */
@keyframes bellPulse {
  0%,
  100% {
    box-shadow: 0 0 0 2px var(--ds-bg-surface);
  }
  50% {
    box-shadow:
      0 0 0 2px var(--ds-bg-surface),
      0 0 10px rgba(239, 68, 68, 0.6);
  }
}

/* === 工作空间下拉菜单：毛玻璃 + 弹簧入场 === */
.ws-menu {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 6px;
  background: var(--glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--glass-border);
  border-radius: var(--ds-radius-md-plus);
  box-shadow:
    0 8px 24px rgba(15, 23, 42, 0.18),
    var(--shadow-glow);
  min-width: 180px;
  z-index: 30;
  overflow: hidden;
  animation: springIn var(--ds-transition-spring);
  transform-origin: top left;
}
.ws-item {
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  font-size: var(--ds-font-size-sm);
  font-weight: var(--ds-font-weight-medium);
  color: var(--ds-text-primary);
  cursor: pointer;
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast);
}
.ws-item:hover {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
}
.ws-item.on {
  color: var(--ds-color-primary-500);
  background: var(--ds-color-primary-50);
  font-weight: var(--ds-font-weight-semibold);
}

/* === 用户菜单 === */
.user-menu {
  position: relative;
}
.user-pop {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 6px;
  background: var(--glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--glass-border);
  border-radius: var(--ds-radius-md-plus);
  box-shadow:
    0 8px 24px rgba(15, 23, 42, 0.18),
    var(--shadow-glow);
  min-width: 200px;
  z-index: 30;
  overflow: hidden;
  animation: springIn var(--ds-transition-spring);
  transform-origin: top right;
}
.user-info {
  padding: var(--ds-spacing-3);
  border-bottom: 1px solid var(--ds-border-subtle);
}
.user-name {
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-primary);
}
.user-email {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-top: 2px;
}
.user-actions {
  padding: 4px 0;
}
.user-action {
  display: block;
  width: 100%;
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
  text-decoration: none;
  background: none;
  border: none;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast);
}
.user-action:hover {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--gradient-primary);
  color: var(--ds-text-inverse);
  font-size: var(--ds-font-size-sm);
  font-weight: var(--ds-font-weight-semibold);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  user-select: none;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.4);
  transition:
    transform var(--ds-transition-quick),
    box-shadow var(--ds-transition-quick);
}
.avatar:hover {
  transform: scale(1.06);
  box-shadow:
    0 4px 14px rgba(99, 102, 241, 0.55),
    var(--shadow-glow);
}

/* === 汉堡菜单：仅移动端 ≤640px 显示 === */
.hamburger {
  display: none;
}
@media (max-width: 640px) {
  .hamburger {
    display: inline-flex;
  }
}
</style>
