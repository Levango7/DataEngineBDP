<template>
  <div>
    <h1>知识工程</h1>
    <div class="sub">L4.5 · 文档入库 → 切片 → 向量化 → RAG 策略配置，构建企业级知识底座。</div>
    <div class="toolbar">
      <button class="btn sm" @click="triggerUpload">⬆ 上传文档</button>
      <div class="spacer"></div>
      <span class="pill c">RAG 已启用</span>
    </div>
    <div class="card">
      <div v-if="loading" style="text-align: center; padding: 24px; color: #888">正在加载知识库列表...</div>
      <div v-else-if="error" style="text-align: center; padding: 24px; color: #d4380d">
        加载失败：{{ error.message }}
        <button class="btn ghost sm" style="margin-left: 8px" @click="loadKnowledgeBases">重试</button>
      </div>
      <table v-else>
        <tr><th>知识库</th><th>文档数</th><th>切片策略</th><th>检索</th><th>状态</th></tr>
        <tr v-for="kb in knowledgeBases" :key="kb.id">
          <td>{{ kb.name }}</td>
          <td>{{ kb.docCount.toLocaleString() }}</td>
          <td>{{ kb.chunkStrategy }}</td>
          <td>{{ kb.retrieval }}</td>
          <td><span class="pill" :class="kbStatusClass(kb.status)">{{ kbStatusLabel(kb.status) }}</span></td>
        </tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>RAG 策略</h3>
      <div v-if="strategyLoading" style="color: #888">加载中...</div>
      <template v-else-if="ragStrategy">
        <div class="kv"><span>检索 TopK</span><span>{{ ragStrategy.topK }}</span></div>
        <div class="kv"><span>分数阈值</span><span>{{ ragStrategy.scoreThreshold }}</span></div>
        <div class="kv"><span>重排模型</span><span>{{ ragStrategy.rerankerModel }}</span></div>
        <div class="kv"><span>引用溯源</span><span>{{ ragStrategy.citationEnabled ? '开启' : '关闭' }}</span></div>
      </template>
      <div v-else style="color: #888">暂无 RAG 策略配置</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import * as knowledgeApi from '@/api/knowledge'
import type { KnowledgeBase, RagStrategy, KbStatus } from '@/api/knowledge'

const store = useAppStore()

// 知识库列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: knowledgeBases,
  loading,
  error,
  execute: loadKnowledgeBases
} = useApi<KnowledgeBase[]>(() => knowledgeApi.listKnowledgeBases(), { initialData: [] })

// RAG 策略：通过 useApi 包装，失败时提示
const {
  data: ragStrategy,
  loading: strategyLoading,
  execute: loadRagStrategy
} = useApi<RagStrategy>(() => knowledgeApi.getRagStrategy(), {
  onError: (err) => store.showToast(`加载 RAG 策略失败：${err.message}`)
})

function kbStatusLabel(s: KbStatus): string {
  const map: Record<KbStatus, string> = {
    ready: '就绪',
    building: '构建中',
    failed: '失败',
  }
  return map[s] || s
}

function kbStatusClass(s: KbStatus): string {
  const map: Record<KbStatus, string> = {
    ready: 'g',
    building: 'a',
    failed: 'p',
  }
  return map[s] || ''
}

function triggerUpload() {
  store.showToast('请选择要上传的文档')
}

onMounted(() => {
  void loadKnowledgeBases()
  void loadRagStrategy()
})
</script>
