<template>
  <div class="dashboard">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">集群健康看板</el-menu-item>
          <el-menu-item index="/override-policies">OverridePolicy 管理</el-menu-item>
          <el-menu-item index="/failover-history">迁移历史</el-menu-item>
          <el-menu-item index="/replica-plans">副本权重分配</el-menu-item>
          <el-menu-item index="/failover-policies">故障迁移策略</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <div class="page-container">
          <!-- 统计卡片 -->
          <div class="grid-3">
            <div class="card stat-card">
              <div class="stat-label">集群总数</div>
              <div class="stat-value">{{ stats.totalClusters }}</div>
            </div>
            <div class="card stat-card">
              <div class="stat-label">健康集群</div>
              <div class="stat-value" style="color: var(--color-success)">{{ stats.healthyClusters }}</div>
            </div>
            <div class="card stat-card">
              <div class="stat-label">异常集群</div>
              <div class="stat-value" style="color: var(--color-danger)">{{ stats.abnormalClusters }}</div>
            </div>
          </div>

          <!-- 集群健康概览 -->
          <div class="card">
            <div class="card-title">集群健康概览</div>
            <el-table :data="clusters" stripe style="width: 100%">
              <el-table-column prop="clusterName" label="集群名称" min-width="140" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span :class="`status-tag status-${row.status}`">{{ row.status }}</span>
                </template>
              </el-table-column>
              <el-table-column label="Ready" width="80">
                <template #default="{ row }">{{ row.ready ? '✓' : '✗' }}</template>
              </el-table-column>
              <el-table-column label="Syncable" width="90">
                <template #default="{ row }">{{ row.syncable ? '✓' : '✗' }}</template>
              </el-table-column>
              <el-table-column prop="nodeCount" label="节点数" width="80" />
              <el-table-column prop="podCount" label="Pod 数" width="80" />
              <el-table-column label="可用/最大副本" width="120">
                <template #default="{ row }">{{ row.availableReplicas }} / {{ row.maxReplicas }}</template>
              </el-table-column>
              <el-table-column label="检查来源" width="120">
                <template #default="{ row }">{{ row.checkSource }}</template>
              </el-table-column>
              <el-table-column label="检查时间" width="180">
                <template #default="{ row }">{{ formatTime(row.checkedAt) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div class="grid-2">
            <!-- CPU 负载仪表盘 -->
            <div class="card">
              <div class="card-title">集群 CPU 负载</div>
              <div ref="cpuGaugeRef" class="chart-container"></div>
            </div>

            <!-- 内存负载仪表盘 -->
            <div class="card">
              <div class="card-title">集群内存负载</div>
              <div ref="memGaugeRef" class="chart-container"></div>
            </div>
          </div>

          <div class="grid-2">
            <!-- 副本容量分布 -->
            <div class="card">
              <div class="card-title">副本容量分布</div>
              <div ref="capacityBarRef" class="chart-container"></div>
            </div>

            <!-- 集群状态饼图 -->
            <div class="card">
              <div class="card-title">集群状态分布</div>
              <div ref="statusPieRef" class="chart-container"></div>
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
import { listClusterHealth, type ClusterHealth } from '@/api/multiCluster'

const route = useRoute()
const activeMenu = ref(route.path)

const cpuGaugeRef = ref<HTMLElement>()
const memGaugeRef = ref<HTMLElement>()
const capacityBarRef = ref<HTMLElement>()
const statusPieRef = ref<HTMLElement>()
let cpuGauge: echarts.ECharts | null = null
let memGauge: echarts.ECharts | null = null
let capacityBar: echarts.ECharts | null = null
let statusPie: echarts.ECharts | null = null

const clusters = ref<ClusterHealth[]>([])
const stats = ref({
  totalClusters: 0,
  healthyClusters: 0,
  abnormalClusters: 0,
})

function formatTime(t: string): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadDashboard() {
  try {
    const resp = await listClusterHealth()
    clusters.value = resp.data.items || []
    stats.value.totalClusters = clusters.value.length
    stats.value.healthyClusters = clusters.value.filter((c) => c.status === 'healthy').length
    stats.value.abnormalClusters = clusters.value.filter(
      (c) => c.status === 'degraded' || c.status === 'down',
    ).length

    await nextTick()
    renderCpuGauge()
    renderMemGauge()
    renderCapacityBar()
    renderStatusPie()
  } catch (e) {
    console.error('加载看板失败:', e)
  }
}

function renderCpuGauge() {
  if (!cpuGaugeRef.value) return
  if (!cpuGauge) cpuGauge = echarts.init(cpuGaugeRef.value)

  const data = clusters.value.map((c) => ({
    name: c.clusterName,
    value: Number(c.cpuLoad?.toFixed(2)) || 0,
  }))

  cpuGauge.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c}%' },
    series: [
      {
        type: 'gauge',
        data,
        min: 0,
        max: 100,
        splitNumber: 10,
        detail: { formatter: '{value}%' },
        title: { fontSize: 12 },
      },
    ],
  })
}

function renderMemGauge() {
  if (!memGaugeRef.value) return
  if (!memGauge) memGauge = echarts.init(memGaugeRef.value)

  const data = clusters.value.map((c) => ({
    name: c.clusterName,
    value: Number(c.memoryLoad?.toFixed(2)) || 0,
  }))

  memGauge.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c}%' },
    series: [
      {
        type: 'gauge',
        data,
        min: 0,
        max: 100,
        splitNumber: 10,
        detail: { formatter: '{value}%' },
        title: { fontSize: 12 },
      },
    ],
  })
}

function renderCapacityBar() {
  if (!capacityBarRef.value) return
  if (!capacityBar) capacityBar = echarts.init(capacityBarRef.value)

  const names = clusters.value.map((c) => c.clusterName)
  capacityBar.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 20 } },
    yAxis: { type: 'value', name: '副本数' },
    series: [
      {
        name: '已用',
        type: 'bar',
        stack: 'total',
        data: clusters.value.map((c) => c.maxReplicas - c.availableReplicas),
        itemStyle: { color: '#e6a23c' },
      },
      {
        name: '可用',
        type: 'bar',
        stack: 'total',
        data: clusters.value.map((c) => c.availableReplicas),
        itemStyle: { color: '#67c23a' },
      },
    ],
  })
}

function renderStatusPie() {
  if (!statusPieRef.value) return
  if (!statusPie) statusPie = echarts.init(statusPieRef.value)

  const statusMap: Record<string, number> = {}
  for (const c of clusters.value) {
    statusMap[c.status] = (statusMap[c.status] || 0) + 1
  }

  statusPie.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: Object.entries(statusMap).map(([name, value]) => ({ name, value })),
        color: ['#67c23a', '#e6a23c', '#f56c6c'],
      },
    ],
  })
}

function handleResize() {
  cpuGauge?.resize()
  memGauge?.resize()
  capacityBar?.resize()
  statusPie?.resize()
}

onMounted(() => {
  loadDashboard()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  cpuGauge?.dispose()
  memGauge?.dispose()
  capacityBar?.dispose()
  statusPie?.dispose()
})
</script>