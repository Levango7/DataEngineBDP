<template>
  <div class="cluster-page" role="main" :aria-label="t('clusterOverview.title')">
    <h1>{{ t('clusterOverview.title') }}</h1>
    <div class="sub">{{ t('clusterOverview.subtitle') }}</div>

    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" class="stat-row" role="region" :aria-label="t('clusterOverview.title')">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card
          v-loading="overviewLoading"
          shadow="never"
          class="stat-card"
          role="region"
          :aria-label="t('clusterOverview.kpi.nodeTotal')"
        >
          <div class="stat-content">
            <div class="stat-label">{{ t('clusterOverview.kpi.nodeTotal') }}</div>
            <div class="stat-value">{{ overview?.nodeTotal ?? '--' }}</div>
            <div class="stat-meta">
              {{
                t('clusterOverview.kpi.nodeTotalMeta', {
                  ready: overview?.nodeReady ?? 0,
                  abnormal: (overview?.nodeTotal ?? 0) - (overview?.nodeReady ?? 0)
                })
              }}
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card
          v-loading="overviewLoading"
          shadow="never"
          class="stat-card"
          role="region"
          :aria-label="t('clusterOverview.kpi.nodeReady')"
        >
          <div class="stat-content">
            <div class="stat-label">{{ t('clusterOverview.kpi.nodeReady') }}</div>
            <div class="stat-value healthy">{{ overview?.nodeReady ?? '--' }}</div>
            <div class="stat-meta">
              {{ t('clusterOverview.kpi.nodeReadyMeta', { rate: healthRate }) }}
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card
          v-loading="overviewLoading"
          shadow="never"
          class="stat-card"
          role="region"
          :aria-label="t('clusterOverview.kpi.cpu')"
        >
          <div class="stat-content">
            <div class="stat-label">{{ t('clusterOverview.kpi.cpu') }}</div>
            <div class="stat-value" :class="usageLevel(cpuPercent)">{{ cpuPercent }}%</div>
            <div class="stat-meta">
              {{ overview?.cpuUsed ?? 0 }} / {{ overview?.cpuCapacity ?? 0 }}
              {{ t('clusterOverview.kpi.cpuUnit') }}
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card
          v-loading="overviewLoading"
          shadow="never"
          class="stat-card"
          role="region"
          :aria-label="t('clusterOverview.kpi.mem')"
        >
          <div class="stat-content">
            <div class="stat-label">{{ t('clusterOverview.kpi.mem') }}</div>
            <div class="stat-value" :class="usageLevel(memPercent)">{{ memPercent }}%</div>
            <div class="stat-meta">
              {{ overview?.memUsed ?? 0 }} / {{ overview?.memCapacity ?? 0 }}
              {{ t('clusterOverview.kpi.memUnit') }}
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 资源使用趋势图 -->
    <el-card
      shadow="never"
      class="page-card"
      style="margin-top: 16px"
      role="region"
      :aria-label="t('clusterOverview.trend.title')"
    >
      <template #header>
        <div class="card-header">
          <span>{{ t('clusterOverview.trend.title') }}</span>
          <el-tag type="info" effect="plain" size="small">
            {{ t('clusterOverview.trend.subtitle') }}
          </el-tag>
        </div>
      </template>
      <div
        ref="trendChartRef"
        v-loading="overviewLoading"
        class="trend-chart"
        role="img"
        :aria-label="t('clusterOverview.trend.title')"
      ></div>
    </el-card>

    <!-- 节点列表 -->
    <el-card
      shadow="never"
      class="page-card"
      style="margin-top: 16px"
      role="region"
      :aria-label="t('clusterOverview.nodes.title')"
    >
      <template #header>
        <div class="card-header">
          <span>{{ t('clusterOverview.nodes.title') }}</span>
          <el-button
            :icon="Refresh"
            circle
            size="small"
            :aria-label="t('clusterOverview.nodes.refreshAria')"
            @click="loadNodes"
          />
        </div>
      </template>
      <el-table
        v-loading="nodesLoading"
        :data="nodeList"
        stripe
        border
        role="table"
        :aria-label="t('clusterOverview.nodes.title')"
        :empty-text="
          nodesError ? t('clusterOverview.nodes.loadFailed') : t('clusterOverview.nodes.empty')
        "
      >
        <el-table-column
          prop="name"
          :label="t('clusterOverview.nodes.columns.name')"
          min-width="180"
        />
        <el-table-column :label="t('clusterOverview.nodes.columns.role')" width="120">
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
        <el-table-column :label="t('clusterOverview.nodes.columns.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="nodeStatusType(row.status)" effect="light">
              {{ nodeStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('clusterOverview.nodes.columns.cpu')" width="160">
          <template #default="{ row }">
            <div class="cell-bar">
              <span>
                {{ t('clusterOverview.nodes.cpuFmt', { used: row.cpuUsed, cap: row.cpuCapacity }) }}
              </span>
              <el-progress
                :percentage="nodeCpuPercent(row)"
                :stroke-width="6"
                :show-text="false"
                :color="usageColor(nodeCpuPercent(row))"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('clusterOverview.nodes.columns.mem')" width="160">
          <template #default="{ row }">
            <div class="cell-bar">
              <span>
                {{ t('clusterOverview.nodes.memFmt', { used: row.memUsed, cap: row.memCapacity }) }}
              </span>
              <el-progress
                :percentage="nodeMemPercent(row)"
                :stroke-width="6"
                :show-text="false"
                :color="usageColor(nodeMemPercent(row))"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('clusterOverview.nodes.columns.pod')" width="140" align="center">
          <template #default="{ row }">{{ row.podCount }} / {{ row.podCapacity }}</template>
        </el-table-column>
        <el-table-column
          prop="osImage"
          :label="t('clusterOverview.nodes.columns.os')"
          min-width="160"
        />
      </el-table>
    </el-card>

    <!-- 组件状态 -->
    <el-card
      shadow="never"
      class="page-card"
      style="margin-top: 16px"
      role="region"
      :aria-label="t('clusterOverview.components.title')"
    >
      <template #header>
        <div class="card-header">
          <span>{{ t('clusterOverview.components.title') }}</span>
        </div>
      </template>
      <el-row :gutter="12" role="list" :aria-label="t('clusterOverview.components.listAria')">
        <el-col v-for="comp in components" :key="comp.name" :xs="12" :sm="8" :md="6" :lg="4">
          <div
            class="comp-card"
            :class="comp.status"
            role="listitem"
            :aria-label="
              t('clusterOverview.components.itemAria', {
                name: comp.name,
                status: compStatusLabel(comp.status)
              })
            "
          >
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
    <el-card
      shadow="never"
      class="page-card"
      style="margin-top: 16px"
      role="region"
      :aria-label="t('clusterOverview.resources.title')"
    >
      <template #header>
        <div class="card-header">
          <span>{{ t('clusterOverview.resources.title') }}</span>
          <el-select
            v-model="selectedEnv"
            size="small"
            style="width: 120px; margin-right: 8px"
            :aria-label="t('clusterOverview.resources.envAria')"
            @change="loadClusterResources"
          >
            <el-option :label="t('clusterOverview.resources.envs.xinchuang')" value="xinchuang" />
            <el-option :label="t('clusterOverview.resources.envs.private')" value="private" />
            <el-option :label="t('clusterOverview.resources.envs.cloud')" value="cloud" />
          </el-select>
          <el-input
            v-model="selectedClusterId"
            size="small"
            style="width: 180px"
            :placeholder="t('clusterOverview.resources.clusterIdPlaceholder')"
            :aria-label="t('clusterOverview.resources.clusterIdAria')"
            @change="loadClusterResources"
          />
        </div>
      </template>
      <el-tabs
        v-model="resourceTab"
        role="tablist"
        :aria-label="t('clusterOverview.resources.tabsAria')"
        @tab-change="loadClusterResources"
      >
        <!-- 网络配置 Tab -->
        <el-tab-pane :label="t('clusterOverview.resources.tabs.network')" name="network">
          <div v-if="networkLoading" class="tab-loading" role="status" aria-live="polite">
            {{ t('clusterOverview.resources.loading') }}
          </div>
          <div v-else-if="networkError" class="tab-error" role="alert">
            {{ t('clusterOverview.resources.loadFailed', { message: networkError.message }) }}
          </div>
          <div v-else-if="networkConfig">
            <el-descriptions :column="4" border size="small" style="margin-bottom: 12px">
              <el-descriptions-item :label="t('clusterOverview.resources.network.podCidr')">
                {{ networkConfig.podCidr }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('clusterOverview.resources.network.serviceCidr')">
                {{ networkConfig.serviceCidr }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('clusterOverview.resources.network.cni')">
                {{ networkConfig.cni }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('clusterOverview.resources.network.mtu')">
                {{ networkConfig.mtu }}
              </el-descriptions-item>
            </el-descriptions>
            <h4>
              {{
                t('clusterOverview.resources.network.policiesTitle', {
                  count: networkConfig.policies?.length ?? 0
                })
              }}
            </h4>
            <el-table
              :data="networkConfig.policies || []"
              stripe
              size="small"
              border
              role="table"
              :aria-label="t('clusterOverview.resources.network.policiesAria')"
              :empty-text="t('clusterOverview.resources.network.policiesEmpty')"
            >
              <el-table-column
                prop="name"
                :label="t('clusterOverview.resources.network.columns.name')"
                min-width="120"
              />
              <el-table-column
                prop="namespace"
                :label="t('clusterOverview.resources.network.columns.namespace')"
                min-width="100"
              />
              <el-table-column
                prop="type"
                :label="t('clusterOverview.resources.network.columns.type')"
                width="80"
              />
              <el-table-column
                prop="selector"
                :label="t('clusterOverview.resources.network.columns.selector')"
                min-width="120"
              />
            </el-table>
            <h4 style="margin-top: 12px">
              {{
                t('clusterOverview.resources.network.servicesTitle', {
                  count: networkConfig.services?.length ?? 0
                })
              }}
            </h4>
            <el-table
              :data="networkConfig.services || []"
              stripe
              size="small"
              border
              role="table"
              :aria-label="t('clusterOverview.resources.network.servicesAria')"
              :empty-text="t('clusterOverview.resources.network.servicesEmpty')"
            >
              <el-table-column
                prop="name"
                :label="t('clusterOverview.resources.network.columns.name')"
                min-width="120"
              />
              <el-table-column
                prop="namespace"
                :label="t('clusterOverview.resources.network.columns.namespace')"
                min-width="100"
              />
              <el-table-column
                prop="type"
                :label="t('clusterOverview.resources.network.columns.type')"
                width="80"
              />
              <el-table-column
                prop="clusterIP"
                :label="t('clusterOverview.resources.network.columns.clusterIp')"
                min-width="120"
              />
              <el-table-column
                prop="ports"
                :label="t('clusterOverview.resources.network.columns.ports')"
                width="80"
                align="center"
              />
            </el-table>
            <h4 style="margin-top: 12px">
              {{
                t('clusterOverview.resources.network.ingressesTitle', {
                  count: networkConfig.ingresses?.length ?? 0
                })
              }}
            </h4>
            <el-table
              :data="networkConfig.ingresses || []"
              stripe
              size="small"
              border
              role="table"
              :aria-label="t('clusterOverview.resources.network.ingressesAria')"
              :empty-text="t('clusterOverview.resources.network.ingressesEmpty')"
            >
              <el-table-column
                prop="name"
                :label="t('clusterOverview.resources.network.columns.name')"
                min-width="120"
              />
              <el-table-column
                prop="namespace"
                :label="t('clusterOverview.resources.network.columns.namespace')"
                min-width="100"
              />
              <el-table-column
                prop="className"
                :label="t('clusterOverview.resources.network.columns.className')"
                min-width="100"
              />
              <el-table-column
                prop="hosts"
                :label="t('clusterOverview.resources.network.columns.hosts')"
                width="80"
                align="center"
              />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- 存储配置 Tab -->
        <el-tab-pane :label="t('clusterOverview.resources.tabs.storage')" name="storage">
          <div v-if="storageLoading" class="tab-loading" role="status" aria-live="polite">
            {{ t('clusterOverview.resources.loading') }}
          </div>
          <div v-else-if="storageError" class="tab-error" role="alert">
            {{ t('clusterOverview.resources.loadFailed', { message: storageError.message }) }}
          </div>
          <div v-else>
            <h4>
              {{
                t('clusterOverview.resources.storage.classesTitle', {
                  count: storageClasses?.length ?? 0
                })
              }}
            </h4>
            <el-table
              :data="storageClasses || []"
              stripe
              size="small"
              border
              role="table"
              :aria-label="t('clusterOverview.resources.storage.classesAria')"
              :empty-text="t('clusterOverview.resources.storage.classesEmpty')"
            >
              <el-table-column
                prop="name"
                :label="t('clusterOverview.resources.storage.columns.name')"
                min-width="120"
              />
              <el-table-column
                prop="provisioner"
                :label="t('clusterOverview.resources.storage.columns.provisioner')"
                min-width="160"
              />
              <el-table-column
                prop="reclaimPolicy"
                :label="t('clusterOverview.resources.storage.columns.reclaimPolicy')"
                width="100"
              />
              <el-table-column
                :label="t('clusterOverview.resources.storage.columns.default')"
                width="60"
                align="center"
              >
                <template #default="{ row }">
                  <el-tag v-if="row.default" type="success" size="small">
                    {{ t('clusterOverview.resources.storage.columns.defaultYes') }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
            <h4 style="margin-top: 12px">
              {{ t('clusterOverview.resources.storage.pvcsTitle', { count: pvcs?.length ?? 0 }) }}
            </h4>
            <el-table
              :data="pvcs || []"
              stripe
              size="small"
              border
              role="table"
              :aria-label="t('clusterOverview.resources.storage.pvcsAria')"
              :empty-text="t('clusterOverview.resources.storage.pvcsEmpty')"
            >
              <el-table-column
                prop="name"
                :label="t('clusterOverview.resources.storage.columns.name')"
                min-width="120"
              />
              <el-table-column
                prop="namespace"
                :label="t('clusterOverview.resources.storage.columns.namespace')"
                min-width="100"
              />
              <el-table-column
                prop="storageClassName"
                :label="t('clusterOverview.resources.storage.columns.storageClass')"
                min-width="100"
              />
              <el-table-column
                prop="capacity"
                :label="t('clusterOverview.resources.storage.columns.capacity')"
                width="100"
              />
              <el-table-column
                prop="status"
                :label="t('clusterOverview.resources.storage.columns.status')"
                width="80"
              />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- HPA 配置 Tab -->
        <el-tab-pane :label="t('clusterOverview.resources.tabs.hpa')" name="hpa">
          <div v-if="hpaLoading" class="tab-loading" role="status" aria-live="polite">
            {{ t('clusterOverview.resources.loading') }}
          </div>
          <div v-else-if="hpaError" class="tab-error" role="alert">
            {{ t('clusterOverview.resources.loadFailed', { message: hpaError.message }) }}
          </div>
          <el-table
            v-else
            :data="hpas || []"
            stripe
            size="small"
            border
            role="table"
            aria-label="HPA"
            :empty-text="t('clusterOverview.resources.hpa.empty')"
          >
            <el-table-column
              prop="name"
              :label="t('clusterOverview.resources.hpa.columns.name')"
              min-width="120"
            />
            <el-table-column
              prop="namespace"
              :label="t('clusterOverview.resources.hpa.columns.namespace')"
              min-width="100"
            />
            <el-table-column
              prop="targetDeployment"
              :label="t('clusterOverview.resources.hpa.columns.targetDeployment')"
              min-width="120"
            />
            <el-table-column
              prop="minReplicas"
              :label="t('clusterOverview.resources.hpa.columns.minReplicas')"
              width="90"
              align="center"
            />
            <el-table-column
              prop="maxReplicas"
              :label="t('clusterOverview.resources.hpa.columns.maxReplicas')"
              width="90"
              align="center"
            />
            <el-table-column
              prop="currentReplicas"
              :label="t('clusterOverview.resources.hpa.columns.currentReplicas')"
              width="90"
              align="center"
            />
            <el-table-column :label="t('clusterOverview.resources.hpa.columns.status')" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
                  {{
                    row.status === 'active'
                      ? t('clusterOverview.resources.hpa.statusActive')
                      : t('clusterOverview.resources.hpa.statusPaused')
                  }}
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
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useApi } from '@/composables/useApi'
import * as clusterApi from '@/api/cluster'
import * as infraApi from '@/api/infra'
import type { ClusterOverview, Node, NodeStatus } from '@/api/types'
import type { ComponentStatus } from '@/api/cluster'
import type {
  ClusterEnv,
  NetworkConfig,
  StorageClass,
  PersistentVolumeClaim,
  HpaPolicy
} from '@/api/infra'

const { t, te } = useI18n()

/* ------------------------------ 集群概览 ------------------------------ */

// 集群概览：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: overview,
  loading: overviewLoading,
  execute: loadOverviewRaw
} = useApi<ClusterOverview>(() => clusterApi.getClusterOverview(), {
  onError: () => ElMessage.error(t('clusterOverview.messages.overviewLoadFailed'))
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
  onError: () => ElMessage.error(t('clusterOverview.messages.nodesLoadFailed'))
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
  const days = [7, 6, 5, 4, 3, 2].map((n) => t('clusterOverview.trend.daysAgo', { n }))
  days.push(t('clusterOverview.trend.yesterday'))
  const cpuLabel = t('clusterOverview.trend.legend.cpu')
  const memLabel = t('clusterOverview.trend.legend.mem')
  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown[]) => {
        const series = params as Array<Record<string, unknown>>
        let html = `${series[0]?.axisValue as string}<br/>`
        for (const p of series) {
          html +=
            t('clusterOverview.trend.tooltipLabel', {
              name: p.seriesName as string,
              value: p.value as number
            }).replace(/<br\/>$/, '') + '<br/>'
        }
        return html
      }
    },
    legend: {
      data: [cpuLabel, memLabel],
      right: 10,
      top: 0
    },
    grid: { left: 50, right: 30, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: days,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: 'var(--ds-text-secondary)' }
    },
    yAxis: {
      type: 'value',
      max: 100,
      axisLabel: { formatter: '{value}%', color: 'var(--ds-text-secondary)' },
      splitLine: { lineStyle: { color: 'var(--ds-border-default)' } }
    },
    series: [
      {
        name: cpuLabel,
        type: 'line',
        smooth: true,
        data: overview.value.trendCpu,
        itemStyle: { color: 'var(--ds-color-success-700)' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(47, 111, 106, 0.25)' },
            { offset: 1, color: 'rgba(47, 111, 106, 0.02)' }
          ])
        },
        lineStyle: { width: 2 }
      },
      {
        name: memLabel,
        type: 'line',
        smooth: true,
        data: overview.value.trendMem,
        itemStyle: { color: 'var(--ds-color-warning-600)' },
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

/** 组件状态 → 词条 */
function compStatusLabel(status: ComponentStatus['status']): string {
  return t(`clusterOverview.components.status.${status}`)
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
} = useApi<HpaPolicy[]>(() => infraApi.getHpas(selectedEnv.value, selectedClusterId.value), {
  initialData: []
})

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

/** 节点状态 → 词条 + tag 类型 */
const NODE_STATUS_TYPE_MAP: Record<NodeStatus, 'success' | 'danger' | 'info'> = {
  ready: 'success',
  'not-ready': 'danger',
  unknown: 'info'
}

function nodeStatusLabel(status: NodeStatus): string {
  return t(`clusterOverview.nodeStatus.${status}`)
}

function nodeStatusType(status: NodeStatus): 'success' | 'danger' | 'info' {
  return NODE_STATUS_TYPE_MAP[status] ?? 'info'
}

/** 使用率 → 颜色等级 */
function usageLevel(percent: number): string {
  if (percent >= 90) return 'danger'
  if (percent >= 70) return 'warning'
  return 'healthy'
}

/** 使用率 → 进度条颜色 */
function usageColor(percentage: number): string {
  if (percentage >= 90) return 'var(--ds-color-error-600)'
  if (percentage >= 70) return 'var(--ds-color-warning-600)'
  return 'var(--ds-color-success-600)'
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
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}
.stat-row {
  margin-bottom: 0;
}
.stat-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  margin-bottom: 16px;
}
.stat-content {
  text-align: center;
  padding: 4px 0;
}
.stat-label {
  font-size: 13px;
  color: var(--ds-text-secondary);
  margin-bottom: 8px;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.stat-value.healthy {
  color: var(--ds-color-success-600);
}
.stat-value.warning {
  color: var(--ds-color-warning-600);
}
.stat-value.danger {
  color: var(--ds-color-error-600);
}
.stat-meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
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
  color: var(--ds-text-secondary);
}
.comp-card {
  border: 1px solid var(--ds-border-default);
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
  border-color: var(--ds-color-error-600);
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
  color: var(--ds-color-success-600);
}
.comp-card.warning .comp-status {
  color: var(--ds-color-warning-600);
}
.comp-card.error .comp-status {
  color: var(--ds-color-error-600);
}
.comp-meta {
  font-size: 11px;
  color: var(--ds-text-secondary);
}
.tab-loading {
  color: var(--ds-text-secondary);
  text-align: center;
  padding: 20px;
}
.tab-error {
  color: var(--ds-color-error-600);
  text-align: center;
  padding: 20px;
}
</style>
