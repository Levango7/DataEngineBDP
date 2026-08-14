<template>
  <div>
    <h1>运维中心</h1>
    <div class="sub">客户视角运行态监控；底层自研 SKE 发行版自愈、扩容对客户透明。</div>
    <div class="grid g4">
      <div class="card"><h3>集群健康</h3><div class="kpi s"><span class="pill" :class="healthPillClass(overview?.clusterHealth)">{{ healthPillText(overview?.clusterHealth) }}</span></div></div>
      <div class="card"><h3>运行作业</h3><div class="kpi">{{ overview?.runningJobCount ?? '--' }}</div></div>
      <div class="card"><h3>今日失败</h3><div class="kpi s">{{ overview?.todayFailedCount ?? '--' }}</div></div>
      <div class="card"><h3>平均延迟</h3><div class="kpi s">{{ overview?.avgLatencySec ?? '--' }}s</div></div>
    </div>

    <!-- 统一运维台：组件健康总览（红黄绿） -->
    <div class="card" style="margin-top: 14px">
      <h3>组件健康总览
        <span v-if="healthSummary" style="font-size: 12px; color: var(--muted); font-weight: normal">
          （{{ healthSummary.up }}/{{ healthSummary.total }} UP
          <span v-if="healthSummary.warn">· {{ healthSummary.warn }} WARN</span>
          <span v-if="healthSummary.down" style="color: var(--red)">· {{ healthSummary.down }} DOWN</span>）
        </span>
      </h3>
      <div v-if="healthLoading" style="color: var(--muted)">加载中…</div>
      <div v-else-if="healthError" style="color: var(--red)">{{ healthError }}</div>
      <div v-else class="health-grid">
        <div
          v-for="c in healthComponents"
          :key="c.name"
          class="health-item"
          :title="`${c.url} · ${c.latencyMs}ms${c.detail ? ' · ' + c.detail : ''}`"
        >
          <span class="health-dot" :class="'st-' + c.status.toLowerCase()"></span>
          <span class="health-name">{{ c.name }}</span>
          <span class="health-status">{{ c.status }}</span>
        </div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>作业监控</h3>
      <div v-if="jobsLoading" style="color: var(--muted)">加载中…</div>
      <div v-else-if="jobsError" style="color: var(--red)">{{ jobsError }}</div>
      <table v-else>
        <tr><th>作业</th><th>类型</th><th>运行时长</th><th>状态</th><th></th></tr>
        <tr v-for="j in jobs" :key="j.id">
          <td>{{ j.name }}</td>
          <td>{{ jobTypeLabel(j.type) }}</td>
          <td>{{ j.duration }}</td>
          <td><span class="pill" :class="jobStatusPillClass(j.status)">{{ jobStatusPillText(j.status) }}</span></td>
          <td><button class="btn ghost sm" @click="openLog(j)">日志</button></td>
        </tr>
        <tr v-if="jobs.length === 0">
          <td colspan="5" style="text-align: center; color: var(--muted)">暂无作业</td>
        </tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>告警 <span class="pill r">{{ alerts.length }}</span></h3>
      <div v-if="alertsLoading" style="color: var(--muted)">加载中…</div>
      <table v-else>
        <tr><th>告警</th><th>级别</th><th></th></tr>
        <tr v-for="a in alerts" :key="a.id">
          <td>{{ a.content }}</td>
          <td><span class="pill" :class="alertLevelPillClass(a.level)">{{ alertLevelPillText(a.level) }}</span></td>
          <td><button class="btn sm" @click="handleAlert(a)">处理</button></td>
        </tr>
        <tr v-if="alerts.length === 0">
          <td colspan="3" style="text-align: center; color: var(--muted)">暂无告警</td>
        </tr>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>作业日志：{{ currentJob?.name }}</template>
      <div class="runlog" style="height: auto">
        <div v-if="logLoading" style="color: var(--muted)">加载日志…</div>
        <pre v-else style="white-space: pre-wrap; font-family: monospace">{{ logContent || '暂无日志' }}</pre>
      </div>
      <div class="note">日志由封装层归一化输出，隐藏 Pod/容器细节。</div>
    </Drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import Drawer from '@/components/Drawer.vue'
import * as opsApi from '@/api/ops'
import type {
  OpsOverview,
  OpsJob,
  Alert,
  OpsJobType,
  OpsJobStatus,
  AlertLevel,
  ComponentHealth,
  HealthOverview
} from '@/api/ops'

const store = useAppStore()
const drawerVisible = ref(false)

// 概览
const overview = ref<OpsOverview | null>(null)

// 组件健康总览（统一运维台）
const healthLoading = ref(false)
const healthError = ref('')
const healthComponents = ref<ComponentHealth[]>([])
const healthSummary = ref<HealthOverview['summary'] | null>(null)

async function loadHealth() {
  healthLoading.value = true
  healthError.value = ''
  try {
    const data = await opsApi.getHealthOverview()
    healthComponents.value = data.components ?? []
    healthSummary.value = data.summary ?? null
  } catch (e) {
    healthError.value = `组件健康加载失败: ${(e as Error)?.message ?? e}（query-api 未就绪）`
  } finally {
    healthLoading.value = false
  }
}

// 作业列表
const jobs = ref<OpsJob[]>([])
const jobsLoading = ref(false)
const jobsError = ref('')

// 告警列表
const alerts = ref<Alert[]>([])
const alertsLoading = ref(false)

// 日志
const currentJob = ref<OpsJob | null>(null)
const logContent = ref('')
const logLoading = ref(false)

/** 加载概览 */
async function loadOverview() {
  try {
    overview.value = await opsApi.getOverview()
  } catch {
    // 概览加载失败不阻塞页面
  }
}

/** 加载作业列表 */
async function loadJobs() {
  jobsLoading.value = true
  jobsError.value = ''
  try {
    jobs.value = await opsApi.listJobs()
  } catch (err) {
    jobsError.value = (err as Error).message || '作业列表加载失败'
  } finally {
    jobsLoading.value = false
  }
}

/** 加载告警列表 */
async function loadAlerts() {
  alertsLoading.value = true
  try {
    alerts.value = await opsApi.listAlerts()
  } catch {
    alerts.value = []
  } finally {
    alertsLoading.value = false
  }
}

/** 打开日志抽屉 */
async function openLog(job: OpsJob) {
  currentJob.value = job
  drawerVisible.value = true
  logLoading.value = true
  logContent.value = ''
  try {
    logContent.value = await opsApi.getJobLogs(job.id)
  } catch (err) {
    logContent.value = `日志加载失败：${(err as Error).message}`
  } finally {
    logLoading.value = false
  }
}

/** 处理告警 */
async function handleAlert(alert: Alert) {
  try {
    await opsApi.handleAlert(alert.id, '处理')
    store.showToast('已处理')
    await loadAlerts()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 健康状态 → pill 样式 */
function healthPillClass(s?: string): string {
  switch (s) {
    case 'healthy':
      return 'g'
    case 'warning':
      return 'a'
    case 'critical':
      return 'r'
    default:
      return 'b'
  }
}

/** 健康状态 → pill 文案 */
function healthPillText(s?: string): string {
  switch (s) {
    case 'healthy':
      return '健康'
    case 'warning':
      return '警告'
    case 'critical':
      return '故障'
    default:
      return '--'
  }
}

/** 作业类型 → 中文 */
function jobTypeLabel(t: OpsJobType): string {
  switch (t) {
    case 'stream_flink':
      return '流(Flink)'
    case 'batch_spark':
      return '批(Spark)'
    case 'batch_dag':
      return '批(DAG)'
    default:
      return t
  }
}

/** 作业状态 → pill 样式 */
function jobStatusPillClass(s: OpsJobStatus): string {
  switch (s) {
    case 'running':
      return 'a'
    case 'success':
      return 'g'
    case 'failed':
      return 'r'
    default:
      return 'b'
  }
}

/** 作业状态 → pill 文案 */
function jobStatusPillText(s: OpsJobStatus): string {
  switch (s) {
    case 'running':
      return '运行中'
    case 'success':
      return '成功'
    case 'failed':
      return '失败'
    case 'pending':
      return '等待中'
    default:
      return s
  }
}

/** 告警级别 → pill 样式 */
function alertLevelPillClass(l: AlertLevel): string {
  switch (l) {
    case 'warn':
      return 'a'
    case 'critical':
      return 'r'
    default:
      return 'b'
  }
}

/** 告警级别 → pill 文案 */
function alertLevelPillText(l: AlertLevel): string {
  switch (l) {
    case 'warn':
      return '警告'
    case 'critical':
      return '严重'
    default:
      return '信息'
  }
}

onMounted(() => {
  loadOverview()
  loadJobs()
  loadAlerts()
  loadHealth()
})
</script>