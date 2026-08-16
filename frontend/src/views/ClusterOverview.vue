<template>
  <div class="cluster-page">
    <h1>集群概览</h1>
    <div class="sub">实时监控 Kubernetes 集群节点健康状态、资源使用率与大数据组件运行情况。</div>

    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card">
          <div class="stat-content">
            <div class="stat-label">总节点数</div>
            <div class="stat-value">{{ overview?.nodeTotal ?? '--' }}</div>
            <div class="stat-meta">
              就绪 {{ overview?.nodeReady ?? 0 }} · 异常
              {{ (overview?.nodeTotal ?? 0) - (overview?.nodeReady ?? 0) }}
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card">
          <div class="stat-content">
            <div class="stat-label">健康节点数</div>
            <div class="stat-value healthy">{{ overview?.nodeReady ?? '--' }}</div>
            <div class="stat-meta">健康率 {{ healthRate }}%</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card">
          <div class="stat-content">
            <div class="stat-label">CPU 使用率</div>
            <div class="stat-value" :class="usageLevel(cpuPercent)">{{ cpuPercent }}%</div>
            <div class="stat-meta">
              {{ overview?.cpuUsed ?? 0 }} / {{ overview?.cpuCapacity ?? 0 }} 核
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card">
          <div class="stat-content">
            <div class="stat-label">内存使用率</div>
            <div class="stat-value" :class="usageLevel(memPercent)">{{ memPercent }}%</div>
            <div class="stat-meta">
              {{ overview?.memUsed ?? 0 }} / {{ overview?.memCapacity ?? 0 }} GB
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 资源使用趋势图 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>资源使用趋势（近 7 日）</span>
          <el-tag type="info" effect="plain" size="small">CPU / 内存</el-tag>
        </div>
      </template>
      <div ref="trendChartRef" v-loading="overviewLoading" class="trend-chart"></div>
    </el-card>

    <!-- 节点列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>节点列表</span>
          <el-button :icon="Refresh" circle size="small" @click="loadNodes" />
        </div>
      </template>
      <el-table
        v-loading="nodesLoading"
        :data="nodeList"
        stripe
        border
        :empty-text="nodesError ? '节点列表加载失败，请重试' : '暂无节点'"
      >
        <el-table-column prop="name" label="节点名" min-width="180" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag
              v-for="role in row.roles"
              :key="role"
              :type="role === 'master' ? 'warning' : 'primary'"
              effect="light"
              size="small"
              style="margin-right: 4px"
            >
              {{ role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="nodeStatusType(row.status)" effect="light">
              {{ nodeStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU" width="160">
          <template #default="{ row }">
            <div class="cell-bar">
              <span>{{ row.cpuUsed }} / {{ row.cpuCapacity }} 核</span>
              <el-progress
                :percentage="nodeCpuPercent(row)"
                :stroke-width="6"
                :show-text="false"
                :color="usageColor(nodeCpuPercent(row))"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="内存" width="160">
          <template #default="{ row }">
            <div class="cell-bar">
              <span>{{ row.memUsed }} / {{ row.memCapacity }} GB</span>
              <el-progress
                :percentage="nodeMemPercent(row)"
                :stroke-width="6"
                :show-text="false"
                :color="usageColor(nodeMemPercent(row))"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Pod 数量" width="140" align="center">
          <template #default="{ row }">{{ row.podCount }} / {{ row.podCapacity }}</template>
        </el-table-column>
        <el-table-column prop="osImage" label="操作系统" min-width="160" />
      </el-table>
    </el-card>

    <!-- 组件状态 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>大数据组件状态</span>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col v-for="comp in components" :key="comp.name" :xs="12" :sm="8" :md="6" :lg="4">
          <div class="comp-card" :class="comp.status">
            <div class="comp-name">{{ comp.name }}</div>
            <div class="comp-status">
              <span class="dot"></span>
              {{ compStatusLabel(comp.status) }}
            </div>
            <div class="comp-meta">{{ comp.meta }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useApi } from '@/composables/useApi'
import * as clusterApi from '@/api/cluster'
import type { ClusterOverview, Node, NodeStatus } from '@/api/types'
import type { ComponentStatus } from '@/api/cluster'

/* ------------------------------ 集群概览 ------------------------------ */

// 集群概览：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: overview,
  loading: overviewLoading,
  execute: loadOverviewRaw
} = useApi<ClusterOverview>(() => clusterApi.getClusterOverview(), {
  onError: () => ElMessage.error('集群概览加载失败')
})

/** 拉取集群概览（数据就绪后渲染趋势图） */
async function loadOverview() {
  await loadOverviewRaw()
  if (overview.value) {
    await nextTick()
    renderTrendChart()
  }
}

/** CPU 使用率（百分比） */
const cpuPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.cpuCapacity || 1
  return Math.round((overview.value.cpuUsed / cap) * 100)
})

/** 内存使用率（百分比） */
const memPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.memCapacity || 1
  return Math.round((overview.value.memUsed / cap) * 100)
})

/** 健康率 */
const healthRate = computed(() => {
  if (!overview.value || !overview.value.nodeTotal) return 0
  return Math.round((overview.value.nodeReady / overview.value.nodeTotal) * 100)
})

/* ------------------------------ 节点列表 ------------------------------ */

// 节点列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: nodeList,
  loading: nodesLoading,
  error: nodesError,
  execute: loadNodes
} = useApi<Node[]>(() => clusterApi.listNodes(), {
  initialData: [],
  onError: () => ElMessage.error('节点列表加载失败')
})

/** 节点 CPU 使用率 */
function nodeCpuPercent(node: Node): number {
  if (!node.cpuCapacity) return 0
  return Math.round((node.cpuUsed / node.cpuCapacity) * 100)
}

/** 节点内存使用率 */
function nodeMemPercent(node: Node): number {
  if (!node.memCapacity) return 0
  return Math.round((node.memUsed / node.memCapacity) * 100)
}

/* ------------------------------ 资源趋势图 ------------------------------ */

const trendChartRef = ref<HTMLElement>()
let trendChart: echarts.ECharts | null = null

/** 渲染趋势图 */
function renderTrendChart() {
  if (!trendChartRef.value || !overview.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value)
  }
  const days = ['7天前', '6天前', '5天前', '4天前', '3天前', '2天前', '昨日']
  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        let html = `${params[0].axisValue}<br/>`
        params.forEach((p: any) => {
          html += `${p.marker}${p.seriesName}：${p.value}%<br/>`
        })
        return html
      }
    },
    legend: {
      data: ['CPU 使用率', '内存使用率'],
      right: 10,
      top: 0
    },
    grid: { left: 50, right: 30, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: days,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#717a80' }
    },
    yAxis: {
      type: 'value',
      max: 100,
      axisLabel: { formatter: '{value}%', color: '#717a80' },
      splitLine: { lineStyle: { color: '#e4e8ea' } }
    },
    series: [
      {
        name: 'CPU 使用率',
        type: 'line',
        smooth: true,
        data: overview.value.trendCpu,
        itemStyle: { color: '#2f6f6a' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(47, 111, 106, 0.25)' },
            { offset: 1, color: 'rgba(47, 111, 106, 0.02)' }
          ])
        },
        lineStyle: { width: 2 }
      },
      {
        name: '内存使用率',
        type: 'line',
        smooth: true,
        data: overview.value.trendMem,
        itemStyle: { color: '#c08a2e' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(192, 138, 46, 0.25)' },
            { offset: 1, color: 'rgba(192, 138, 46, 0.02)' }
          ])
        },
        lineStyle: { width: 2 }
      }
    ]
  })
}

/** 窗口大小变化时重绘图表 */
function handleResize() {
  trendChart?.resize()
}

/* ------------------------------ 组件状态 ------------------------------ */

// 大数据组件状态：通过 useApi 包装，失败时不阻塞页面
const {
  data: components,
  loading: componentsLoading,
  execute: loadComponents
} = useApi<ComponentStatus[]>(() => clusterApi.listComponentStatuses(), {
  initialData: []
})

/** 组件状态 → 中文 */
function compStatusLabel(status: ComponentStatus['status']): string {
  const map = { healthy: '健康', warning: '警告', error: '故障' }
  return map[status]
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 节点状态 → 中文 */
function nodeStatusLabel(status: NodeStatus): string {
  const map: Record<NodeStatus, string> = {
    ready: '就绪',
    'not-ready': '未就绪',
    unknown: '未知'
  }
  return map[status] || status
}

/** 节点状态 → tag 类型 */
function nodeStatusType(status: NodeStatus): 'success' | 'danger' | 'info' {
  const map: Record<NodeStatus, 'success' | 'danger' | 'info'> = {
    ready: 'success',
    'not-ready': 'danger',
    unknown: 'info'
  }
  return map[status] || 'info'
}

/** 使用率 → 颜色等级 */
function usageLevel(percent: number): string {
  if (percent >= 90) return 'danger'
  if (percent >= 70) return 'warning'
  return 'healthy'
}

/** 使用率 → 进度条颜色 */
function usageColor(percentage: number): string {
  if (percentage >= 90) return '#c0504d'
  if (percentage >= 70) return '#c08a2e'
  return '#2f9e6f'
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(() => {
  void loadOverview()
  void loadNodes()
  void loadComponents()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  trendChart = null
})
</script>

<style scoped>
.cluster-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.stat-row {
  margin-bottom: 0;
}
.stat-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  margin-bottom: 16px;
}
.stat-content {
  text-align: center;
  padding: 4px 0;
}
.stat-label {
  font-size: 13px;
  color: #717a80;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.stat-value.healthy {
  color: #2f9e6f;
}
.stat-value.warning {
  color: #c08a2e;
}
.stat-value.danger {
  color: #c0504d;
}
.stat-meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.trend-chart {
  width: 100%;
  height: 320px;
}
.cell-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #717a80;
}
.comp-card {
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: #fff;
  text-align: center;
}
.comp-card.healthy {
  border-color: #bbf7d0;
  background: #ecfdf5;
}
.comp-card.warning {
  border-color: #fbbf24;
  background: #fffbeb;
}
.comp-card.error {
  border-color: #c0504d;
  background: #fef2f2;
}
.comp-name {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
}
.comp-status {
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-bottom: 4px;
}
.comp-status .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}
.comp-card.healthy .comp-status {
  color: #2f9e6f;
}
.comp-card.warning .comp-status {
  color: #c08a2e;
}
.comp-card.error .comp-status {
  color: #c0504d;
}
.comp-meta {
  font-size: 11px;
  color: #717a80;
}
</style>
