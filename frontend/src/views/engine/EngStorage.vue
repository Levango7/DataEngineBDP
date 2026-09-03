<template>
  <div class="eng-storage-page">
    <h1>{{ t('engStorage.title') }}</h1>
    <div class="sub">{{ t('engStorage.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="kpiLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="kpiError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ kpiError.message }}，
            <a href="javascript:void(0)" @click="reloadKpi">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('engStorage.kpi.virtualTables') }}</h3>
          <div class="kpi">{{ virtualTables?.length ?? 0 }}</div>
          <div class="meta">{{ t('engStorage.kpi.virtualTablesMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engStorage.kpi.mvCount') }}</h3>
          <div class="kpi s">{{ materializedViews?.length ?? 0 }}</div>
          <div class="meta">{{ t('engStorage.kpi.mvCountMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engStorage.kpi.cacheHitRate') }}</h3>
          <div class="kpi s">{{ cacheHitRate }}%</div>
          <div class="meta">
            {{ t('engStorage.kpi.cacheHitMeta', { hit: cacheStats?.hitCount ?? 0, miss: cacheStats?.missCount ?? 0 }) }}
          </div>
        </div>
        <div class="card">
          <h3>{{ t('engStorage.kpi.refreshToday') }}</h3>
          <div class="kpi">{{ cacheStats?.refreshToday ?? 0 }}</div>
          <div class="meta">{{ t('engStorage.kpi.refreshTodayMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：Tabs 切换虚拟表 / 物化视图 / 缓存统计 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-tabs v-model="activeTab" type="card" class="main-tabs">
          <el-tab-pane :label="t('engStorage.tabs.virtualTables')" name="virtual-tables" />
          <el-tab-pane :label="t('engStorage.tabs.mv')" name="materialized-views" />
          <el-tab-pane :label="t('engStorage.tabs.cache')" name="cache-stats" />
        </el-tabs>
        <div class="spacer"></div>
        <el-button v-if="activeTab === 'virtual-tables'" type="primary" @click="openRegisterDialog">
          {{ t('engStorage.vt.register') }}
        </el-button>
        <el-button :icon="Refresh" circle @click="reloadAll" />
      </div>

      <!-- Tab1 虚拟表列表 -->
      <template v-if="activeTab === 'virtual-tables'">
        <el-table
          v-loading="vtLoading"
          :data="virtualTables ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="vtError ? t('engStorage.vt.loadFailed') : t('engStorage.vt.empty')"
        >
          <el-table-column prop="tableName" :label="t('engStorage.vt.columns.name')" min-width="180" />
          <el-table-column prop="dataSourceType" :label="t('engStorage.vt.columns.dataSourceType')" width="140">
            <template #default="{ row }">
              <el-tag effect="plain" size="small">{{ row.dataSourceType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dataSourceName" :label="t('engStorage.vt.columns.dataSource')" width="140" />
          <el-table-column prop="schema" :label="t('engStorage.vt.columns.schema')" min-width="160" />
          <el-table-column :label="t('engStorage.vt.columns.status')" width="110">
            <template #default="{ row }">
              <el-tag :type="vtStatusTagType(row.status)" effect="light" size="small">
                {{ vtStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lastQueryAt" :label="t('engStorage.vt.columns.lastQuery')" width="180" />
          <el-table-column :label="t('engStorage.vt.columns.actions')" width="240" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openQueryDialog(row)">{{ t('engStorage.vt.actions.query') }}</el-button>
              <el-button link type="primary" @click="handleTestConnection(row)">{{ t('engStorage.vt.actions.test') }}</el-button>
              <el-button link type="warning" @click="handleRefreshVt(row)">{{ t('engStorage.vt.actions.refresh') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab2 物化视图列表 -->
      <template v-else-if="activeTab === 'materialized-views'">
        <el-table
          v-loading="mvLoading"
          :data="materializedViews ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="mvError ? t('engStorage.mv.loadFailed') : t('engStorage.mv.empty')"
        >
          <el-table-column prop="viewName" :label="t('engStorage.mv.columns.name')" min-width="180" />
          <el-table-column prop="sourceTable" :label="t('engStorage.mv.columns.sourceTable')" min-width="160" />
          <el-table-column prop="refreshStrategy" :label="t('engStorage.mv.columns.refreshStrategy')" width="140" />
          <el-table-column prop="lastRefreshAt" :label="t('engStorage.mv.columns.lastRefresh')" width="180" />
          <el-table-column :label="t('engStorage.mv.columns.status')" width="120">
            <template #default="{ row }">
              <el-tag :type="mvStatusTagType(row.status)" effect="light" size="small">
                {{ row.status ?? t('engStorage.cache.noData') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('engStorage.mv.columns.rowCount')" width="120" align="right">
            <template #default="{ row }">{{ row.rowCount ?? t('engStorage.cache.noData') }}</template>
          </el-table-column>
          <el-table-column :label="t('engStorage.mv.columns.actions')" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" @click="handleRefreshMv(row)">{{ t('engStorage.mv.actions.refresh') }}</el-button>
              <el-button link type="primary" @click="handleViewMvStatus(row)">{{ t('engStorage.mv.actions.viewStatus') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab3 缓存统计 -->
      <template v-else>
        <el-descriptions v-loading="cacheLoading" :column="3" border :title="t('engStorage.cache.title')">
          <el-descriptions-item :label="t('engStorage.cache.hitRate')">
            {{ cacheStats ? cacheStats.hitRate + '%' : t('engStorage.cache.noData') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engStorage.cache.totalEntries')">
            {{ cacheStats?.totalEntries ?? t('engStorage.cache.noData') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engStorage.cache.size')">
            {{ cacheStats?.sizeMb != null ? cacheStats.sizeMb + t('engStorage.cache.sizeUnit') : t('engStorage.cache.noData') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engStorage.cache.hitCount')">
            {{ cacheStats?.hitCount ?? t('engStorage.cache.noData') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engStorage.cache.missCount')">
            {{ cacheStats?.missCount ?? t('engStorage.cache.noData') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engStorage.cache.refreshToday')">
            {{ cacheStats?.refreshToday ?? t('engStorage.cache.noData') }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- 注册虚拟表弹窗 -->
    <el-dialog
      v-model="registerDialogVisible"
      :title="t('engStorage.register.title')"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetRegisterForm"
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('engStorage.register.fields.tableName')" prop="tableName">
          <el-input v-model="registerForm.tableName" :placeholder="t('engStorage.register.fields.tableNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('engStorage.register.fields.dataSourceType')" prop="dataSourceType">
          <el-select
            v-model="registerForm.dataSourceType"
            :placeholder="t('engStorage.register.fields.dataSourceTypePlaceholder')"
            style="width: 100%"
          >
            <el-option
              v-for="dt in dataSourceTypes ?? defaultTypes"
              :key="dt"
              :label="dt"
              :value="dt"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('engStorage.register.fields.dataSourceName')" prop="dataSourceName">
          <el-input v-model="registerForm.dataSourceName" :placeholder="t('engStorage.register.fields.dataSourceNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('engStorage.register.fields.schema')" prop="schema">
          <el-input v-model="registerForm.schema" :placeholder="t('engStorage.register.fields.schemaPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('engStorage.register.fields.comment')" prop="comment">
          <el-input v-model="registerForm.comment" type="textarea" :rows="2" :placeholder="t('engStorage.register.fields.commentPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialogVisible = false">{{ t('engStorage.register.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="registering" @click="handleRegister">{{ t('engStorage.register.actions.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 虚拟表查询结果弹窗 -->
    <el-dialog
      v-model="queryDialogVisible"
      :title="t('engStorage.query.title', { name: currentQueryTable ?? '' })"
      width="800px"
      :close-on-click-modal="true"
    >
      <div v-loading="querying" class="query-result">
        <template v-if="queryResult">
          <div class="query-meta">
            {{ t('engStorage.query.meta', { rows: queryResult.rowCount, ms: queryResult.durationMs ?? t('engStorage.cache.noData') }) }}
          </div>
          <el-table
            :data="queryResult.rows"
            stripe
            border
            size="small"
            style="width: 100%"
            max-height="420"
          >
            <el-table-column
              v-for="(col, idx) in queryResult.columns"
              :key="idx"
              :prop="String(idx)"
              :label="col"
              min-width="120"
            >
              <template #default="{ row }">{{ row[idx] }}</template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!querying" :description="t('engStorage.query.empty')" />
      </div>
      <template #footer>
        <el-button @click="queryDialogVisible = false">{{ t('engStorage.query.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type {
  VirtualTableDefinition,
  MaterializedViewDef,
  CacheStats,
  VirtualTableQueryResult,
  VirtualTableStatus
} from '@/api/engine'

const { t, te } = useI18n()

/* ------------------------------ 数据加载 ------------------------------ */

const activeTab = ref<'virtual-tables' | 'materialized-views' | 'cache-stats'>('virtual-tables')

const {
  data: virtualTables,
  loading: vtLoading,
  error: vtError,
  execute: loadVt
} = useApi<VirtualTableDefinition[]>(() => engineApi.getVirtualTables())

const {
  data: materializedViews,
  loading: mvLoading,
  error: mvError,
  execute: loadMv
} = useApi<MaterializedViewDef[]>(() => engineApi.getMaterializedViews())

const {
  data: cacheStats,
  loading: cacheLoading,
  execute: loadCache
} = useApi<CacheStats>(() => engineApi.getCacheStats())

const { data: dataSourceTypes, execute: loadTypes } = useApi<string[]>(() =>
  engineApi.listDataSourceTypes()
)

/** KPI 区聚合 loading */
const kpiLoading = computed(() => vtLoading.value || mvLoading.value || cacheLoading.value)
/** KPI 区聚合 error */
const kpiError = computed(() => vtError.value ?? mvError.value ?? null)
/** 缓存命中率 */
const cacheHitRate = computed(() => cacheStats.value?.hitRate ?? 0)

/** 默认数据源类型（接口失败#拉取失败时兜底） */
const defaultTypes = ['mysql', 'postgresql', 'doris', 'hive', 'kafka', 'iotdb']

/** 重新加载所有 */
async function reloadAll() {
  await Promise.all([loadVt(), loadMv(), loadCache(), loadTypes()])
}

/** 重新加载 KPI */
async function reloadKpi() {
  await reloadAll()
}

/* ------------------------------ 注册虚拟表 ------------------------------ */

const registerDialogVisible = ref(false)
const registering = ref(false)
const registerFormRef = ref<FormInstance>()

interface RegisterForm {
  tableName: string
  dataSourceType: string
  dataSourceName: string
  schema: string
  comment: string
}

const registerForm = reactive<RegisterForm>({
  tableName: '',
  dataSourceType: 'mysql',
  dataSourceName: '',
  schema: '',
  comment: ''
})

const registerRules = computed<FormRules>(() => ({
  tableName: [{ required: true, message: t('engStorage.rules.tableNameRequired'), trigger: 'blur' }],
  dataSourceType: [{ required: true, message: t('engStorage.rules.dataSourceTypeRequired'), trigger: 'change' }]
}))

/** 打开注册弹窗 */
function openRegisterDialog() {
  resetRegisterForm()
  registerDialogVisible.value = true
}

/** 重置注册表单 */
function resetRegisterForm() {
  registerForm.tableName = ''
  registerForm.dataSourceType = 'mysql'
  registerForm.dataSourceName = ''
  registerForm.schema = ''
  registerForm.comment = ''
  registerFormRef.value?.clearValidate()
}

/** 提交注册 */
async function handleRegister() {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    registering.value = true
    try {
      await engineApi.registerVirtualTable({
        tableName: registerForm.tableName,
        dataSourceType: registerForm.dataSourceType,
        dataSourceName: registerForm.dataSourceName || undefined,
        schema: registerForm.schema || undefined,
        comment: registerForm.comment || undefined
      })
      ElMessage.success(t('engStorage.messages.registered'))
      registerDialogVisible.value = false
      await loadVt()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      registering.value = false
    }
  })
}

/* ------------------------------ 虚拟表操作 ------------------------------ */

/** 测试连接 */
async function handleTestConnection(row: VirtualTableDefinition) {
  try {
    const { connected } = await engineApi.testVirtualTableConnection(row.tableName)
    ElMessage[connected ? 'success' : 'error'](t(connected ? 'engStorage.messages.testOk' : 'engStorage.messages.testFailed'))
  } catch {
    // 拦截器已提示
  }
}

/** 刷新虚拟表 */
async function handleRefreshVt(row: VirtualTableDefinition) {
  try {
    const { rows } = await engineApi.refreshVirtualTable(row.tableName)
    ElMessage.success(t('engStorage.messages.vtRefreshed', { rows }))
    await loadVt()
  } catch {
    // 拦截器已提示
  }
}

/* ------------------------------ 物化视图操作 ------------------------------ */

/** 刷新物化视图 */
async function handleRefreshMv(row: MaterializedViewDef) {
  try {
    const { eventId } = await engineApi.refreshMaterializedView(row.viewName)
    ElMessage.success(t('engStorage.messages.mvRefreshed', { eventId }))
    await loadMv()
  } catch {
    // 拦截器已提示
  }
}

/** 查看物化视图状态 */
async function handleViewMvStatus(row: MaterializedViewDef) {
  try {
    const status = await engineApi.getMaterializedViewStatus(row.viewName)
    ElMessage.info(
      t('engStorage.messages.mvStatusFmt', { name: row.viewName, status: status.status }) +
        (status.lastRefreshAt ? t('engStorage.messages.mvStatusRefreshFmt', { time: status.lastRefreshAt }) : '') +
        (status.errorMessage ? t('engStorage.messages.mvStatusErrorFmt', { message: status.errorMessage }) : '')
    )
  } catch {
    // 拦截器已提示
  }
}

/* ------------------------------ 虚拟表查询 ------------------------------ */

const queryDialogVisible = ref(false)
const querying = ref(false)
const currentQueryTable = ref<string>('')
const queryResult = ref<VirtualTableQueryResult | null>(null)

/** 打开查询弹窗 */
async function openQueryDialog(row: VirtualTableDefinition) {
  currentQueryTable.value = row.tableName
  queryDialogVisible.value = true
  querying.value = true
  queryResult.value = null
  try {
    queryResult.value = await engineApi.queryVirtualTable(row.tableName, undefined, 100)
  } catch {
    // 拦截器已提示
  } finally {
    querying.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 虚拟表状态 → 词条 */
function vtStatusLabel(status?: VirtualTableStatus | string): string {
  if (!status) return t('engStorage.cache.noData')
  const key = `engStorage.vt.status.${status}`
  return te(key) ? t(key) : status
}

/** 虚拟表状态 → tag 类型 */
function vtStatusTagType(
  status?: VirtualTableStatus | string
): 'success' | 'warning' | 'danger' | 'info' {
  if (!status) return 'info'
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    ACTIVE: 'success',
    INACTIVE: 'info',
    ERROR: 'danger',
    REFRESHING: 'warning'
  }
  return map[status] ?? 'info'
}

/** 物化视图状态 → tag 类型 */
function mvStatusTagType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (!status) return 'info'
  const s = status.toUpperCase()
  if (s.includes('SUCCESS') || s.includes('ACTIVE')) return 'success'
  if (s.includes('FAIL') || s.includes('ERROR')) return 'danger'
  if (s.includes('REFRESH') || s.includes('RUN')) return 'warning'
  return 'info'
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void reloadAll()
  // 15s 轮询刷新
  timer = setInterval(() => void reloadAll(), 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.eng-storage-page {
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
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g4 {
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
.main-tabs {
  margin-left: 0;
}
.query-result {
  min-height: 240px;
}
.query-meta {
  color: var(--ds-text-secondary);
  font-size: 12px;
  margin-bottom: 12px;
}
</style>
