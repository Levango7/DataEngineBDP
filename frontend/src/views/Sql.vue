<template>
  <div>
    <h1>统一 SQL 查询</h1>
    <div class="sub">
      单一入口跨 Doris / Trino / Iceberg 联邦查询，Trino 作为统一 SQL 网关，底层引擎对客户透明。
    </div>
    <div class="ide" style="grid-template-columns: 1fr 240px">
      <div class="code-wrap">
        <div class="tabs">
          <div class="tab on">联邦查询</div>
          <div class="tab">+ 新查询</div>
        </div>
        <textarea
          v-model="sqlText"
          class="code-editor"
          spellcheck="false"
          placeholder="-- 输入 SQL，如：SELECT city, COUNT(*) cnt FROM doris.dim.user GROUP BY city"
        ></textarea>
        <div ref="sqllogEl" class="runlog">
          <div v-for="(line, i) in sqllog" :key="i" :class="line.cls">{{ line.text }}</div>
        </div>
      </div>
      <div class="params">
        <h3 style="font-size: 13px">查询配置</h3>
        <label>路由引擎</label>
        <select>
          <option>网关自动</option>
          <option>Trino</option>
          <option>Doris</option>
        </select>
        <label>超时(s)</label>
        <input value="120" />
        <div class="chips" style="margin-top: 10px">
          <span class="chip on">iceberg</span>
          <span class="chip on">doris</span>
          <span class="chip">kafka</span>
        </div>
        <label>AI 辅助（自然语言 → SQL）</label>
        <div style="display: flex; gap: 6px">
          <input placeholder="如：各城市 GMV 排名" style="flex: 1" />
          <button class="btn ghost sm" @click="store.showToast('已生成 SQL（mock）（待接入）')">
            生成
          </button>
        </div>
        <button class="btn" style="width: 100%; margin-top: 12px" @click="runSql">
          <svg class="play" viewBox="0 0 24 24"><path d="M7 5l12 7-12 7Z" /></svg>
          执行
        </button>
        <div class="note">网关自动选择最优引擎并下推，客户只见结果。</div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>结果预览</h3>
      <div v-if="queryLoading" class="note">查询中…</div>
      <div v-else-if="queryError" class="note" style="color: var(--red)">
        {{ queryError.message }}，
        <a href="javascript:void(0)" @click="runSql">重试</a>
      </div>
      <table v-else-if="queryResult && queryResult.rows.length > 0">
        <thead>
          <tr>
            <th v-for="(col, idx) in queryResult.columns" :key="idx">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rIdx) in queryResult.rows" :key="rIdx">
            <td v-for="(cell, cIdx) in row" :key="cIdx">{{ formatCell(cell) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="note">点击「执行」后由网关回填结果集与耗时/扫描量指标。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { executeCrossSourceSql, type CrossSourceQueryResult } from '@/api/sqlworkbench'

const store = useAppStore()

interface LogLine {
  cls: string
  text: string
}

const sqllog = ref<LogLine[]>([{ cls: 'info', text: '[就绪] 点击「执行」经统一 SQL 网关路由…' }])
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
  if (val === null || val === undefined) return 'NULL'
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

/** 可编辑 SQL（打通链路：用户输入 → 网关 → 引擎） */
const sqlText = ref('SELECT city, COUNT(*) cnt FROM doris.dim.user GROUP BY city')

/** 路由引擎选择 */
const selectedEngine = ref('auto')

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
  sqllog.value = [{ cls: 'info', text: '[网关] 提交查询…' }]
  const sql = sqlText.value.trim() || SAMPLE_SQL
  const result = await executeQuery(sql)
  if (result) {
    // 根据返回结果追加日志
    const lines: LogLine[] = [
      { cls: 'info', text: `[网关] 解析查询 → 涉及源 ${result.sources.join(', ')}` },
      {
        cls: result.status === 'SUCCESS' ? 'ok' : 'info',
        text: `[返回] ${result.rowCount} 行 · 耗时 ${result.durationMs}ms`
      }
    ]
    if (result.error) {
      lines.push({ cls: 'info', text: `[错误] ${result.error}` })
    }
    renderLogs(lines)
  } else if (queryError.value) {
    sqllog.value.push({ cls: 'info', text: `[错误] ${queryError.value.message}` })
  }
}
</script>
