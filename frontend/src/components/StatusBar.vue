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
  gap: var(--ds-spacing-3);
  padding: 0 18px;
  /* v3：底栏改用专属令牌（#dfe4ec），比画布 #e9edf4 再深一档。
   * v2 用的是 --ds-surface-2（#f5f6f7），与当时的画布只差 1%，
   * 用户反馈"顶栏和尾栏看不出区别"——现在顶栏是浅色玻璃、尾栏是实底深一档灰，
   * 深色侧栏夹在左侧，三区一眼可分。文字仍走 --ds-statusbar-ink（实测 7.68:1）。 */
  background: var(--ds-statusbar-bg);
  color: var(--ds-statusbar-ink);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-medium);
  border-top: 1px solid var(--ds-border-default);
  box-shadow: none;
  user-select: none;
  position: relative;
}
/* v2：删除状态栏顶部的 2px 彩虹渐变饰条（蓝 → 靛 → 紫 → 透明）。
 * 它不承载任何信息；且"主色边框"会被读成"状态栏处于激活/告警态"，是视觉语义错误。 */
.sb-left,
.sb-right {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.sb-label {
  /* v2：收敛为中性 —— 旧 --ds-color-primary-800 是**非主题感知**的深藏蓝
   * （亮暗都取 #1e40af），暗色下压在 #1d2128 上几乎不可见。改中性次级文字。 */
  color: var(--ds-text-tertiary);
  font-size: var(--ds-font-size-xs);
  letter-spacing: 1.2px;
  font-weight: var(--ds-font-weight-extrabold);
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
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  transition: all var(--ds-transition-quick);
}
.sb-service:hover {
  /* v2：收敛为随主题切换的强调色（旧 primary-300/50 非主题感知） */
  border-color: var(--ds-accent);
  background: var(--ds-accent-soft);
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
  animation: sbBreath var(--ds-animation-status-breath) var(--ease-smooth) infinite;
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
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
}
.sb-item.sb-ws {
  color: var(--ds-text-primary);
  font-weight: var(--ds-font-weight-extrabold);
}
.sb-clock {
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  letter-spacing: 0.4px;
  color: var(--ds-text-primary);
  font-weight: var(--ds-font-weight-extrabold);
  /* v2：强调胶囊改为随主题切换（旧 primary-100/500 非主题感知，
   * 暗色下会变成亮蓝底 + 浅色文字 → 1.2:1 不可读） */
  background: var(--ds-accent-soft);
  border-color: var(--ds-accent);
}
.sb-env {
  /* v2：同 .sb-label，收敛为中性（旧 primary-900 非主题感知） */
  color: var(--ds-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-extrabold);
}

@keyframes sbBreath {
  0%,
  100% {
    opacity: var(--ds-opacity-10);
  }
  50% {
    opacity: var(--ds-opacity-6);
  }
}
</style>
