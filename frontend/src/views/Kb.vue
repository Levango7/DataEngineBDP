<template>
  <div class="kb-page">
    <h1>{{ t('kb.title') }}</h1>
    <div class="sub">{{ t('kb.subtitle') }}</div>

    <!-- KPI 卡片区 -->
    <div class="grid g3">
      <template v-if="kbLoading">
        <div v-for="i in 3" :key="i" class="card">
          <h3>{{ t('common.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('kb.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('kb.kpi.kbCount') }}</h3>
          <div class="kpi">{{ kbKpi.total }}</div>
          <div class="meta">{{ t('kb.kpi.activeMeta', { count: kbKpi.active }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('kb.kpi.docCount') }}</h3>
          <div class="kpi s">{{ kbKpi.docs }}</div>
          <div class="meta">{{ t('kb.kpi.vectorizedMeta', { count: kbKpi.vectorized }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('kb.kpi.ragStrategy') }}</h3>
          <div class="kpi" :class="{ s: ragStrategy?.citationEnabled }">
            {{ ragStrategy?.citationEnabled ? t('kb.kpi.enabled') : t('kb.kpi.disabled') }}
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
        <el-tab-pane :label="t('kb.tabs.kb')" name="kb">
          <div class="toolbar">
            <el-button type="primary" @click="openCreateKbDialog">{{ t('kb.createKb') }}</el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadKnowledgeBases" />
          </div>

          <el-table
            v-loading="kbLoading"
            :data="knowledgeBases"
            stripe
            border
            style="width: 100%"
            :empty-text="kbError ? t('kb.emptyError') : t('kb.empty')"
          >
            <el-table-column prop="name" :label="t('kb.cols.name')" min-width="180" />
            <el-table-column prop="docCount" :label="t('kb.cols.docCount')" width="100" />
            <el-table-column prop="chunkStrategy" :label="t('kb.cols.chunkStrategy')" width="160">
              <template #default="{ row }">{{ row.chunkStrategy || '--' }}</template>
            </el-table-column>
            <el-table-column prop="retrieval" :label="t('kb.cols.retrieval')" width="140">
              <template #default="{ row }">{{ row.retrieval || '--' }}</template>
            </el-table-column>
            <el-table-column :label="t('kb.cols.status')" width="100">
              <template #default="{ row }">
                <el-tag :type="kbStatusType(row.status)" effect="light">
                  {{ kbStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('kb.cols.createdAt')" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column :label="t('kb.cols.actions')" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDocDialog(row)">
                  {{ t('kb.docManage') }}
                </el-button>
                <el-button link type="danger" @click="handleDeleteKb(row)">
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab2 RAG 策略 -->
        <el-tab-pane :label="t('kb.tabs.rag')" name="rag">
          <div v-if="strategyLoading" style="text-align: center; padding: 40px; color: #888">
            {{ t('kb.rag.loading') }}
          </div>
          <div v-else-if="ragStrategy">
            <el-form :model="ragForm" label-width="140px" style="max-width: 640px">
              <el-form-item :label="t('kb.rag.topK')">
                <el-input-number v-model="ragForm.topK" :min="1" :max="50" />
              </el-form-item>
              <el-form-item :label="t('kb.rag.scoreThreshold')">
                <el-input-number
                  v-model="ragForm.scoreThreshold"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :precision="2"
                />
              </el-form-item>
              <el-form-item :label="t('kb.rag.rerankerModel')">
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
              <el-form-item :label="t('kb.rag.chunkStrategy')">
                <el-select v-model="ragForm.chunkStrategy" style="width: 100%">
                  <el-option :label="t('kb.rag.chunks.by_paragraph')" value="by_paragraph" />
                  <el-option :label="t('kb.rag.chunks.by_title')" value="by_title" />
                  <el-option :label="t('kb.rag.chunks.by_turn')" value="by_turn" />
                  <el-option :label="t('kb.rag.chunks.by_sentence')" value="by_sentence" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('kb.rag.retrievalMethod')">
                <el-select v-model="ragForm.retrievalMethod" style="width: 100%">
                  <el-option :label="t('kb.rag.retrieval.vector')" value="vector" />
                  <el-option :label="t('kb.rag.retrieval.keyword')" value="keyword" />
                  <el-option :label="t('kb.rag.retrieval.hybrid')" value="hybrid" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('kb.rag.citation')">
                <el-switch v-model="ragForm.citationEnabled" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="ragSaving" @click="handleSaveRag">
                  {{ t('kb.rag.save') }}
                </el-button>
                <el-button @click="resetRagForm">{{ t('kb.rag.reset') }}</el-button>
              </el-form-item>
            </el-form>
          </div>
          <div v-else style="text-align: center; padding: 40px; color: #888">
            {{ t('kb.rag.empty') }}
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 创建知识库弹窗 -->
    <el-dialog v-model="createKbDialogVisible" :title="t('kb.createModal.title')" width="480px">
      <el-form ref="kbFormRef" :model="kbForm" :rules="kbRules" label-width="100px">
        <el-form-item :label="t('kb.createModal.name')" prop="name">
          <el-input v-model="kbForm.name" :placeholder="t('kb.createModal.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('kb.createModal.chunkStrategy')">
          <el-select v-model="kbForm.chunkStrategy" style="width: 100%">
            <el-option :label="t('kb.rag.chunks.by_paragraph')" value="by_paragraph" />
            <el-option :label="t('kb.rag.chunks.by_title')" value="by_title" />
            <el-option :label="t('kb.rag.chunks.by_turn')" value="by_turn" />
            <el-option :label="t('kb.rag.chunks.by_sentence')" value="by_sentence" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('kb.createModal.retrieval')">
          <el-select v-model="kbForm.retrieval" style="width: 100%">
            <el-option :label="t('kb.rag.retrieval.vector')" value="vector" />
            <el-option :label="t('kb.rag.retrieval.keyword')" value="keyword" />
            <el-option :label="t('kb.rag.retrieval.hybrid')" value="hybrid" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createKbDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="kbCreating" @click="handleCreateKb">
          {{ t('kb.createModal.create') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 文档管理弹窗 -->
    <el-dialog
      v-model="docDialogVisible"
      :title="t('kb.docModal.title', { name: currentKb?.name ?? '' })"
      width="800px"
    >
      <div class="toolbar">
        <el-upload
          :show-file-list="false"
          :before-upload="handleBeforeUpload"
          :http-request="handleUpload"
          multiple
        >
          <el-button type="primary">{{ t('kb.docModal.upload') }}</el-button>
        </el-upload>
        <span style="color: var(--ds-text-secondary); font-size: 12px; margin-left: 8px">
          {{ t('kb.docModal.uploadHint') }}
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
        :empty-text="docError ? t('kb.docModal.emptyError') : t('kb.docModal.empty')"
      >
        <el-table-column prop="fileName" :label="t('kb.docModal.cols.fileName')" min-width="200" />
        <el-table-column :label="t('kb.docModal.cols.size')" width="110">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="fileType" :label="t('kb.docModal.cols.type')" width="80">
          <template #default="{ row }">{{ row.fileType || '--' }}</template>
        </el-table-column>
        <el-table-column :label="t('kb.docModal.cols.chunks')" width="90">
          <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column :label="t('kb.docModal.cols.vectors')" width="90">
          <template #default="{ row }">{{ row.vectorCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column :label="t('kb.docModal.cols.status')" width="110">
          <template #default="{ row }">
            <el-tag :type="docStatusType(row.status)" effect="light" size="small">
              {{ docStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="uploadedAt" :label="t('kb.docModal.cols.uploadedAt')" width="170">
          <template #default="{ row }">{{ formatTime(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('kb.docModal.cols.actions')" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeleteDoc(row)">
              {{ t('common.delete') }}
            </el-button>
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
import { useI18n } from 'vue-i18n'
import { useApi } from '@/composables/useApi'
import * as knowledgeApi from '@/api/knowledge'
import type { KnowledgeBase, RagStrategy, KnowledgeDocument } from '@/api/knowledge'

/* ------------------------------ 通用 ------------------------------ */

const { t, locale } = useI18n()

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
    ElMessage.success(t('kb.rag.saved'))
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

const kbRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('kb.createModal.nameRequired'), trigger: 'blur' }]
}))

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
      ElMessage.success(t('kb.createModal.created'))
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
    await ElMessageBox.confirm(
      t('kb.deleteKb.message', { name: row.name }),
      t('kb.deleteKb.title'),
      {
        type: 'warning',
        confirmButtonText: t('kb.deleteKb.confirm'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await knowledgeApi.deleteKnowledgeBase(row.id)
    ElMessage.success(t('kb.deleteKb.deleted'))
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
    ElMessage.warning(t('kb.docModal.tooLarge', { name: file.name }))
    return false
  }
  return true
}

/** 自定义上传：调用 uploadDocument API */
async function handleUpload(options: UploadRequestOptions) {
  if (!currentKb.value) {
    ElMessage.error(t('kb.docModal.noKb'))
    return
  }
  const file = options.file as File
  try {
    await knowledgeApi.uploadDocument(currentKb.value.id, file)
    ElMessage.success(t('kb.docModal.uploaded', { name: file.name }))
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
    await ElMessageBox.confirm(
      t('kb.docModal.deleteMessage', { name: row.fileName }),
      t('kb.docModal.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await knowledgeApi.deleteDocument(currentKb.value.id, row.id)
    ElMessage.success(t('kb.docModal.deleted'))
    await loadDocuments()
    await loadKnowledgeBases()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 知识库状态 → 词条 */
const KB_STATUSES = ['active', 'ready', 'pending', 'building', 'disabled', 'failed']

function kbStatusLabel(s: string): string {
  return KB_STATUSES.includes(s) ? t(`kb.kbStatus.${s}`) : s
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

/** 文档状态 → 词条 */
const DOC_STATUSES = ['uploaded', 'parsed', 'vectorized', 'failed']

function docStatusLabel(s: string): string {
  return DOC_STATUSES.includes(s) ? t(`kb.docStatus.${s}`) : s
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

/** 时间格式化（跟随当前语言环境） */
function formatTime(iso?: string): string {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString(locale.value, { hour12: false })
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
