<template>
  <div class="sql-workbench">
    <h1>{{ t('sqlWorkbench.title') }}</h1>
    <div class="sub">
      {{ t('sqlWorkbench.subtitle') }}
    </div>

    <!-- 上半区：SQL 编辑器 + 控制栏 -->
    <el-card shadow="never" class="editor-card">
      <div class="toolbar">
        <el-select
          v-model="dialect"
          :placeholder="t('sqlWorkbench.editor.dialect')"
          style="width: 130px"
        >
          <el-option :label="t('sqlWorkbench.editor.dialects.ANSI')" value="ANSI" />
          <el-option :label="t('sqlWorkbench.editor.dialects.HIVE')" value="HIVE" />
          <el-option :label="t('sqlWorkbench.editor.dialects.DORIS')" value="DORIS" />
          <el-option :label="t('sqlWorkbench.editor.dialects.TRINO')" value="TRINO" />
        </el-select>
        <el-input
          v-model="tenantId"
          :placeholder="t('sqlWorkbench.editor.tenantPlaceholder')"
          style="width: 180px"
        />
        <el-input-number
          v-model="timeoutSeconds"
          :min="5"
          :max="300"
          :step="5"
          controls-position="right"
          style="width: 130px"
        />
        <span class="hint">{{ t('sqlWorkbench.editor.timeoutLabel') }}</span>

        <div class="spacer"></div>

        <el-button :loading="explainLoading" @click="handleExplain">
          <el-icon><Document /></el-icon>
          {{ t('sqlWorkbench.editor.explain') }}
        </el-button>
        <el-button :loading="validateLoading" @click="handleValidate">
          <el-icon><CircleCheck /></el-icon>
          {{ t('sqlWorkbench.editor.validate') }}
        </el-button>
        <el-button type="primary" :loading="executeLoading" @click="handleExecute">
          <el-icon><CaretRight /></el-icon>
          {{ t('sqlWorkbench.editor.execute') }}
        </el-button>
      </div>

      <!-- SQL 编辑器（textarea + 行号） -->
      <div class="sql-editor">
        <div ref="lineNumbersRef" class="line-numbers">
          <div v-for="n in lineCount" :key="n" class="line-number">{{ n }}</div>
        </div>
        <textarea
          v-model="sql"
          class="sql-textarea"
          spellcheck="false"
          :placeholder="t('sqlWorkbench.editor.placeholder')"
          @scroll="syncScroll"
        ></textarea>
      </div>

      <!-- SQL 提示 -->
      <div class="sql-hint">
        <el-tag size="small" type="info" effect="plain">
          {{ t('sqlWorkbench.editor.hintTag') }}
        </el-tag>
        <span class="hint-text">
          {{ t('sqlWorkbench.editor.hint', { hive: 'hive.users', doris: 'doris.orders' }) }}
        </span>
      </div>
    </el-card>

    <!-- 下半区：结果展示 -->
    <el-card shadow="never" class="result-card">
      <el-tabs v-model="activeTab">
        <!-- 结果表格 -->
        <el-tab-pane :label="t('sqlWorkbench.result.tab')" name="result">
          <div v-if="result" class="result-summary">
            <StatusTag :status="result.status" :status-map="STATUS_TAG_TYPE_MAP" />
            <el-tag v-if="result.crossSource" type="warning" effect="light">
              {{ t('sqlWorkbench.result.crossSource') }}
            </el-tag>
            <el-tag v-else type="success" effect="light">
              {{ t('sqlWorkbench.result.singleSource') }}
            </el-tag>
            <span class="meta">
              {{ t('sqlWorkbench.result.rowCount') }}
              <b>{{ result.rowCount }}</b>
            </span>
            <span class="meta">
              {{ t('sqlWorkbench.result.duration') }}
              <b>{{ result.durationMs }}{{ t('sqlWorkbench.result.durationUnit') }}</b>
            </span>
            <span class="meta">
              {{ t('sqlWorkbench.result.source') }}
              <b>{{ result.source }}</b>
            </span>
            <span class="meta">
              {{ t('sqlWorkbench.result.queryId') }}
              <b>{{ result.queryId }}</b>
            </span>
          </div>

          <div v-if="result?.error" class="error-box">
            <el-alert :title="result.error" type="error" :closable="false" show-icon>
              <template #default>
                <a
                  href="javascript:void(0)"
                  style="color: var(--el-color-primary)"
                  @click="handleExecute"
                >
                  {{ t('sqlWorkbench.result.retry') }}
                </a>
              </template>
            </el-alert>
          </div>

          <el-table
            v-if="result && result.rows.length > 0"
            :data="result.rows"
            border
            stripe
            style="width: 100%"
            :max-height="500"
          >
            <el-table-column
              v-for="(col, idx) in result.columns"
              :key="idx"
              :prop="String(idx)"
              :label="col"
              min-width="120"
            >
              <template #default="{ row }">
                {{ formatCell(row[idx]) }}
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-else-if="result && !result.error"
            :description="t('sqlWorkbench.result.empty')"
          />
          <el-empty v-else :description="t('sqlWorkbench.result.executeFirst')" />
        </el-tab-pane>

        <!-- 执行计划 -->
        <el-tab-pane :label="t('sqlWorkbench.explain.tab')" name="explain">
          <div v-if="explainResult" class="explain-block">
            <div class="explain-summary">
              <el-tag :type="explainResult.crossSource ? 'warning' : 'success'" effect="light">
                {{
                  explainResult.crossSource
                    ? t('sqlWorkbench.explain.crossSource')
                    : t('sqlWorkbench.explain.singleSource')
                }}
              </el-tag>
              <el-tag type="info" effect="plain">
                {{ explainResult.strategy }}
              </el-tag>
              <span class="meta">
                {{ t('sqlWorkbench.explain.statementType') }}
                <b>{{ explainResult.statementType || '-' }}</b>
              </span>
              <span class="meta">
                {{ t('sqlWorkbench.explain.parseDuration') }}
                <b>
                  {{ explainResult.durationMs }}{{ t('sqlWorkbench.explain.parseDurationUnit') }}
                </b>
              </span>
            </div>

            <el-collapse v-model="explainCollapse">
              <el-collapse-item :title="t('sqlWorkbench.explain.tablesTitle')" name="tables">
                <el-table :data="tableSourceRows" border style="width: 100%">
                  <el-table-column
                    prop="table"
                    :label="t('sqlWorkbench.explain.tableCol')"
                    min-width="200"
                  />
                  <el-table-column
                    prop="source"
                    :label="t('sqlWorkbench.explain.sourceCol')"
                    width="160"
                  >
                    <template #default="{ row }">
                      <el-tag :type="sourceTagType(row.source)" effect="light">
                        {{ row.source }}
                      </el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </el-collapse-item>

              <el-collapse-item :title="t('sqlWorkbench.explain.sourcesTitle')" name="sources">
                <div class="source-list">
                  <el-tag
                    v-for="src in explainResult.sources || []"
                    :key="src"
                    :type="sourceTagType(src)"
                    effect="light"
                    size="large"
                  >
                    {{ src }}
                  </el-tag>
                </div>
              </el-collapse-item>

              <el-collapse-item :title="t('sqlWorkbench.explain.vizTitle')" name="viz">
                <div class="join-viz">
                  <div v-for="src in explainResult.sources || []" :key="src" class="source-node">
                    <el-card shadow="hover" class="source-card">
                      <div class="source-header">
                        <el-icon><Connection /></el-icon>
                        <span>{{ src }}</span>
                      </div>
                      <div class="source-tables">
                        <el-tag
                          v-for="tbl in tablesOfSource(src)"
                          :key="tbl"
                          size="small"
                          effect="plain"
                        >
                          {{ tbl }}
                        </el-tag>
                      </div>
                    </el-card>
                  </div>
                  <div v-if="(explainResult.sources || []).length > 1" class="merge-arrow">
                    <el-icon><Right /></el-icon>
                    <span>{{ t('sqlWorkbench.explain.mergeArrow') }}</span>
                  </div>
                  <div v-if="(explainResult.sources || []).length > 1" class="merge-result">
                    <el-card shadow="never" class="result-node">
                      <el-icon><Histogram /></el-icon>
                      <span>{{ t('sqlWorkbench.explain.merged') }}</span>
                    </el-card>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <el-empty v-else :description="t('sqlWorkbench.explain.empty')" />
        </el-tab-pane>

        <!-- 校验结果 -->
        <el-tab-pane :label="t('sqlWorkbench.validate.tab')" name="validate">
          <div v-if="validateResult">
            <el-alert
              :title="
                validateResult.valid
                  ? t('sqlWorkbench.validate.valid')
                  : t('sqlWorkbench.validate.invalid')
              "
              :type="validateResult.valid ? 'success' : 'error'"
              :description="
                validateResult.error ||
                t('sqlWorkbench.validate.dialect', { dialect: validateResult.dialect })
              "
              :closable="false"
              show-icon
            />
          </div>
          <el-empty v-else :description="t('sqlWorkbench.validate.empty')" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CaretRight,
  CircleCheck,
  Connection,
  Document,
  Histogram,
  Right
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { StatusTag } from '@/components/ui'
import { useApi } from '@/composables/useApi'
import {
  executeCrossSourceSql,
  explainCrossSourceSql,
  validateSql,
  type CrossSourceQueryResult,
  type CrossSourceExplainResult
} from '@/api/sqlworkbench'

const { t } = useI18n()

// ===================== 响应式状态 =====================
const sql = ref('SELECT * FROM hive.users JOIN doris.orders ON hive.users.id = doris.orders.uid')

const lineNumbersRef = ref<HTMLElement | null>(null)
const dialect = ref<'ANSI' | 'HIVE' | 'DORIS' | 'TRINO'>('ANSI')
const tenantId = ref('')
const timeoutSeconds = ref(30)

// 跨源查询：通过 useApi 包装，自动维护 loading / error / data 三态
const {
  data: result,
  loading: executeLoading,
  execute: executeQuery
} = useApi<CrossSourceQueryResult>(
  () =>
    executeCrossSourceSql({
      sql: sql.value,
      dialect: dialect.value,
      tenantId: tenantId.value || undefined,
      timeoutSeconds: timeoutSeconds.value
    }),
  {
    onError: (err) =>
      ElMessage.error(t('sqlWorkbench.messages.executeError', { message: err.message }))
  }
)

// 执行计划：通过 useApi 包装
const {
  data: explainResult,
  loading: explainLoading,
  execute: explainQuery
} = useApi<CrossSourceExplainResult>(
  () =>
    explainCrossSourceSql({
      sql: sql.value,
      dialect: dialect.value,
      tenantId: tenantId.value || undefined,
      timeoutSeconds: timeoutSeconds.value
    }),
  {
    onError: (err) =>
      ElMessage.error(t('sqlWorkbench.messages.explainError', { message: err.message }))
  }
)

// 语法校验：通过 useApi 包装
const {
  data: validateResult,
  loading: validateLoading,
  execute: validateQuery
} = useApi<{ valid: boolean; dialect: string; error?: string }>(
  () => validateSql({ sql: sql.value, dialect: dialect.value }),
  {
    onError: (err) =>
      ElMessage.error(t('sqlWorkbench.messages.validateError', { message: err.message }))
  }
)

const activeTab = ref('result')
const explainCollapse = ref(['tables', 'sources', 'viz'])

// ===================== 计算属性 =====================
/** SQL 行数（用于行号显示） */
const lineCount = computed(() => {
  if (!sql.value) return 1
  return sql.value.split('\n').length
})

/** 表→源映射转为表格数据 */
const tableSourceRows = computed(() => {
  if (!explainResult.value?.tableToSource) return []
  return Object.entries(explainResult.value.tableToSource).map(([table, source]) => ({
    table,
    source
  }))
})

// ===================== 方法 =====================
/** 执行跨源查询 */
async function handleExecute(): Promise<void> {
  if (!sql.value.trim()) {
    ElMessage.warning(t('sqlWorkbench.messages.needSql'))
    return
  }
  activeTab.value = 'result'
  await executeQuery()
  if (result.value && result.value.status !== 'SUCCESS') {
    ElMessage.error(
      t('sqlWorkbench.messages.executeFailed', {
        error: result.value.error || t('sqlWorkbench.messages.unknownError')
      })
    )
  } else if (result.value?.crossSource) {
    ElMessage.success(t('sqlWorkbench.messages.crossSourceDone', { count: result.value.rowCount }))
  } else if (result.value) {
    ElMessage.success(t('sqlWorkbench.messages.singleSourceDone', { count: result.value.rowCount }))
  }
}

/** 生成执行计划 */
async function handleExplain(): Promise<void> {
  if (!sql.value.trim()) {
    ElMessage.warning(t('sqlWorkbench.messages.needSql'))
    return
  }
  activeTab.value = 'explain'
  await explainQuery()
  if (explainResult.value?.error) {
    ElMessage.error(t('sqlWorkbench.messages.explainFailed', { error: explainResult.value.error }))
  } else if (explainResult.value) {
    ElMessage.success(
      explainResult.value.crossSource
        ? t('sqlWorkbench.messages.explainCross', {
            count: explainResult.value.sources?.length || 0
          })
        : t('sqlWorkbench.messages.explainSingle')
    )
  }
}

/** 语法校验 */
async function handleValidate(): Promise<void> {
  if (!sql.value.trim()) {
    ElMessage.warning(t('sqlWorkbench.messages.needSql'))
    return
  }
  activeTab.value = 'validate'
  await validateQuery()
}

/** 同步行号滚动 */
function syncScroll(e: Event): void {
  const textarea = e.target as HTMLTextAreaElement
  if (lineNumbersRef.value) {
    lineNumbersRef.value.scrollTop = textarea.scrollTop
  }
}

/** 格式化单元格显示 */
function formatCell(val: unknown): string {
  if (val === null || val === undefined) return t('common.null')
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

/** 状态标签类型映射 */
const STATUS_TAG_TYPE_MAP: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
  SUCCESS: 'success',
  DEGRADED: 'warning',
  FAILED: 'danger'
}



/** 源标签类型 */
function sourceTagType(source: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (source) {
    case 'trino':
      return 'primary'
    case 'doris':
      return 'success'
    case 'hive':
      return 'warning'
    default:
      return 'info'
  }
}

/** 获取指定源下的所有表 */
function tablesOfSource(source: string): string[] {
  if (!explainResult.value?.tableToSource) return []
  return Object.entries(explainResult.value.tableToSource)
    .filter(([, src]) => src === source)
    .map(([table]) => table)
}
</script>

<style scoped>
.sql-workbench {
  padding: 20px;
}

.sub {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-base);
  margin-bottom: 16px;
}

.editor-card {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}

.hint {
  color: var(--ds-text-tertiary);
  font-size: var(--ds-font-size-xs);
}

/* SQL 编辑器 */
.sql-editor {
  display: flex;
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-sm);
  overflow: hidden;
  height: 220px;
  background: var(--ds-bg-surface);
}

.line-numbers {
  width: 48px;
  background: var(--ds-bg-subtle);
  border-right: 1px solid var(--ds-border-default);
  overflow: hidden;
  text-align: right;
  padding: 8px 6px;
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-secondary);
  line-height: 1.6;
  user-select: none;
}

.line-number {
  height: 1.6em;
}

.sql-textarea {
  flex: 1;
  border: none;
  outline: 2px solid var(--ds-border-focus);
  outline-offset: 2px;
  resize: none;
  padding: 8px 12px;
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-base);
  line-height: 1.6;
  background: var(--ds-bg-surface);
  color: var(--ds-text-primary);
}

.sql-hint {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint-text {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-xs);
}

.hint-text code {
  background: var(--ds-bg-muted);
  padding: 1px 4px;
  border-radius: var(--ds-radius-sm);
  font-family: var(--ds-font-family-mono);
  color: var(--ds-color-warning-500);
}

/* 结果区 */
.result-card {
  min-height: 400px;
}

.result-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.meta {
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-secondary);
}

.meta b {
  color: var(--ds-text-primary);
}

.error-box {
  margin-bottom: 12px;
}

/* 执行计划 */
.explain-block {
  margin-top: 8px;
}

.explain-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.source-list {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/* 跨源 JOIN 可视化 */
.join-viz {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
  padding: 12px 0;
}

.source-node {
  min-width: 180px;
}

.source-card {
  text-align: center;
}

.source-header {
  font-size: var(--ds-font-size-lg);
  font-weight: var(--ds-font-weight-semibold);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--ds-color-primary-500);
}

.source-tables {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: center;
}

.merge-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: var(--ds-color-warning-500);
  font-size: var(--ds-font-size-base);
  gap: 4px;
}

.merge-arrow .el-icon {
  font-size: var(--ds-font-size-3xl);
}

.merge-result {
  min-width: 120px;
}

.result-node {
  text-align: center;
  background: var(--ds-color-success-50);
  border-color: var(--ds-color-success-100);
}

/* 暗色模式覆写：success-50 在暗色下过暗，改用更深的语义背景 */
:root[data-theme='dark'] .result-node {
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.3);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) .result-node {
    background: rgba(16, 185, 129, 0.12);
    border-color: rgba(16, 185, 129, 0.3);
  }
}

.result-node .el-icon {
  color: var(--ds-color-success-500);
  font-size: var(--ds-font-size-2xl);
  margin-right: 6px;
}
</style>
