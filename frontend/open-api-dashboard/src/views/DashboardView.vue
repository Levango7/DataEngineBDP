<template>
  <div class="dashboard-view">
    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总 API 数</div>
            <div class="stat-value">{{ stats.totalApis }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-item">
            <div class="stat-label">运行中 API</div>
            <div class="stat-value success">{{ stats.runningApis }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总调用量</div>
            <div class="stat-value primary">{{ formatNumber(stats.totalCalls) }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总费用(元)</div>
            <div class="stat-value warning">{{ stats.totalCost.toFixed(2) }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区 -->
    <el-row :gutter="16">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>调用量趋势</span>
              <el-radio-group v-model="timeRange" size="small" @change="loadMetrics">
                <el-radio-button value="1h">1 小时</el-radio-button>
                <el-radio-button value="24h">24 小时</el-radio-button>
                <el-radio-button value="7d">7 天</el-radio-button>
                <el-radio-button value="30d">30 天</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <v-chart :option="callTrendOption" autoresize style="height: 320px" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>
            <span>状态码分布</span>
          </template>
          <v-chart :option="statusPieOption" autoresize style="height: 320px" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span>延迟分布(ms)</span>
          </template>
          <v-chart :option="latencyBarOption" autoresize style="height: 300px" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span>计费策略占比</span>
          </template>
          <v-chart :option="billingPieOption" autoresize style="height: 300px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- Top API 表 -->
    <el-card shadow="never">
      <template #header>
        <span>调用量 Top 10</span>
      </template>
      <el-table :data="topApis" stripe>
        <el-table-column type="index" label="排名" width="70" />
        <el-table-column prop="name" label="API 名称" min-width="200" />
        <el-table-column prop="callCount" label="调用量" width="120" align="right" sortable />
        <el-table-column prop="errorCount" label="错误数" width="100" align="right" />
        <el-table-column prop="errorRate" label="错误率" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ 'error-rate': row.errorRate > 0.05 }">
              {{ (row.errorRate * 100).toFixed(2) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="avgLatency" label="平均延迟" width="120" align="right">
          <template #default="{ row }">
            {{ row.avgLatency.toFixed(1) }} ms
          </template>
        </el-table-column>
        <el-table-column prop="totalCost" label="费用" width="120" align="right">
          <template #default="{ row }">
            {{ row.totalCost.toFixed(2) }} 元
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import { listApis, getMetrics } from '@/api/catalog'

use([
  CanvasRenderer,
  LineChart,
  PieChart,
  BarChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
])

const timeRange = ref('7d')
const apis = ref([])
const metricsList = ref([])

const stats = reactive({
  totalApis: 0,
  runningApis: 0,
  totalCalls: 0,
  totalCost: 0,
})

const topApis = ref([])

// 调用量趋势图配置
const callTrendOption = computed(() => {
  const timeseries = metricsList.value[0]?.timeseries || []
  return {
    tooltip: {
      trigger: 'axis',
    },
    legend: {
      data: ['调用量', '错误量'],
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: timeseries.map((p) => formatTime(p.timestamp)),
    },
    yAxis: {
      type: 'value',
    },
    series: [
      {
        name: '调用量',
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.3 },
        data: timeseries.map((p) => p.callCount),
      },
      {
        name: '错误量',
        type: 'line',
        smooth: true,
        data: timeseries.map((p) => p.errorCount),
      },
    ],
  }
})

// 状态码饼图
const statusPieOption = computed(() => {
  const total = stats.totalCalls || 1
  const success = metricsList.value.reduce((sum, m) => sum + (m.successCount || 0), 0)
  const error = metricsList.value.reduce((sum, m) => sum + (m.errorCount || 0), 0)
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        label: { show: false },
        emphasis: { label: { show: true, fontSize: 14 } },
        data: [
          { value: success, name: '成功(2xx)', itemStyle: { color: '#67c23a' } },
          { value: error, name: '错误(4xx/5xx)', itemStyle: { color: '#f56c6c' } },
        ],
      },
    ],
  }
})

// 延迟柱状图
const latencyBarOption = computed(() => {
  const data = topApis.value.slice(0, 8)
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: data.map((d) => d.name),
      axisLabel: { rotate: 30 },
    },
    yAxis: { type: 'value', name: 'ms' },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.avgLatency),
        itemStyle: { color: '#409eff' },
      },
    ],
  }
})

// 计费策略饼图
const billingPieOption = computed(() => {
  const byCall = apis.value.filter((a) => a.costStrategy === 'by_call').length
  const byBytes = apis.value.filter((a) => a.costStrategy === 'by_bytes').length
  const monthly = apis.value.filter((a) => a.costStrategy === 'monthly_package').length
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: '60%',
        data: [
          { value: byCall, name: '按次', itemStyle: { color: '#409eff' } },
          { value: byBytes, name: '按量', itemStyle: { color: '#67c23a' } },
          { value: monthly, name: '月包', itemStyle: { color: '#e6a23c' } },
        ],
      },
    ],
  }
})

async function loadData() {
  try {
    const data = await listApis({ limit: 1000 })
    apis.value = Array.isArray(data) ? data : (data.items || [])
    stats.totalApis = apis.value.length
    stats.runningApis = apis.value.filter((a) => a.status === 'running').length
    stats.totalCalls = apis.value.reduce((sum, a) => sum + (a.callCount || 0), 0)

    // Top API
    topApis.value = apis.value
      .map((a) => ({
        name: a.name,
        callCount: a.callCount || 0,
        errorCount: a.errorCount || 0,
        errorRate: a.callCount ? (a.errorCount || 0) / a.callCount : 0,
        avgLatency: a.callCount ? a.totalLatencyMs / a.callCount : 0,
        totalCost: 0,
      }))
      .sort((a, b) => b.callCount - a.callCount)
      .slice(0, 10)

    await loadMetrics()
  } catch (err) {
    console.error('加载失败:', err)
  }
}

async function loadMetrics() {
  metricsList.value = []
  stats.totalCost = 0
  for (const api of apis.value.slice(0, 5)) {
    try {
      const m = await getMetrics(api.id, { range: timeRange.value })
      metricsList.value.push(m)
      stats.totalCost += m.totalCost || 0
    } catch (err) {
      // 忽略单个 API 计量查询失败
    }
  }
}

function formatNumber(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(2) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(2) + 'K'
  return String(n)
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:00`
}

onMounted(loadData)
</script>

<style scoped>
.dashboard-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.stat-row {
  margin-bottom: 0;
}
.stat-card {
  text-align: center;
}
.stat-item {
  padding: 8px 0;
}
.stat-label {
  font-size: 14px;
  color: #646a73;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #1f2329;
}
.stat-value.success {
  color: #67c23a;
}
.stat-value.primary {
  color: #409eff;
}
.stat-value.warning {
  color: #e6a23c;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.error-rate {
  color: #f56c6c;
  font-weight: 600;
}
</style>