<template>
  <footer class="statusbar" role="contentinfo" :aria-label="t('app.statusbar')">
    <div class="sb-left">
      <span class="sb-label">{{ t('app.services') }}</span>
      <span class="sb-div" aria-hidden="true"></span>
      <!-- 服务健康点：后端健康检查真实数据（降级为 unknown 灰点） -->
      <span
        v-for="s in services"
        :key="s.name"
        class="sb-service"
        :title="`${s.name}：${statusText(s.status)}`"
      >
        <span class="sb-dot" :class="s.status" aria-hidden="true"></span>
        {{ s.name }}
      </span>
    </div>
    <div class="sb-right">
      <span class="sb-item sb-ws" :title="t('app.currentWorkspace')">
        <el-icon :size="11" aria-hidden="true"><component :is="'Folder'" /></el-icon>
        {{ store.workspace }}
      </span>
      <span class="sb-sep" aria-hidden="true"></span>
      <span class="sb-item sb-clock" :title="clockFull">{{ clockShort }}</span>
      <span class="sb-sep" aria-hidden="true"></span>
      <span class="sb-item">v{{ appVersion }}</span>
      <span class="sb-sep" aria-hidden="true"></span>
      <span class="sb-item sb-env">{{ appEnv }}</span>
    </div>
  </footer>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'

declare const __APP_VERSION__: string
declare const __APP_ENV__: string

const { t } = useI18n()
const appVersion = __APP_VERSION__ || 'dev'
const appEnv = __APP_ENV__ || 'dev'
const store = useAppStore()

/** 服务健康状态：探 encaps-layer /actuator/health（2s 超时）。
 * 数据源标注：真实健康检查（GET /actuator/health，经 Vite /actuator 代理），失败降级 unknown。 */
interface SvcStatus {
  name: string
  status: 'up' | 'warn' | 'down' | 'unknown'
}

const services = ref<SvcStatus[]>([
  { name: 'API Gateway', status: 'unknown' },
  { name: 'Metadata', status: 'unknown' },
  { name: 'Scheduler', status: 'unknown' }
])

function statusText(s: SvcStatus['status']): string {
  return s === 'up'
    ? t('common.servicesUp')
    : s === 'warn'
      ? t('common.servicesWarn')
      : s === 'down'
        ? t('common.servicesDown')
        : t('common.servicesChecking')
}

async function probeHealth() {
  const t0 = Date.now()
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 2000)
    const resp = await fetch('/actuator/health', { signal: ctrl.signal })
    clearTimeout(timer)
    const latency = Date.now() - t0
    const status: SvcStatus['status'] = resp.ok ? (latency > 800 ? 'warn' : 'up') : 'down'
    // 单服务架构：三项同源，统一状态（多服务后逐项探活）
    services.value = services.value.map((x) => ({ ...x, status }))
  } catch {
    services.value = services.value.map((x) => ({ ...x, status: 'down' as const }))
  }
}

/** 实时时钟（状态栏常驻） */
const clockShort = ref('')
const clockFull = ref('')
let clockTimer: number | undefined
let healthTimer: number | undefined

function tick() {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  clockShort.value = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`
  clockFull.value = clockShort.value
}

onMounted(() => {
  tick()
  clockTimer = window.setInterval(tick, 1000)
  void probeHealth()
  healthTimer = window.setInterval(probeHealth, 30_000)
})

onUnmounted(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  if (healthTimer) window.clearInterval(healthTimer)
})
</script>

<style scoped>
.statusbar {
  height: 42px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 18px;
  background: linear-gradient(
    90deg,
    var(--ds-color-gray-50) 0%,
    var(--ds-color-gray-100) 50%,
    var(--ds-color-gray-200) 100%
  );
  color: var(--ds-text-secondary);
  font-size: 12px;
  font-weight: 500;
  border-top: 1.5px solid var(--ds-color-primary-300);
  box-shadow:
    0 -1px 0 rgba(255, 255, 255, 0.7) inset,
    0 -4px 12px rgba(59, 130, 246, 0.06);
  user-select: none;
  position: relative;
}
.statusbar::before {
  content: '';
  position: absolute;
  top: -1.5px;
  left: 0;
  right: 0;
  height: 1.5px;
  background: linear-gradient(
    90deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 35%,
    rgba(99, 102, 241, 0.3) 70%,
    transparent 100%
  );
  pointer-events: none;
}
.sb-left,
.sb-right {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.sb-label {
  color: var(--ds-color-primary-800);
  font-size: var(--ds-font-size-xs);
  letter-spacing: 1.2px;
  font-weight: 700;
}
.sb-div,
.sb-sep {
  width: 1px;
  height: 16px;
  background: var(--ds-border-default);
  flex: none;
}
.sb-service {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  transition: all 0.2s var(--ease-smooth);
}
.sb-service:hover {
  border-color: var(--ds-color-primary-300);
  background: var(--ds-color-primary-50);
  transform: translateY(-1px);
}
.sb-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
.sb-dot.up {
  background: var(--ds-color-success-500);
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.8);
  animation: sbBreath 2.6s var(--ease-smooth) infinite;
}
.sb-dot.warn {
  background: var(--ds-color-warning-500);
  box-shadow: 0 0 6px rgba(245, 158, 11, 0.8);
}
.sb-dot.down {
  background: var(--ds-color-error-500);
  box-shadow: 0 0 6px rgba(239, 68, 68, 0.8);
}
.sb-dot.unknown {
  background: var(--ds-color-gray-400);
}
.sb-item {
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
}
.sb-item.sb-ws {
  color: var(--ds-text-primary);
  font-weight: 700;
}
.sb-clock {
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  letter-spacing: 0.4px;
  color: var(--ds-text-primary);
  font-weight: 700;
  background: var(--ds-color-primary-100);
  border-color: var(--ds-color-primary-500);
}
.sb-env {
  color: var(--ds-color-primary-900);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-size: var(--ds-font-size-xs);
  font-weight: 700;
}

@keyframes sbBreath {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}
</style>
