<template>
  <aside
    class="side"
    :class="{ collapsed: ui.sidebarFolded, 'is-open': ui.sidebarOpen }"
    role="complementary"
    :aria-label="t('app.sidebar')"
  >
    <div class="brand" :aria-label="t('nav.brand')" :title="t('nav.brand')">
      <span class="dot" aria-hidden="true"></span>
      <span class="brand-text">{{ t('nav.brand') }}</span>
      <button
        class="side-collapse"
        :aria-label="ui.sidebarFolded ? t('app.expandSidebar') : t('app.collapseSidebar')"
        :title="
          ui.sidebarFolded
            ? `${t('app.expandSidebar')} (Ctrl+B)`
            : `${t('app.collapseSidebar')} (Ctrl+B)`
        "
        @click="ui.toggleSidebar"
      >
        <el-icon :size="14" aria-hidden="true">
          <component :is="iconOf(ui.sidebarFolded ? 'Expand' : 'Fold')" />
        </el-icon>
      </button>
    </div>
    <nav class="nav" role="navigation" :aria-label="t('app.mainNav')">
      <template v-for="(group, gi) in groups" :key="group.title">
        <div
          class="grp"
          role="button"
          :aria-expanded="isOpen(gi)"
          :aria-controls="`nav-group-${gi}`"
          :aria-label="`${group.title} (${group.items.length})`"
          :title="ui.sidebarFolded ? group.title : undefined"
          tabindex="0"
          @click="toggleGroup(gi)"
          @keyup.enter="toggleGroup(gi)"
        >
          <span class="grp-arrow" :class="{ open: isOpen(gi) }" aria-hidden="true">▸</span>
          <span class="grp-label">{{ group.title }}</span>
          <span class="grp-count" aria-hidden="true">{{ group.items.length }}</span>
        </div>
        <div
          :id="`nav-group-${gi}`"
          class="grp-items"
          :class="{ collapsed: !isOpen(gi) }"
          role="group"
          :aria-label="`${group.title}`"
        >
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            active-class="active"
            :aria-label="item.label"
            :title="ui.sidebarFolded ? item.label : undefined"
            @click="ui.closeSidebarOpen"
          >
            <el-icon class="nav-ic" :size="18" aria-hidden="true">
              <component :is="iconOf(item.icon)" />
            </el-icon>
            <span class="nav-label">{{ item.label }}</span>
            <span v-if="item.badge" class="badge">{{ item.badge }}</span>
          </router-link>
        </div>
      </template>
    </nav>
    <div class="side-foot" :aria-label="t('app.sidebarVersionInfo')">
      <div class="side-foot-text">
        {{ t('app.versionInfo', { version: appVersion }) }}
        <br />
        {{ t('app.envInfo', { env: appEnv }) }}
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import * as EPIcons from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useUiStore } from '@/stores/ui'
import { useNavGroups } from '@/composables/useNavGroups'
import { persistLocale, type SupportedLocale } from '@/i18n'

const { t, locale } = useI18n()
const store = useAppStore()
const ui = useUiStore()
const groups = useNavGroups()

declare const __APP_VERSION__: string

const appVersion = __APP_VERSION__ || 'dev'

const appEnv = __APP_ENV__ || 'dev'

/** EP 图标名 → 组件（@element-plus/icons-vue 全量导出表） */
const iconTable = EPIcons as unknown as Record<string, Component>

function iconOf(name: string): Component {
  return iconTable[name] ?? iconTable.Menu
}

/** 语言切换放到顶栏（TopBar.vue）后，此处保留切换函数但不再渲染选择框 */
function onLocaleChange(e: Event): void {
  const v = (e.target as HTMLSelectElement).value as SupportedLocale
  locale.value = v
  persistLocale(v)
}

// 分组展开/折叠状态：默认全部展开（未在 collapsed 中记录即展开）
const collapsed = ref<number[]>([])

function isOpen(idx: number): boolean {
  return !collapsed.value.includes(idx)
}

function toggleGroup(idx: number): void {
  if (collapsed.value.includes(idx)) {
    collapsed.value = collapsed.value.filter((i) => i !== idx)
  } else {
    collapsed.value = [...collapsed.value, idx]
  }
}
</script>

<style scoped>
/* === 分组标题：可点击 + 折叠箭头 === */
.nav .grp {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 12px 18px 4px;
  transition: color 0.2s var(--ease-smooth);
  white-space: nowrap;
}
.nav .grp:hover {
  color: var(--sidebar-ink);
}
.nav .grp:hover .grp-arrow,
.nav .grp:hover .grp-count {
  color: var(--ds-color-primary-500);
}
.grp-arrow {
  display: inline-block;
  font-size: 10px;
  line-height: 1;
  color: var(--sidebar-muted);
  transition:
    transform 0.25s var(--ease-smooth),
    color 0.2s var(--ease-smooth);
  transform: rotate(0deg);
}
.grp-arrow.open {
  transform: rotate(90deg);
}
.grp-label {
  flex: 1;
}
.grp-count {
  font-size: 10px;
  color: var(--sidebar-muted);
  background: var(--sidebar-hover-bg);
  border-radius: 8px;
  padding: 1px 6px;
  min-width: 16px;
  text-align: center;
  transition:
    color 0.2s var(--ease-smooth),
    background 0.2s var(--ease-smooth);
}

/* === 分组容器：平滑高度过渡 === */
.grp-items {
  overflow: hidden;
  max-height: 1200px;
  opacity: 1;
  transition:
    max-height 0.32s var(--ease-drawer),
    opacity 0.24s var(--ease-smooth);
}
.grp-items.collapsed {
  max-height: 0;
  opacity: 0;
}

/* === 菜单项流光 hover 效果（::after 横向流光，不遮文字） === */
.nav-item {
  position: relative;
  overflow: hidden;
}
.nav-item::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(99, 102, 241, 0.22) 50%,
    transparent 100%
  );
  background-size: 200% 100%;
  background-position: -100% 0;
  opacity: 0;
  transition: opacity 0.3s var(--ease-smooth);
  pointer-events: none;
  z-index: 0;
}
.nav-item:hover::after {
  opacity: 1;
  animation: flowLight 0.9s var(--ease-smooth);
}
/* 文字与图标置于流光之上 */
.nav-item > * {
  position: relative;
  z-index: 1;
}

/* === 激活态额外发光 === */
.nav-item.active {
  box-shadow: inset 0 0 12px rgba(99, 102, 241, 0.08);
}
</style>
