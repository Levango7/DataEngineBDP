<template>
  <div class="view-quickbar" role="toolbar" :aria-label="t('app.quickSwitch')">
    <div class="vb-capsule">
      <button
        class="vb-pill"
        :aria-label="t('app.localeLabel')"
        :title="t('app.localeLabel')"
        @click="toggleLocale"
      >
        {{ current === 'zh-CN' ? 'EN' : '中' }}
      </button>
      <span class="vb-divider" aria-hidden="true"></span>
      <button
        class="vb-pill vb-theme"
        :aria-label="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
        :title="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
        @click="theme.toggle"
      >
        <span class="vb-ic" aria-hidden="true">{{ theme.isDark ? '☀️' : '🌙' }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/stores/theme'
import { persistLocale, type SupportedLocale } from '@/i18n'

const { t, locale } = useI18n()
const theme = useThemeStore()

const current = computed<string>(() => (locale as unknown as { value: string }).value)

function toggleLocale() {
  const next: SupportedLocale = current.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  ;(locale as unknown as { value: string }).value = next
  persistLocale(next)
}
</script>

<style scoped>
/* 正文右上角悬浮二合一胶囊：i18n + theme 飞书风 */
.view-quickbar {
  position: fixed;
  top: 80px;
  right: 28px;
  z-index: 50;
}
.vb-capsule {
  display: inline-flex;
  align-items: stretch;
  background: rgba(255, 255, 255, 0.92);
  border: 1.5px solid #cbd5e1;
  border-radius: 24px;
  box-shadow:
    0 6px 20px rgba(15, 23, 42, 0.1),
    0 2px 6px rgba(15, 23, 42, 0.04);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  overflow: hidden;
  transition:
    background 0.2s var(--ease-smooth),
    border-color 0.2s var(--ease-smooth),
    box-shadow 0.2s var(--ease-smooth);
}
.vb-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 34px;
  padding: 0 12px;
  background: transparent;
  color: #475569;
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.3px;
  cursor: pointer;
  border: none;
  transition:
    background 0.18s var(--ease-smooth),
    color 0.18s var(--ease-smooth);
}
.vb-pill:hover {
  background: var(--ds-color-primary-50);
  color: #1d4ed8;
}
.vb-ic {
  font-size: 14px;
  line-height: 1;
}
.vb-divider {
  width: 1px;
  align-self: stretch;
  background: #cbd5e1;
  margin: 4px 0;
}
.vb-theme {
  padding: 0 11px;
}

/* 暗色模式（Vue SFC scoped 的 :root 处理对 :root[] 有限制，
   但 :root[data-theme='dark'] .view-quickbar 这种子选择器模式有效） */
:global(:root[data-theme='dark']) .view-quickbar .vb-capsule {
  background: rgba(15, 23, 42, 0.88);
  border-color: rgba(99, 102, 241, 0.5);
  box-shadow:
    0 6px 20px rgba(0, 0, 0, 0.4),
    0 2px 6px rgba(0, 0, 0, 0.2);
}
:global(:root[data-theme='dark']) .view-quickbar .vb-pill {
  color: #cbd5e1;
}
:global(:root[data-theme='dark']) .view-quickbar .vb-pill:hover {
  background: rgba(99, 102, 241, 0.2);
  color: #f1f5f9;
}
:global(:root[data-theme='dark']) .view-quickbar .vb-divider {
  background: rgba(99, 102, 241, 0.4);
}
</style>
