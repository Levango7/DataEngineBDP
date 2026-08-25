<template>
  <div class="page">
    <h2>成本趋势</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-form :inline="true" style="margin-bottom: 16px">
      <el-form-item label="粒度">
        <el-select v-model="granularity" style="width: 120px" @change="onGranularityChange">
          <el-option label="小时" value="HOUR" />
          <el-option label="天" value="DAY" />
          <el-option label="月" value="MONTH" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-card shadow="never">
      <template #header>成本趋势折线图（按维度）</template>
      <EChart v-if="lineOption" :option="lineOption" height="500px" />
      <el-empty v-else description="暂无数据" />
    </el-card>
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>成本趋势堆叠面积图</template>
      <EChart v-if="stackOption" :option="stackOption" height="400px" />
      <el-empty v-else description="暂无数据" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChart from '@/components/EChart.vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { getCostTrend } from '@/api/finops'
import { formatTime } from '@/utils/format'
import type { CostTrendPoint } from '@/types'

const trendData = ref<CostTrendPoint[]>([])
const granularity = ref('HOUR')
const lastParams = ref<{ start: string; end: string; namespace?: string } | null>(null)
let fetchTimer: ReturnType<typeof setTimeout> | null = null

const lineOption = computed<EChartsOption | null>(() => {
  if (trendData.value.length === 0) return null
  const xData = trendData.value.map((p) => formatTime(p.timestamp, granularity.value))
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['总成本', 'CPU', '内存', '存储', 'GPU', '网络'] },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value', name: '成本（元）' },
    series: [
      { name: '总成本', type: 'line', data: trendData.value.map((p) => p.totalCost) },
      { name: 'CPU', type: 'line', data: trendData.value.map((p) => p.cpuCost) },
      { name: '内存', type: 'line', data: trendData.value.map((p) => p.memoryCost) },
      { name: '存储', type: 'line', data: trendData.value.map((p) => p.storageCost) },
      { name: 'GPU', type: 'line', data: trendData.value.map((p) => p.gpuCost) },
      { name: '网络', type: 'line', data: trendData.value.map((p) => p.networkCost) }
    ]
  }
})

const stackOption = computed<EChartsOption | null>(() => {
  if (trendData.value.length === 0) return null
  const xData = trendData.value.map((p) => formatTime(p.timestamp, granularity.value))
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU', '内存', '存储', 'GPU', '网络'] },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value', name: '成本（元）' },
    series: [
      { name: 'CPU', type: 'line', stack: 'total', areaStyle: {}, data: trendData.value.map((p) => p.cpuCost) },
      { name: '内存', type: 'line', stack: 'total', areaStyle: {}, data: trendData.value.map((p) => p.memoryCost) },
      { name: '存储', type: 'line', stack: 'total', areaStyle: {}, data: trendData.value.map((p) => p.storageCost) },
      { name: 'GPU', type: 'line', stack: 'total', areaStyle: {}, data: trendData.value.map((p) => p.gpuCost) },
      { name: '网络', type: 'line', stack: 'total', areaStyle: {}, data: trendData.value.map((p) => p.networkCost) }
    ]
  }
})

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  lastParams.value = params
  await fetchData()
}

async function fetchData() {
  if (!lastParams.value) return
  try {
    const resp = await getCostTrend({ ...lastParams.value, granularity: granularity.value })
    trendData.value = resp.items
  } catch (e) {
    console.error('查询趋势失败', e)
  }
}

function onGranularityChange() {
  if (fetchTimer !== null) clearTimeout(fetchTimer)
  fetchTimer = setTimeout(() => {
    fetchTimer = null
    void fetchData()
  }, 300)
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
</style>