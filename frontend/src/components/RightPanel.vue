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
 * 演示数据：通知中心/动态流的展示壳。
 * 数据源标注：真实通知中心后端尚未提供（数据源=前端内置），
 * 接入后端后替换为 API 拉取（预期 /api/v1/notifications）。
 */
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
    transform 0.32s var(--ease-drawer),
    opacity 0.28s var(--ease-smooth);
}
.panel-slide-enter-from,
.panel-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}

.rp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ds-border-subtle);
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05) 0%, rgba(99, 102, 241, 0.04) 100%);
}
.rp-tabs {
  display: flex;
  gap: 4px;
  flex: 1;
}
.rp-tab {
  border: none;
  background: transparent;
  padding: 6px 10px;
  font-size: 13px;
  color: var(--ds-text-tertiary);
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition:
    color 0.18s var(--ease-smooth),
    background 0.18s var(--ease-smooth);
}
.rp-tab:hover {
  color: var(--ds-text-primary);
  background: var(--c-surface-hover);
}
.rp-tab.on {
  color: var(--ds-color-primary-500);
  background: var(--ds-color-primary-50);
  font-weight: 600;
}
.rp-tab-count {
  font-size: 10px;
  min-width: 15px;
  height: 15px;
  line-height: 15px;
  text-align: center;
  border-radius: 8px;
  background: var(--ds-color-error-500);
  color: #fff;
  font-weight: 700;
}
.rp-close {
  border: none;
  background: transparent;
  color: var(--ds-text-tertiary);
  font-size: 14px;
  width: 26px;
  height: 26px;
  border-radius: var(--ds-radius-md);
  cursor: pointer;
  transition:
    color 0.18s var(--ease-smooth),
    background 0.18s var(--ease-smooth),
    transform 0.18s var(--ease-smooth);
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
.rp-item {
  display: flex;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--ds-border-subtle);
  transition: background 0.18s var(--ease-smooth);
}
.rp-item:hover {
  background: var(--c-surface-hover);
}
.rp-item.unread .rp-item-title {
  font-weight: 600;
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
  font-size: 13px;
  color: var(--ds-text-primary);
  line-height: 1.45;
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
  font-weight: 600;
  cursor: pointer;
  padding: 5px 10px;
  border-radius: var(--ds-radius-md);
  transition: background 0.18s var(--ease-smooth);
}
.rp-clear:hover {
  background: var(--ds-color-primary-50);
}
</style>
