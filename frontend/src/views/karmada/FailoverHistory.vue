<template>
  <div class="km-page" role="main" data-design-contract="v2" :aria-label="t('karmada.history.title')">
    <PageHeader :title="t('karmada.history.title')" :subtitle="t('karmada.history.subtitle')" />

    <p v-if="error" class="km-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('karmada.actionRetry') }}</el-button>
    </p>

    <section class="km-toolbar">
      <label class="km-field">
        <span class="km-field__label">{{ t('karmada.history.filterCluster') }}</span>
        <el-input v-model="clusterFilter" clearable style="width: 220px" />
      </label>
      <label class="km-field">
        <span class="km-field__label">{{ t('karmada.colStatus') }}</span>
        <el-select v-model="statusFilter" clearable :placeholder="t('karmada.history.statusAll')" style="width: 180px">
          <el-option v-for="s in FAILOVER_EVENT_STATUSES" :key="s" :label="s" :value="s" />
        </el-select>
      </label>
      <label class="km-field">
        <span class="km-field__label">{{ t('karmada.history.fieldLimit') }}</span>
        <el-input v-model.number="limit" type="number" :min="1" :max="100" style="width: 120px" />
      </label>
      <el-button type="primary" :loading="loading" @click="load">
        {{ t('karmada.actionQuery') }}
      </el-button>
      <span class="km-hint">{{ t('karmada.history.filterHint') }}</span>
    </section>

    <PageCard :title="t('karmada.history.triggerTitle')">
      <el-form class="km-form" label-width="140px" label-position="left">
        <el-form-item :label="t('karmada.history.fieldSourceCluster')">
          <el-input v-model="trigger.sourceCluster" :placeholder="t('karmada.history.sourcePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.history.fieldTargetCluster')">
          <el-input v-model="trigger.targetCluster" :placeholder="t('karmada.history.targetPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.history.fieldPolicyName')">
          <el-input v-model="trigger.policyName" :placeholder="t('karmada.history.policyPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.history.fieldReason')">
          <el-select v-model="trigger.reason" style="width: 220px">
            <el-option v-for="r in FAILOVER_TRIGGER_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="triggering" @click="triggerFailoverEvent">
            {{ t('karmada.history.actionTrigger') }}
          </el-button>
        </el-form-item>
      </el-form>
      <p v-if="triggerError" class="km-error" role="alert">{{ triggerError }}</p>
      <p v-if="triggerOk" class="km-ok" role="status">{{ triggerOk }}</p>
    </PageCard>

    <PageCard :title="t('karmada.history.listTitle')">
      <el-table v-if="visible.length" v-loading="loading" :data="visible" stripe>
        <el-table-column :label="t('karmada.history.colEventId')" min-width="180">
          <template #default="{ row }"><span class="km-mono">{{ row.eventId }}</span></template>
        </el-table-column>
        <el-table-column :label="t('karmada.history.colSource')" width="150" prop="sourceCluster" />
        <el-table-column :label="t('karmada.history.colTarget')" width="150" prop="targetCluster" />
        <el-table-column :label="t('karmada.history.colTriggerReason')" width="150" prop="triggerReason" />
        <el-table-column :label="t('karmada.history.colPolicyName')" min-width="150" prop="policyName" />
        <el-table-column :label="t('karmada.colStatus')" width="120">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="row.status" :status-map="KARMADA_EVENT_STATUS_MAP" />
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.history.colDuration')" width="110">
          <template #default="{ row }">{{ durationMs(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.history.colWorkloads')" min-width="160">
          <template #default="{ row }">{{ workloadsLabel(row.affectedWorkloads) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.history.colStartedAt')" width="190">
          <template #default="{ row }">
            <span class="km-mono">{{ formatTime(row.startedAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="emptyMessage" />
    </PageCard>

    <PageCard :title="t('karmada.history.chartTitle')">
      <div ref="timelineRef" class="km-chart" />
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { formatTime as fmtTime } from '../exchange/format'
import { KARMADA_EVENT_STATUS_MAP, durationMs, parseJsonList } from './format'
import { useChartPalette } from './useChartPalette'
import {
  FAILOVER_EVENT_STATUSES,
  FAILOVER_TRIGGER_REASONS,
  listFailoverEvents,
  triggerFailover,
  type FailoverEvent
} from '@/api/karmada'

const { t, locale } = useI18n()

const events = ref<FailoverEvent[]>([])
const loading = ref(false)
const triggering = ref(false)
const error = ref('')
const triggerError = ref('')
const triggerOk = ref('')

// 带路径参数的单集群健康历史在网关侧不可达（粗前缀 /api/v1/clusters 归 infra-orchestrator，
// 台账 #89），所以"看某个集群发生过什么"这件事改由 /failover-events 按集群名过滤呈现。
const clusterFilter = ref('')
const statusFilter = ref('')
const limit = ref(50)

const trigger = reactive({
  sourceCluster: '',
  targetCluster: '',
  policyName: '',
  reason: 'manual'
})

const timelineRef = ref<HTMLElement>()
const charts: echarts.ECharts[] = []
const { palette, onThemeChange } = useChartPalette()

const visible = computed(() => {
  const c = clusterFilter.value.trim()
  const s = statusFilter.value
  return events.value.filter(
    (e) => (!c || e.sourceCluster === c || e.targetCluster === c) && (!s || e.status === s)
  )
})

const emptyMessage = computed(() =>
  events.value.length ? t('karmada.history.emptyFiltered') : t('karmada.history.emptyEvents')
)

const formatTime = (iso: string | null | undefined): string => fmtTime(iso, locale.value)

function workloadsLabel(json: string): string {
  const list = parseJsonList(json)
  return list.length ? list.join(', ') : '—'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    // 后端 limit<=0 或 >100 都会静默回落成 20（failover.go:96-98），所以钳到 1..100
    const safe = Math.min(Math.max(Number(limit.value) || 50, 1), 100)
    const resp = await listFailoverEvents({ limit: safe })
    events.value = resp?.items ?? []
    await nextTick()
    renderTimeline()
  } catch (e) {
    error.value = `${t('karmada.loadFailed')}${(e as Error)?.message ?? ''}`
    events.value = []
  } finally {
    loading.value = false
  }
}

function ensureChart(el: HTMLElement | undefined): echarts.ECharts | null {
  if (!el) return null
  const found = charts.find((c) => c.getDom() === el)
  if (found) return found
  const created = echarts.init(el)
  charts.push(created)
  return created
}

function renderTimeline(): void {
  const p = palette.value
  const colorOf: Record<string, string> = {
    succeeded: p['--ds-success'],
    failed: p['--ds-danger'],
    rolled_back: p['--ds-danger'],
    running: p['--ds-info'],
    pending: p['--ds-warning']
  }
  const chart = ensureChart(timelineRef.value)
  chart?.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: p['--ds-text-secondary'] } },
    grid: { left: 48, right: 16, top: 24, bottom: 44 },
    xAxis: {
      type: 'time',
      name: t('karmada.history.axisTime'),
      axisLabel: { color: p['--ds-text-secondary'] }
    },
    yAxis: {
      type: 'value',
      name: t('karmada.history.axisDuration'),
      axisLabel: { color: p['--ds-text-secondary'] }
    },
    series: FAILOVER_EVENT_STATUSES.map((status) => ({
      name: status,
      type: 'scatter' as const,
      symbolSize: 12,
      itemStyle: { color: colorOf[status] ?? p['--ds-accent'] },
      data: visible.value
        .filter((e) => e.status === status)
        .map((e) => [new Date(e.startedAt).getTime(), e.durationMs])
    }))
  })
}

function handleResize(): void {
  for (const c of charts) c.resize()
}

async function triggerFailoverEvent(): Promise<void> {
  triggerError.value = ''
  triggerOk.value = ''
  if (!trigger.sourceCluster.trim() || !trigger.targetCluster.trim()) {
    // Go 侧 binding:"required"（failover.go:142-143），少一个就是 400，先本地拦
    triggerError.value = t('karmada.history.needClusters')
    return
  }
  triggering.value = true
  try {
    await triggerFailover({
      sourceCluster: trigger.sourceCluster.trim(),
      targetCluster: trigger.targetCluster.trim(),
      policyName: trigger.policyName.trim() || undefined,
      reason: trigger.reason || undefined
    })
    // 端点只落一条 pending 事件，真正的迁移由 failover-engine 异步做（failover.go:151 注释）
    triggerOk.value = t('karmada.history.triggerOk')
    await load()
  } catch (e) {
    triggerError.value = `${t('karmada.triggerFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    triggering.value = false
  }
}

// 过滤条件改变时要重画：图上只剩上次的全量点会误导"这个集群没迁移过"。
// 必须建在 setup 作用域——建在 onMounted 里不会随组件卸载自动停止。
watch(visible, () => renderTimeline())

onMounted(() => {
  void load()
  onThemeChange(() => renderTimeline())
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  for (const c of charts) c.dispose()
  charts.length = 0
})
</script>

<style scoped>
.km-page {
  padding: var(--ds-spacing-4);
}

.km-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-4);
}

.km-field {
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-1);
}

.km-field__label {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.km-form {
  max-width: 720px;
}

.km-hint {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.km-chart {
  height: 300px;
}

.km-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.km-ok {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-success);
  font-size: var(--ds-font-size-sm);
}

.km-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
