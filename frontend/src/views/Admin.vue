<template>
  <div>
    <h1>{{ t('admin.title') }}</h1>
    <div class="sub">{{ t('admin.subtitle') }}</div>
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">
      {{ t('admin.loading') }}
    </div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
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
          <div class="kpi s">¥ {{ formatRevenue(kpi?.monthlyRevenue ?? 0) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('admin.kpi.alerts') }}</h3>
          <div class="kpi s">{{ kpi?.alertCount ?? 0 }}</div>
          <div class="meta">{{ t('admin.kpi.alertsMeta', { count: kpi?.alertAutoHandled ?? 0 }) }}</div>
        </div>
      </div>
      <div class="card" style="margin-top: 14px">
        <h3>{{ t('admin.envTitle') }}</h3>
        <div v-if="envLoading" style="text-align: center; padding: 24px; color: #888">
          {{ t('admin.envLoading') }}
        </div>
        <table v-else>
          <thead>
            <tr>
              <th>{{ t('admin.cols.env') }}</th>
              <th>{{ t('admin.cols.namespace') }}</th>
              <th>{{ t('admin.cols.nodes') }}</th>
              <th>{{ t('admin.cols.controlPlane') }}</th>
              <th>{{ t('admin.cols.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="env in envMatrix" :key="env.id">
              <td>{{ env.name }}</td>
              <td>{{ env.namespaceCount }}</td>
              <td>{{ env.nodeCount }}</td>
              <td>{{ env.controlPlane }}</td>
              <td>
                <span class="pill" :class="envStatusClass(env.status)">
                  {{ envStatusLabel(env.status) }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="note">{{ t('admin.envNote') }}</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '@/composables/useApi'
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
