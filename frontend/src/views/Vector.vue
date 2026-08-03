<template>
  <div>
    <h1>向量库</h1>
    <div class="sub">L4.5 智能数据层 · 基于 Milvus 统一管理 embedding 集合，支撑语义检索与 RAG。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建集合</button>
      <div class="spacer"></div>
      <input
        style="width: 260px"
        placeholder="相似度检索：输入查询文本…"
        @keydown.enter="store.showToast('检索 top5 结果已返回')"
      />
    </div>
    <div class="card">
      <table>
        <tr><th>集合</th><th>维度</th><th>条数</th><th>索引</th><th>关联知识库</th></tr>
        <tr><td>product_embed</td><td>1536</td><td>1.2M</td><td>HNSW</td><td>商品知识库</td></tr>
        <tr><td>doc_chunk</td><td>768</td><td>860K</td><td>IVF_PQ</td><td>制度文档库</td></tr>
        <tr><td>user_vec</td><td>512</td><td>3.4M</td><td>HNSW</td><td>用户画像库</td></tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建向量集合" @close="modalVisible = false">
      <label>集合名</label><input placeholder="如 doc_chunk" />
      <label>维度</label><input value="768" />
      <label>索引类型</label>
      <select><option>HNSW</option><option>IVF_PQ</option></select>
      <label>关联知识库</label><input placeholder="如 制度文档库" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('向量集合已创建')">创建</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'

const store = useAppStore()
const modalVisible = ref(false)
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>