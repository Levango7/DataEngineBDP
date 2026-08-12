<template>
  <div>
    <h1>血缘分析</h1>
    <div class="sub">端到端自动采集字段级血缘；影响分析一键追溯。当前高亮：{{ highlightTable || '未指定' }}</div>
    <div class="legend">
      <span style="color: #8a9ba0">■ 上游</span>
      <span style="color: var(--primary)">■ 当前</span>
      <span style="color: var(--green)">■ 下游</span>
      <span style="color: #94a3b8">□ 弱化</span>
    </div>
    <div v-if="loading" class="card" style="padding: 16px; color: var(--muted)">加载血缘中…</div>
    <div v-else-if="error" class="card" style="padding: 16px; color: var(--red)">
      {{ error }}，<a href="javascript:void(0)" @click="loadLineage">重试</a>
    </div>
    <div v-else class="card">
      <div class="lineage">
        <div class="lvl">
          <div class="ln" v-for="t in upstreamTables" :key="t">{{ t }}</div>
          <div v-if="upstreamTables.length === 0" class="ln" style="color: var(--muted)">无上游</div>
        </div>
        <div class="lvl">
          <div class="ln hot" @click="store.showToast(`当前节点 ${highlightTable}`)">{{ highlightTable }}</div>
        </div>
        <div class="lvl">
          <div class="ln" v-for="t in downstreamTables" :key="t">{{ t }}</div>
          <div v-if="downstreamTables.length === 0" class="ln" style="color: var(--muted)">无下游</div>
        </div>
        <div class="lvl">
          <div class="ln" v-for="t in impactTables" :key="t">{{ t }}</div>
          <div v-if="impactTables.length === 0" class="ln" style="color: var(--muted)">无影响</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { getUpstream, getDownstream, impactAnalysis } from '@/api/lineage'

const store = useAppStore()

// 当前高亮表（示例使用 dwd.order_wide）
const highlightTable = ref('dwd.order_wide')

// 血缘数据
const upstreamTables = ref<string[]>([])
const downstreamTables = ref<string[]>([])
const impactTables = ref<string[]>([])

// 加载状态
const loading = ref(false)
const error = ref('')

/** 加载血缘数据 */
async function loadLineage() {
  loading.value = true
  error.value = ''
  try {
    const [upstream, downstream, impact] = await Promise.all([
      getUpstream(highlightTable.value).catch(() => null),
      getDownstream(highlightTable.value).catch(() => null),
      impactAnalysis(highlightTable.value).catch(() => null)
    ])
    upstreamTables.value = upstream?.tables ?? []
    downstreamTables.value = downstream?.tables ?? []
    impactTables.value = impact?.tables ?? []
  } catch (err) {
    error.value = (err as Error).message || '血缘加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadLineage()
})
</script>