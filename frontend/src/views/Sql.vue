<template>
  <div>
    <PageHeader :title="t('sql.title')" :subtitle="t('sql.subtitle')" />
    <div class="ide" style="grid-template-columns: 1fr 240px">
      <div class="code-wrap">
        <div class="tabs">
          <div class="tab on">{{ t('sql.tabFederated') }}</div>
          <div class="tab">{{ t('sql.tabNew') }}</div>
        </div>
        <textarea
          v-model="sqlText"
          class="code-editor"
          spellcheck="false"
          :placeholder="t('sql.editorPlaceholder')"
        ></textarea>
        <div ref="sqllogEl" class="runlog">
          <div v-for="(line, i) in sqllog" :key="i" :class="line.cls">{{ line.text }}</div>
        </div>
      </div>
      <div class="params">
        <h3 style="font-size: 14px">{{ t('sql.config') }}</h3>
        <label>{{ t('sql.routeEngine') }}</label>
        <el-select v-model="selectedEngine" class="engine-select">
          <el-option value="auto" :label="t('sql.engineAuto')" />
          <el-option value="trino" label="Trino" />
          <el-option value="doris" label="Doris" />
        </el-select>
        <label>{{ t('sql.timeout') }}</label>
        <el-input v-model="timeout" type="number" />
        <div class="chips" style="margin-top: 10px">
          <span class="chip on">iceberg</span>
          <span class="chip on">doris</span>
          <span class="chip">kafka</span>
        </div>
        <label>{{ t('sql.aiAssist') }}</label>
        <div style="display: flex; gap: 6px">
          <el-input
            v-model="aiInput"
            :placeholder="t('sql.aiPlaceholder')"
            style="flex: 1"
          />
          <button class="btn ghost sm" @click="handleAiGenerate">
            {{ t('sql.aiGenerate') }}
          </button>
        </div>
        <button class="btn" style="width: 100%; margin-top: 12px" @click="runSql">
          <el-icon class="play"><VideoPlay /></el-icon>
          {{ t('sql.execute') }}
        </button>
        <div class="note">{{ t('sql.gatewayNote') }}</div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>{{ t('sql.result') }}</h3>
      <div v-if="queryLoading" class="note">{{ t('sql.querying') }}</div>
      <div v-else-if="queryError" class="note" style="color: var(--ds-color-error-500)">
        {{ queryError.message }}，
        <a href="javascript:void(0)" @click="runSql">{{ t('common.retry') }}</a>
      </div>
      <el-table
        v-else-if="queryResult && queryResult.rows.length > 0"
        :data="queryResult.rows"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column
          v-for="(col, idx) in queryResult.columns"
          :key="idx"
          :label="col"
          :prop="String(idx)"
        >
          <template #default="{ row }">{{ formatCell(row[idx]) }}</template>
        </el-table-column>
      </el-table>
      <div v-else class="note">{{ t('sql.resultHint') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import { executeCrossSourceSql, type CrossSourceQueryResult } from '@/api/sqlworkbench'
import { VideoPlay } from '@element-plus/icons-vue'

const { t } = useI18n()
const store = useAppStore()

interface LogLine {
  cls: string
  text: string
}

const sqllog = ref<LogLine[]>([{ cls: 'info', text: t('sql.log.ready') }])
const sqllogEl = ref<HTMLElement | null>(null)

// 查询结果：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: queryResult,
  loading: queryLoading,
  error: queryError,
  execute: executeQuery
} = useApi<CrossSourceQueryResult, [string]>((sql: string) =>
  executeCrossSourceSql({ sql, dialect: 'ANSI' })
)

/** 格式化单元格显示 */
function formatCell(val: unknown): string {
  if (val === null || val === undefined) return t('common.null')
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

/** 可编辑 SQL（打通链路：用户输入 → 网关 → 引擎） */
const sqlText = ref('SELECT city, COUNT(*) cnt FROM doris.dim.user GROUP BY city')

/** 路由引擎选择 */
const selectedEngine = ref('auto')

/** 查询超时设置（秒） */
const timeout = ref(120)

/** AI 助手输入（自然语言 → SQL） */
const aiInput = ref('')

/** AI 生成 SQL（mock：待接入真实链路） */
function handleAiGenerate(): void {
  if (!aiInput.value.trim()) return
  store.showToast(t('sql.aiTodo'))
}

/** 示例 SQL（与模板中展示的联邦查询保持一致） */
const SAMPLE_SQL =
  'SELECT u.city, SUM(p.amount) gmv FROM iceberg.ods.orders o JOIN doris.dim.user u ON o.user_id = u.user_id GROUP BY u.city ORDER BY gmv DESC'

/** 渐进式渲染日志（逐行 push，自动滚动到底部） */
function renderLogs(lines: LogLine[]): void {
  sqllog.value = []
  let i = 0
  function step(): void {
    if (i >= lines.length) return
    sqllog.value.push(lines[i++])
    nextTick(() => {
      if (sqllogEl.value) sqllogEl.value.scrollTop = sqllogEl.value.scrollHeight
    })
    setTimeout(step, 500)
  }
  step()
}

async function runSql() {
  sqllog.value = [{ cls: 'info', text: t('sql.log.submit') }]
  const sql = sqlText.value.trim() || SAMPLE_SQL
  const result = await executeQuery(sql)
  if (result) {
    // 根据返回结果追加日志
    const lines: LogLine[] = [
      { cls: 'info', text: t('sql.log.parsed', { sources: result.sources.join(', ') }) },
      {
        cls: result.status === 'SUCCESS' ? 'ok' : 'info',
        text: t('sql.log.returned', { rows: result.rowCount, ms: result.durationMs })
      }
    ]
    if (result.error) {
      lines.push({ cls: 'info', text: t('sql.log.error', { msg: result.error }) })
    }
    renderLogs(lines)
  } else if (queryError.value) {
    sqllog.value.push({ cls: 'info', text: t('sql.log.error', { msg: queryError.value.message }) })
  }
}
</script>
