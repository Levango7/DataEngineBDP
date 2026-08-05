<template>
  <div class="sql-workbench">
    <h1>SQL 工作台</h1>
    <div class="sub">
      跨源归并查询工作台，支持跨 Trino / Doris / Hive 数据源的 JOIN / UNION 查询，并行执行 + 内存归并。
    </div>

    <!-- 上半区：SQL 编辑器 + 控制栏 -->
    <el-card shadow="never" class="editor-card">
      <div class="toolbar">
        <el-select
          v-model="dialect"
          placeholder="SQL 方言"
          style="width: 130px"
        >
          <el-option label="ANSI" value="ANSI" />
          <el-option label="Hive" value="HIVE" />
          <el-option label="Doris" value="DORIS" />
          <el-option label="Trino" value="TRINO" />
        </el-select>
        <el-input
          v-model="tenantId"
          placeholder="租户 ID（可选）"
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
        <span class="hint">超时(秒)</span>

        <div class="spacer"></div>

        <el-button @click="handleExplain" :loading="explainLoading">
          <el-icon><Document /></el-icon>
          执行计划
        </el-button>
        <el-button @click="handleValidate" :loading="validateLoading">
          <el-icon><CircleCheck /></el-icon>
          语法校验
        </el-button>
        <el-button type="primary" @click="handleExecute" :loading="executeLoading">
          <el-icon><CaretRight /></el-icon>
          执行查询
        </el-button>
      </div>

      <!-- SQL 编辑器（textarea + 行号） -->
      <div class="sql-editor">
        <div class="line-numbers">
          <div
            v-for="n in lineCount"
            :key="n"
            class="line-number"
          >{{ n }}</div>
        </div>
        <textarea
          v-model="sql"
          class="sql-textarea"
          spellcheck="false"
          placeholder="输入 SQL，例如：&#10;SELECT * FROM hive.users JOIN doris.orders ON hive.users.id = doris.orders.uid"
          @scroll="syncScroll"
        ></textarea>
      </div>

      <!-- SQL 提示 -->
      <div class="sql-hint">
        <el-tag size="small" type="info" effect="plain">提示</el-tag>
        <span class="hint-text">
          跨源查询时表名需带 catalog 前缀（如
          <code>hive.users</code>、<code>doris.orders</code>），系统自动识别源并并行查询。
        </span>
      </div>
    </el-card>

    <!-- 下半区：结果展示 -->
    <el-card shadow="never" class="result-card">
      <el-tabs v-model="activeTab">
        <!-- 结果表格 -->
        <el-tab-pane label="查询结果" name="result">
          <div v-if="result" class="result-summary">
            <el-tag :type="statusTagType(result.status)" effect="light">
              {{ result.status }}
            </el-tag>
            <el-tag v-if="result.crossSource" type="warning" effect="light">
              跨源查询
            </el-tag>
            <el-tag v-else type="success" effect="light">
              单源查询
            </el-tag>
            <span class="meta">行数: <b>{{ result.rowCount }}</b></span>
            <span class="meta">耗时: <b>{{ result.durationMs }}ms</b></span>
            <span class="meta">来源: <b>{{ result.source }}</b></span>
            <span class="meta">查询ID: <b>{{ result.queryId }}</b></span>
          </div>

          <div v-if="result?.error" class="error-box">
            <el-alert :title="result.error" type="error" :closable="false" show-icon />
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
            description="查询结果为空"
          />
          <el-empty
            v-else
            description="执行查询后展示结果"
          />
        </el-tab-pane>

        <!-- 执行计划 -->
        <el-tab-pane label="执行计划" name="explain">
          <div v-if="explainResult" class="explain-block">
            <div class="explain-summary">
              <el-tag :type="explainResult.crossSource ? 'warning' : 'success'" effect="light">
                {{ explainResult.crossSource ? '跨源查询' : '单源查询' }}
              </el-tag>
              <el-tag type="info" effect="plain">
                {{ explainResult.strategy }}
              </el-tag>
              <span class="meta">语句类型: <b>{{ explainResult.statementType || '-' }}</b></span>
              <span class="meta">解析耗时: <b>{{ explainResult.durationMs }}ms</b></span>
            </div>

            <el-collapse v-model="explainCollapse">
              <el-collapse-item title="涉及的表与源映射" name="tables">
                <el-table
                  :data="tableSourceRows"
                  border
                  style="width: 100%"
                >
                  <el-table-column prop="table" label="表名" min-width="200" />
                  <el-table-column prop="source" label="数据源" width="160">
                    <template #default="{ row }">
                      <el-tag :type="sourceTagType(row.source)" effect="light">
                        {{ row.source }}
                      </el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </el-collapse-item>

              <el-collapse-item title="涉及的源" name="sources">
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

              <el-collapse-item title="跨源 JOIN 可视化" name="viz">
                <div class="join-viz">
                  <div
                    v-for="src in explainResult.sources || []"
                    :key="src"
                    class="source-node"
                  >
                    <el-card shadow="hover" class="source-card">
                      <div class="source-header">
                        <el-icon><Connection /></el-icon>
                        <span>{{ src }}</span>
                      </div>
                      <div class="source-tables">
                        <el-tag
                          v-for="t in tablesOfSource(src)"
                          :key="t"
                          size="small"
                          effect="plain"
                        >
                          {{ t }}
                        </el-tag>
                      </div>
                    </el-card>
                  </div>
                  <div
                    v-if="(explainResult.sources || []).length > 1"
                    class="merge-arrow"
                  >
                    <el-icon><Right /></el-icon>
                    <span>内存归并</span>
                  </div>
                  <div
                    v-if="(explainResult.sources || []).length > 1"
                    class="merge-result"
                  >
                    <el-card shadow="never" class="result-node">
                      <el-icon><Histogram /></el-icon>
                      <span>merged</span>
                    </el-card>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <el-empty v-else description="点击「执行计划」按钮生成" />
        </el-tab-pane>

        <!-- 校验结果 -->
        <el-tab-pane label="语法校验" name="validate">
          <div v-if="validateResult">
            <el-alert
              :title="validateResult.valid ? 'SQL 语法合法' : 'SQL 语法错误'"
              :type="validateResult.valid ? 'success' : 'error'"
              :description="validateResult.error || `方言: ${validateResult.dialect}`"
              :closable="false"
              show-icon
            />
          </div>
          <el-empty v-else description="点击「语法校验」按钮校验" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  CaretRight,
  CircleCheck,
  Connection,
  Document,
  Histogram,
  Right
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  executeCrossSourceSql,
  explainCrossSourceSql,
  validateSql,
  type CrossSourceQueryResult,
  type CrossSourceExplainResult
} from '@/api/sqlworkbench'

// ===================== 响应式状态 =====================
const sql = ref('SELECT * FROM hive.users JOIN doris.orders ON hive.users.id = doris.orders.uid')
const dialect = ref<'ANSI' | 'HIVE' | 'DORIS' | 'TRINO'>('ANSI')
const tenantId = ref('')
const timeoutSeconds = ref(30)

const executeLoading = ref(false)
const explainLoading = ref(false)
const validateLoading = ref(false)

const result = ref<CrossSourceQueryResult | null>(null)
const explainResult = ref<CrossSourceExplainResult | null>(null)
const validateResult = ref<{ valid: boolean; dialect: string; error?: string } | null>(null)

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
    ElMessage.warning('请输入 SQL')
    return
  }
  executeLoading.value = true
  activeTab.value = 'result'
  try {
    result.value = await executeCrossSourceSql({
      sql: sql.value,
      dialect: dialect.value,
      tenantId: tenantId.value || undefined,
      timeoutSeconds: timeoutSeconds.value
    })
    if (result.value.status !== 'SUCCESS') {
      ElMessage.error(`查询失败: ${result.value.error || '未知错误'}`)
    } else if (result.value.crossSource) {
      ElMessage.success(`跨源查询完成，返回 ${result.value.rowCount} 行`)
    } else {
      ElMessage.success(`查询完成，返回 ${result.value.rowCount} 行`)
    }
  } catch (err) {
    ElMessage.error(`查询异常: ${(err as Error).message}`)
  } finally {
    executeLoading.value = false
  }
}

/** 生成执行计划 */
async function handleExplain(): Promise<void> {
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  explainLoading.value = true
  activeTab.value = 'explain'
  try {
    explainResult.value = await explainCrossSourceSql({
      sql: sql.value,
      dialect: dialect.value,
      tenantId: tenantId.value || undefined,
      timeoutSeconds: timeoutSeconds.value
    })
    if (explainResult.value.error) {
      ElMessage.error(`执行计划生成失败: ${explainResult.value.error}`)
    } else {
      ElMessage.success(
        explainResult.value.crossSource
          ? `跨源查询，涉及 ${explainResult.value.sources?.length || 0} 个源`
          : '单源查询'
      )
    }
  } catch (err) {
    ElMessage.error(`执行计划异常: ${(err as Error).message}`)
  } finally {
    explainLoading.value = false
  }
}

/** 语法校验 */
async function handleValidate(): Promise<void> {
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  validateLoading.value = true
  activeTab.value = 'validate'
  try {
    validateResult.value = await validateSql({
      sql: sql.value,
      dialect: dialect.value
    })
  } catch (err) {
    ElMessage.error(`校验异常: ${(err as Error).message}`)
  } finally {
    validateLoading.value = false
  }
}

/** 同步行号滚动 */
function syncScroll(e: Event): void {
  const textarea = e.target as HTMLTextAreaElement
  const lineNumbers = document.querySelector('.line-numbers')
  if (lineNumbers) {
    lineNumbers.scrollTop = textarea.scrollTop
  }
}

/** 格式化单元格显示 */
function formatCell(val: unknown): string {
  if (val === null || val === undefined) return 'NULL'
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

/** 状态标签类型 */
function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'DEGRADED':
      return 'warning'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
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
  color: #666;
  font-size: 14px;
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
  color: #999;
  font-size: 12px;
}

/* SQL 编辑器 */
.sql-editor {
  display: flex;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
  height: 220px;
  background: #fafafa;
}

.line-numbers {
  width: 48px;
  background: #f5f7fa;
  border-right: 1px solid #dcdfe6;
  overflow: hidden;
  text-align: right;
  padding: 8px 6px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #909399;
  line-height: 1.6;
  user-select: none;
}

.line-number {
  height: 1.6em;
}

.sql-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  padding: 8px 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  background: #fafafa;
  color: #303133;
}

.sql-hint {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint-text {
  color: #909399;
  font-size: 12px;
}

.hint-text code {
  background: #f0f0f0;
  padding: 1px 4px;
  border-radius: 2px;
  font-family: monospace;
  color: #e6a23c;
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
  font-size: 13px;
  color: #606266;
}

.meta b {
  color: #303133;
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
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #409eff;
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
  color: #e6a23c;
  font-size: 13px;
  gap: 4px;
}

.merge-arrow .el-icon {
  font-size: 24px;
}

.merge-result {
  min-width: 120px;
}

.result-node {
  text-align: center;
  background: #f0f9eb;
  border-color: #e1f3d8;
}

.result-node .el-icon {
  color: #67c23a;
  font-size: 20px;
  margin-right: 6px;
}
</style>