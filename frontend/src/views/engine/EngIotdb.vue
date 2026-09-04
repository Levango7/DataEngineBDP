<template>
  <div class="eng-iotdb-page">
    <h1>{{ t('engIotdb.title') }}</h1>
    <div class="sub">{{ t('engIotdb.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="instancesLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="instancesError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ instancesError.message }}，
            <a href="javascript:void(0)" @click="reloadInstances">
              {{ t('engines.kpi.loadFailedRetry') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('engIotdb.kpi.instances') }}</h3>
          <div class="kpi">{{ instances?.length ?? 0 }}</div>
          <div class="meta">{{ t('engIotdb.kpi.instancesMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engIotdb.kpi.storageGroups') }}</h3>
          <div class="kpi">{{ storageGroups?.length ?? 0 }}</div>
          <div class="meta">{{ t('engIotdb.kpi.storageGroupsMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engIotdb.kpi.devices') }}</h3>
          <div class="kpi s">{{ devices?.length ?? 0 }}</div>
          <div class="meta">{{ t('engIotdb.kpi.devicesMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engIotdb.kpi.writeRate') }}</h3>
          <div class="kpi">{{ writeRate }}</div>
          <div class="meta">{{ t('engIotdb.kpi.writeRateMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：左右分栏 -->
    <div class="split-layout">
      <!-- 左：实例选择 + 存储组/设备/测点树 -->
      <el-card shadow="never" class="page-card tree-card">
        <template #header>
          <div class="tree-header">
            <span>{{ t('engIotdb.catalog.title') }}</span>
          </div>
        </template>
        <el-select
          v-model="selectedInstanceId"
          :placeholder="t('engIotdb.catalog.selectInstance')"
          style="width: 100%; margin-bottom: 12px"
          @change="handleInstanceChange"
        >
          <el-option v-for="i in instances ?? []" :key="i.id" :label="i.name" :value="i.id" />
        </el-select>
        <el-tree
          :data="catalogTree"
          :props="{ label: 'label', children: 'children' }"
          :load="loadCatalogNode"
          lazy
          node-key="id"
          @node-click="handleNodeClick"
        />
      </el-card>

      <!-- 右：Tab [写入监控 | 时序预览 | SQL 工作台] -->
      <el-card shadow="never" class="page-card main-card">
        <div class="toolbar">
          <el-tabs v-model="activeTab" type="card" class="main-tabs">
            <el-tab-pane :label="t('engIotdb.tabs.throughput')" name="throughput" />
            <el-tab-pane :label="t('engIotdb.tabs.preview')" name="preview" />
            <el-tab-pane :label="t('engIotdb.tabs.sql')" name="sql" />
          </el-tabs>
          <div class="spacer"></div>
          <el-button :icon="Refresh" circle @click="reloadCurrent" />
        </div>

        <!-- Tab1 写入监控 -->
        <template v-if="activeTab === 'throughput'">
          <div v-loading="throughputLoading" class="throughput-panel">
            <template v-if="throughput && throughput.length > 0">
              <div class="throughput-summary">
                <span>{{ t('engIotdb.throughput.summaryFmt', { count: throughput.length }) }}</span>
                <span>
                  {{ t('engIotdb.throughput.maxRate', { rate: maxRate.toLocaleString() }) }}
                </span>
                <span>
                  {{ t('engIotdb.throughput.avgRate', { rate: avgRate.toLocaleString() }) }}
                </span>
              </div>
              <el-table
                :data="throughput"
                stripe
                border
                size="small"
                style="width: 100%"
                max-height="420"
              >
                <el-table-column
                  prop="timestamp"
                  :label="t('engIotdb.throughput.columns.timestamp')"
                  width="200"
                />
                <el-table-column
                  :label="t('engIotdb.throughput.columns.points')"
                  width="160"
                  align="right"
                >
                  <template #default="{ row }">{{ row.points.toLocaleString() }}</template>
                </el-table-column>
                <el-table-column
                  :label="t('engIotdb.throughput.columns.rate')"
                  width="200"
                  align="right"
                >
                  <template #default="{ row }">{{ row.rate.toLocaleString() }}</template>
                </el-table-column>
                <el-table-column :label="t('engIotdb.throughput.columns.ratio')">
                  <template #default="{ row }">
                    <el-progress
                      :percentage="Math.round((row.rate / maxRate) * 100)"
                      :stroke-width="14"
                      :text-inside="true"
                    />
                  </template>
                </el-table-column>
              </el-table>
            </template>
            <el-empty
              v-else-if="!throughputLoading"
              :description="
                throughputError
                  ? t('engIotdb.throughput.loadFailed')
                  : t('engIotdb.throughput.empty')
              "
            />
          </div>
        </template>

        <!-- Tab2 时序预览 -->
        <template v-else-if="activeTab === 'preview'">
          <div class="preview-panel">
            <div class="preview-form">
              <el-input
                v-model="previewDevice"
                :placeholder="t('engIotdb.preview.devicePlaceholder')"
                style="width: 280px"
              />
              <el-button
                type="primary"
                :loading="loadingTimeseries"
                :disabled="!selectedInstanceId || !previewDevice"
                @click="handleLoadTimeseries"
              >
                {{ t('engIotdb.preview.load') }}
              </el-button>
            </div>
            <el-table
              v-loading="loadingTimeseries"
              :data="timeseries"
              stripe
              border
              style="width: 100%; margin-top: 12px"
              :empty-text="
                timeseriesError ? t('engIotdb.preview.loadFailed') : t('engIotdb.preview.empty')
              "
            >
              <el-table-column
                prop="name"
                :label="t('engIotdb.preview.columns.name')"
                min-width="240"
              />
              <el-table-column
                prop="device"
                :label="t('engIotdb.preview.columns.device')"
                min-width="180"
              />
              <el-table-column
                prop="dataType"
                :label="t('engIotdb.preview.columns.dataType')"
                width="120"
              />
              <el-table-column
                prop="encoding"
                :label="t('engIotdb.preview.columns.encoding')"
                width="120"
              />
              <el-table-column
                prop="compression"
                :label="t('engIotdb.preview.columns.compression')"
                width="120"
              />
              <el-table-column
                prop="description"
                :label="t('engIotdb.preview.columns.description')"
                min-width="160"
              />
            </el-table>
          </div>
        </template>

        <!-- Tab3 SQL 工作台 -->
        <template v-else>
          <div class="sql-workbench">
            <el-input
              v-model="sqlText"
              type="textarea"
              :rows="8"
              :placeholder="t('engIotdb.sql.placeholder')"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            />
            <div class="sql-actions">
              <el-button
                type="primary"
                :loading="executing"
                :disabled="!selectedInstanceId"
                @click="handleExecuteSql"
              >
                {{ t('engIotdb.sql.execute') }}
              </el-button>
              <el-button @click="sqlText = ''">{{ t('engIotdb.sql.clear') }}</el-button>
            </div>
            <div v-if="sqlResult" class="sql-result">
              <div class="result-meta">
                {{
                  t('engIotdb.sql.rowsTime', { rows: sqlResult.rowCount, ms: sqlResult.durationMs })
                }}
              </div>
              <el-table
                :data="sqlResult.rows"
                stripe
                border
                size="small"
                style="width: 100%"
                max-height="360"
              >
                <el-table-column
                  v-for="(col, idx) in sqlResult.columns"
                  :key="idx"
                  :label="col"
                  min-width="160"
                >
                  <template #default="{ row }">{{ row[idx] }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type { IotdbInstance, Timeseries, ThroughputPoint, SqlExecuteResponse } from '@/api/engine'

const { t } = useI18n()

/* ------------------------------ 实例列表 ------------------------------ */

const {
  data: instances,
  loading: instancesLoading,
  error: instancesError,
  execute: reloadInstances
} = useApi<IotdbInstance[]>(() => engineApi.getIotdbInstances())

const selectedInstanceId = ref<string>('')
const activeTab = ref<'throughput' | 'preview' | 'sql'>('throughput')

/* ------------------------------ 存储组/设备/吞吐 ------------------------------ */

const { data: storageGroups, execute: loadStorageGroups } = useApi<string[]>(
  () => engineApi.getIotdbStorageGroups(selectedInstanceId.value),
  {
    immediate: false
  }
)

const { data: devices, execute: loadDevices } = useApi<string[]>(
  () => engineApi.getIotdbDevices(selectedInstanceId.value),
  {
    immediate: false
  }
)

const {
  data: throughput,
  loading: throughputLoading,
  error: throughputError,
  execute: loadThroughput
} = useApi<ThroughputPoint[]>(() => engineApi.getIotdbWriteThroughput(selectedInstanceId.value), {
  immediate: false
})

/** 实例切换 */
function handleInstanceChange() {
  if (selectedInstanceId.value) {
    void loadStorageGroups()
    void loadDevices()
    void loadThroughput()
  }
}

/** 重新加载当前选中实例数据 */
async function reloadCurrent() {
  if (selectedInstanceId.value) {
    await Promise.all([loadStorageGroups(), loadDevices(), loadThroughput()])
  } else {
    await reloadInstances()
  }
}

/** 实例列表加载完成后自动选中第一个 */
watch(instances, (list) => {
  if (list && list.length > 0 && !selectedInstanceId.value) {
    selectedInstanceId.value = list[0].id
    handleInstanceChange()
  }
})

/** KPI 聚合 */
const writeRate = computed(() => {
  const list = throughput.value ?? []
  if (list.length === 0) return 0
  return list[list.length - 1].rate
})
const maxRate = computed(() => {
  const list = throughput.value ?? []
  if (list.length === 0) return 0
  return Math.max(...list.map((p) => p.rate))
})
const avgRate = computed(() => {
  const list = throughput.value ?? []
  if (list.length === 0) return 0
  return Math.round(list.reduce((s, p) => s + p.rate, 0) / list.length)
})

/* ------------------------------ 目录树 ------------------------------ */

interface CatalogNode {
  id: string
  label: string
  type: 'sg' | 'device'
  children?: CatalogNode[]
  leaf?: boolean
}

/** 目录树根节点（存储组） */
const catalogTree = computed<CatalogNode[]>(() => {
  return (storageGroups.value ?? []).map((sg) => ({
    id: `sg:${sg}`,
    label: sg,
    type: 'sg' as const,
    leaf: false
  }))
})

/** 懒加载子节点（存储组下的设备） */
async function loadCatalogNode(
  node: { data: CatalogNode },
  resolve: (data: CatalogNode[]) => void
) {
  if (node.data.type === 'sg') {
    const deviceList = devices.value ?? []
    resolve(
      deviceList.map((d) => ({
        id: `device:${d}`,
        label: d,
        type: 'device' as const,
        leaf: true
      }))
    )
  } else {
    resolve([])
  }
}

/** 节点点击 */
function handleNodeClick(node: CatalogNode) {
  if (node.type === 'device') {
    // 切到时序预览并预填设备名
    activeTab.value = 'preview'
    previewDevice.value = node.label
  }
}

/* ------------------------------ 时序预览 ------------------------------ */

const previewDevice = ref<string>('')
const timeseries = ref<Timeseries[]>([])
const loadingTimeseries = ref(false)
const timeseriesError = ref(false)

/** 加载测点列表 */
async function handleLoadTimeseries() {
  if (!selectedInstanceId.value || !previewDevice.value) return
  loadingTimeseries.value = true
  timeseriesError.value = false
  try {
    timeseries.value = await engineApi.getIotdbTimeseries(
      selectedInstanceId.value,
      previewDevice.value
    )
  } catch {
    timeseriesError.value = true
  } finally {
    loadingTimeseries.value = false
  }
}

/* ------------------------------ SQL 工作台 ------------------------------ */

const sqlText = ref<string>('SELECT ** FROM root.ln.wf01 LIMIT 100;')
const executing = ref(false)
const sqlResult = ref<SqlExecuteResponse | null>(null)

/** 执行 SQL */
async function handleExecuteSql() {
  if (!selectedInstanceId.value) {
    ElMessage.warning(t('engIotdb.messages.needInstance'))
    return
  }
  if (!sqlText.value.trim()) {
    ElMessage.warning(t('engIotdb.messages.needSql'))
    return
  }
  executing.value = true
  sqlResult.value = null
  try {
    sqlResult.value = await engineApi.executeIotdbSql(selectedInstanceId.value, sqlText.value)
  } catch {
    // 拦截器已提示
  } finally {
    executing.value = false
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void reloadInstances()
  // 10s 轮询刷新
  timer = setInterval(() => void reloadCurrent(), 10000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.eng-iotdb-page {
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
.split-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 14px;
  margin-top: 16px;
}
@media (max-width: 1100px) {
  .split-layout {
    grid-template-columns: 1fr;
  }
}
.tree-card {
  height: fit-content;
}
.tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.main-card {
  min-width: 0;
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
.throughput-panel {
  min-height: 240px;
}
.throughput-summary {
  display: flex;
  gap: 24px;
  color: var(--ds-text-secondary);
  font-size: 12px;
  margin-bottom: 12px;
}
.preview-panel {
  min-height: 240px;
}
.preview-form {
  display: flex;
  gap: 10px;
  align-items: center;
}
.sql-workbench {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sql-actions {
  display: flex;
  gap: 8px;
}
.sql-result {
  border: 1px solid var(--ds-border-default);
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.result-meta {
  color: var(--ds-text-secondary);
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
