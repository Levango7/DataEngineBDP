<template>
  <div class="dashboard">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">流通看板</el-menu-item>
          <el-menu-item index="/assets">资产市场</el-menu-item>
          <el-menu-item index="/register">资产登记</el-menu-item>
          <el-menu-item index="/settlements">结算分账</el-menu-item>
          <el-menu-item index="/audit-logs">审计日志</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <div class="page-container">
          <!-- 统计卡片 -->
          <div class="grid-3">
            <div class="card stat-card">
              <div class="stat-label">资产总数</div>
              <div class="stat-value">{{ stats.totalAssets }}</div>
            </div>
            <div class="card stat-card">
              <div class="stat-label">累计收益（元）</div>
              <div class="stat-value">{{ stats.totalRevenue.toFixed(2) }}</div>
            </div>
            <div class="card stat-card">
              <div class="stat-label">活跃订阅数</div>
              <div class="stat-value">{{ stats.activeSubscriptions }}</div>
            </div>
          </div>

          <!-- 资产 Top N 排行 -->
          <div class="card">
            <div class="card-title">资产 Top N 排行（按收益）</div>
            <div ref="topNChartRef" class="chart-container"></div>
          </div>

          <div class="grid-2">
            <!-- 流通趋势图 -->
            <div class="card">
              <div class="card-title">流通趋势图（近 6 个月）</div>
              <div ref="trendChartRef" class="chart-container"></div>
            </div>

            <!-- 收益分布 -->
            <div class="card">
              <div class="card-title">收益分布（按定价方式）</div>
              <div ref="revenuePieRef" class="chart-container"></div>
            </div>
          </div>

          <div class="grid-2">
            <!-- 收益明细 -->
            <div class="card">
              <div class="card-title">收益明细</div>
              <el-table :data="revenueDetails" stripe size="small" style="width: 100%">
                <el-table-column prop="assetName" label="资产名称" min-width="120" />
                <el-table-column prop="period" label="周期" width="100" />
                <el-table-column prop="totalAmount" label="总金额" width="100">
                  <template #default="{ row }">{{ row.totalAmount.toFixed(2) }}</template>
                </el-table-column>
                <el-table-column prop="providerRevenue" label="提供方收益" width="110">
                  <template #default="{ row }">{{ row.providerRevenue.toFixed(2) }}</template>
                </el-table-column>
                <el-table-column prop="platformRevenue" label="平台抽成" width="100">
                  <template #default="{ row }">{{ row.platformRevenue.toFixed(2) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <!-- 分账明细 -->
            <div class="card">
              <div class="card-title">分账明细</div>
              <el-table :data="allocationDetails" stripe size="small" style="width: 100%">
                <el-table-column prop="assetId" label="资产 ID" min-width="120">
                  <template #default="{ row }">{{ row.assetId.slice(0, 8) }}</template>
                </el-table-column>
                <el-table-column prop="providerAmount" label="提供方" width="100">
                  <template #default="{ row }">{{ row.providerAmount.toFixed(2) }}</template>
                </el-table-column>
                <el-table-column prop="platformAmount" label="平台" width="100">
                  <template #default="{ row }">{{ row.platformAmount.toFixed(2) }}</template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="100" />
              </el-table>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import {
  listAssets,
  getAssetBilling,
  listSettlements,
  listAllocations,
  type Asset,
  type Settlement,
  type Allocation,
} from '@/api/assetExchange'

const route = useRoute()
const activeMenu = ref(route.path)

const topNChartRef = ref<HTMLElement>()
const trendChartRef = ref<HTMLElement>()
const revenuePieRef = ref<HTMLElement>()
let topNChart: echarts.ECharts | null = null
let trendChart: echarts.ECharts | null = null
let revenuePieChart: echarts.ECharts | null = null

const stats = ref({
  totalAssets: 0,
  totalRevenue: 0,
  activeSubscriptions: 0,
})

const revenueDetails = ref<Array<Settlement & { assetName: string }>>([])
const allocationDetails = ref<Allocation[]>([])

async function loadDashboard() {
  try {
    const resp = await listAssets({ limit: 100 })
    const assets: Asset[] = resp.data
    stats.value.totalAssets = assets.length

    // 收集每个资产的计费与结算
    const allSettlements: Settlement[] = []
    const allAllocations: Allocation[] = []
    let totalRevenue = 0
    const assetRevenueMap: Record<string, number> = {}

    for (const asset of assets) {
      try {
        const billingResp = await getAssetBilling(asset.id)
        totalRevenue += billingResp.data.totalAmount
        assetRevenueMap[asset.name] = billingResp.data.totalAmount

        const settleResp = await listSettlements(asset.id)
        allSettlements.push(...settleResp.data)

        const allocResp = await listAllocations(asset.id)
        allAllocations.push(...allocResp.data)
      } catch {
        // 单个资产查询失败不影响整体
      }
    }

    stats.value.totalRevenue = totalRevenue
    stats.value.activeSubscriptions = assets.reduce(
      (sum, a) => sum + a.subscriberCount,
      0
    )

    // 收益明细
    revenueDetails.value = allSettlements
      .slice(0, 20)
      .map((s) => ({
        ...s,
        assetName: assets.find((a) => a.id === s.assetId)?.name || s.assetId.slice(0, 8),
      }))

    // 分账明细
    allocationDetails.value = allAllocations.slice(0, 20)

    // 渲染图表
    await nextTick()
    renderTopNChart(assetRevenueMap)
    renderTrendChart(allSettlements)
    renderRevenuePie(assets)
  } catch (e) {
    console.error('加载看板失败:', e)
  }
}

function renderTopNChart(assetRevenueMap: Record<string, number>) {
  if (!topNChartRef.value) return
  if (!topNChart) topNChart = echarts.init(topNChartRef.value)

  const sorted = Object.entries(assetRevenueMap)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)

  topNChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: sorted.map(([name]) => name.length > 10 ? name.slice(0, 10) + '...' : name),
      axisLabel: { rotate: 30 },
    },
    yAxis: { type: 'value', name: '收益（元）' },
    series: [
      {
        type: 'bar',
        data: sorted.map(([, v]) => v),
        itemStyle: { color: '#409eff' },
      },
    ],
  })
}

function renderTrendChart(settlements: Settlement[]) {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)

  // 按周期聚合
  const periodMap: Record<string, number> = {}
  for (const s of settlements) {
    periodMap[s.period] = (periodMap[s.period] || 0) + s.totalAmount
  }
  const periods = Object.keys(periodMap).sort().slice(-6)

  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: periods },
    yAxis: { type: 'value', name: '收益（元）' },
    series: [
      {
        type: 'line',
        data: periods.map((p) => periodMap[p]),
        smooth: true,
        areaStyle: { opacity: 0.3 },
        itemStyle: { color: '#67c23a' },
      },
    ],
  })
}

function renderRevenuePie(assets: Asset[]) {
  if (!revenuePieRef.value) return
  if (!revenuePieChart) revenuePieChart = echarts.init(revenuePieRef.value)

  const modeMap: Record<string, number> = {}
  for (const a of assets) {
    const mode = a.pricing?.mode || 'unknown'
    modeMap[mode] = (modeMap[mode] || 0) + 1
  }

  revenuePieChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: Object.entries(modeMap).map(([name, value]) => ({ name, value })),
      },
    ],
  })
}

function handleResize() {
  topNChart?.resize()
  trendChart?.resize()
  revenuePieChart?.resize()
}

onMounted(() => {
  loadDashboard()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  topNChart?.dispose()
  trendChart?.dispose()
  revenuePieChart?.dispose()
})
</script>