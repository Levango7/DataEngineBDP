<template>
  <transition name="panel-slide">
    <aside v-if="ui.rightPanelOpen" class="rpanel" :aria-label="t('app.notificationPanel')">
      <div class="rp-head">
        <div class="rp-tabs" role="tablist" :aria-label="t('app.panelTabs')">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="rp-tab"
            role="tab"
            :class="{ on: activeTab === tab.key }"
            :aria-selected="activeTab === tab.key ? 'true' : 'false'"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
            <span v-if="tab.count > 0" class="rp-tab-count">{{ tab.count }}</span>
          </button>
        </div>
        <button
          class="rp-close"
          :aria-label="t('app.closePanel')"
          :title="`${t('app.closePanel')} (Esc)`"
          @click="ui.toggleRightPanel"
        >
          ✕
        </button>
      </div>

      <div class="rp-body">
        <!-- 示例数据标记：这些条目不是真实通知，必须让使用者看得见（后端接口尚未提供） -->
        <p v-if="DEMO_DATA" class="rp-demo-banner" role="note">
          {{ t('app.demoDataBanner') }}
        </p>

        <!-- 通知中心 -->
        <div v-if="activeTab === 'notice'" role="tabpanel" :aria-label="t('app.noticeList')">
          <div v-for="n in notices" :key="n.id" class="rp-item" :class="{ unread: n.unread }">
            <span class="rp-dot" :class="n.level" aria-hidden="true"></span>
            <div class="rp-item-main">
              <div class="rp-item-title">{{ n.title }}</div>
              <div class="rp-item-meta">{{ n.time }}</div>
            </div>
          </div>
        </div>

        <!-- 平台动态 -->
        <div v-else role="tabpanel" :aria-label="t('app.activityList')">
          <div v-for="a in activities" :key="a.id" class="rp-item">
            <span class="rp-dot" :class="a.level" aria-hidden="true"></span>
            <div class="rp-item-main">
              <div class="rp-item-title">{{ a.title }}</div>
              <div class="rp-item-meta">{{ a.time }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="rp-foot">
        <button class="rp-clear" :aria-label="t('app.markAllRead')">
          {{ t('app.markAllRead') }}
        </button>
      </div>
    </aside>
  </transition>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const { t } = useI18n()

type TabKey = 'notice' | 'activity'
const activeTab = ref<TabKey>('notice')

/**
 * 通知中心 / 动态流当前**没有后端数据源**：全仓（Java/Go/Python）与前端 api 层都
 * 不存在 notifications / activities 端点，故下面是内置示例数据。
 *
 * 保持示例数据 + 显式标记（而不是静默展示，也不是删掉面板）的理由：
 * 这些文案读起来就是真实告警（如"数据源 MySQL-生产库 连接恢复正常"），
 * 不标注会让运维人员误以为故障已恢复 —— 那比"面板空着"危险得多。
 * 后端接口就绪后：把 DEMO_DATA 置 false 并换成 api 拉取，无需改动模板结构。
 */
const DEMO_DATA = true
interface PanelItem {
  id: string
  title: string
  time: string
  level: 'ok' | 'warn' | 'err' | 'info'
  unread?: boolean
}

const notices = computed<PanelItem[]>(() => [
  {
    id: 'n1',
    title: t('app.demoNotice1'),
    time: t('app.timeMinutesAgo', { n: 5 }),
    level: 'ok',
    unread: true
  },
  {
    id: 'n2',
    title: t('app.demoNotice2'),
    time: t('app.timeMinutesAgo', { n: 32 }),
    level: 'warn',
    unread: true
  },
  { id: 'n3', title: t('app.demoNotice3'), time: t('app.timeHoursAgo', { n: 2 }), level: 'info' },
  {
    id: 'n4',
    title: t('app.demoNotice4'),
    time: t('app.timeYesterday', { time: '18:40' }),
    level: 'info'
  }
])

const activities = computed<PanelItem[]>(() => [
  {
    id: 'a1',
    title: t('app.demoActivity1'),
    time: t('app.timeMinutesAgo', { n: 10 }),
    level: 'info'
  },
  { id: 'a2', title: t('app.demoActivity2'), time: t('app.timeHoursAgo', { n: 1 }), level: 'ok' },
  { id: 'a3', title: t('app.demoActivity3'), time: t('app.timeHoursAgo', { n: 3 }), level: 'info' },
  {
    id: 'a4',
    title: t('app.demoActivity4'),
    time: t('app.timeYesterday', { time: '22:15' }),
    level: 'warn'
  }
])

const tabs = computed(() => [
  {
    key: 'notice' as TabKey,
    label: t('app.tabNotice'),
    count: notices.value.filter((n) => n.unread).length
  },
  { key: 'activity' as TabKey, label: t('app.tabActivity'), count: 0 }
])
</script>

<style scoped>
.rpanel {
  width: 320px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--ds-bg-surface);
  border-left: 1px solid var(--ds-border-subtle);
  box-shadow: -4px 0 16px rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

/* 开合过渡：右滑收起 */
.panel-slide-enter-active,
.panel-slide-leave-active {
  transition:
    transform var(--ds-transition-moderate),
    opacity var(--ds-transition-normal);
}
.panel-slide-enter-from,
.panel-slide-leave-to {
  transform: translateX(100%);
  opacity: var(--ds-opacity-0);
}

.rp-head {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
  padding: 10px 12px;
  border-bottom: 1px solid var(--ds-border-subtle);
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05) 0%, rgba(99, 102, 241, 0.04) 100%);
}
.rp-tabs {
  display: flex;
  gap: var(--ds-spacing-1);
  flex: 1;
}
.rp-tab {
  border: none;
  background: transparent;
  padding: 6px 10px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-tertiary);
  border-radius: var(--ds-radius-md);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition:
    color var(--ds-transition-fast),
    background var(--ds-transition-fast);
}
.rp-tab:hover {
  color: var(--ds-text-primary);
  background: var(--c-surface-hover);
}
.rp-tab.on {
  color: var(--ds-color-primary-500);
  background: var(--ds-color-primary-50);
  font-weight: var(--ds-font-weight-semibold);
}
.rp-tab-count {
  font-size: var(--ds-font-size-xs);
  min-width: 15px;
  height: 15px;
  line-height: var(--ds-line-height-none);
  text-align: center;
  border-radius: var(--ds-radius-md);
  background: var(--ds-color-error-500);
  color: var(--ds-text-inverse);
  font-weight: var(--ds-font-weight-extrabold);
}
.rp-close {
  border: none;
  background: transparent;
  color: var(--ds-text-tertiary);
  font-size: var(--ds-font-size-base);
  width: 26px;
  height: 26px;
  border-radius: var(--ds-radius-md);
  cursor: pointer;
  transition:
    color var(--ds-transition-fast),
    background var(--ds-transition-fast),
    transform var(--ds-transition-fast);
}
.rp-close:hover {
  color: var(--ds-color-error-500);
  background: var(--c-red-50);
  transform: rotate(90deg);
}

.rp-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px 0;
}
.rp-demo-banner {
  margin: 8px 12px;
  padding: 8px 10px;
  border: 1px solid var(--ds-warning);
  border-radius: var(--ds-radius-sm, 4px);
  background: var(--ds-warning-soft);
  color: var(--ds-text-primary);
  font-size: var(--ds-font-size-xs, 12px);
  line-height: 1.5;
}
.rp-item {
  display: flex;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--ds-border-subtle);
  transition: background var(--ds-transition-fast);
}
.rp-item:hover {
  background: var(--c-surface-hover);
}
.rp-item.unread .rp-item-title {
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-primary);
}
.rp-item.unread {
  background: rgba(59, 130, 246, 0.04);
}
.rp-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex: none;
}
.rp-dot.ok {
  background: var(--ds-color-success-500);
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.5);
}
.rp-dot.warn {
  background: var(--ds-color-warning-500);
  box-shadow: 0 0 6px rgba(245, 158, 11, 0.5);
}
.rp-dot.err {
  background: var(--ds-color-error-500);
  box-shadow: 0 0 6px rgba(239, 68, 68, 0.5);
}
.rp-dot.info {
  background: var(--ds-color-primary-500);
  box-shadow: 0 0 6px rgba(59, 130, 246, 0.5);
}
.rp-item-title {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
  line-height: var(--ds-line-height-normal);
}
.rp-item-meta {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-top: 2px;
}

.rp-foot {
  padding: 10px 14px;
  border-top: 1px solid var(--ds-border-subtle);
  text-align: center;
}
.rp-clear {
  border: none;
  background: transparent;
  color: var(--ds-color-primary-500);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-semibold);
  cursor: pointer;
  padding: 5px 10px;
  border-radius: var(--ds-radius-md);
  transition: background var(--ds-transition-fast);
}
.rp-clear:hover {
  background: var(--ds-color-primary-50);
}
</style>
