<!--
  SqlPreview.vue — SQL 预览组件（T011）

  功能：
  - 语法高亮（关键字 / 字符串 / 注释）
  - 显示方言、涉及表、列、跨源标识、置信度、耗时
  - 复制 SQL 到剪贴板
  - 重新执行按钮（emit reexecute）
  - 折叠 / 展开

  事件：
  - reexecute 重新执行
-->
<template>
  <div class="sql-preview">
    <!-- 头部 -->
    <div class="sql-header">
      <div class="sql-title">
        <el-icon><Document /></el-icon>
        <span>SQL</span>
        <el-tag size="small" effect="plain" type="info">
          {{ dialectLabel }}
        </el-tag>
        <el-tag v-if="meta?.crossSource" size="small" effect="light" type="warning">
          {{ t.crossSource }}
        </el-tag>
      </div>
      <div class="sql-actions">
        <el-tooltip :content="t.copy" placement="top">
          <el-button :icon="CopyDocument" circle text size="small" @click="copySql" />
        </el-tooltip>
        <el-tooltip :content="collapsed ? t.expand : t.collapse" placement="top">
          <el-button
            :icon="collapsed ? ArrowDown : ArrowUp"
            circle
            text
            size="small"
            @click="collapsed = !collapsed"
          />
        </el-tooltip>
        <el-button
          type="primary"
          size="small"
          :icon="CaretRight"
          @click="emit('reexecute')"
        >
          {{ t.rerun }}
        </el-button>
      </div>
    </div>

    <!-- SQL 代码（已通过 DOMPurify 净化防 XSS） -->
    <div v-show="!collapsed" class="sql-code-wrap">
      <!-- eslint-disable-next-line vue/no-v-html -- 已通过 DOMPurify 净化，安全使用 v-html -->
      <pre class="sql-code" v-html="sanitizedSql"></pre>
    </div>

    <!-- 元信息 -->
    <div v-if="meta && !collapsed" class="sql-meta">
      <div class="meta-row">
        <span class="meta-label">{{ t.tables }}</span>
        <el-tag
          v-for="tb in meta.tables"
          :key="tb"
          size="small"
          effect="plain"
          type="info"
        >
          {{ tb }}
        </el-tag>
      </div>
      <div class="meta-row">
        <span class="meta-label">{{ t.confidence }}</span>
        <el-progress
          :percentage="Math.round(meta.confidence * 100)"
          :stroke-width="8"
          :show-text="true"
          style="width: 180px"
        />
      </div>
      <div class="meta-row">
        <span class="meta-label">{{ t.duration }}</span>
        <span class="meta-value">{{ meta.durationMs }} ms</span>
      </div>
    </div>

    <!-- 复制成功提示 -->
    <transition name="fade">
      <div v-if="copied" class="copy-toast">{{ t.copied }}</div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  ElButton,
  ElIcon,
  ElTag,
  ElTooltip,
  ElProgress
} from 'element-plus'
import {
  Document,
  CopyDocument,
  CaretRight,
  ArrowUp,
  ArrowDown
} from '@element-plus/icons-vue'
import DOMPurify from 'dompurify'
import type { SqlMeta, Locale, SqlDialect } from '@/types/ai-assistant'
import { SQL_DIALECT_LABELS } from '@/types/ai-assistant'

interface Props {
  /** SQL 文本 */
  sql: string
  /** 元信息 */
  meta?: SqlMeta
  /** 语言 */
  locale: Locale
}
const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'reexecute'): void
}>()

/* ------------------------------ 状态 ------------------------------ */
const collapsed = ref(false)
const copied = ref(false)

/* ------------------------------ 文案 ------------------------------ */
const t = computed(() =>
  props.locale === 'zh'
    ? {
        crossSource: '跨源',
        copy: '复制 SQL',
        collapse: '折叠',
        expand: '展开',
        rerun: '重新执行',
        copied: '已复制',
        tables: '涉及表',
        confidence: '置信度',
        duration: '生成耗时'
      }
    : {
        crossSource: 'Cross-Source',
        copy: 'Copy SQL',
        collapse: 'Collapse',
        expand: 'Expand',
        rerun: 'Rerun',
        copied: 'Copied',
        tables: 'Tables',
        confidence: 'Confidence',
        duration: 'Generation Time'
      }
)

const dialectLabel = computed(() => {
  const d: SqlDialect = props.meta?.dialect ?? 'ANSI'
  const label = SQL_DIALECT_LABELS[d]
  return props.locale === 'zh' ? label.zh : label.en
})

/* ------------------------------ 语法高亮 ------------------------------ */
const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'JOIN', 'LEFT JOIN',
  'RIGHT JOIN', 'INNER JOIN', 'OUTER JOIN', 'FULL JOIN', 'ON', 'AS', 'AND', 'OR',
  'NOT', 'IN', 'NOT IN', 'EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
  'UNION', 'UNION ALL', 'INTERSECT', 'EXCEPT', 'LIMIT', 'OFFSET', 'DISTINCT',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'WITH', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'DROP', 'ALTER', 'TRUNCATE',
  'OVER', 'PARTITION BY', 'ROW_NUMBER', 'RANK', 'DENSE_RANK', 'CAST', 'CONVERT'
]

const highlightedSql = computed(() => {
  let html = escapeHtml(props.sql)
  // 注释（行内）
  html = html.replace(/(--[^\n]*)/g, '<span class="c">$1</span>')
  // 字符串
  html = html.replace(/('[^']*')/g, '<span class="s">$1</span>')
  // 关键字
  for (const kw of SQL_KEYWORDS) {
    const re = new RegExp(`\\b${kw.replace(/\s+/g, '\\s+')}\\b`, 'gi')
    html = html.replace(re, (match) => `<span class="k">${match.toUpperCase()}</span>`)
  }
  return html
})

/**
 * 净化后的 SQL 高亮 HTML：使用 DOMPurify 仅保留语法高亮所需的 <span> 标签，
 * 防止 SQL 文本中潜在的 HTML 片段引发 XSS。
 *
 * 安全策略：白名单（ALLOWED_TAGS/ALLOWED_ATTR）+ 黑名单（FORBID_TAGS/FORBID_ATTR）双层防御。
 * 白名单已只允许 <span class>，黑名单显式禁止脚本/表单/嵌入等危险标签与事件属性，
 * 防止未来误扩展白名单时引入 XSS 向量。
 */
const SANITIZE_OPTS = {
  ALLOWED_TAGS: ['span'],
  ALLOWED_ATTR: ['class'],
  FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'textarea', 'style', 'link', 'meta', 'base'],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onmouseout', 'onfocus', 'onblur', 'onchange', 'onsubmit', 'onreset', 'onabort', 'onanimationstart', 'style', 'src', 'href', 'xlink:href']
}
const sanitizedSql = computed(() =>
  DOMPurify.sanitize(highlightedSql.value, SANITIZE_OPTS)
)

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/* ------------------------------ 复制 ------------------------------ */
async function copySql(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.sql)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    // 降级：使用 textarea
    const ta = document.createElement('textarea')
    ta.value = props.sql
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      copied.value = true
      setTimeout(() => {
        copied.value = false
      }, 1500)
    } finally {
      document.body.removeChild(ta)
    }
  }
}
</script>

<style scoped>
.sql-preview {
  width: 100%;
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  position: relative;
}
.sql-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--c-surface-alt);
  border-bottom: 1px solid var(--line);
}
.sql-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}
.sql-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.sql-code-wrap {
  margin: 0;
  background: #fafbfc;
}
.sql-code {
  margin: 0;
  padding: 12px 14px;
  font-family: "SFMono-Regular", Consolas, "Courier New", monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--c-slate-700);
  max-height: 260px;
  overflow-y: auto;
}
.sql-code :deep(.k) {
  color: var(--c-violet);
  font-weight: 600;
}
.sql-code :deep(.s) {
  color: var(--c-green-600);
}
.sql-code :deep(.c) {
  color: var(--c-slate-400);
  font-style: italic;
}
.sql-meta {
  padding: 10px 14px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}
.meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.meta-label {
  color: var(--muted);
  min-width: 70px;
}
.meta-value {
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.copy-toast {
  position: absolute;
  top: 8px;
  right: 12px;
  background: var(--c-green-600);
  color: #fff;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 6px;
  z-index: 2;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>