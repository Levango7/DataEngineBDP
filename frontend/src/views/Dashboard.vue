<template>
  <div role="main" :aria-label="t('dashboard.title')">
    <h1>{{ t('dashboard.title') }}</h1>
    <div
      class="sub"
      :aria-label="
        t('dashboard.subtitle', { tenant: '华东生产集群', plan: '企业版', usage: '62%' })
      "
    >
      {{ t('dashboard.subtitle', { tenant: '华东生产集群', plan: '企业版', usage: '62%' }) }}
      <span class="pill b" :aria-label="t('dashboard.todo.title')">
        {{ t('dashboard.todoBadge', { count: store.todoCount }) }}
      </span>
    </div>

    <!-- 集群概览 KPI 卡片：三态 loading / error / data -->
    <div class="grid g4" role="region" :aria-label="t('dashboard.title')">
      <template v-if="overviewLoading">
        <div v-for="i in 4" :key="i" class="card" role="status" aria-live="polite">
          <h3>{{ t('dashboard.kpi.loadingTitle') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('dashboard.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="overviewError">
        <div class="card" style="grid-column: span 4" role="alert" aria-live="assertive">
          <h3>{{ t('dashboard.kpi.errorTitle') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ overviewError.message }}，
            <a
              href="javascript:void(0)"
              :aria-label="t('dashboard.kpi.retry')"
              @click="loadOverview"
            >
              {{ t('dashboard.kpi.retryAction') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else-if="overview">
        <div class="card" role="region" :aria-label="t('dashboard.kpi.projects')">
          <h3>{{ t('dashboard.kpi.projects') }}</h3>
          <div class="kpi">{{ overview.projectCount }}</div>
          <div class="meta">
            {{
              t('dashboard.kpi.projectsMeta', {
                running: runningProjects,
                paused: overview.projectCount - runningProjects
              })
            }}
          </div>
        </div>
        <div class="card" role="region" :aria-label="t('dashboard.kpi.jobs')">
          <h3>{{ t('dashboard.kpi.jobs') }}</h3>
          <div class="kpi">{{ overview.jobCount }}</div>
          <div class="meta">
            {{
              t('dashboard.kpi.jobsMeta', {
                success: overview.jobSuccessToday,
                failed: overview.jobFailToday
              })
            }}
          </div>
        </div>
        <div class="card" role="region" :aria-label="t('dashboard.kpi.storage')">
          <h3>{{ t('dashboard.kpi.storage') }}</h3>
          <div class="kpi s">{{ overview.storageUsed }} TB</div>
          <div class="meta">{{ t('dashboard.kpi.storageMeta') }}</div>
        </div>
        <div class="card" role="region" :aria-label="t('dashboard.kpi.assets')">
          <h3>{{ t('dashboard.kpi.assets') }}</h3>
          <div class="kpi s">{{ overview.assetCount.toLocaleString() }}</div>
          <div class="meta">{{ t('dashboard.kpi.assetsMeta') }}</div>
        </div>
      </template>
    </div>

    <div
      class="grid g2"
      style="margin-top: 14px"
      role="region"
      :aria-label="t('dashboard.trend.title')"
    >
      <!-- 资源趋势：三态 -->
      <div class="card" role="region" :aria-label="t('dashboard.trend.title')">
        <h3>{{ t('dashboard.trend.title') }}</h3>
        <template v-if="overviewLoading">
          <div class="meta" style="color: var(--muted)" role="status" aria-live="polite">
            {{ t('common.loading') }}
          </div>
        </template>
        <template v-else-if="overviewError">
          <div class="meta" style="color: var(--muted)" role="alert">
            {{ t('dashboard.kpi.errorTitle') }}
          </div>
        </template>
        <template v-else-if="overview">
          <div class="mini" role="img" :aria-label="t('dashboard.trend.cpuChart')">
            <i
              v-for="(h, idx) in overview.trendCpu"
              :key="`cpu-${idx}`"
              :style="{ height: h + '%' }"
            ></i>
          </div>
          <div class="row" style="margin-top: 10px">
            <span>CPU</span>
            <span>{{ cpuPercent }}%</span>
          </div>
          <div
            class="bar"
            role="progressbar"
            :aria-valuenow="cpuPercent"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-label="t('dashboard.trend.cpuUsage')"
          >
            <i :style="{ width: cpuPercent + '%' }"></i>
          </div>
          <div class="row" style="margin-top: 8px">
            <span>{{ t('dashboard.trend.memory') }}</span>
            <span>{{ memPercent }}%</span>
          </div>
          <div
            class="bar"
            role="progressbar"
            :aria-valuenow="memPercent"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-label="t('dashboard.trend.memoryUsage')"
          >
            <i class="a" :style="{ width: memPercent + '%' }"></i>
          </div>
          <div class="note">{{ t('dashboard.trend.note') }}</div>
        </template>
      </div>
      <div class="card" role="region" :aria-label="t('dashboard.todo.title')">
        <h3>
          {{ t('dashboard.todo.title') }}
          <span class="pill r" :aria-label="t('dashboard.todo.title')">{{ store.todoCount }}</span>
        </h3>
        <table role="table" :aria-label="t('dashboard.todo.title')">
          <thead>
            <tr role="row">
              <th role="columnheader">{{ t('dashboard.todo.colApplicant') }}</th>
              <th role="columnheader">{{ t('dashboard.todo.colOwner') }}</th>
              <th role="columnheader">{{ t('dashboard.todo.colAction') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="approval in store.secApprovals" :key="approval.id" role="row">
              <td role="cell">{{ approval.asset }}（{{ approval.perm }}）</td>
              <td role="cell">{{ approval.applicant }}</td>
              <td role="cell">
                <button
                  class="btn sm"
                  :aria-label="t('dashboard.todo.approve')"
                  @click="store.approve(approval.id)"
                >
                  {{ t('dashboard.todo.approve') }}
                </button>
                <button
                  class="btn ghost sm"
                  :aria-label="t('dashboard.todo.reject')"
                  @click="store.reject(approval.id)"
                >
                  {{ t('dashboard.todo.reject') }}
                </button>
              </td>
            </tr>
            <tr v-if="store.secApprovals.length === 0" role="row">
              <td colspan="3" style="text-align: center; color: var(--muted)" role="cell">
                {{ t('dashboard.todo.empty') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div
      class="card"
      style="margin-top: 14px"
      role="region"
      :aria-label="t('dashboard.quickActions.title')"
    >
      <h3>{{ t('dashboard.quickActions.title') }}</h3>
      <div class="chips" role="navigation" :aria-label="t('dashboard.quickActions.title')">
        <span
          class="chip on"
          role="link"
          tabindex="0"
          :aria-label="t('dashboard.quickActions.newJob')"
          @click="router.push('/develop')"
        >
          {{ t('dashboard.quickActions.newJob') }}
        </span>
        <span
          class="chip"
          role="link"
          tabindex="0"
          :aria-label="t('dashboard.quickActions.configSync')"
          @click="router.push('/integrate')"
        >
          {{ t('dashboard.quickActions.configSync') }}
        </span>
        <span
          class="chip"
          role="link"
          tabindex="0"
          :aria-label="t('dashboard.quickActions.registerAsset')"
          @click="router.push('/govern')"
        >
          {{ t('dashboard.quickActions.registerAsset') }}
        </span>
        <span
          class="chip"
          role="link"
          tabindex="0"
          :aria-label="t('dashboard.quickActions.trainModel')"
          @click="router.push('/llmops')"
        >
          {{ t('dashboard.quickActions.trainModel') }}
        </span>
        <span
          class="chip"
          role="link"
          tabindex="0"
          :aria-label="t('dashboard.quickActions.createDashboard')"
          @click="router.push('/analyze')"
        >
          {{ t('dashboard.quickActions.createDashboard') }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import * as clusterApi from '@/api/cluster'
import type { ClusterOverview } from '@/api/types'

const { t } = useI18n()
const router = useRouter()
const store = useAppStore()

// 集群概览：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: overview,
  loading: overviewLoading,
  error: overviewError,
  execute: loadOverview
} = useApi<ClusterOverview>(() => clusterApi.getClusterOverview())

// CPU 使用率（百分比，保留整数）
const cpuPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.cpuCapacity || 1
  return Math.round((overview.value.cpuUsed / cap) * 100)
})

// 内存使用率（百分比，保留整数）
const memPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.memCapacity || 1
  return Math.round((overview.value.memUsed / cap) * 100)
})

// 运行中项目数：优先用 API 精确值，否则按 Pod 运行率估算
const runningProjects = computed(() => {
  const ov = overview.value
  if (!ov) return 0
  if (typeof ov.projectRunning === 'number') return ov.projectRunning
  const podRate = ov.podTotal > 0 ? ov.podRunning / ov.podTotal : 0.78
  return Math.round((ov.projectCount ?? 0) * podRate)
})

onMounted(() => {
  void loadOverview()
  // 加载安全审批列表作为待办数据源（todos 已移除，secApprovals 为唯一数据源）
  void store.fetchSecApprovals()
})
</script>
