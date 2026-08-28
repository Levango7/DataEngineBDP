<template>
  <div>
    <h1>向量库</h1>
    <div class="sub">
      L4.5 智能数据层 · 基于 Milvus 统一管理 embedding 集合，支撑语义检索与 RAG。
    </div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建集合</button>
      <div class="spacer"></div>
      <input
        v-model="searchText"
        style="width: 260px"
        placeholder="相似度检索：输入查询文本…"
        @keydown.enter="doSearch"
      />
    </div>
    <div class="card">
      <div v-if="loading" style="text-align: center; padding: 24px; color: #888">
        正在加载向量集合...
      </div>
      <div v-else-if="error" style="text-align: center; padding: 24px; color: #d4380d">
        加载失败：{{ error.message }}
        <button class="btn ghost sm" style="margin-left: 8px" @click="loadCollections">重试</button>
      </div>
      <table v-else>
        <thead>
          <tr>
            <th>集合</th>
            <th>维度</th>
            <th>条数</th>
            <th>索引</th>
            <th>关联知识库</th>
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

    <Modal :visible="modalVisible" title="新建向量集合" @close="modalVisible = false">
      <label>集合名</label>
      <input v-model="newCollection.name" placeholder="如 doc_chunk" />
      <label>维度</label>
      <input v-model.number="newCollection.dimension" type="number" value="768" />
      <label>索引类型</label>
      <select v-model="newCollection.index">
        <option>HNSW</option>
        <option>IVF_PQ</option>
      </select>
      <label>关联知识库</label>
      <input v-model="newCollection.relatedKb" placeholder="如 制度文档库" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="submitCreate">创建</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as vectorApi from '@/api/vector'
import type { VectorCollection, IndexType } from '@/api/vector'

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
    store.showToast('请填写集合名')
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
    store.showToast('向量集合已创建')
    // 重置表单
    newCollection.value = { name: '', dimension: 768, index: 'HNSW', relatedKb: '' }
  } catch (e) {
    store.showToast(`创建失败：${(e as Error).message}`)
  }
}

async function doSearch() {
  if (!searchText.value) return
  try {
    await vectorApi.search(searchText.value, 5)
    store.showToast('检索 top5 结果已返回')
  } catch (e) {
    store.showToast(`检索失败：${(e as Error).message}`)
  }
}

onMounted(() => {
  void loadCollections()
})
</script>
