<template>
  <div>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import * as vectorApi from '@/api/vector'
import type { VectorCollection, IndexType } from '@/api/vector'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)

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
    store.showToast(t('vector.createModal.createFailed', { msg: (e as Error).message }))
  }
}

async function doSearch() {
  if (!searchText.value) return
  try {
    await vectorApi.search(searchText.value, 5)
    store.showToast(t('vector.searchDone'))
  } catch (e) {
    store.showToast(t('vector.searchFailed', { msg: (e as Error).message }))
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
  padding: 24px;
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
    padding-left: 8px;
    padding-right: 8px;
  }
}
</style>
