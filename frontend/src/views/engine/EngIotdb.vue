<template>
  <div class="eng-iotdb-page">
    <h1>时序引擎（IoTDB）</h1>
    <div class="sub">存储组 · 设备 · 测点 · 时序查询 · 15 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="instancesLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="instancesError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ instancesError.message }}，<a href="javascript:void(0)" @click="reloadInstances">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>实例数</h3>
          <div class="kpi">{{ instances?.length ?? 0 }}</div>
          <div class="meta">已接入 IoTDB 实例</div>
        </div>
        <div class="card">
          <h3>存储组数</h3>
          <div class="kpi">{{ storageGroups?.length ?? 0 }}</div>
          <div class="meta">当前实例</div>
        </div>
        <div class="card">
          <h3>设备数</h3>
          <div class="kpi s">{{ devices?.length ?? 0 }}</div>
          <div class="meta">当前实例</div>
        </div>
        <div class="card">
          <h3>写入点/秒</h3>
          <div class="kpi">{{ writeRate }}</div>
          <div class="meta">最近吞吐</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：左右分栏 -->
    <div class="split-layout">
      <!-- 左：实例选择 + 存储组/设备/测点树 -->
      <el-card shadow="never" class="page-card tree-card">
        <template #header>
          <div class="tree-header">
            <span>元数据目录</span>
          </div>
        </template>
        <el-select
          v-model="selectedInstanceId"
          placeholder="选择 IoTDB 实例"
          style="width: 100%; margin-bottom: 12px"
          @change="handleInstanceChange"
        >
          <el-option
            v-for="i in instances ?? []"
            :key="i.id"
            :label="i.name"
            :value="i.id"
          />
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
            <el-tab-pane label="写入监控" name="throughput" />
            <el-tab-pane label="时序预览" name="preview" />
            <el-tab-pane label="SQL 工作台" name="sql" />
          </el-tabs>
          <div class="spacer"></div>
          <el-button :icon="Refresh" circle @click="reloadCurrent" />
        </div>

        <!-- Tab1 写入监控 -->
        <template v-if="activeTab === 'throughput'">
          <div v-loading="throughputLoading" class="throughput-panel">
            <template v-if="throughput && throughput.length > 0">
              <div class="throughput-summary">
                <span>共 {{ throughput.length }} 个采样点</span>
                <span>最大速率 {{ maxRate.toLocaleString() }} 点/秒</span>
                <span>平均速率 {{ avgRate.toLocaleString() }} 点/秒</span>
              </div>
              <el-table
                :data="throughput"
                stripe
                border
                size="small"
                style="width: 100%"
                max-height="420"
              >
                <el-table-column prop="timestamp" label="时间戳" width="200" />
                <el-table-column label="写入点数" width="160" align="right">
                  <template #default="{ row }">{{ row.points.toLocaleString() }}</template>
                </el-table-column>
                <el-table-column label="速率（点/秒）" width="200" align="right">
                  <template #default="{ row }">{{ row.rate.toLocaleString() }}</template>
                </el-table-column>
                <el-table-column label="占比">
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
              :description="throughputError ? '加载失败' : '暂无写入吞吐数据'"
            />
          </div>
        </template>

        <!-- Tab2 时序预览 -->
        <template v-else-if="activeTab === 'preview'">
          <div class="preview-panel">
            <div class="preview-form">
              <el-input
                v-model="previewDevice"
                placeholder="设备全名，如 root.ln.wf01"
                style="width: 280px"
              />
              <el-button
                type="primary"
                :loading="loadingTimeseries"
                :disabled="!selectedInstanceId || !previewDevice"
                @click="handleLoadTimeseries"
              >
                加载测点
              </el-button>
            </div>
            <el-table
              v-loading="loadingTimeseries"
              :data="timeseries"
              stripe
              border
              style="width: 100%; margin-top: 12px"
              :empty-text="timeseriesError ? '加载失败' : '暂无测点'"
            >
              <el-table-column prop="name" label="测点全名" min-width="240" />
              <el-table-column prop="device" label="设备" min-width="180" />
              <el-table-column prop="dataType" label="数据类型" width="120" />
              <el-table-column prop="encoding" label="编码" width="120" />
              <el-table-column prop="compression" label="压缩" width="120" />
              <el-table-column prop="description" label="描述" min-width="160" />
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
              placeholder="输入 IoTDB SQL，如 SELECT * FROM root.ln.wf01 WHERE time > now() - 1h"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            />
            <div class="sql-actions">
              <el-button
                type="primary"
                :loading="executing"
                :disabled="!selectedInstanceId"
                @click="handleExecuteSql"
              >
                执行
              </el-button>
              <el-button @click="sqlText = ''">清空</el-button>
            </div>
            <div v-if="sqlResult" class="sql-result">
              <div class="result-meta">
                共 {{ sqlResult.rowCount }} 行，耗时 {{ sqlResult.durationMs }} ms
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
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type {
  IotdbInstance,
  Timeseries,
  ThroughputPoint,
  SqlExecuteResponse
} from '@/api/engine'

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

const {
  data: storageGroups,
  execute: loadStorageGroups
} = useApi<string[]>(() => engineApi.getIotdbStorageGroups(selectedInstanceId.value), {
  immediate: false
})

const {
  data: devices,
  execute: loadDevices
} = useApi<string[]>(() => engineApi.getIotdbDevices(selectedInstanceId.value), {
  immediate: false
})

const {
  data: throughput,
  loading: throughputLoading,
  error: throughputError,
  execute: loadThroughput
} = useApi<ThroughputPoint[]>(() =>
  engineApi.getIotdbWriteThroughput(selectedInstanceId.value),
  { immediate: false }
)

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
    ElMessage.warning('请先选择 IoTDB 实例')
    return
  }
  if (!sqlText.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  executing.value = true
  sqlResult.value = null
  try {
    sqlResult.value = await engineApi.executeIotdbSql(
      selectedInstanceId.value,
      sqlText.value
    )
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
  // 15s 轮询刷新
  timer = setInterval(() => void reloadCurrent(), 15000)
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
  color: #717a80;
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
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: #717a80;
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
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
  color: #717a80;
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
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.result-meta {
  color: #717a80;
  font-size: 12px;
  margin-bottom: 8px;
}
</style>