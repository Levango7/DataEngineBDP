<!--
  DataTable.vue — 数据表格展示（AI 助手内部组件）

  功能：
  - 渲染 TableData（列定义 + 行）
  - 支持双语列名
  - 支持截断提示
  - 支持数值右对齐、日期格式化
-->
<template>
  <div class="data-table-wrap">
    <div class="table-header">
      <span class="table-title">
        <el-icon><Grid /></el-icon>
        {{ t.title }}
      </span>
      <span class="table-meta">
        {{ table.rows.length }} / {{ table.total }} {{ t.rows }}
        <el-tag v-if="table.truncated" size="small" type="warning" effect="plain">
          {{ t.truncated }}
        </el-tag>
      </span>
    </div>

    <el-table :data="table.rows" border stripe size="small" :max-height="360" style="width: 100%">
      <el-table-column
        v-for="col in table.columns"
        :key="col.name"
        :prop="col.name"
        :label="locale === 'zh' ? col.label.zh : col.label.en"
        :min-width="columnWidth(col)"
        :align="col.isMetric ? 'right' : 'left'"
      >
        <template #default="{ row }">
          <span :class="{ 'num-cell': col.isMetric }">
            {{ formatCell(row[col.name], col) }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElTable, ElTableColumn, ElTag, ElIcon } from 'element-plus'
import { Grid } from '@element-plus/icons-vue'
import type { TableData, TableColumn, Locale, ColumnDataType } from '@/types/ai-assistant'

interface Props {
  table: TableData
  locale: Locale
}
const props = defineProps<Props>()

const t = computed(() =>
  props.locale === 'zh'
    ? { title: '查询结果', rows: '行', truncated: '已截断' }
    : { title: 'Query Result', rows: 'rows', truncated: 'Truncated' }
)

function columnWidth(col: TableColumn): number {
  if (col.dataType === 'date' || col.dataType === 'datetime') return 160
  if (col.dataType === 'string') return 140
  return 110
}

function formatCell(value: unknown, col: TableColumn): string {
  if (value === null || value === undefined) return ''
  if (col.dataType === 'date' || col.dataType === 'datetime') {
    const d = new Date(String(value))
    if (!isNaN(d.getTime())) {
      return col.dataType === 'datetime'
        ? d.toLocaleString(props.locale === 'zh' ? 'zh-CN' : 'en-US')
        : d.toLocaleDateString(props.locale === 'zh' ? 'zh-CN' : 'en-US')
    }
  }
  if (col.dataType === 'float') {
    const n = Number(value)
    return isNaN(n) ? String(value) : n.toFixed(2)
  }
  if (col.dataType === 'integer') {
    const n = Number(value)
    return isNaN(n) ? String(value) : n.toLocaleString(props.locale === 'zh' ? 'zh-CN' : 'en-US')
  }
  return String(value)
}

// 显式标注未使用的类型，避免 lint 报错（dataType 已通过 col.dataType 使用）
void (0 as unknown as ColumnDataType)
</script>

<style scoped>
.data-table-wrap {
  width: 100%;
}
.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}
.table-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ink);
}
.table-meta {
  color: var(--muted);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.num-cell {
  font-variant-numeric: tabular-nums;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
</style>
