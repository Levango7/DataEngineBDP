<template>
  <div>
    <PageHeader :title="t('admin.title')" :subtitle="t('admin.subtitle')" />
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: var(--ds-text-tertiary)">
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
          <div class="kpi">{{ kpi?.tenantTotal ?? 0 }}</div>
          <div class="meta">
            {{
              t('admin.kpi.tenantsMeta', {
                external: kpi?.tenantExternal ?? 0,
                internal: kpi?.tenantInternal ?? 0
              })
            }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.clusters') }}</h3>
          <div class="kpi">{{ kpi?.clusterTotal ?? 0 }}</div>
          <div class="meta">
            {{
              t('admin.kpi.clustersMeta', {
                xinchuang: kpi?.clusterXinchuang ?? 0,
                onprem: kpi?.clusterOnprem ?? 0,
                cloudVm: kpi?.clusterCloudVm ?? 0
              })
            }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.revenue') }}</h3>
          <div class="kpi s">{{ t('common.currency') }} {{ formatRevenue(kpi?.monthlyRevenue ?? 0) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.alerts') }}</h3>
          <div class="kpi s">{{ kpi?.alertCount ?? 0 }}</div>
          <div class="meta">
            {{ t('admin.kpi.alertsMeta', { count: kpi?.alertAutoHandled ?? 0 }) }}
          </div>
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
import { ref, onMounted } from 'vue'
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
