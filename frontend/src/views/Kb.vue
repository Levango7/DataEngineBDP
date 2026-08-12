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
        加载失败：{{ error }}
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
import * as knowledgeApi from '@/api/knowledge'
import type { KnowledgeBase, RagStrategy, KbStatus } from '@/api/knowledge'

const store = useAppStore()

const knowledgeBases = ref<KnowledgeBase[]>([])
const ragStrategy = ref<RagStrategy | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const strategyLoading = ref(false)

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

async function loadKnowledgeBases() {
  loading.value = true
  error.value = null
  try {
    knowledgeBases.value = await knowledgeApi.listKnowledgeBases()
  } catch (e) {
    error.value = (e as Error).message || '加载知识库列表失败'
  } finally {
    loading.value = false
  }
}

async function loadRagStrategy() {
  strategyLoading.value = true
  try {
    ragStrategy.value = await knowledgeApi.getRagStrategy()
  } catch (e) {
    store.showToast(`加载 RAG 策略失败：${(e as Error).message}`)
  } finally {
    strategyLoading.value = false
  }
}

function triggerUpload() {
  store.showToast('请选择要上传的文档')
}

onMounted(() => {
  loadKnowledgeBases()
  loadRagStrategy()
})
</script>
