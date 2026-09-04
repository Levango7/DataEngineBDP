<template>
  <div>
    <h1>{{ t('vector.title') }}</h1>
    <div class="sub">{{ t('vector.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">{{ t('vector.newCollection') }}</button>
      <div class="spacer"></div>
      <input
        v-model="searchText"
        style="width: 260px"
        :placeholder="t('vector.searchPlaceholder')"
        @keydown.enter="doSearch"
      />
    </div>
    <div class="card">
      <div v-if="loading" style="text-align: center; padding: 24px; color: #888">
        {{ t('vector.loading') }}
      </div>
      <div v-else-if="error" style="text-align: center; padding: 24px; color: #d4380d">
        {{ t('vector.loadFailed', { msg: error.message }) }}
        <button class="btn ghost sm" style="margin-left: 8px" @click="loadCollections">
          {{ t('common.retry') }}
        </button>
      </div>
      <table v-else>
        <thead>
          <tr>
            <th>{{ t('vector.cols.collection') }}</th>
            <th>{{ t('vector.cols.dimension') }}</th>
            <th>{{ t('vector.cols.count') }}</th>
            <th>{{ t('vector.cols.index') }}</th>
            <th>{{ t('vector.cols.relatedKb') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in collections" :key="c.id">
            <td>{{ c.name }}</td>
            <td>{{ c.dimension }}</td>
            <td>{{ c.count }}</td>
            <td>{{ c.index }}</td>
            <td>{{ c.relatedKb }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('vector.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('vector.createModal.name') }}</label>
      <input v-model="newCollection.name" :placeholder="t('vector.createModal.namePlaceholder')" />
      <label>{{ t('vector.createModal.dimension') }}</label>
      <input v-model.number="newCollection.dimension" type="number" value="768" />
      <label>{{ t('vector.createModal.indexType') }}</label>
      <select v-model="newCollection.index">
        <option>HNSW</option>
        <option>IVF_PQ</option>
      </select>
      <label>{{ t('vector.createModal.relatedKb') }}</label>
      <input
        v-model="newCollection.relatedKb"
        :placeholder="t('vector.createModal.relatedKbPlaceholder')"
      />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" @click="submitCreate">{{ t('vector.createModal.create') }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
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
