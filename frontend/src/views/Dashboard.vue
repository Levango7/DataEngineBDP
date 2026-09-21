<template>
  <div role="main" :aria-label="t('dashboard.title')">
    <h1>{{ t('dashboard.title') }}</h1>
    <div
      class="sub"
      :aria-label="
        t('dashboard.subtitle', {
          tenant: store.workspace,
          plan: store.plan,
          usage: store.resourceUsage
        })
      "
    >
      {{
        t('dashboard.subtitle', {
          tenant: store.workspace,
          plan: store.plan,
          usage: store.resourceUsage
        })
      }}
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
          <div class="meta" style="color: var(--ds-text-tertiary)">
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
          <div class="kpi s">{{ overview.storageUsed }} {{ t('dashboard.kpi.storageUnit') }}</div>
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
          <div class="meta" style="color: var(--ds-text-tertiary)" role="status" aria-live="polite">
            {{ t('common.loading') }}
          </div>
        </template>
        <template v-else-if="overviewError">
          <div class="meta" style="color: var(--ds-text-tertiary)" role="alert">
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
        <el-table
          :data="store.secApprovals"
          stripe
          border
          role="table"
          :aria-label="t('dashboard.todo.title')"
          :empty-text="t('dashboard.todo.empty')"
        >
          <el-table-column :label="t('dashboard.todo.colApplicant')">
            <template #default="{ row }">
              {{ t('dashboard.todo.assetPerm', { asset: row.asset, perm: row.perm }) }}
            </template>
          </el-table-column>
          <el-table-column prop="applicant" :label="t('dashboard.todo.colOwner')" />
          <el-table-column :label="t('dashboard.todo.colAction')">
            <template #default="{ row }">
              <button
                class="btn sm"
                :aria-label="t('dashboard.todo.approve')"
                @click="store.approve(row.id)"
              >
                {{ t('dashboard.todo.approve') }}
              </button>
              <button
                class="btn ghost sm"
                :aria-label="t('dashboard.todo.reject')"
                @click="store.reject(row.id)"
              >
                {{ t('dashboard.todo.reject') }}
              </button>
            </template>
          </el-table-column>
        </el-table>
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
  // 加载租户信息填充 workspace/plan/resourceUsage（替代原硬编码业务参数）
  void store.fetchTenantInfo()
  // 加载安全审批列表作为待办数据源（todos 已移除，secApprovals 为唯一数据源）
  void store.fetchSecApprovals()
})
</script>

<style scoped>
/* ============================================================
 * 响应式断点：平板 / 移动端布局适配
 * 参考 Llmops.vue 模式：1100px 两列，720px 单列堆叠
 * ============================================================ */
@media (max-width: 1024px) {
  /* KPI 四卡片退化为两列 */
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
  /* 趋势 + 待办保持两列，但缩小间距 */
  .grid.g2 {
    gap: 14px;
  }
}

@media (max-width: 640px) {
  /* 移动端：所有网格单列堆叠 */
  .grid.g4,
  .grid.g2 {
    grid-template-columns: 1fr;
    gap: var(--ds-spacing-3);
  }
  /* 卡片内边距收紧，提升小屏空间利用率 */
  .card {
    padding: 14px 14px 12px;
  }
  /* KPI 数值字号略减 */
  .kpi {
    font-size: var(--ds-font-size-2xl);
  }
  .kpi.s {
    font-size: var(--ds-font-size-lg);
  }
  /* 快捷操作 chips 横向滚动，避免换行拥挤 */
  .chips {
    flex-wrap: nowrap;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }
  .chip {
    flex: none;
  }
}
</style>
