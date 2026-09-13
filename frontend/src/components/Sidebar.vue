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

const { t } = useI18n()
const store = useAppStore()
const ui = useUiStore()
const groups = useNavGroups()

declare const __APP_VERSION__: string

const appVersion = __APP_VERSION__ || 'dev'

const appEnv = __APP_ENV__ || 'dev'

/** EP 图标名 → 组件（@element-plus/icons-vue 全量导出表） */
// 双重断言安全说明：EPIcons 来自 @element-plus/icons-vue 的全量导出，
// 其运行时结构为 { [iconName: string]: Component }，但库类型声明为具名导出联合；
// 经 unknown 中转后断言为 Record<string, Component> 以支持动态按名查找图标。
const iconTable = EPIcons as unknown as Record<string, Component>

function iconOf(name: string): Component {
  return iconTable[name] ?? iconTable.Menu
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
  transition: color var(--ds-transition-quick);
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
  font-size: var(--ds-font-size-xs);
  line-height: var(--ds-line-height-none);
  color: var(--sidebar-muted);
  transition:
    transform var(--ds-transition-normal),
    color var(--ds-transition-quick);
  transform: rotate(0deg);
}
.grp-arrow.open {
  transform: rotate(90deg);
}
.grp-label {
  flex: 1;
}
.grp-count {
  font-size: var(--ds-font-size-xs);
  color: var(--sidebar-muted);
  background: var(--sidebar-hover-bg);
  border-radius: var(--ds-radius-md);
  padding: 1px 6px;
  min-width: 16px;
  text-align: center;
  transition:
    color var(--ds-transition-quick),
    background var(--ds-transition-quick);
}

/* === 分组容器：平滑高度过渡 === */
.grp-items {
  overflow: hidden;
  max-height: 1200px;
  opacity: var(--ds-opacity-10);
  transition:
    max-height var(--ds-transition-moderate),
    opacity var(--ds-transition-normal);
}
.grp-items.collapsed {
  max-height: 0;
  opacity: var(--ds-opacity-0);
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
  opacity: var(--ds-opacity-0);
  transition: opacity var(--ds-transition-moderate);
  pointer-events: none;
  z-index: 0;
}
.nav-item:hover::after {
  opacity: var(--ds-opacity-10);
  animation: flowLight var(--ds-animation-flow-light) var(--ease-smooth);
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
