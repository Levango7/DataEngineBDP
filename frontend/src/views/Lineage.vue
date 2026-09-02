<template>
  <div>
    <h1>血缘分析</h1>
    <div class="sub">
      端到端自动采集字段级血缘；影响分析一键追溯。当前高亮：{{ highlightTable || '未指定' }}
    </div>
    <div class="legend">
      <span style="color: #8a9ba0">■ 上游</span>
      <span style="color: var(--primary)">■ 当前</span>
      <span style="color: var(--green)">■ 下游</span>
      <span style="color: #94a3b8">□ 弱化</span>
    </div>
    <div v-if="loading" class="card" style="padding: 16px; color: var(--muted)">加载血缘中…</div>
    <div v-else-if="error" class="card" style="padding: 16px; color: var(--red)">
      {{ error.message }}，
      <a href="javascript:void(0)" @click="loadLineage(highlightTable)">重试</a>
    </div>
    <div v-else class="card">
      <div class="lineage">
        <div class="lvl">
          <div v-for="t in upstreamTables" :key="t" class="ln">{{ t }}</div>
          <div v-if="upstreamTables.length === 0" class="ln" style="color: var(--muted)">
            无上游
          </div>
        </div>
        <div class="lvl">
          <div class="ln hot" @click="store.showToast(`当前节点 ${highlightTable}`)">
            {{ highlightTable }}
          </div>
        </div>
        <div class="lvl">
          <div v-for="t in downstreamTables" :key="t" class="ln">{{ t }}</div>
          <div v-if="downstreamTables.length === 0" class="ln" style="color: var(--muted)">
            无下游
          </div>
        </div>
        <div class="lvl">
          <div v-for="t in impactTables" :key="t" class="ln">{{ t }}</div>
          <div v-if="impactTables.length === 0" class="ln" style="color: var(--muted)">无影响</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { getUpstream, getDownstream, impactAnalysis, type LineageQueryResult } from '@/api/lineage'

const store = useAppStore()

// 当前高亮表（示例使用 dwd.order_wide）
const highlightTable = ref('dwd.order_wide')

// 血缘数据：通过 useApi 包装并行加载，自动维护 loading / error / data 三态
const {
  data: lineageData,
  loading,
  error,
  execute: loadLineage
} = useApi<
  [LineageQueryResult | null, LineageQueryResult | null, LineageQueryResult | null],
  [string]
>((table: string) =>
  Promise.all([
    getUpstream(table).catch(() => null),
    getDownstream(table).catch(() => null),
    impactAnalysis(table).catch(() => null)
  ])
)

// 上游表
const upstreamTables = computed<string[]>(() => lineageData.value?.[0]?.tables ?? [])
// 下游表
const downstreamTables = computed<string[]>(() => lineageData.value?.[1]?.tables ?? [])
// 影响表
const impactTables = computed<string[]>(() => lineageData.value?.[2]?.tables ?? [])

onMounted(() => {
  void loadLineage(highlightTable.value)
})
</script>
