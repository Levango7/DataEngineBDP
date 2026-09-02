<template>
  <aside class="side" role="complementary" aria-label="平台侧边栏">
    <div class="brand" :aria-label="t('nav.brand')">
      <span class="dot" aria-hidden="true"></span>
      {{ t('nav.brand') }}
    </div>
    <nav class="nav" role="navigation" aria-label="主导航菜单">
      <template v-for="(group, gi) in groups" :key="group.title">
        <div
          class="grp"
          role="button"
          :aria-expanded="isOpen(gi)"
          :aria-controls="`nav-group-${gi}`"
          :aria-label="`${group.title} 分组，共 ${group.items.length} 项`"
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
          :aria-label="`${group.title} 导航项`"
        >
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            active-class="active"
            :aria-label="item.label"
          >
            <svg class="ic" aria-hidden="true"><use :href="`#i-${item.icon}`" /></svg>
            <span class="nav-label">{{ item.label }}</span>
            <span v-if="item.badge" class="badge" aria-label="待办数量">{{ item.badge }}</span>
          </router-link>
        </div>
      </template>
    </nav>
    <div class="side-foot" aria-label="平台版本信息">
      <select
        class="locale-switcher"
        :aria-label="t('app.localeLabel')"
        :title="t('app.localeLabel')"
        :value="locale"
        @change="onLocaleChange"
      >
        <option value="zh-CN">中文</option>
        <option value="en-US">EN</option>
      </select>
      <button
        class="theme-toggle"
        :aria-label="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
        :title="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
        @click="theme.toggle"
      >
        {{ theme.isDark ? '☀️' : '🌙' }}
      </button>
      <div class="side-foot-text">
        {{ t('app.versionInfo', { version: appVersion }) }}
        <br />
        {{ t('app.envInfo', { env: appEnv }) }}
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useThemeStore } from '@/stores/theme'
import { persistLocale, type SupportedLocale } from '@/i18n'

const { t, locale } = useI18n()
const store = useAppStore()
const theme = useThemeStore()

declare const __APP_VERSION__: string

const appVersion = __APP_VERSION__ || 'dev'

const appEnv = __APP_ENV__ || 'dev'

function onLocaleChange(e: Event): void {
  const v = (e.target as HTMLSelectElement).value as SupportedLocale
  locale.value = v
  persistLocale(v)
}

interface NavItem {
  path: string
  label: string
  icon: string
  badge?: number
}
interface NavGroup {
  title: string
  items: NavItem[]
}

// 导航配置（35 项，7 分组）—— label/title 通过 i18n key 渲染，
// 语言切换时 computed 自动重算（vue-i18n 响应式 t()）
const groups = computed<NavGroup[]>(() => [
  {
    title: t('nav.groups.infra'),
    items: [
      { path: '/infra-machine', label: t('nav.items.infra-machine'), icon: 'ws' },
      { path: '/infra-k8s', label: t('nav.items.infra-k8s'), icon: 'ops' },
      { path: '/cluster', label: t('nav.items.cluster'), icon: 'ops' },
      { path: '/datasources', label: t('nav.items.datasources'), icon: 'integrate' },
      { path: '/infra-net', label: t('nav.items.infra-net'), icon: 'integrate' },
      { path: '/infra-store', label: t('nav.items.infra-store'), icon: 'folder' },
      { path: '/infra-sched', label: t('nav.items.infra-sched'), icon: 'develop' }
    ]
  },
  {
    title: t('nav.groups.engine'),
    items: [
      { path: '/eng-storage', label: t('nav.items.eng-storage'), icon: 'folder' },
      { path: '/eng-spark', label: t('nav.items.eng-spark'), icon: 'develop' },
      { path: '/eng-flink', label: t('nav.items.eng-flink'), icon: 'develop' },
      { path: '/sql', label: t('nav.items.sql'), icon: 'sql' },
      { path: '/eng-doris', label: t('nav.items.eng-doris'), icon: 'analyze' },
      { path: '/eng-kafka', label: t('nav.items.eng-kafka'), icon: 'integrate' },
      { path: '/eng-iotdb', label: t('nav.items.eng-iotdb'), icon: 'ops' },
      { path: '/eng-mmg', label: t('nav.items.eng-mmg'), icon: 'vector' }
    ]
  },
  {
    title: t('nav.groups.governance'),
    items: [
      { path: '/govern-meta', label: t('nav.items.govern-meta'), icon: 'standard' },
      { path: '/quality', label: t('nav.items.quality'), icon: 'quality' },
      { path: '/lineage', label: t('nav.items.lineage'), icon: 'lineage' },
      { path: '/data-lineage', label: t('nav.items.data-lineage'), icon: 'lineage' },
      { path: '/govern', label: t('nav.items.govern'), icon: 'govern' },
      { path: '/standard', label: t('nav.items.standard'), icon: 'standard' },
      { path: '/sec', label: t('nav.items.sec'), icon: 'sec', badge: store.todoCount }
    ]
  },
  {
    title: t('nav.groups.devtools'),
    items: [
      { path: '/integrate', label: t('nav.items.integrate'), icon: 'integrate' },
      { path: '/dev-sched', label: t('nav.items.dev-sched'), icon: 'develop' },
      { path: '/scheduler-ops', label: t('nav.items.scheduler-ops'), icon: 'ops' },
      { path: '/jobs', label: t('nav.items.jobs'), icon: 'develop' },
      { path: '/develop', label: t('nav.items.develop'), icon: 'develop' },
      { path: '/sql-workbench', label: t('nav.items.sql-workbench'), icon: 'sql' },
      { path: '/analyze', label: t('nav.items.analyze'), icon: 'analyze' },
      { path: '/dev-tag', label: t('nav.items.dev-tag'), icon: 'vector' },
      { path: '/dev-ml', label: t('nav.items.dev-ml'), icon: 'llmops' }
    ]
  },
  {
    title: t('nav.groups.tenant'),
    items: [
      { path: '/tenants', label: t('nav.items.tenants'), icon: 'ws' },
      { path: '/workspaces', label: t('nav.items.workspaces'), icon: 'ws' },
      { path: '/workspace-management', label: t('nav.items.workspace-management'), icon: 'ws' },
      { path: '/quota-management', label: t('nav.items.quota-management'), icon: 'ops' },
      { path: '/projects', label: t('nav.items.projects'), icon: 'proj' },
      { path: '/account', label: t('nav.items.account'), icon: 'admin' }
    ]
  },
  {
    title: t('nav.groups.intelligent'),
    items: [
      { path: '/ai-assistant', label: t('nav.items.ai-assistant'), icon: 'llmops' },
      { path: '/vector', label: t('nav.items.vector'), icon: 'vector' },
      { path: '/kb', label: t('nav.items.kb'), icon: 'kb' },
      { path: '/llmops', label: t('nav.items.llmops'), icon: 'llmops' },
      { path: '/orchestrator/dag', label: t('nav.items.orchestrator-dag'), icon: 'lineage' },
      { path: '/gateway', label: t('nav.items.gateway'), icon: 'gateway' }
    ]
  },
  {
    title: t('nav.groups.operations'),
    items: [
      { path: '/dashboard', label: t('nav.items.dashboard'), icon: 'dash' },
      { path: '/ops', label: t('nav.items.ops'), icon: 'ops' },
      { path: '/search', label: t('nav.items.search'), icon: 'kb' },
      { path: '/admin', label: t('nav.items.admin'), icon: 'admin' },
      { path: '/ops-tpl', label: t('nav.items.ops-tpl'), icon: 'proj' },
      { path: '/ops-portal', label: t('nav.items.ops-portal'), icon: 'ws' },
      { path: '/ops-api', label: t('nav.items.ops-api'), icon: 'gateway' },
      { path: '/ops-flow', label: t('nav.items.ops-flow'), icon: 'govern' }
    ]
  }
])

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
}
.nav .grp:hover {
  color: var(--sidebar-ink);
}
.nav .grp:hover .grp-arrow,
.nav .grp:hover .grp-count {
  color: var(--primary);
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
