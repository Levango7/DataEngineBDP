<template>
  <div>
    <h1>运营后台（平台侧）</h1>
    <div class="sub">仅平台运维可见；租户、计量、底座运维与多环境管理。</div>
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">正在加载运营数据...</div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
      加载失败：{{ error.message }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAll">重试</button>
    </div>
    <template v-else>
    <div class="grid g4">
      <div class="card"><h3>租户总数</h3><div class="kpi">{{ kpi?.tenantTotal ?? 0 }}</div><div class="meta">外部 {{ kpi?.tenantExternal ?? 0 }} · 内部 {{ kpi?.tenantInternal ?? 0 }}</div></div>
      <div class="card"><h3>集群实例</h3><div class="kpi">{{ kpi?.clusterTotal ?? 0 }}</div><div class="meta">信创 {{ kpi?.clusterXinchuang ?? 0 }} · 本地 {{ kpi?.clusterOnprem ?? 0 }} · 云VM {{ kpi?.clusterCloudVm ?? 0 }}</div></div>
      <div class="card"><h3>本月营收</h3><div class="kpi s">¥ {{ formatRevenue(kpi?.monthlyRevenue ?? 0) }}</div></div>
      <div class="card"><h3>底座告警</h3><div class="kpi s">{{ kpi?.alertCount ?? 0 }}</div><div class="meta">已自动处置 {{ kpi?.alertAutoHandled ?? 0 }}</div></div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>环境矩阵</h3>
      <div v-if="envLoading" style="text-align: center; padding: 24px; color: #888">正在加载环境矩阵...</div>
      <table v-else>
        <thead>
          <tr><th>环境</th><th>Namespace</th><th>节点</th><th>控制面</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="env in envMatrix" :key="env.id">
            <td>{{ env.name }}</td>
            <td>{{ env.namespaceCount }}</td>
            <td>{{ env.nodeCount }}</td>
            <td>{{ env.controlPlane }}</td>
            <td><span class="pill" :class="envStatusClass(env.status)">{{ envStatusLabel(env.status) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="note">
        此处为平台运维视图，印证「自研 SKE 发行版封装层」将底层复杂度对客屏蔽；客户控制台仅见工作空间/项目/配额。
      </div>
    </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useApi } from '@/composables/useApi'
import * as adminApi from '@/api/admin'
import type { AdminKpi, EnvMatrixItem, EnvStatus } from '@/api/admin'

// 运营 KPI：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: kpi,
  loading,
  error,
  execute: loadKpi
} = useApi<AdminKpi>(() => adminApi.getKpi())

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

function envStatusLabel(s: EnvStatus): string {
  const map: Record<EnvStatus, string> = {
    healthy: '健康',
    scaling: '扩容中',
    warning: '告警',
    critical: '严重',
  }
  return map[s] || s
}

function envStatusClass(s: EnvStatus): string {
  const map: Record<EnvStatus, string> = {
    healthy: 'g',
    scaling: 'a',
    warning: 'a',
    critical: 'p',
  }
  return map[s] || ''
}

/** 加载全部数据 */
async function loadAll() {
  await Promise.all([void loadKpi(), void loadEnvMatrix()])
}

onMounted(() => {
  void loadAll()
})
</script>
