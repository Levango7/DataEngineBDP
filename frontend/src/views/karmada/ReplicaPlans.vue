<template>
  <div class="km-page" role="main" data-design-contract="v2" :aria-label="t('karmada.plans.title')">
    <PageHeader :title="t('karmada.plans.title')" :subtitle="t('karmada.plans.subtitle')" />

    <p v-if="error" class="km-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('karmada.actionRetry') }}</el-button>
    </p>

    <PageCard :title="t('karmada.plans.formTitle')">
      <el-form class="km-form" label-width="140px" label-position="left">
        <el-form-item :label="t('karmada.plans.fieldPolicyName')">
          <el-input v-model="form.policyName" :placeholder="t('karmada.plans.policyPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldWorkload')">
          <el-input v-model="form.workload" :placeholder="t('karmada.plans.workloadPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldTotalReplicas')">
          <el-input v-model.number="form.totalReplicas" type="number" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldWeights')">
          <div v-for="(w, i) in form.weights" :key="i" class="km-row">
            <el-input v-model="w.cluster" :placeholder="t('karmada.plans.clusterPlaceholder')" />
            <el-input v-model.number="w.weight" type="number" :min="0" style="width: 120px" />
            <el-button link type="danger" @click="removeWeight(i)">
              {{ t('karmada.plans.actionRemoveWeight') }}
            </el-button>
          </div>
          <el-button @click="addWeight">{{ t('karmada.plans.actionAddWeight') }}</el-button>
          <p class="km-hint">{{ t('karmada.plans.weightsHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldReason')">
          <el-input v-model="form.reason" :placeholder="t('karmada.plans.reasonPlaceholder')" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="creating" @click="create">
            {{ t('karmada.plans.actionCreate') }}
          </el-button>
          <el-button @click="reset">{{ t('karmada.actionReset') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="formError" class="km-error" role="alert">{{ formError }}</p>
      <p v-if="formOk" class="km-ok" role="status">{{ formOk }}</p>
    </PageCard>

    <PageCard :title="t('karmada.plans.listTitle')">
      <el-table v-if="rows.length" v-loading="loading" :data="rows" stripe>
        <el-table-column :label="t('karmada.plans.colPolicyName')" min-width="160" prop="policyName" />
        <el-table-column :label="t('karmada.plans.colWorkload')" min-width="140" prop="workload" />
        <el-table-column :label="t('karmada.plans.colTotalReplicas')" width="110" prop="totalReplicas" />
        <el-table-column :label="t('karmada.plans.colWeights')" min-width="180">
          <template #default="{ row }">{{ formatJsonPairs(row.weights) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.plans.colAllocation')" min-width="180">
          <template #default="{ row }">{{ formatJsonPairs(row.allocation) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.plans.colReason')" width="120" prop="reason" />
        <el-table-column :label="t('karmada.colActions')" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="select(row)">
              {{ t('karmada.plans.actionVisualize') }}
            </el-button>
            <el-button link type="primary" @click="openAdjust(row)">
              {{ t('karmada.plans.actionAdjust') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="t('karmada.plans.emptyPlans')" />
    </PageCard>

    <PageCard v-if="current" :title="t('karmada.plans.chartTitle')">
      <div ref="allocationRef" class="km-chart" />
    </PageCard>

    <PageCard v-if="adjusting" :title="t('karmada.plans.adjustTitle')">
      <el-form class="km-form" label-width="140px" label-position="left">
        <el-form-item :label="t('karmada.plans.colPolicyName')">
          <span class="km-mono">{{ adjusting.policyName }}</span>
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldTotalReplicas')">
          <el-input v-model.number="adjust.totalReplicas" type="number" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('karmada.plans.fieldWeights')">
          <div v-for="(w, i) in adjust.weights" :key="i" class="km-row">
            <el-input :model-value="w.cluster" disabled />
            <el-input v-model.number="w.weight" type="number" :min="0" style="width: 120px" />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="updating" @click="update">
            {{ t('karmada.plans.actionApply') }}
          </el-button>
          <el-button @click="closeAdjust">{{ t('karmada.actionCancel') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="adjustError" class="km-error" role="alert">{{ adjustError }}</p>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { formatJsonPairs, parseJsonNumberRecord } from './format'
import { useChartPalette } from './useChartPalette'
import {
  createReplicaPlan,
  listReplicaPlans,
  updateReplicaPlan,
  type ReplicaWeightPlan
} from '@/api/karmada'

const { t } = useI18n()

const rows = ref<ReplicaWeightPlan[]>([])
const current = ref<ReplicaWeightPlan | null>(null)
const adjusting = ref<ReplicaWeightPlan | null>(null)
const loading = ref(false)
const creating = ref(false)
const updating = ref(false)
const error = ref('')
const formError = ref('')
const formOk = ref('')
const adjustError = ref('')

const emptyForm = () => ({
  policyName: '',
  workload: '',
  totalReplicas: 6,
  reason: '',
  weights: [{ cluster: '', weight: 1 }] as Array<{ cluster: string; weight: number }>
})
const form = reactive(emptyForm())
const adjust = reactive({
  totalReplicas: 0,
  weights: [] as Array<{ cluster: string; weight: number }>
})

const allocationRef = ref<HTMLElement>()
const charts: echarts.ECharts[] = []
const { palette, onThemeChange } = useChartPalette()

function addWeight(): void {
  form.weights.push({ cluster: '', weight: 1 })
}

function removeWeight(index: number): void {
  form.weights.splice(index, 1)
}

function reset(): void {
  Object.assign(form, emptyForm())
  formError.value = ''
  formOk.value = ''
}

function weightsMap(list: Array<{ cluster: string; weight: number }>): Record<string, number> {
  const out: Record<string, number> = {}
  for (const w of list) {
    if (w.cluster.trim()) out[w.cluster.trim()] = Math.trunc(Number(w.weight) || 0)
  }
  return out
}

async function load(selectName?: string): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const resp = await listReplicaPlans({ limit: 100 })
    rows.value = resp?.items ?? []
    const currentName = current.value?.policyName
    const target =
      (selectName && rows.value.find((p) => p.policyName === selectName)) ||
      (currentName && rows.value.find((p) => p.policyName === currentName)) ||
      rows.value[0]
    current.value = target ?? null
    await nextTick()
    renderChart()
  } catch (e) {
    error.value = `${t('karmada.loadFailed')}${(e as Error)?.message ?? ''}`
    rows.value = []
    current.value = null
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

function renderChart(): void {
  if (!current.value) return
  const chart = ensureChart(allocationRef.value)
  if (!chart) return
  const p = palette.value
  const allocation = parseJsonNumberRecord(current.value.allocation)
  const weights = parseJsonNumberRecord(current.value.weights)
  const clusters = Object.keys(allocation)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: p['--ds-text-secondary'] } },
    grid: { left: 40, right: 16, top: 24, bottom: 44 },
    xAxis: {
      type: 'category',
      data: clusters,
      axisLabel: { rotate: 20, color: p['--ds-text-secondary'] }
    },
    yAxis: { type: 'value', axisLabel: { color: p['--ds-text-secondary'] } },
    series: [
      {
        name: t('karmada.plans.seriesAllocated'),
        type: 'bar',
        itemStyle: { color: p['--ds-accent'] },
        data: clusters.map((k) => allocation[k])
      },
      {
        name: t('karmada.plans.seriesWeight'),
        type: 'line',
        itemStyle: { color: p['--ds-warning'] },
        data: clusters.map((k) => weights[k] ?? 0)
      }
    ]
  })
}

function select(plan: ReplicaWeightPlan): void {
  current.value = plan
  void nextTick(() => renderChart())
}

function openAdjust(plan: ReplicaWeightPlan): void {
  adjusting.value = plan
  adjustError.value = ''
  adjust.totalReplicas = plan.totalReplicas
  adjust.weights = Object.entries(parseJsonNumberRecord(plan.weights)).map(([cluster, weight]) => ({
    cluster,
    weight
  }))
}

function closeAdjust(): void {
  adjusting.value = null
  adjustError.value = ''
}

async function create(): Promise<void> {
  formError.value = ''
  formOk.value = ''
  if (!form.policyName.trim()) {
    formError.value = t('karmada.plans.needPolicyName')
    return
  }
  if (!form.workload.trim()) {
    formError.value = t('karmada.plans.needWorkload')
    return
  }
  const weights = weightsMap(form.weights)
  if (Object.keys(weights).length === 0) {
    formError.value = t('karmada.plans.needWeights')
    return
  }
  if (Object.values(weights).reduce((a, b) => a + b, 0) <= 0) {
    // 后端 failover.go:348-350 对"权重和为 0"给 400，本地先拦住
    formError.value = t('karmada.plans.needPositiveWeight')
    return
  }

  creating.value = true
  try {
    const created = await createReplicaPlan({
      policyName: form.policyName.trim(),
      workload: form.workload.trim(),
      totalReplicas: Math.trunc(Number(form.totalReplicas) || 0),
      weights,
      reason: form.reason.trim() || undefined
    })
    formOk.value = t('karmada.plans.createOk')
    reset()
    await load(created?.policyName)
  } catch (e) {
    formError.value = `${t('karmada.createFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    creating.value = false
  }
}

async function update(): Promise<void> {
  if (!adjusting.value) return
  adjustError.value = ''
  const weights = weightsMap(adjust.weights)
  updating.value = true
  try {
    const updated = await updateReplicaPlan(adjusting.value.policyName, {
      // totalReplicas 在后端是指针字段：不传即不改，所以只在有效正整数时才带上
      totalReplicas:
        Number(adjust.totalReplicas) > 0 ? Math.trunc(Number(adjust.totalReplicas)) : undefined,
      weights: Object.keys(weights).length ? weights : undefined,
      reason: 'manual'
    })
    closeAdjust()
    await load(updated?.policyName)
  } catch (e) {
    adjustError.value = `${t('karmada.saveFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    updating.value = false
  }
}

function handleResize(): void {
  for (const c of charts) c.resize()
}

onMounted(() => {
  void load()
  onThemeChange(() => renderChart())
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

.km-form {
  max-width: 720px;
}

.km-row {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: center;
  margin-bottom: var(--ds-spacing-2);
}

.km-hint {
  margin: var(--ds-spacing-1) 0 0;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.km-chart {
  height: 280px;
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
