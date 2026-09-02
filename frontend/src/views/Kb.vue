<template>
  <div class="kb-page">
    <h1>知识工程</h1>
    <div class="sub">L4.5 · 文档入库 → 切片 → 向量化 → RAG 策略配置，构建企业级知识底座。</div>

    <!-- KPI 卡片区 -->
    <div class="grid g3">
      <template v-if="kbLoading">
        <div v-for="i in 3" :key="i" class="card">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>知识库数</h3>
          <div class="kpi">{{ kbKpi.total }}</div>
          <div class="meta">活跃 {{ kbKpi.active }} 个</div>
        </div>
        <div class="card">
          <h3>文档总数</h3>
          <div class="kpi s">{{ kbKpi.docs }}</div>
          <div class="meta">已向量化 {{ kbKpi.vectorized }} 个</div>
        </div>
        <div class="card">
          <h3>RAG 策略</h3>
          <div class="kpi" :class="{ s: ragStrategy?.citationEnabled }">
            {{ ragStrategy?.citationEnabled ? '已启用' : '未启用' }}
          </div>
          <div class="meta">
            TopK={{ ragStrategy?.topK ?? '--' }} · {{ ragStrategy?.retrievalMethod ?? '--' }}
          </div>
        </div>
      </template>
    </div>

    <!-- Tabs 主区 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <!-- Tab1 知识库列表 -->
        <el-tab-pane label="知识库" name="kb">
          <div class="toolbar">
            <el-button type="primary" @click="openCreateKbDialog">+ 创建知识库</el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadKnowledgeBases" />
          </div>

          <el-table
            v-loading="kbLoading"
            :data="knowledgeBases"
            stripe
            border
            style="width: 100%"
            :empty-text="kbError ? '加载失败，请重试' : '暂无知识库'"
          >
            <el-table-column prop="name" label="知识库名" min-width="180" />
            <el-table-column prop="docCount" label="文档数" width="100" />
            <el-table-column prop="chunkStrategy" label="切片策略" width="160">
              <template #default="{ row }">{{ row.chunkStrategy || '--' }}</template>
            </el-table-column>
            <el-table-column prop="retrieval" label="检索方式" width="140">
              <template #default="{ row }">{{ row.retrieval || '--' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="kbStatusType(row.status)" effect="light">
                  {{ kbStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDocDialog(row)">文档管理</el-button>
                <el-button link type="danger" @click="handleDeleteKb(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab2 RAG 策略 -->
        <el-tab-pane label="RAG 策略" name="rag">
          <div v-if="strategyLoading" style="text-align: center; padding: 40px; color: #888">
            正在加载 RAG 策略...
          </div>
          <div v-else-if="ragStrategy">
            <el-form :model="ragForm" label-width="140px" style="max-width: 640px">
              <el-form-item label="检索 TopK">
                <el-input-number v-model="ragForm.topK" :min="1" :max="50" />
              </el-form-item>
              <el-form-item label="分数阈值">
                <el-input-number
                  v-model="ragForm.scoreThreshold"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :precision="2"
                />
              </el-form-item>
              <el-form-item label="重排模型">
                <el-select
                  v-model="ragForm.rerankerModel"
                  style="width: 100%"
                  filterable
                  allow-create
                >
                  <el-option label="bge-reranker-large" value="bge-reranker-large" />
                  <el-option label="bge-reranker-base" value="bge-reranker-base" />
                  <el-option label="cohere-rerank" value="cohere-rerank" />
                </el-select>
              </el-form-item>
              <el-form-item label="切片策略">
                <el-select v-model="ragForm.chunkStrategy" style="width: 100%">
                  <el-option label="按段落" value="by_paragraph" />
                  <el-option label="按标题" value="by_title" />
                  <el-option label="按对话轮次" value="by_turn" />
                  <el-option label="按句子" value="by_sentence" />
                </el-select>
              </el-form-item>
              <el-form-item label="检索方式">
                <el-select v-model="ragForm.retrievalMethod" style="width: 100%">
                  <el-option label="向量检索" value="vector" />
                  <el-option label="关键字检索" value="keyword" />
                  <el-option label="混合检索" value="hybrid" />
                </el-select>
              </el-form-item>
              <el-form-item label="引用溯源">
                <el-switch v-model="ragForm.citationEnabled" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="ragSaving" @click="handleSaveRag">
                  保存策略
                </el-button>
                <el-button @click="resetRagForm">重置</el-button>
              </el-form-item>
            </el-form>
          </div>
          <div v-else style="text-align: center; padding: 40px; color: #888">暂无 RAG 策略配置</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 创建知识库弹窗 -->
    <el-dialog v-model="createKbDialogVisible" title="创建知识库" width="480px">
      <el-form ref="kbFormRef" :model="kbForm" :rules="kbRules" label-width="100px">
        <el-form-item label="知识库名" prop="name">
          <el-input v-model="kbForm.name" placeholder="如 营销知识库" />
        </el-form-item>
        <el-form-item label="切片策略">
          <el-select v-model="kbForm.chunkStrategy" style="width: 100%">
            <el-option label="按段落" value="by_paragraph" />
            <el-option label="按标题" value="by_title" />
            <el-option label="按对话轮次" value="by_turn" />
            <el-option label="按句子" value="by_sentence" />
          </el-select>
        </el-form-item>
        <el-form-item label="检索方式">
          <el-select v-model="kbForm.retrieval" style="width: 100%">
            <el-option label="向量检索" value="vector" />
            <el-option label="关键字检索" value="keyword" />
            <el-option label="混合检索" value="hybrid" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createKbDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="kbCreating" @click="handleCreateKb">创建</el-button>
      </template>
    </el-dialog>

    <!-- 文档管理弹窗 -->
    <el-dialog
      v-model="docDialogVisible"
      :title="`文档管理 - ${currentKb?.name ?? ''}`"
      width="800px"
    >
      <div class="toolbar">
        <el-upload
          :show-file-list="false"
          :before-upload="handleBeforeUpload"
          :http-request="handleUpload"
          multiple
        >
          <el-button type="primary">+ 上传文档</el-button>
        </el-upload>
        <span style="color: var(--ds-text-secondary); font-size: 12px; margin-left: 8px">
          支持 pdf/txt/md/docx 等格式
        </span>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadDocuments" />
      </div>

      <el-table
        v-loading="docLoading"
        :data="documents"
        stripe
        border
        style="width: 100%"
        :empty-text="docError ? '加载失败，请重试' : '暂无文档'"
      >
        <el-table-column prop="fileName" label="文件名" min-width="200" />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="80">
          <template #default="{ row }">{{ row.fileType || '--' }}</template>
        </el-table-column>
        <el-table-column label="切片数" width="90">
          <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="向量数" width="90">
          <template #default="{ row }">{{ row.vectorCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="docStatusType(row.status)" effect="light" size="small">
              {{ docStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="uploadedAt" label="上传时间" width="170">
          <template #default="{ row }">{{ formatTime(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeleteDoc(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type UploadRequestOptions
} from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as knowledgeApi from '@/api/knowledge'
import type { KnowledgeBase, RagStrategy, KnowledgeDocument } from '@/api/knowledge'

/* ------------------------------ 通用 ------------------------------ */

/** 当前激活的 Tab */
const activeTab = ref('kb')

/* ------------------------------ 知识库列表 ------------------------------ */

// 知识库列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: knowledgeBases,
  loading: kbLoading,
  error: kbError,
  execute: loadKnowledgeBases
} = useApi<KnowledgeBase[]>(() => knowledgeApi.listKnowledgeBases(), { initialData: [] })

/** 知识库 KPI */
const kbKpi = computed(() => {
  const list = knowledgeBases.value ?? []
  const total = list.length
  const active = list.filter((k) => k.status === 'active' || k.status === 'ready').length
  const docs = list.reduce((sum, k) => sum + (k.docCount ?? 0), 0)
  return { total, active, docs, vectorized: 0 }
})

/* ------------------------------ RAG 策略 ------------------------------ */

// RAG 策略
const {
  data: ragStrategy,
  loading: strategyLoading,
  execute: loadRagStrategy
} = useApi<RagStrategy>(() => knowledgeApi.getRagStrategy())

/** RAG 策略表单（编辑态） */
const ragForm = reactive({
  topK: 5,
  scoreThreshold: 0.7,
  rerankerModel: 'bge-reranker-large',
  citationEnabled: true,
  chunkStrategy: 'by_paragraph',
  retrievalMethod: 'vector'
})

const ragSaving = ref(false)

/** 将后端策略同步到表单 */
function syncRagForm(s: RagStrategy | null) {
  if (!s) return
  ragForm.topK = s.topK ?? 5
  ragForm.scoreThreshold = s.scoreThreshold ?? 0.7
  ragForm.rerankerModel = s.rerankerModel ?? 'bge-reranker-large'
  ragForm.citationEnabled = s.citationEnabled ?? true
  ragForm.chunkStrategy = s.chunkStrategy ?? 'by_paragraph'
  ragForm.retrievalMethod = s.retrievalMethod ?? 'vector'
}

/** 重置表单到上次加载的值 */
function resetRagForm() {
  syncRagForm(ragStrategy.value)
}

/** 保存 RAG 策略 */
async function handleSaveRag() {
  ragSaving.value = true
  try {
    await knowledgeApi.updateRagStrategy({
      topK: ragForm.topK,
      scoreThreshold: ragForm.scoreThreshold,
      rerankerModel: ragForm.rerankerModel,
      citationEnabled: ragForm.citationEnabled,
      chunkStrategy: ragForm.chunkStrategy,
      retrievalMethod: ragForm.retrievalMethod
    })
    ElMessage.success('RAG 策略已保存')
    await loadRagStrategy()
  } catch {
    // 拦截器已提示
  } finally {
    ragSaving.value = false
  }
}

/* ------------------------------ 创建知识库弹窗 ------------------------------ */

const createKbDialogVisible = ref(false)
const kbCreating = ref(false)
const kbFormRef = ref<FormInstance>()

const kbForm = reactive({
  name: '',
  chunkStrategy: 'by_paragraph',
  retrieval: 'vector'
})

const kbRules: FormRules = {
  name: [{ required: true, message: '请输入知识库名', trigger: 'blur' }]
}

/** 打开创建知识库弹窗 */
function openCreateKbDialog() {
  kbForm.name = ''
  kbForm.chunkStrategy = 'by_paragraph'
  kbForm.retrieval = 'vector'
  createKbDialogVisible.value = true
}

/** 提交创建知识库 */
async function handleCreateKb() {
  if (!kbFormRef.value) return
  await kbFormRef.value.validate(async (valid) => {
    if (!valid) return
    kbCreating.value = true
    try {
      await knowledgeApi.createKnowledgeBase({
        name: kbForm.name,
        chunkStrategy: kbForm.chunkStrategy,
        retrieval: kbForm.retrieval
      })
      ElMessage.success('知识库已创建')
      createKbDialogVisible.value = false
      await loadKnowledgeBases()
    } catch {
      // 拦截器已提示
    } finally {
      kbCreating.value = false
    }
  })
}

/** 删除知识库 */
async function handleDeleteKb(row: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(`确认删除知识库「${row.name}」？该操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await knowledgeApi.deleteKnowledgeBase(row.id)
    ElMessage.success('知识库已删除')
    await loadKnowledgeBases()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 文档管理 ------------------------------ */

const docDialogVisible = ref(false)
const currentKb = ref<KnowledgeBase | null>(null)
const docLoading = ref(false)
const docError = ref(false)
const documents = ref<KnowledgeDocument[]>([])

/** 打开文档管理弹窗 */
async function openDocDialog(row: KnowledgeBase) {
  currentKb.value = row
  docDialogVisible.value = true
  await loadDocuments()
}

/** 加载文档列表 */
async function loadDocuments() {
  if (!currentKb.value) return
  docLoading.value = true
  docError.value = false
  try {
    documents.value = await knowledgeApi.listDocuments(currentKb.value.id)
  } catch {
    docError.value = true
    documents.value = []
  } finally {
    docLoading.value = false
  }
}

/** 上传前校验 */
function handleBeforeUpload(file: File): boolean {
  // 限制单文件 50MB
  const sizeLimit = 50 * 1024 * 1024
  if (file.size > sizeLimit) {
    ElMessage.warning(`文件 ${file.name} 超过 50MB 限制`)
    return false
  }
  return true
}

/** 自定义上传：调用 uploadDocument API */
async function handleUpload(options: UploadRequestOptions) {
  if (!currentKb.value) {
    ElMessage.error('未选择知识库')
    return
  }
  const file = options.file as File
  try {
    await knowledgeApi.uploadDocument(currentKb.value.id, file)
    ElMessage.success(`文件 ${file.name} 上传成功`)
    await loadDocuments()
    // 同步刷新知识库列表（文档数变化）
    await loadKnowledgeBases()
  } catch {
    // 拦截器已提示
  }
}

/** 删除文档 */
async function handleDeleteDoc(row: KnowledgeDocument) {
  if (!currentKb.value) return
  try {
    await ElMessageBox.confirm(`确认删除文档「${row.fileName}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await knowledgeApi.deleteDocument(currentKb.value.id, row.id)
    ElMessage.success('文档已删除')
    await loadDocuments()
    await loadKnowledgeBases()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 知识库状态 → 中文 */
function kbStatusLabel(s: string): string {
  const map: Record<string, string> = {
    active: '活跃',
    ready: '就绪',
    pending: '构建中',
    building: '构建中',
    disabled: '已禁用',
    failed: '失败'
  }
  return map[s] ?? s
}

/** 知识库状态 → tag 类型 */
function kbStatusType(s: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    active: 'success',
    ready: 'success',
    pending: 'warning',
    building: 'warning',
    disabled: 'info',
    failed: 'danger'
  }
  return map[s] ?? 'info'
}

/** 文档状态 → 中文 */
function docStatusLabel(s: string): string {
  const map: Record<string, string> = {
    uploaded: '已上传',
    parsed: '已解析',
    vectorized: '已向量化',
    failed: '失败'
  }
  return map[s] ?? s
}

/** 文档状态 → tag 类型 */
function docStatusType(s: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    uploaded: 'info',
    parsed: 'primary',
    vectorized: 'success',
    failed: 'danger'
  }
  return map[s] ?? 'info'
}

/** 时间格式化 */
function formatTime(iso?: string): string {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

/** 文件大小格式化 */
function formatSize(bytes?: number): string {
  if (bytes == null) return '--'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  await loadKnowledgeBases()
  await loadRagStrategy()
  // RAG 策略加载完成后同步到表单
  syncRagForm(ragStrategy.value)
})
</script>

<style scoped>
.kb-page {
  padding: 0;
}
.sub {
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g3 {
  grid-template-columns: repeat(3, 1fr);
}
@media (max-width: 1100px) {
  .grid.g3 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g3 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.s {
  color: var(--ds-color-success-600);
}
.kpi.d {
  color: var(--ds-color-error-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
</style>
