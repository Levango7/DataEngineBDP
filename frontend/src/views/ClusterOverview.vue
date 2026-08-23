<template>
  <div class="cluster-page" role="main" aria-label="集群概览页面">
    <h1>集群概览</h1>
    <div class="sub">实时监控 Kubernetes 集群节点健康状态、资源使用率与大数据组件运行情况。</div>

    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" class="stat-row" role="region" aria-label="集群统计卡片">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card" role="region" aria-label="总节点数">
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
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card" role="region" aria-label="健康节点数">
          <div class="stat-content">
            <div class="stat-label">健康节点数</div>
            <div class="stat-value healthy">{{ overview?.nodeReady ?? '--' }}</div>
            <div class="stat-meta">健康率 {{ healthRate }}%</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card" role="region" aria-label="CPU 使用率">
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
        <el-card v-loading="overviewLoading" shadow="never" class="stat-card" role="region" aria-label="内存使用率">
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
    <el-card shadow="never" class="page-card" style="margin-top: 16px" role="region" aria-label="资源使用趋势">
      <template #header>
        <div class="card-header">
          <span>资源使用趋势（近 7 日）</span>
          <el-tag type="info" effect="plain" size="small">CPU / 内存</el-tag>
        </div>
      </template>
      <div ref="trendChartRef" v-loading="overviewLoading" class="trend-chart" role="img" aria-label="资源使用趋势图表"></div>
    </el-card>

    <!-- 节点列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px" role="region" aria-label="节点列表">
      <template #header>
        <div class="card-header">
          <span>节点列表</span>
          <el-button :icon="Refresh" circle size="small" aria-label="刷新节点列表" @click="loadNodes" />
        </div>
      </template>
      <el-table
        v-loading="nodesLoading"
        :data="nodeList"
        stripe
        border
        role="table"
        aria-label="节点列表表格"
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
    <el-card shadow="never" class="page-card" style="margin-top: 16px" role="region" aria-label="大数据组件状态">
      <template #header>
        <div class="card-header">
          <span>大数据组件状态</span>
        </div>
      </template>
      <el-row :gutter="12" role="list" aria-label="大数据组件状态列表">
        <el-col v-for="comp in components" :key="comp.name" :xs="12" :sm="8" :md="6" :lg="4">
          <div class="comp-card" :class="comp.status" role="listitem" :aria-label="`组件 ${comp.name} 状态：${compStatusLabel(comp.status)}`">
            <div class="comp-name">{{ comp.name }}</div>
            <div class="comp-status">
              <span class="dot" aria-hidden="true"></span>
              {{ compStatusLabel(comp.status) }}
            </div>
            <div class="comp-meta">{{ comp.meta }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 集群资源配置：网络 / 存储 / HPA -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px" role="region" aria-label="集群资源配置">
      <template #header>
        <div class="card-header">
          <span>集群资源配置</span>
          <el-select v-model="selectedEnv" size="small" style="width: 120px; margin-right: 8px" aria-label="环境选择" @change="loadClusterResources">
            <el-option label="信创" value="xinchuang" />
            <el-option label="私有" value="private" />
            <el-option label="公有" value="cloud" />
          </el-select>
          <el-input v-model="selectedClusterId" size="small" style="width: 180px" placeholder="集群 ID" aria-label="集群 ID" @change="loadClusterResources" />
        </div>
      </template>
      <el-tabs v-model="resourceTab" role="tablist" aria-label="集群资源配置分类" @tab-change="loadClusterResources">
        <!-- 网络配置 Tab -->
        <el-tab-pane label="网络配置" name="network">
          <div v-if="networkLoading" class="tab-loading" role="status" aria-live="polite">加载中…</div>
          <div v-else-if="networkError" class="tab-error" role="alert">加载失败：{{ networkError.message }}</div>
          <div v-else-if="networkConfig">
            <el-descriptions :column="4" border size="small" style="margin-bottom: 12px">
              <el-descriptions-item label="Pod CIDR">{{ networkConfig.podCidr }}</el-descriptions-item>
              <el-descriptions-item label="Service CIDR">{{ networkConfig.serviceCidr }}</el-descriptions-item>
              <el-descriptions-item label="CNI">{{ networkConfig.cni }}</el-descriptions-item>
              <el-descriptions-item label="MTU">{{ networkConfig.mtu }}</el-descriptions-item>
            </el-descriptions>
            <h4>NetworkPolicy 列表（{{ networkConfig.policies?.length ?? 0 }}）</h4>
            <el-table :data="networkConfig.policies || []" stripe size="small" border role="table" aria-label="NetworkPolicy 列表" empty-text="暂无 NetworkPolicy">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column prop="namespace" label="命名空间" min-width="100" />
              <el-table-column prop="type" label="类型" width="80" />
              <el-table-column prop="selector" label="选择器" min-width="120" />
            </el-table>
            <h4 style="margin-top: 12px">Service 列表（{{ networkConfig.services?.length ?? 0 }}）</h4>
            <el-table :data="networkConfig.services || []" stripe size="small" border role="table" aria-label="Service 列表" empty-text="暂无 Service">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column prop="namespace" label="命名空间" min-width="100" />
              <el-table-column prop="type" label="类型" width="80" />
              <el-table-column prop="clusterIP" label="ClusterIP" min-width="120" />
              <el-table-column prop="ports" label="端口数" width="80" align="center" />
            </el-table>
            <h4 style="margin-top: 12px">Ingress 列表（{{ networkConfig.ingresses?.length ?? 0 }}）</h4>
            <el-table :data="networkConfig.ingresses || []" stripe size="small" border role="table" aria-label="Ingress 列表" empty-text="暂无 Ingress">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column prop="namespace" label="命名空间" min-width="100" />
              <el-table-column prop="className" label="IngressClass" min-width="100" />
              <el-table-column prop="hosts" label="主机数" width="80" align="center" />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- 存储配置 Tab -->
        <el-tab-pane label="存储配置" name="storage">
          <div v-if="storageLoading" class="tab-loading" role="status" aria-live="polite">加载中…</div>
          <div v-else-if="storageError" class="tab-error" role="alert">加载失败：{{ storageError.message }}</div>
          <div v-else>
            <h4>StorageClass 列表（{{ storageClasses?.length ?? 0 }}）</h4>
            <el-table :data="storageClasses || []" stripe size="small" border role="table" aria-label="StorageClass 列表" empty-text="暂无 StorageClass">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column prop="provisioner" label="Provisioner" min-width="160" />
              <el-table-column prop="reclaimPolicy" label="回收策略" width="100" />
              <el-table-column label="默认" width="60" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.default" type="success" size="small">是</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <h4 style="margin-top: 12px">PVC 列表（{{ pvcs?.length ?? 0 }}）</h4>
            <el-table :data="pvcs || []" stripe size="small" border role="table" aria-label="PVC 列表" empty-text="暂无 PVC">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column prop="namespace" label="命名空间" min-width="100" />
              <el-table-column prop="storageClassName" label="StorageClass" min-width="100" />
              <el-table-column prop="capacity" label="容量" width="100" />
              <el-table-column prop="status" label="状态" width="80" />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- HPA 配置 Tab -->
        <el-tab-pane label="HPA 自动伸缩" name="hpa">
          <div v-if="hpaLoading" class="tab-loading" role="status" aria-live="polite">加载中…</div>
          <div v-else-if="hpaError" class="tab-error" role="alert">加载失败：{{ hpaError.message }}</div>
          <el-table v-else :data="hpas || []" stripe size="small" border role="table" aria-label="HPA 自动伸缩策略列表" empty-text="暂无 HPA 策略">
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="namespace" label="命名空间" min-width="100" />
            <el-table-column prop="targetDeployment" label="目标 Deployment" min-width="120" />
            <el-table-column prop="minReplicas" label="最小副本" width="90" align="center" />
            <el-table-column prop="maxReplicas" label="最大副本" width="90" align="center" />
            <el-table-column prop="currentReplicas" label="当前副本" width="90" align="center" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
                  {{ row.status === 'active' ? '活跃' : '暂停' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
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
import * as infraApi from '@/api/infra'
import type { ClusterOverview, Node, NodeStatus } from '@/api/types'
import type { ComponentStatus } from '@/api/cluster'
import type { ClusterEnv, NetworkConfig, StorageClass, PersistentVolumeClaim, HpaPolicy } from '@/api/infra'

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
      formatter: (params: unknown[]) => {
        const series = params as Array<Record<string, unknown>>
        let html = `${series[0]?.axisValue as string}<br/>`
        for (const p of series) {
          html += `${p.marker as string}${p.seriesName as string}：${p.value as number}%<br/>`
        }
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

/* ------------------------------ 集群资源配置（网络/存储/HPA） ------------------------------ */

const resourceTab = ref<'network' | 'storage' | 'hpa'>('network')
const selectedEnv = ref<ClusterEnv>('xinchuang')
const selectedClusterId = ref('default')

// 网络配置
const {
  data: networkConfig,
  loading: networkLoading,
  error: networkError,
  execute: loadNetworkConfig
} = useApi<NetworkConfig>(
  () => infraApi.getNetworkConfig(selectedEnv.value, selectedClusterId.value),
  { initialData: null as unknown as NetworkConfig }
)

// StorageClass 列表
const {
  data: storageClasses,
  loading: storageLoading,
  error: storageError,
  execute: loadStorageClasses
} = useApi<StorageClass[]>(
  () => infraApi.getStorageClasses(selectedEnv.value, selectedClusterId.value),
  { initialData: [] }
)

// PVC 列表
const {
  data: pvcs,
  loading: pvcLoading,
  execute: loadPvcs
} = useApi<PersistentVolumeClaim[]>(
  () => infraApi.getPersistentVolumes(selectedEnv.value, selectedClusterId.value),
  { initialData: [] }
)

// HPA 列表
const {
  data: hpas,
  loading: hpaLoading,
  error: hpaError,
  execute: loadHpas
} = useApi<HpaPolicy[]>(
  () => infraApi.getHpas(selectedEnv.value, selectedClusterId.value),
  { initialData: [] }
)

/** 加载当前 Tab 的集群资源数据 */
async function loadClusterResources(): Promise<void> {
  if (!selectedClusterId.value) return
  switch (resourceTab.value) {
    case 'network':
      await loadNetworkConfig()
      break
    case 'storage':
      await Promise.all([loadStorageClasses(), loadPvcs()])
      break
    case 'hpa':
      await loadHpas()
      break
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 节点状态 → 中文 */
const NODE_STATUS_MAP: Record<NodeStatus, { label: string; type: 'success' | 'danger' | 'info' }> = {
  ready: { label: '就绪', type: 'success' },
  'not-ready': { label: '未就绪', type: 'danger' },
  unknown: { label: '未知', type: 'info' },
}

function nodeStatusLabel(status: NodeStatus): string {
  return NODE_STATUS_MAP[status]?.label ?? status
}

function nodeStatusType(status: NodeStatus): 'success' | 'danger' | 'info' {
  return NODE_STATUS_MAP[status]?.type ?? 'info'
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
  void loadClusterResources()
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
.tab-loading {
  color: #717a80;
  text-align: center;
  padding: 20px;
}
.tab-error {
  color: #c0504d;
  text-align: center;
  padding: 20px;
}
</style>
