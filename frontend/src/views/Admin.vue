<template>
  <div>
    <PageHeader :title="t('admin.title')" :subtitle="t('admin.subtitle')" />
    <div
      v-if="loading"
      class="card"
      style="text-align: center; padding: 24px; color: var(--ds-text-tertiary)"
    >
      {{ t('admin.loading') }}
    </div>
    <div
      v-else-if="error"
      class="card"
      style="text-align: center; padding: 24px; color: var(--ds-color-error-500)"
    >
      {{ t('admin.loadFailed', { msg: error.message }) }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAll">
        {{ t('common.retry') }}
      </button>
    </div>
    <template v-else>
      <div class="grid g4">
        <div class="card">
          <h3>{{ t('admin.kpi.tenants') }}</h3>
          <div class="kpi">{{ kpi?.tenantTotal ?? NOT_WIRED }}</div>
          <div class="meta">
            {{
              t('admin.kpi.tenantsMeta', {
                external: kpi?.tenantExternal ?? NOT_WIRED,
                internal: kpi?.tenantInternal ?? NOT_WIRED
              })
            }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.clusters') }}</h3>
          <div class="kpi">{{ kpi?.clusterTotal ?? NOT_WIRED }}</div>
          <div class="meta">
            {{
              t('admin.kpi.clustersMeta', {
                xinchuang: kpi?.clusterXinchuang ?? NOT_WIRED,
                onprem: kpi?.clusterOnprem ?? NOT_WIRED,
                cloudVm: kpi?.clusterCloudVm ?? NOT_WIRED
              })
            }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.revenue') }}</h3>
          <div class="kpi s">{{ revenueText }}</div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.alerts') }}</h3>
          <div class="kpi s">{{ kpi?.alertCount ?? NOT_WIRED }}</div>
          <div class="meta">
            {{ t('admin.kpi.alertsMeta', { count: kpi?.alertAutoHandled ?? NOT_WIRED }) }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.workspaces') }}</h3>
          <div class="kpi">{{ kpi?.workspaceTotal ?? NOT_WIRED }}</div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.quotas') }}</h3>
          <div class="kpi">{{ kpi?.quotaTotal ?? NOT_WIRED }}</div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.projects') }}</h3>
          <div class="kpi">{{ kpi?.projectTotal ?? NOT_WIRED }}</div>
        </div>
      </div>
      <div class="card" style="margin-top: 14px">
        <h3>{{ t('admin.envTitle') }}</h3>
        <div
          v-if="envLoading"
          style="text-align: center; padding: 24px; color: var(--ds-text-tertiary)"
        >
          {{ t('admin.envLoading') }}
        </div>
        <el-table v-else :data="envMatrix ?? []" stripe border style="width: 100%">
          <el-table-column prop="name" :label="t('admin.cols.env')" />
          <el-table-column prop="namespaceCount" :label="t('admin.cols.namespace')" />
          <el-table-column prop="nodeCount" :label="t('admin.cols.nodes')" />
          <el-table-column prop="controlPlane" :label="t('admin.cols.controlPlane')" />
          <el-table-column :label="t('admin.cols.status')">
            <template #default="{ row }">
              <span class="pill" :class="envStatusClass(row.status)">
                {{ envStatusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="note">{{ t('admin.envNote') }}</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import * as adminApi from '@/api/admin'
import type { AdminKpi, EnvMatrixItem, EnvStatus } from '@/api/admin'

const { t } = useI18n()

// 运营 KPI：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const { data: kpi, loading, error, execute: loadKpi } = useApi<AdminKpi>(() => adminApi.getKpi())

// 环境矩阵：通过 useApi 包装，失败时不阻塞页面
const {
  data: envMatrix,
  loading: envLoading,
  execute: loadEnvMatrix
} = useApi<EnvMatrixItem[]>(() => adminApi.getEnvMatrix(), { initialData: [] })

function formatRevenue(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`
  if (v >= 1_000) return `${(v / 1_000).toFixed(1)}K`
  return v.toFixed(0)
}

/**
 * 未接入字段的占位符。
 *
 * 卡片分两种口径，改动前务必看清：
 *
 * **A. 未接入 ⇒ 一律 `NOT_WIRED`**
 *    后端不返回 `clusterOnprem` / `clusterCloudVm` /
 *    `monthlyRevenue` / `alertCount` / `alertAutoHandled`（需跨服务聚合，尚未实现），
 *    以及 2026-10-13 从契约删除的 `tenantExternal` / `tenantInternal` /
 *    `clusterTotal` / `clusterXinchuang`（后端曾硬编码 / 结构性恒 0）。
 *    这些位置此前写 `?? 0`，界面会显示一个**看起来很确定的 0**，
 *    运维据此判断"本月没有营收、底座没有告警" —— 这是界面在说谎。
 *    未接入就显示 `—`，让"没有数据"与"数据是 0"可区分。
 *
 * **B. 已接入 ⇒ 显示真实 count**
 *    `tenantTotal` / `workspaceTotal` / `quotaTotal` / `projectTotal`
 *    是后端按租户口径的真实计数（见 `AdminController#kpi()`）。
 *    这里仍然写 `?? NOT_WIRED` 而不是裸渲染，是为了在后端**回归**
 *    （某天又不返回该字段）时显示 `—` 而不是空字符串，保持可诊断。
 */
const NOT_WIRED = '—'

/** 本月营收：后端未接入 ⇒ 显示占位符，不显示 ¥0。 */
const revenueText = computed(() => {
  const v = kpi.value?.monthlyRevenue
  return v == null ? NOT_WIRED : `${t('common.currency')} ${formatRevenue(v)}`
})

const ENV_STATUS_CLS: Record<EnvStatus, string> = {
  healthy: 'g',
  scaling: 'a',
  warning: 'a',
  critical: 'p'
}

const ENV_STATUSES: EnvStatus[] = ['healthy', 'scaling', 'warning', 'critical']

function envStatusLabel(s: EnvStatus): string {
  return ENV_STATUSES.includes(s) ? t(`admin.envStatus.${s}`) : s
}

function envStatusClass(s: EnvStatus): string {
  return ENV_STATUS_CLS[s] ?? ''
}

async function loadAll() {
  await Promise.all([void loadKpi(), void loadEnvMatrix()])
}

onMounted(() => {
  void loadAll()
})
</script>
