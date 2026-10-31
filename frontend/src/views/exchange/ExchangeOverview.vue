<template>
  <div
    class="ex-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('exchange.overview.title')"
  >
    <PageHeader :title="t('exchange.overview.title')" :subtitle="t('exchange.overview.subtitle')" />

    <section class="ex-toolbar">
      <el-button type="primary" :loading="loading" data-test="overview-reload" @click="load">
        {{ t('exchange.overview.actionReload') }}
      </el-button>
      <span class="ex-toolbar__hint">
        {{ t('exchange.overview.scopeHint', { tenant: tenant }) }}
      </span>
    </section>

    <!-- 不用 el-alert：EP 组件在本仓单测里靠显式 stub 才渲染（test-setup 的 stub 表没有
         ElAlert），一个"文案只有真浏览器里才出现"的错误态等于没测到。这里用自带 div +
         role="alert"，文案与 data-test 都在同一棵 DOM 里，单测与读屏都能拿到。 -->
    <div v-if="error" class="ex-error" role="alert" data-test="overview-error">{{ error }}</div>

    <div v-else-if="!loading && !rows.length" class="ex-card" data-test="overview-empty">
      <EmptyState :message="t('exchange.overview.empty')" />
    </div>

    <template v-else>
      <div class="ex-kpis">
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiSettlements') }}</div>
          <div class="ex-kpi__value" data-test="kpi-settlement-count">
            {{ summary.settlementCount }}
          </div>
        </div>
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiPeriods') }}</div>
          <div class="ex-kpi__value">{{ summary.periodCount }}</div>
        </div>
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiAssets') }}</div>
          <div class="ex-kpi__value">{{ summary.assetCount }}</div>
        </div>
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiProvider') }}</div>
          <div class="ex-kpi__value ex-kpi__value--money" data-test="kpi-provider-revenue">
            {{ money(summary.providerRevenue) }}
          </div>
        </div>
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiPlatform') }}</div>
          <div class="ex-kpi__value ex-kpi__value--money">{{ money(summary.platformRevenue) }}</div>
        </div>
        <div class="ex-card ex-kpi">
          <div class="ex-kpi__label">{{ t('exchange.overview.kpiTotal') }}</div>
          <div class="ex-kpi__value ex-kpi__value--money">{{ money(summary.totalAmount) }}</div>
        </div>
      </div>

      <div class="ex-charts">
        <PageCard :title="t('exchange.overview.chartTrend')">
          <div ref="trendRef" class="ex-chart" data-test="chart-trend"></div>
        </PageCard>
        <PageCard :title="t('exchange.overview.chartTopAssets')">
          <div ref="topRef" class="ex-chart" data-test="chart-top"></div>
        </PageCard>
      </div>

      <PageCard :title="t('exchange.overview.tableTitle')">
        <el-table :data="top" size="small" style="width: 100%">
          <el-table-column :label="t('exchange.colAsset')" min-width="160">
            <template #default="{ row }">{{ shortId(row.assetId) }}</template>
          </el-table-column>
          <el-table-column
            :label="t('exchange.overview.colSettlementCount')"
            width="120"
            prop="settlementCount"
          />
          <el-table-column :label="t('exchange.overview.colProviderRevenue')" width="150">
            <template #default="{ row }">{{ money(row.providerRevenue) }}</template>
          </el-table-column>
        </el-table>
      </PageCard>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { listTenantSettlements, type TenantSettlementRow } from '@/api/exchange'
import { money as fmtMoney, shortId } from './format'
import { summarize, topAssets, trendByPeriod } from './overview'

const { t, locale } = useI18n()

const rows = ref<TenantSettlementRow[]>([])
const tenant = ref('')
const loading = ref(false)
const error = ref('')

const summary = computed(() => summarize(rows.value))
const trend = computed(() => trendByPeriod(rows.value))
const top = computed(() => topAssets(rows.value, 5))

const trendRef = ref<HTMLElement>()
const topRef = ref<HTMLElement>()
let trendChart: echarts.ECharts | null = null
let topChart: echarts.ECharts | null = null

function money(v: number): string {
  return fmtMoney(v, locale.value)
}

/**
 * 图表取色一律走 --ds-* 令牌：硬编码色会被 scripts/check-hardcoded-colors.py 计入棘轮，
 * 而 ECharts 的 canvas 配色也必须跟随暗色档，否则同一页在两档主题下对比度不一样。
 * 取不到值时返回 undefined 让 ECharts 用它自己的默认色——**不写十六进制兜底**，
 * 那等于把兜底色焊死在 JS 里（同一份代码在 light/dark 下就会给出同一个颜色）。
 */
function token(name: string): string | undefined {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || undefined
}

function renderCharts(): void {
  if (!trendRef.value || !topRef.value) return
  trendChart = trendChart ?? echarts.init(trendRef.value)
  topChart = topChart ?? echarts.init(topRef.value)

  const accent = token('--ds-color-primary-600')
  const warm = token('--ds-color-warning-600')
  const ink = token('--ds-text-secondary')

  trendChart.setOption({
    textStyle: { color: ink },
    tooltip: { trigger: 'axis' },
    legend: {
      data: [t('exchange.overview.kpiProvider'), t('exchange.overview.kpiPlatform')],
      textStyle: { color: ink }
    },
    xAxis: { type: 'category', data: trend.value.map((p) => p.period), axisLabel: { color: ink } },
    yAxis: { type: 'value', axisLabel: { color: ink } },
    series: [
      {
        name: t('exchange.overview.kpiProvider'),
        type: 'line',
        smooth: true,
        data: trend.value.map((p) => p.providerRevenue),
        itemStyle: { color: accent }
      },
      {
        name: t('exchange.overview.kpiPlatform'),
        type: 'line',
        smooth: true,
        data: trend.value.map((p) => p.platformRevenue),
        itemStyle: { color: warm }
      }
    ]
  })

  topChart.setOption({
    textStyle: { color: ink },
    tooltip: { trigger: 'axis' },
    grid: { left: 12, right: 16, bottom: 8, containLabel: true },
    xAxis: { type: 'value', axisLabel: { color: ink } },
    yAxis: {
      type: 'category',
      data: [...top.value].reverse().map((a) => shortId(a.assetId)),
      axisLabel: { color: ink }
    },
    series: [
      {
        type: 'bar',
        data: [...top.value].reverse().map((a) => a.providerRevenue),
        itemStyle: { color: accent }
      }
    ]
  })
}

async function load(): Promise<void> {
  loading.value = true
  // 失败态与空态必须分开：旧实现把两者都渲染成"暂无数据"，于是"后端没打到"
  // 与"这个租户确实没结算"在 DOM 上不可区分（台账 #78 续 2 踩过一次）。
  error.value = ''
  try {
    const data = await listTenantSettlements({ limit: 500 })
    tenant.value = data.tenant
    rows.value = data.settlements ?? []
    await nextTick()
    renderCharts()
  } catch (e) {
    rows.value = []
    error.value = `${t('exchange.overview.loadFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    loading.value = false
  }
}

function handleResize(): void {
  trendChart?.resize()
  topChart?.resize()
}

/**
 * 主题切档后必须重取令牌色再画一次：图表文字用的是 --ds-text-secondary，
 * 实测 light=#273951 / dark=#adb5c2 —— 不重绘的话切换主题后坐标轴与图例仍是旧档的颜色，
 * 而这是 canvas，DOM 里看不出问题（仓内另外 5 个图表页都有这个既存缺口，见台账）。
 */
let themeObserver: MutationObserver | null = null

onMounted(() => {
  window.addEventListener('resize', handleResize)
  themeObserver = new MutationObserver(() => {
    void nextTick(() => renderCharts())
  })
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme']
  })
  void load()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  themeObserver?.disconnect()
  themeObserver = null
  trendChart?.dispose()
  topChart?.dispose()
  trendChart = null
  topChart = null
})
</script>

<style scoped>
.ex-page {
  padding: var(--ds-spacing-4);
}

.ex-toolbar {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-3);
  margin-bottom: var(--ds-spacing-4);
}

.ex-toolbar__hint {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.ex-error {
  margin-bottom: var(--ds-spacing-4);
  padding: var(--ds-spacing-3);
  border: 1px solid var(--ds-color-error-600);
  border-radius: var(--ds-radius-md-plus);
  background: var(--ds-bg-subtle);
  color: var(--ds-color-error-600);
  font-size: var(--ds-font-size-sm);
}

.ex-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--ds-spacing-3);
  margin-bottom: var(--ds-spacing-4);
}

.ex-card {
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md-plus);
  padding: var(--ds-spacing-4);
}

.ex-kpi__label {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.ex-kpi__value {
  font-size: var(--ds-font-size-3xl);
  font-weight: var(--ds-font-weight-bold);
  color: var(--ds-text-primary);
}

.ex-kpi__value--money {
  color: var(--ds-color-success-600);
}

.ex-charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: var(--ds-spacing-3);
  margin-bottom: var(--ds-spacing-4);
}

.ex-chart {
  height: 280px;
}
</style>
