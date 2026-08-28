<template>
  <div class="ds-page" role="main" aria-label="数据源管理页面">
    <h1>数据源管理</h1>
    <div class="sub">
      统一管理平台数据接入源，支持 MySQL / PostgreSQL / ClickHouse / Kafka
      等多类型数据源的注册、测试与维护。
    </div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar" role="toolbar" aria-label="数据源列表操作栏">
        <el-button type="primary" aria-label="新增数据源" @click="openCreateDialog">
          + 新增数据源
        </el-button>
        <el-input
          v-model="searchKeyword"
          placeholder="按名称搜索"
          clearable
          style="width: 220px"
          aria-label="按数据源名称搜索"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterType"
          placeholder="类型筛选"
          clearable
          style="width: 160px"
          aria-label="按数据源类型筛选"
          @change="handleSearch"
        >
          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle aria-label="刷新数据源列表" @click="loadList" />
      </div>

      <!-- 数据源列表 -->
      <el-table
        v-loading="loading"
        :data="dsList"
        stripe
        border
        role="table"
        aria-label="数据源列表表格"
        :empty-text="error ? '加载失败，请重试' : '暂无数据源'"
      >
        <el-table-column prop="id" label="ID" width="120" />
        <el-table-column prop="name" label="数据源名称" min-width="160" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag effect="light">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主机:端口" width="180">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="database" label="数据库" width="140" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :aria-label="`编辑数据源 ${row.name}`"
              @click="openEditDialog(row)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="success"
              :loading="testingId === row.id"
              :aria-label="`测试数据源 ${row.name} 连接`"
              @click="handleTest(row)"
            >
              测试连接
            </el-button>
            <el-button
              link
              type="danger"
              :aria-label="`删除数据源 ${row.name}`"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap" role="navigation" aria-label="数据源列表分页">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          aria-label="分页导航"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑数据源' : '新增数据源'"
      width="560px"
      :close-on-click-modal="false"
      role="dialog"
      aria-modal="true"
      :aria-label="isEdit ? '编辑数据源弹窗' : '新增数据源弹窗'"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="数据源名称" prop="name">
          <el-input v-model="formData.name" placeholder="如 业务订单库" aria-label="数据源名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select
            v-model="formData.type"
            style="width: 100%"
            aria-label="数据源类型"
            @change="onTypeChange"
          >
            <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机地址" prop="host">
          <el-input v-model="formData.host" placeholder="如 192.168.1.10" aria-label="主机地址" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number
            v-model="formData.port"
            :min="1"
            :max="65535"
            controls-position="right"
            style="width: 100%"
            aria-label="端口号"
          />
        </el-form-item>
        <el-form-item v-if="needDatabase" label="数据库" prop="database">
          <el-input
            v-model="formData.database"
            placeholder="数据库名 / Kafka topic 前缀"
            aria-label="数据库名或Kafka topic前缀"
          />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="formData.username" placeholder="登录用户名" aria-label="登录用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="formData.password"
            type="password"
            show-password
            :placeholder="isEdit ? '不修改请留空' : '登录密码'"
            aria-label="登录密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button aria-label="取消操作" @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :aria-label="isEdit ? '保存数据源' : '创建数据源'"
          @click="handleSubmit"
        >
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as datasourceApi from '@/api/datasource'
import type {
  DataSource,
  DataSourceType,
  DataSourceStatus,
  SaveDataSourceParams
} from '@/api/datasource'
import type { PagedResult } from '@/api/types'

/* ------------------------------ 类型选项 ------------------------------ */

const typeOptions: { label: string; value: DataSourceType }[] = [
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgresql' },
  { label: 'ClickHouse', value: 'clickhouse' },
  { label: 'Kafka', value: 'kafka' },
  { label: 'Hive', value: 'hive' },
  { label: 'Oracle', value: 'oracle' },
  { label: 'SQL Server', value: 'sqlserver' },
  { label: 'Doris', value: 'doris' },
  { label: 'Trino', value: 'trino' }
]

/** 各类型默认端口 */
const defaultPortMap: Record<DataSourceType, number> = {
  mysql: 3306,
  postgresql: 5432,
  clickhouse: 8123,
  kafka: 9092,
  hive: 10000,
  oracle: 1521,
  sqlserver: 1433,
  doris: 9030,
  trino: 8080
}

/* ------------------------------ 列表查询 ------------------------------ */

// 数据源列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: dsPaged,
  loading,
  error,
  execute: loadList
} = useApi<PagedResult<DataSource>>(
  () =>
    datasourceApi.listDataSources({
      keyword: searchKeyword.value || undefined,
      type: filterType.value || undefined,
      page: currentPage.value,
      pageSize: pageSize.value
    }),
  {
    onError: () => ElMessage.error('数据源列表加载失败')
  }
)

const dsList = computed<DataSource[]>(() => dsPaged.value?.list ?? [])
const total = computed<number>(() => dsPaged.value?.total ?? 0)
const currentPage = ref(1)
const pageSize = ref(20)
const searchKeyword = ref('')
const filterType = ref<DataSourceType | ''>('')

/** 搜索 */
function handleSearch() {
  currentPage.value = 1
  void loadList()
}

/* ------------------------------ 创建/编辑 ------------------------------ */

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string>('')

interface DsForm {
  name: string
  type: DataSourceType
  host: string
  port: number
  database: string
  username: string
  password: string
}

const formData = reactive<DsForm>({
  name: '',
  type: 'mysql',
  host: '',
  port: 3306,
  database: '',
  username: '',
  password: ''
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }]
}

/** 是否需要数据库字段 */
const needDatabase = computed(() => {
  return formData.type !== 'kafka'
})

/** 类型切换时同步默认端口 */
function onTypeChange(type: DataSourceType) {
  formData.port = defaultPortMap[type] || 3306
}

/** 打开新建弹窗 */
function openCreateDialog() {
  isEdit.value = false
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEditDialog(row: DataSource) {
  isEdit.value = true
  editingId.value = row.id
  formData.name = row.name
  formData.type = row.type
  formData.host = row.host
  formData.port = row.port
  formData.database = row.database || ''
  formData.username = row.username
  formData.password = ''
  dialogVisible.value = true
}

/** 重置表单 */
function resetForm() {
  formData.name = ''
  formData.type = 'mysql'
  formData.host = ''
  formData.port = 3306
  formData.database = ''
  formData.username = ''
  formData.password = ''
  formRef.value?.clearValidate()
}

/** 提交表单 */
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const payload: SaveDataSourceParams = {
        name: formData.name,
        type: formData.type,
        host: formData.host,
        port: formData.port,
        username: formData.username
      }
      if (needDatabase.value && formData.database) {
        payload.database = formData.database
      }
      if (formData.password) {
        payload.password = formData.password
      }
      if (isEdit.value) {
        await datasourceApi.updateDataSource(editingId.value, payload)
        ElMessage.success('数据源已更新')
      } else {
        if (!formData.password) {
          ElMessage.warning('请输入密码')
          submitting.value = false
          return
        }
        payload.password = formData.password
        await datasourceApi.createDataSource(payload)
        ElMessage.success('数据源已创建')
      }
      dialogVisible.value = false
      await loadList()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 测试连接 ------------------------------ */

const testingId = ref<string>('')

/** 测试连接 */
async function handleTest(row: DataSource) {
  testingId.value = row.id
  try {
    const result = await datasourceApi.testDataSource(row.id)
    if (result.success) {
      ElMessage.success(`连接成功 · 耗时 ${result.latency ?? '--'} ms`)
    } else {
      ElMessage.error(`连接失败：${result.message}`)
    }
    // 测试后刷新列表以更新状态
    await loadList()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    testingId.value = ''
  }
}

/* ------------------------------ 删除 ------------------------------ */

/** 删除数据源 */
async function handleDelete(row: DataSource) {
  try {
    await ElMessageBox.confirm(
      `确定删除数据源「${row.name}」吗？关联的同步任务可能受影响。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await datasourceApi.deleteDataSource(row.id)
    ElMessage.success('数据源已删除')
    await loadList()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

function typeLabel(type: DataSourceType): string {
  const item = typeOptions.find((t) => t.value === type)
  return item?.label || type
}

const STATUS_MAP: Record<
  DataSourceStatus,
  { label: string; type: 'success' | 'info' | 'warning' }
> = {
  connected: { label: '已连接', type: 'success' },
  disconnected: { label: '未连接', type: 'info' },
  testing: { label: '测试中', type: 'warning' }
}

function statusLabel(status: DataSourceStatus): string {
  return STATUS_MAP[status]?.label ?? status
}

function statusTagType(status: DataSourceStatus): 'success' | 'info' | 'warning' {
  return STATUS_MAP[status]?.type ?? 'info'
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void loadList()
})
</script>

<style scoped>
.ds-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.page-card {
  border: 1px solid #e4e8ea;
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
