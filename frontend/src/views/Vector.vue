<template>
  <!-- C-2 导航合并：/kb 已并入本页（原 Kb.vue），用 tab 切换「向量数据库/知识工程」 -->
  <div class="page-tabs" role="group" :aria-label="t('nav.items.vector')">
    <button
      type="button"
      class="page-tab"
      :class="{ active: activeTab === 'vector' }"
      @click="switchTab('vector')"
    >
      {{ t('nav.items.vector') }}
    </button>
    <button
      type="button"
      class="page-tab"
      :class="{ active: activeTab === 'kb' }"
      @click="switchTab('kb')"
    >
      {{ t('nav.items.kb') }}
    </button>
  </div>

  <template v-if="activeTab === 'vector'">
    <PageHeader :title="t('vector.title')" :subtitle="t('vector.subtitle')" />
    <Toolbar
      v-model:search-value="searchText"
      :show-create="true"
      :create-label="t('vector.newCollection')"
      :create-aria-label="t('vector.newCollection')"
      :search-placeholder="t('vector.searchPlaceholder')"
      :search-aria-label="t('vector.searchPlaceholder')"
      :show-refresh="false"
      @create="modalVisible = true"
      @search="doSearch"
    />
    <div class="card">
      <div v-if="loading" class="state-tip">
        {{ t('vector.loading') }}
      </div>
      <div v-else-if="error" class="state-tip error">
        {{ t('vector.loadFailed', { msg: error.message }) }}
        <el-button size="small" style="margin-left: 8px" @click="loadCollections">
          {{ t('common.retry') }}
        </el-button>
      </div>
      <el-table v-else :data="collections" stripe :empty-text="t('common.empty')">
        <el-table-column :label="t('vector.cols.collection')" prop="name" />
        <el-table-column :label="t('vector.cols.dimension')" prop="dimension" />
        <el-table-column :label="t('vector.cols.count')" prop="count" />
        <el-table-column :label="t('vector.cols.index')" prop="index" />
        <el-table-column :label="t('vector.cols.relatedKb')" prop="relatedKb" />
      </el-table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('vector.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('vector.createModal.name') }}</label>
      <el-input
        v-model="newCollection.name"
        :placeholder="t('vector.createModal.namePlaceholder')"
      />
      <label>{{ t('vector.createModal.dimension') }}</label>
      <el-input-number v-model="newCollection.dimension" :min="1" />
      <label>{{ t('vector.createModal.indexType') }}</label>
      <el-select v-model="newCollection.index">
        <el-option label="HNSW" value="HNSW" />
        <el-option label="IVF_PQ" value="IVF_PQ" />
      </el-select>
      <label>{{ t('vector.createModal.relatedKb') }}</label>
      <el-input
        v-model="newCollection.relatedKb"
        :placeholder="t('vector.createModal.relatedKbPlaceholder')"
      />
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitCreate">
          {{ t('vector.createModal.create') }}
        </el-button>
      </template>
    </Modal>
  </template>

  <!-- 知识工程：原 /kb 页（Kb.vue）完整内容，作为本页第二个 tab -->
  <Kb v-else />
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import Kb from '@/views/Kb.vue'
import * as vectorApi from '@/api/vector'
import type { VectorCollection, IndexType } from '@/api/vector'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)

/* ------------------------------ 导航合并 tab ------------------------------ */

// /kb 重定向到 /vector?tab=kb；当前 tab 由路由 query 派生
const route = useRoute()
const router = useRouter()
type MergedTab = 'vector' | 'kb'

/** 解析当前 tab（无 router 注入时回退 vector） */
function tabFromQuery(): MergedTab {
  return route?.query?.tab === 'kb' ? 'kb' : 'vector'
}

const activeTab = computed(tabFromQuery)

/** 切换 tab：更新 URL query（保留可直达链接语义），tab 随之重算 */
function switchTab(tab: MergedTab) {
  if (activeTab.value === tab) return
  const query: Record<string, string> = tab === 'kb' ? { tab: 'kb' } : {}
  void router?.replace?.({ path: '/vector', query })
}

// 向量集合列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: collections,
  loading,
  error,
  execute: loadCollections
} = useApi<VectorCollection[]>(() => vectorApi.listCollections(), { initialData: [] })

const searchText = ref('')

const newCollection = ref({
  name: '',
  dimension: 768,
  index: 'HNSW' as IndexType,
  relatedKb: ''
})

async function submitCreate() {
  if (!newCollection.value.name) {
    store.showToast(t('vector.createModal.nameRequired'))
    return
  }
  try {
    const created = await vectorApi.createCollection({
      name: newCollection.value.name,
      dimension: newCollection.value.dimension,
      index: newCollection.value.index,
      relatedKb: newCollection.value.relatedKb
    })
    if (collections.value) {
      collections.value.push(created)
    }
    modalVisible.value = false
    store.showToast(t('vector.createModal.created'))
    // 重置表单
    newCollection.value = { name: '', dimension: 768, index: 'HNSW', relatedKb: '' }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('vector.createModal.createFailed', { msg }))
  }
}

async function doSearch() {
  if (!searchText.value) return
  try {
    await vectorApi.search(searchText.value, 5)
    store.showToast(t('vector.searchDone'))
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('vector.searchFailed', { msg }))
  }
}

onMounted(() => {
  void loadCollections()
})
</script>

<style scoped>
/* 状态提示：加载/错误统一样式，颜色使用 design tokens */
.state-tip {
  text-align: center;
  padding: var(--ds-spacing-6);
  color: var(--ds-text-tertiary);
}
.state-tip.error {
  color: var(--ds-color-error-600);
}

/* 响应式断点：中等屏幕收窄表格列内边距 */
@media (max-width: 1024px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-sm);
  }
}

/* 响应式断点：小屏幕进一步紧凑 */
@media (max-width: 640px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-xs);
  }
  :deep(.el-table .cell) {
    padding-left: var(--ds-spacing-2);
    padding-right: var(--ds-spacing-2);
  }
}

/* 导航合并 tab：/vector 与 /kb 合并为同一页后的切换条 */
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
