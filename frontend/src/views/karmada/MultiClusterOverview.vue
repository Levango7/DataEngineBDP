<template>
  <div
    class="km-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('karmada.overview.title')"
  >
    <PageHeader :title="t('karmada.overview.title')" :subtitle="t('karmada.overview.subtitle')">
      <template #actions>
        <el-button :loading="loading" @click="load">{{ t('karmada.actionRefresh') }}</el-button>
      </template>
    </PageHeader>

    <p v-if="error" class="km-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('karmada.actionRetry') }}</el-button>
    </p>

    <section class="km-stats">
      <StatCard :label="t('karmada.overview.statTotalClusters')" :value="clusters.length" />
      <StatCard :label="t('karmada.overview.statHealthy')" :value="healthyCount" />
      <StatCard :label="t('karmada.overview.statAbnormal')" :value="abnormalCount" />
    </section>

    <PageCard :title="t('karmada.overview.tableTitle')">
      <el-table v-if="clusters.length" v-loading="loading" :data="clusters" stripe>
        <el-table-column
          :label="t('karmada.overview.colCluster')"
          min-width="150"
          prop="clusterName"
        />
        <el-table-column :label="t('karmada.colStatus')" width="110">
          <template #default="{ row }">
            <StatusTag
              :status="row.status"
              :label="row.status"
              :status-map="KARMADA_CLUSTER_STATUS_MAP"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.overview.colReady')" width="90">
          <template #default="{ row }">{{ row.ready ? yesText : noText }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.overview.colSyncable')" width="100">
          <template #default="{ row }">{{ row.syncable ? yesText : noText }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.overview.colNodes')" width="90" prop="nodeCount" />
        <el-table-column :label="t('karmada.overview.colPods')" width="90" prop="podCount" />
        <el-table-column :label="t('karmada.overview.colReplicas')" width="120">
          <template #default="{ row }">
            {{ replicaPair(row.availableReplicas, row.maxReplicas) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.overview.colLoad')" width="150">
          <template #default="{ row }">
            {{ t('karmada.overview.loadCpu') }} {{ loadPercent(row.cpuLoad) }} ·
            {{ t('karmada.overview.loadMem') }} {{ loadPercent(row.memoryLoad) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('karmada.overview.colCheckSource')"
          width="130"
          prop="checkSource"
        />
        <el-table-column :label="t('karmada.overview.colCheckedAt')" width="190">
          <template #default="{ row }">
            <span class="km-mono">{{ formatTime(row.checkedAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="t('karmada.overview.emptyClusters')" />
    </PageCard>

    <section v-if="clusters.length" class="km-charts">
      <PageCard :title="t('karmada.overview.chartCapacityTitle')">
        <div ref="capacityRef" class="km-chart" />
      </PageCard>
      <PageCard :title="t('karmada.overview.chartStatusTitle')">
        <div ref="statusRef" class="km-chart" />
      </PageCard>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { formatTime as fmtTime } from '../exchange/format'
import { KARMADA_CLUSTER_STATUS_MAP, loadPercent, replicaPair } from './format'
import { useChartPalette } from './useChartPalette'
import { listClusterHealth, type ClusterHealth } from '@/api/karmada'

const { t, locale } = useI18n()

const clusters = ref<ClusterHealth[]>([])
const loading = ref(false)
const error = ref('')

const capacityRef = ref<HTMLElement>()
const statusRef = ref<HTMLElement>()
const charts: echarts.ECharts[] = []
const { palette, onThemeChange } = useChartPalette()

const healthyCount = computed(() => clusters.value.filter((c) => c.status === 'healthy').length)
const abnormalCount = computed(
  () => clusters.value.filter((c) => c.status === 'degraded' || c.status === 'down').length
)
const yesText = computed(() => t('karmada.yes'))
const noText = computed(() => t('karmada.no'))

// 后端只有"所有集群最新健康"这一个可达端点：带路径参数的
// GET /clusters/{name}/health 归 infra-orchestrator 的粗前缀，网关到不了 failover-api
// （台账 #89），所以这里不画单集群历史曲线，改由「迁移历史」页按集群过滤 /failover-events。
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const resp = await listClusterHealth()
    clusters.value = resp?.items ?? []
    await nextTick()
    renderCharts()
  } catch (e) {
    // 必须把后端原因摊出来：静默回落成空数组会让"服务挂了"和"没有集群"长得一模一样
    error.value = `${t('karmada.loadFailed')}${(e as Error)?.message ?? ''}`
    clusters.value = []
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

function renderCharts(): void {
  const p = palette.value

  const capacity = ensureChart(capacityRef.value)
  capacity?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: p['--ds-text-secondary'] } },
    grid: { left: 40, right: 16, top: 24, bottom: 40 },
    xAxis: {
      type: 'category',
      data: clusters.value.map((c) => c.clusterName),
      axisLabel: { rotate: 20, color: p['--ds-text-secondary'] }
    },
    yAxis: { type: 'value', axisLabel: { color: p['--ds-text-secondary'] } },
    series: [
      {
        name: t('karmada.overview.chartSeriesUsed'),
        type: 'bar',
        stack: 'replicas',
        itemStyle: { color: p['--ds-warning'] },
        data: clusters.value.map((c) =>
          Math.max((c.maxReplicas ?? 0) - (c.availableReplicas ?? 0), 0)
        )
      },
      {
        name: t('karmada.overview.chartSeriesAvailable'),
        type: 'bar',
        stack: 'replicas',
        itemStyle: { color: p['--ds-success'] },
        data: clusters.value.map((c) => c.availableReplicas ?? 0)
      }
    ]
  })

  const statusMap: Record<string, number> = {}
  for (const c of clusters.value) statusMap[c.status] = (statusMap[c.status] ?? 0) + 1
  const colorOf: Record<string, string> = {
    healthy: p['--ds-success'],
    degraded: p['--ds-warning'],
    down: p['--ds-danger']
  }

  const status = ensureChart(statusRef.value)
  status?.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: p['--ds-text-secondary'] } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: Object.entries(statusMap).map(([name, value]) => ({
          name,
          value,
          itemStyle: { color: colorOf[name] ?? p['--ds-info'] }
        }))
      }
    ]
  })
}

function handleResize(): void {
  for (const c of charts) c.resize()
}

onMounted(() => {
  void load()
  onThemeChange(() => renderCharts())
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  for (const c of charts) c.dispose()
  charts.length = 0
})

const formatTime = (iso: string | null | undefined): string => fmtTime(iso, locale.value)
</script>

<style scoped>
.km-page {
  padding: var(--ds-spacing-4);
}

.km-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ds-spacing-3);
  margin-bottom: var(--ds-spacing-4);
}

.km-charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ds-spacing-4);
  margin-top: var(--ds-spacing-4);
}

.km-chart {
  height: 280px;
}

.km-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.km-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
