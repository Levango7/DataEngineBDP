<template>
  <!-- C-2 导航合并：/data-lineage 已并入本页（原 DataLineage.vue），用 tab 切换「数据血缘/血缘可视化」 -->
  <div class="page-tabs" role="group" :aria-label="t('nav.items.lineage')">
    <button
      type="button"
      class="page-tab"
      :class="{ active: activeTab === 'lineage' }"
      @click="switchTab('lineage')"
    >
      {{ t('nav.items.lineage') }}
    </button>
    <button
      type="button"
      class="page-tab"
      :class="{ active: activeTab === 'data-lineage' }"
      @click="switchTab('data-lineage')"
    >
      {{ t('nav.items.data-lineage') }}
    </button>
  </div>

  <template v-if="activeTab === 'lineage'">
    <PageHeader
      :title="t('lineage.title')"
      :subtitle="t('lineage.subtitle', { table: highlightTable || t('lineage.noHighlight') })"
    />
    <div class="legend">
      <span style="color: var(--ds-color-gray-400)">{{ t('lineage.legend.upstream') }}</span>
      <span style="color: var(--ds-color-primary-500)">{{ t('lineage.legend.current') }}</span>
      <span style="color: var(--ds-color-success-500)">{{ t('lineage.legend.downstream') }}</span>
      <span style="color: var(--ds-color-gray-400)">{{ t('lineage.legend.faded') }}</span>
    </div>
    <div v-if="loading" class="card" style="padding: 16px; color: var(--ds-text-tertiary)">
      {{ t('lineage.loading') }}
    </div>
    <div v-else-if="error" class="card" style="padding: 16px; color: var(--ds-color-error-500)">
      {{ error.message }}，
      <a href="javascript:void(0)" @click="loadLineage(highlightTable)">{{ t('common.retry') }}</a>
    </div>
    <div v-else class="card">
      <div class="lineage">
        <div class="lvl">
          <div v-for="tbl in upstreamTables" :key="tbl" class="ln">{{ tbl }}</div>
          <div v-if="upstreamTables.length === 0" class="ln" style="color: var(--ds-text-tertiary)">
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
          <div
            v-if="downstreamTables.length === 0"
            class="ln"
            style="color: var(--ds-text-tertiary)"
          >
            {{ t('lineage.noDownstream') }}
          </div>
        </div>
        <div class="lvl">
          <div v-for="tbl in impactTables" :key="tbl" class="ln">{{ tbl }}</div>
          <div v-if="impactTables.length === 0" class="ln" style="color: var(--ds-text-tertiary)">
            {{ t('lineage.noImpact') }}
          </div>
        </div>
      </div>
    </div>
  </template>

  <!-- 血缘可视化：原 /data-lineage 页（DataLineage.vue）完整内容，作为本页第二个 tab -->
  <DataLineage v-else />
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import DataLineage from '@/views/DataLineage.vue'
import { getUpstream, getDownstream, impactAnalysis, type LineageQueryResult } from '@/api/lineage'

const { t } = useI18n()
const store = useAppStore()

/* ------------------------------ 导航合并 tab ------------------------------ */

// /data-lineage 重定向到 /lineage?tab=data-lineage；当前 tab 由路由 query 派生
const route = useRoute()
const router = useRouter()
type MergedTab = 'lineage' | 'data-lineage'

/** 解析当前 tab（无 router 注入时回退 lineage） */
function tabFromQuery(): MergedTab {
  return route?.query?.tab === 'data-lineage' ? 'data-lineage' : 'lineage'
}

const activeTab = computed(tabFromQuery)

/** 切换 tab：更新 URL query（保留可直达链接语义），tab 随之重算 */
function switchTab(tab: MergedTab) {
  if (activeTab.value === tab) return
  const query: Record<string, string> = tab === 'data-lineage' ? { tab: 'data-lineage' } : {}
  void router?.replace?.({ path: '/lineage', query })
}

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

<style scoped>
/* 导航合并 tab：/lineage 与 /data-lineage 合并为同一页后的切换条 */
.page-tabs {
  display: flex;
  gap: var(--ds-spacing-1);
  margin-bottom: var(--ds-spacing-4);
  border-bottom: 1px solid var(--ds-border-subtle);
}
.page-tab {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  color: var(--ds-text-secondary);
  font: inherit;
  font-weight: var(--ds-font-weight-medium);
  cursor: pointer;
}
.page-tab.active {
  color: var(--ds-color-primary-600);
  border-bottom-color: var(--ds-color-primary-600);
}
</style>
