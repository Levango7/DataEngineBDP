<template>
  <div>
    <h1>{{ t('lineage.title') }}</h1>
    <div class="sub">
      {{ t('lineage.subtitle', { table: highlightTable || t('lineage.noHighlight') }) }}
    </div>
    <div class="legend">
      <span style="color: #8a9ba0">{{ t('lineage.legend.upstream') }}</span>
      <span style="color: var(--primary)">{{ t('lineage.legend.current') }}</span>
      <span style="color: var(--green)">{{ t('lineage.legend.downstream') }}</span>
      <span style="color: #94a3b8">{{ t('lineage.legend.faded') }}</span>
    </div>
    <div v-if="loading" class="card" style="padding: 16px; color: var(--muted)">
      {{ t('lineage.loading') }}
    </div>
    <div v-else-if="error" class="card" style="padding: 16px; color: var(--red)">
      {{ error.message }}，
      <a href="javascript:void(0)" @click="loadLineage(highlightTable)">{{ t('common.retry') }}</a>
    </div>
    <div v-else class="card">
      <div class="lineage">
        <div class="lvl">
          <div v-for="tbl in upstreamTables" :key="tbl" class="ln">{{ tbl }}</div>
          <div v-if="upstreamTables.length === 0" class="ln" style="color: var(--muted)">
            {{ t('lineage.noUpstream') }}
          </div>
        </div>
        <div class="lvl">
          <div
            class="ln hot"
            @click="store.showToast(t('lineage.currentNode', { table: highlightTable }))"
          >
            {{ highlightTable }}
          </div>
        </div>
        <div class="lvl">
          <div v-for="tbl in downstreamTables" :key="tbl" class="ln">{{ tbl }}</div>
          <div v-if="downstreamTables.length === 0" class="ln" style="color: var(--muted)">
            {{ t('lineage.noDownstream') }}
          </div>
        </div>
        <div class="lvl">
          <div v-for="tbl in impactTables" :key="tbl" class="ln">{{ tbl }}</div>
          <div v-if="impactTables.length === 0" class="ln" style="color: var(--muted)">
            {{ t('lineage.noImpact') }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { getUpstream, getDownstream, impactAnalysis, type LineageQueryResult } from '@/api/lineage'

const { t } = useI18n()
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
