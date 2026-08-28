<!--
  SearchExport.vue — 检索结果导出

  功能：
  - 支持三种格式：CSV / JSON / Excel(xlsx)
  - 支持导出范围：当前页 / 全部命中
  - 支持字段白名单选择
  - 优先调用后端导出 API 获取下载链接
  - 后端不可用时降级为前端本地导出（基于当前 results）

  事件：
  - exported(result) 导出成功
  - error(err) 导出失败
-->
<template>
  <div class="search-export">
    <el-button :icon="Download" @click="openDialog">导出</el-button>

    <el-dialog
      v-model="dialogVisible"
      title="导出检索结果"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" label-position="right">
        <!-- 格式 -->
        <el-form-item label="格式">
          <el-radio-group v-model="form.format">
            <el-radio-button value="csv">CSV</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
            <el-radio-button value="xlsx">Excel</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- 范围 -->
        <el-form-item label="范围">
          <el-radio-group v-model="form.scope">
            <el-radio value="current">当前页（{{ currentCount }} 条）</el-radio>
            <el-radio value="all">全部命中（{{ total }} 条）</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 字段选择 -->
        <el-form-item label="字段">
          <el-checkbox
            v-model="allFields"
            :indeterminate="someFieldsChecked"
            @change="toggleAllFields"
          >
            全选
          </el-checkbox>
          <el-checkbox-group v-model="form.fields" class="field-group">
            <el-checkbox
              v-for="f in fieldOptions"
              :key="f.value"
              :value="f.value"
              :label="f.label"
            />
          </el-checkbox-group>
        </el-form-item>

        <!-- 提示 -->
        <div class="export-tip">
          <el-icon><InfoFilled /></el-icon>
          <span v-if="form.scope === 'all' && total > 10000">
            全部命中超过 1 万条，导出可能耗时较长，建议缩小检索范围。
          </span>
          <span v-else>导出文件由平台生成，包含当前检索条件下的结果数据。</span>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="handleExport">开始导出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElRadioGroup,
  ElRadio,
  ElRadioButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElIcon
} from 'element-plus'
import { Download, InfoFilled } from '@element-plus/icons-vue'
import * as searchApi from '@/api/search'
import type {
  SearchQuery,
  SearchResultItem,
  ExportFormat,
  ExportScope,
  ExportResult
} from '@/types/search'

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 当前检索查询 */
  query: SearchQuery
  /** 当前页结果（用于本地导出降级） */
  results: SearchResultItem[]
  /** 总命中数 */
  total: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'exported', result: ExportResult): void
  (e: 'error', err: Error): void
}>()

/* ------------------------------ 状态 ------------------------------ */
const dialogVisible = ref(false)
const exporting = ref(false)

const form = reactive<{
  format: ExportFormat
  scope: ExportScope
  fields: string[]
}>({
  format: 'csv',
  scope: 'current',
  fields: []
})

/** 可导出字段 */
const fieldOptions = [
  { label: 'ID', value: 'id' },
  { label: '名称', value: 'name' },
  { label: '类型', value: 'type' },
  { label: '数据源', value: 'sourceName' },
  { label: '描述', value: 'description' },
  { label: '负责人', value: 'owner' },
  { label: '标签', value: 'tags' },
  { label: '创建时间', value: 'createdAt' },
  { label: '更新时间', value: 'updatedAt' },
  { label: '评分', value: 'score' }
]

/** 默认全选 */
const ALL_FIELD_VALUES = fieldOptions.map((f) => f.value)

/* ------------------------------ 字段全选 ------------------------------ */
const allFields = ref(true)
const someFieldsChecked = computed(
  () => form.fields.length > 0 && form.fields.length < ALL_FIELD_VALUES.length
)

function toggleAllFields(val: boolean | string | number): void {
  form.fields = val === true ? [...ALL_FIELD_VALUES] : []
}

/* ------------------------------ 计算 ------------------------------ */
const currentCount = computed(() => props.results.length)

/* ------------------------------ 事件 ------------------------------ */
function openDialog(): void {
  // 默认选中全部字段
  if (form.fields.length === 0) {
    form.fields = [...ALL_FIELD_VALUES]
    allFields.value = true
  }
  // 默认范围：当前页有数据则 current，否则 all
  form.scope = props.results.length > 0 ? 'current' : 'all'
  dialogVisible.value = true
}

async function handleExport(): Promise<void> {
  if (form.fields.length === 0) {
    ElMessage.warning('请至少选择一个导出字段')
    return
  }

  exporting.value = true
  try {
    // 优先调用后端导出
    const result = await searchApi.exportResults({
      query: props.query,
      format: form.format,
      scope: form.scope,
      fields: form.fields
    })

    // 触发下载
    triggerDownload(result.downloadUrl, result.filename)

    emit('exported', result)
    ElMessage.success(`导出成功，共 ${result.count} 条`)
    dialogVisible.value = false
  } catch (e) {
    // 后端导出失败，降级为前端本地导出
    if (form.scope === 'current' && props.results.length > 0) {
      try {
        localExport(props.results)
        ElMessage.success(`已本地导出 ${props.results.length} 条`)
        dialogVisible.value = false
        return
      } catch (localErr) {
        emit('error', localErr instanceof Error ? localErr : new Error(String(localErr)))
      }
    }
    const err = e instanceof Error ? e : new Error(String(e))
    emit('error', err)
    ElMessage.error(`导出失败：${err.message}`)
  } finally {
    exporting.value = false
  }
}

/** 触发浏览器下载 */
function triggerDownload(url: string, filename: string): void {
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/* ------------------------------ 本地导出降级 ------------------------------ */
/** 将结果项转换为可导出的扁平对象 */
function flattenItem(item: SearchResultItem): Record<string, unknown> {
  const all: Record<string, unknown> = {
    id: item.id,
    name: item.name,
    type: item.type,
    sourceName: item.sourceName,
    description: item.description,
    owner: item.owner ?? '',
    tags: item.tags.join('|'),
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    score: item.score
  }
  const picked: Record<string, unknown> = {}
  for (const f of form.fields) {
    picked[f] = all[f] ?? ''
  }
  return picked
}

/** 本地 CSV 导出 */
function exportCsv(items: SearchResultItem[]): void {
  const rows = items.map(flattenItem)
  const headers = form.fields
  const lines: string[] = [headers.join(',')]
  for (const row of rows) {
    const cells = headers.map((h) => {
      const v = String(row[h] ?? '')
      // CSV 转义：含逗号/引号/换行则用双引号包裹
      if (v.includes(',') || v.includes('"') || v.includes('\n')) {
        return `"${v.replace(/"/g, '""')}"`
      }
      return v
    })
    lines.push(cells.join(','))
  }
  const csv = '\ufeff' + lines.join('\n') // BOM 头，避免 Excel 中文乱码
  downloadBlob(new Blob([csv], { type: 'text/csv;charset=utf-8' }), 'search-results.csv')
}

/** 本地 JSON 导出 */
function exportJson(items: SearchResultItem[]): void {
  const rows = items.map(flattenItem)
  const json = JSON.stringify(rows, null, 2)
  downloadBlob(new Blob([json], { type: 'application/json;charset=utf-8' }), 'search-results.json')
}

/** 本地 Excel 导出（简化为 CSV 兼容 Excel） */
function exportXlsx(items: SearchResultItem[]): void {
  // 真正的 xlsx 需要 SheetJS 依赖；这里降级为带 .xls 扩展的 CSV，
  // Excel 可正常打开。如需严格 xlsx，可后续引入 xlsx 库。
  exportCsv(items)
  // 重命名已由 downloadBlob 处理，这里再补一份 .xls
  const rows = items.map(flattenItem)
  const headers = form.fields
  const lines: string[] = [headers.join('\t')]
  for (const row of rows) {
    lines.push(headers.map((h) => String(row[h] ?? '')).join('\t'))
  }
  const xls = '\ufeff' + lines.join('\n')
  downloadBlob(
    new Blob([xls], { type: 'application/vnd.ms-excel;charset=utf-8' }),
    'search-results.xls'
  )
}

function localExport(items: SearchResultItem[]): void {
  switch (form.format) {
    case 'csv':
      exportCsv(items)
      break
    case 'json':
      exportJson(items)
      break
    case 'xlsx':
      exportXlsx(items)
      break
  }
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  triggerDownload(url, filename)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<style scoped>
.search-export {
  display: inline-flex;
}
.field-group {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-left: 8px;
}
.export-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--c-amber-50, #fffbeb);
  border-radius: 6px;
  font-size: 12px;
  color: var(--amber, #c08a2e);
  margin-top: 8px;
}
</style>
