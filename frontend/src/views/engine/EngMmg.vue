<template>
  <div class="eng-mmg-page">
    <h1>{{ t('engMmg.title') }}</h1>
    <div class="sub">{{ t('engMmg.subtitle') }}</div>

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
          <h3>{{ t('engMmg.kpi.modelCount') }}</h3>
          <div class="kpi">{{ MODEL_GROUPS.length }}</div>
          <div class="meta">{{ t('engMmg.kpi.modelCountMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engMmg.kpi.supportedTypes') }}</h3>
          <div class="kpi s">{{ supportedTypes?.length ?? 0 }}</div>
          <div class="meta">{{ t('engMmg.kpi.supportedTypesMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engMmg.kpi.virtualTables') }}</h3>
          <div class="kpi">{{ virtualTables?.length ?? 0 }}</div>
          <div class="meta">{{ t('engMmg.kpi.virtualTablesMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engMmg.kpi.queryCount') }}</h3>
          <div class="kpi">{{ queryCountToday }}</div>
          <div class="meta">{{ t('engMmg.kpi.queryCountMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 模型类型卡片网格 -->
    <div class="model-grid">
      <div
        v-for="group in MODEL_GROUPS"
        :key="group.key"
        class="model-card"
        :class="{ active: selectedModelKey === group.key }"
        @click="handleSelectModel(group.key)"
      >
        <div class="model-name">{{ t(`engMmg.modelGroups.${group.key}.label`, group.label) }}</div>
        <div class="model-count">{{ t(`engMmg.modelGroups.${group.key}.countFmt`, { count: modelTypeCount(group.types) }, `${modelTypeCount(group.types)} 个类型`) }}</div>
        <div class="model-types">
          <el-tag
            v-for="tp in group.types"
            :key="tp"
            :type="isTypeSupported(tp) ? 'success' : 'info'"
            effect="light"
            size="small"
          >
            {{ tp }}
          </el-tag>
        </div>
      </div>
    </div>

    <!-- 主内容区：虚拟表列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-select
          v-model="selectedTypeFilter"
          :placeholder="t('engMmg.toolbar.typeFilterPlaceholder')"
          clearable
          style="width: 220px"
          @change="handleTypeFilterChange"
        >
          <el-option v-for="t in supportedTypes ?? allTypeOptions" :key="t" :label="t" :value="t" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('engMmg.toolbar.refreshAria')" @click="reloadAll" />
      </div>

      <el-table
        v-loading="vtLoading"
        :data="virtualTables ?? []"
        stripe
        border
        style="width: 100%"
        :empty-text="vtError ? t('engMmg.table.loadFailed') : t('engMmg.table.empty')"
      >
        <el-table-column prop="tableName" :label="t('engMmg.table.columns.name')" min-width="200" />
        <el-table-column :label="t('engMmg.table.columns.dataSourceType')" width="140">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.dataSourceType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('engMmg.table.columns.model')" width="120">
          <template #default="{ row }">
            <el-tag :type="modelTagType(row.dataSourceType)" effect="light" size="small">
              {{ modelLabel(row.dataSourceType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dataSourceName" :label="t('engMmg.table.columns.dataSource')" width="140" />
        <el-table-column :label="t('engMmg.table.columns.rowCount')" width="140" align="right">
          <template #default="{ row }">{{ row.rowCount?.toLocaleString() ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="lastQueryAt" :label="t('engMmg.table.columns.lastQuery')" width="180" />
        <el-table-column :label="t('engMmg.table.columns.actions')" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openQueryDialog(row)">{{ t('engMmg.table.actions.query') }}</el-button>
            <el-button link type="primary" @click="handleTestConnection(row)">{{ t('engMmg.table.actions.test') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 跨模型查询弹窗 -->
    <el-dialog
      v-model="queryDialogVisible"
      :title="t('engMmg.query.title', { name: currentQueryTable ?? '' })"
      width="800px"
      :close-on-click-modal="true"
    >
      <div class="query-dialog">
        <el-input
          v-model="queryPredicate"
          :placeholder="t('engMmg.query.predicatePlaceholder')"
          style="
            font-family: 'SFMono-Regular', Consolas, monospace;
            font-size: 12.5px;
            margin-bottom: 12px;
          "
        />
        <div v-loading="querying" class="query-result">
          <template v-if="queryResult">
            <div class="query-meta">
              {{ t('engMmg.query.meta', { rows: queryResult.rowCount, ms: queryResult.durationMs ?? '--' }) }}
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
                :label="col"
                min-width="120"
              >
                <template #default="{ row }">{{ row[idx] }}</template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else-if="!querying" :description="t('engMmg.query.empty')" />
        </div>
      </div>
      <template #footer>
        <el-button @click="queryDialogVisible = false">{{ t('engMmg.query.close') }}</el-button>
        <el-button type="primary" :loading="querying" @click="handleExecuteQuery">{{ t('engMmg.query.execute') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import { MODEL_GROUPS, type ModelGroupKey } from '@/api/engine'
import type { VirtualTableDefinition, VirtualTableQueryResult } from '@/api/engine'

const { t } = useI18n()

/* ------------------------------ 数据加载 ------------------------------ */

const {
  data: supportedTypes,
  loading: typesLoading,
  error: typesError,
  execute: loadTypes
} = useApi<string[]>(() => engineApi.getMultiModelTypes())

const {
  data: virtualTables,
  loading: vtLoading,
  error: vtError,
  execute: loadVirtualTables
} = useApi<VirtualTableDefinition[]>(() =>
  engineApi.getMultiModelTables(selectedTypeFilter.value || '')
)

const selectedTypeFilter = ref<string>('')
const selectedModelKey = ref<ModelGroupKey | ''>('')
const queryCountToday = ref<number>(0)

/** KPI 区聚合 loading */
const kpiLoading = computed(() => typesLoading.value || vtLoading.value)
/** KPI 区聚合 error */
const kpiError = computed(() => typesError.value ?? vtError.value ?? null)

/** 重新加载所有 */
async function reloadAll() {
  await Promise.all([loadTypes(), loadVirtualTables()])
}

/** 重新加载 KPI */
async function reloadKpi() {
  await reloadAll()
}

/** 类型筛选变化 */
function handleTypeFilterChange() {
  selectedModelKey.value = ''
  void loadVirtualTables()
}

/** 模型卡片点击 */
function handleSelectModel(key: ModelGroupKey) {
  selectedModelKey.value = key
  const group = MODEL_GROUPS.find((g) => g.key === key)
  if (group) {
    // 选中模型后，加载该模型下第一个支持类型的虚拟表
    const firstSupported = group.types.find((t) => isTypeSupported(t))
    selectedTypeFilter.value = firstSupported ?? ''
    void loadVirtualTables()
  }
}

/* ------------------------------ 跨模型查询 ------------------------------ */

const queryDialogVisible = ref(false)
const querying = ref(false)
const currentQueryTable = ref<string>('')
const queryPredicate = ref<string>('')
const queryResult = ref<VirtualTableQueryResult | null>(null)

/** 打开查询弹窗 */
function openQueryDialog(row: VirtualTableDefinition) {
  currentQueryTable.value = row.tableName
  queryPredicate.value = ''
  queryResult.value = null
  queryDialogVisible.value = true
}

/** 执行跨模型查询 */
async function handleExecuteQuery() {
  if (!currentQueryTable.value) return
  querying.value = true
  queryResult.value = null
  try {
    queryResult.value = await engineApi.crossModelQuery(
      currentQueryTable.value,
      queryPredicate.value || undefined
    )
    queryCountToday.value++
    ElMessage.success(t('engMmg.messages.queryDone'))
  } catch {
    // 拦截器已提示
  } finally {
    querying.value = false
  }
}

/** 测试连接 */
async function handleTestConnection(row: VirtualTableDefinition) {
  try {
    const { connected } = await engineApi.testMultiModelConnection(row.tableName)
    ElMessage[connected ? 'success' : 'error'](t(connected ? 'engMmg.messages.testOk' : 'engMmg.messages.testFailed'))
  } catch {
    // 拦截器已提示
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 全部类型选项（接口未返回时使用 MODEL_GROUPS 静态定义） */
const allTypeOptions = computed(() => {
  const set = new Set<string>()
  MODEL_GROUPS.forEach((g) => g.types.forEach((t) => set.add(t)))
  return Array.from(set)
})

/** 判断类型是否被后端支持 */
function isTypeSupported(type: string): boolean {
  const list = supportedTypes.value
  if (!list || list.length === 0) return true // 接口未返回时不区分
  return list.includes(type)
}

/** 统计某模型分组下已支持的类型数 */
function modelTypeCount(types: readonly string[]): number {
  const list = supportedTypes.value
  if (!list || list.length === 0) return types.length
  return types.filter((t) => list.includes(t)).length
}

/** 数据源类型 → 所属模型标签 */
function modelLabel(type: string): string {
  for (const g of MODEL_GROUPS) {
    if (g.types.includes(type as never)) {
      return g.label
    }
  }
  return '未分类'
}

/** 数据源类型 → 模型 tag 颜色 */
function modelTagType(type: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  for (const g of MODEL_GROUPS) {
    if (g.types.includes(type as never)) {
      const map: Record<ModelGroupKey, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
        relational: 'primary',
        document: 'success',
        graph: 'warning',
        timeseries: 'danger',
        vector: 'info',
        kv: 'info'
      }
      return map[g.key]
    }
  }
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
.eng-mmg-page {
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
.model-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-top: 16px;
}
@media (max-width: 1100px) {
  .model-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}
.model-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.model-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}
.model-card.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.model-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--ds-text-primary);
  margin-bottom: 8px;
}
.model-count {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-bottom: 12px;
}
.model-types {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
.query-dialog {
  min-height: 240px;
}
.query-result {
  min-height: 200px;
}
.query-meta {
  color: var(--ds-text-secondary);
  font-size: 12px;
  margin-bottom: 12px;
}
</style>
