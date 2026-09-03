<template>
  <div class="ds-page" role="main" :aria-label="t('dataSourceManagement.title')">
    <h1>{{ t('dataSourceManagement.title') }}</h1>
    <div class="sub">
      {{ t('dataSourceManagement.subtitle') }}
    </div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar" role="toolbar" :aria-label="t('dataSourceManagement.table.paginationAria')">
        <el-button type="primary" :aria-label="t('dataSourceManagement.toolbar.create')" @click="openCreateDialog">
          {{ t('dataSourceManagement.toolbar.create') }}
        </el-button>
        <el-input
          v-model="searchKeyword"
          :placeholder="t('dataSourceManagement.toolbar.searchPlaceholder')"
          clearable
          style="width: 220px"
          :aria-label="t('dataSourceManagement.toolbar.searchAria')"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterType"
          :placeholder="t('dataSourceManagement.toolbar.typeFilterPlaceholder')"
          clearable
          style="width: 160px"
          :aria-label="t('dataSourceManagement.toolbar.typeFilterAria')"
          @change="handleSearch"
        >
          <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('dataSourceManagement.toolbar.refreshAria')" @click="loadList" />
      </div>

      <!-- 数据源列表 -->
      <el-table
        v-loading="loading"
        :data="dsList"
        stripe
        border
        role="table"
        :aria-label="t('dataSourceManagement.table.aria')"
        :empty-text="error ? t('dataSourceManagement.table.loadFailed') : t('dataSourceManagement.table.empty')"
      >
        <el-table-column prop="id" :label="t('dataSourceManagement.table.columns.id')" width="120" />
        <el-table-column prop="name" :label="t('dataSourceManagement.table.columns.name')" min-width="160" />
        <el-table-column :label="t('dataSourceManagement.table.columns.type')" width="120">
          <template #default="{ row }">
            <el-tag effect="light">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataSourceManagement.table.columns.hostPort')" width="180">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="database" :label="t('dataSourceManagement.table.columns.database')" width="140" />
        <el-table-column prop="username" :label="t('dataSourceManagement.table.columns.username')" width="120" />
        <el-table-column :label="t('dataSourceManagement.table.columns.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('dataSourceManagement.table.columns.createdAt')" width="180" />
        <el-table-column :label="t('dataSourceManagement.table.columns.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :aria-label="t('dataSourceManagement.table.actions.editAria', { name: row.name })"
              @click="openEditDialog(row)"
            >
              {{ t('dataSourceManagement.table.actions.edit') }}
            </el-button>
            <el-button
              link
              type="success"
              :loading="testingId === row.id"
              :aria-label="t('dataSourceManagement.table.actions.testAria', { name: row.name })"
              @click="handleTest(row)"
            >
              {{ t('dataSourceManagement.table.actions.test') }}
            </el-button>
            <el-button
              link
              type="danger"
              :aria-label="t('dataSourceManagement.table.actions.deleteAria', { name: row.name })"
              @click="handleDelete(row)"
            >
              {{ t('dataSourceManagement.table.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap" role="navigation" :aria-label="t('dataSourceManagement.table.paginationAria')">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          :aria-label="t('dataSourceManagement.table.paginationAria')"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('dataSourceManagement.dialog.editTitle') : t('dataSourceManagement.dialog.createTitle')"
      width="560px"
      :close-on-click-modal="false"
      role="dialog"
      aria-modal="true"
      :aria-label="isEdit ? t('dataSourceManagement.dialog.editAria') : t('dataSourceManagement.dialog.createAria')"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('dataSourceManagement.dialog.fields.name')" prop="name">
          <el-input v-model="formData.name" :placeholder="t('dataSourceManagement.dialog.fields.namePlaceholder')" :aria-label="t('dataSourceManagement.dialog.fields.nameAria')" />
        </el-form-item>
        <el-form-item :label="t('dataSourceManagement.dialog.fields.type')" prop="type">
          <el-select
            v-model="formData.type"
            style="width: 100%"
            :aria-label="t('dataSourceManagement.dialog.fields.typeAria')"
            @change="onTypeChange"
          >
            <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('dataSourceManagement.dialog.fields.host')" prop="host">
          <el-input v-model="formData.host" :placeholder="t('dataSourceManagement.dialog.fields.hostPlaceholder')" :aria-label="t('dataSourceManagement.dialog.fields.hostAria')" />
        </el-form-item>
        <el-form-item :label="t('dataSourceManagement.dialog.fields.port')" prop="port">
          <el-input-number
            v-model="formData.port"
            :min="1"
            :max="65535"
            controls-position="right"
            style="width: 100%"
            :aria-label="t('dataSourceManagement.dialog.fields.portAria')"
          />
        </el-form-item>
        <el-form-item v-if="needDatabase" :label="t('dataSourceManagement.dialog.fields.database')" prop="database">
          <el-input
            v-model="formData.database"
            :placeholder="t('dataSourceManagement.dialog.fields.databasePlaceholder')"
            :aria-label="t('dataSourceManagement.dialog.fields.databaseAria')"
          />
        </el-form-item>
        <el-form-item :label="t('dataSourceManagement.dialog.fields.username')" prop="username">
          <el-input v-model="formData.username" :placeholder="t('dataSourceManagement.dialog.fields.usernamePlaceholder')" :aria-label="t('dataSourceManagement.dialog.fields.usernameAria')" />
        </el-form-item>
        <el-form-item :label="t('dataSourceManagement.dialog.fields.password')" prop="password">
          <el-input
            v-model="formData.password"
            type="password"
            show-password
            :placeholder="isEdit ? t('dataSourceManagement.dialog.fields.passwordPlaceholderEdit') : t('dataSourceManagement.dialog.fields.passwordPlaceholderCreate')"
            :aria-label="t('dataSourceManagement.dialog.fields.passwordAria')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :aria-label="t('dataSourceManagement.dialog.actions.cancelAria')" @click="dialogVisible = false">{{ t('dataSourceManagement.dialog.actions.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :aria-label="isEdit ? t('dataSourceManagement.dialog.actions.saveAria') : t('dataSourceManagement.dialog.actions.createAriaBtn')"
          @click="handleSubmit"
        >
          {{ isEdit ? t('dataSourceManagement.dialog.actions.save') : t('dataSourceManagement.dialog.actions.create') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()

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
    onError: () => ElMessage.error(t('dataSourceManagement.messages.listLoadFailed'))
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

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('dataSourceManagement.rules.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('dataSourceManagement.rules.typeRequired'), trigger: 'change' }],
  host: [{ required: true, message: t('dataSourceManagement.rules.hostRequired'), trigger: 'blur' }],
  port: [{ required: true, message: t('dataSourceManagement.rules.portRequired'), trigger: 'blur' }],
  username: [{ required: true, message: t('dataSourceManagement.rules.usernameRequired'), trigger: 'blur' }]
}))

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
        ElMessage.success(t('dataSourceManagement.messages.updated'))
      } else {
        if (!formData.password) {
          ElMessage.warning(t('dataSourceManagement.rules.passwordRequired'))
          submitting.value = false
          return
        }
        payload.password = formData.password
        await datasourceApi.createDataSource(payload)
        ElMessage.success(t('dataSourceManagement.messages.created'))
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
      const latency = result.latency ?? t('dataSourceManagement.messages.connectedUnknown')
      ElMessage.success(t('dataSourceManagement.messages.connectedOk', { latency }))
    } else {
      ElMessage.error(t('dataSourceManagement.messages.connectFailed', { message: result.message }))
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
      t('dataSourceManagement.messages.deleteConfirm', { name: row.name }),
      t('dataSourceManagement.messages.deleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('dataSourceManagement.messages.deleteConfirmOk'),
        cancelButtonText: t('dataSourceManagement.messages.deleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await datasourceApi.deleteDataSource(row.id)
    ElMessage.success(t('dataSourceManagement.messages.deleted'))
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

const STATUS_TAG_TYPE_MAP: Record<
  DataSourceStatus,
  'success' | 'info' | 'warning'
> = {
  connected: 'success',
  disconnected: 'info',
  testing: 'warning'
}

function statusLabel(status: DataSourceStatus): string {
  return t(`dataSourceManagement.status.${status}`)
}

function statusTagType(status: DataSourceStatus): 'success' | 'info' | 'warning' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
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
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
