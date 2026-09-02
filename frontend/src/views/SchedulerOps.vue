<template>
  <div class="scheduler-ops-page">
    <h1>任务运维中心</h1>
    <div class="sub">
      流批 DAG 运行历史 / 失败重跑 / 补数据（stream-batch-scheduler）。 输入 DAG ID
      后查看执行实例，可对失败实例一键重跑，或按时间区间补数据。
    </div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar">
        <el-input
          v-model="dagId"
          placeholder="输入 DAG ID，如 dag-etl-001"
          clearable
          style="width: 280px"
          @keyup.enter="handleQuery"
        />
        <el-button type="primary" @click="handleQuery">查询</el-button>
        <div class="spacer"></div>
        <el-button type="success" plain :disabled="!dagId" @click="openBackfill">补数据</el-button>
        <el-button :icon="Refresh" circle @click="handleQuery" />
      </div>

      <!-- 状态筛选 tabs -->
      <el-tabs v-model="activeStatus" @tab-change="handleQuery">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="成功" name="SUCCESS" />
        <el-tab-pane label="失败" name="FAILED" />
        <el-tab-pane label="运行中" name="RUNNING" />
      </el-tabs>

      <!-- 运行历史表格 -->
      <el-table
        v-loading="loading"
        :data="runs"
        stripe
        border
        :empty-text="dagId ? (error ? '加载失败，请重试' : '暂无运行历史') : '请先输入 DAG ID 查询'"
      >
        <el-table-column prop="id" label="RunId" width="90" />
        <el-table-column label="运行类型" width="110">
          <template #default="{ row }">
            <el-tag :type="runTypeTagType(row.runType)" effect="plain" size="small">
              {{ runTypeLabel(row.runType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务时间" width="130">
          <template #default="{ row }">
            {{ row.bizTime ? row.bizTime.slice(0, 10) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">
            {{ formatDuration(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column prop="triggeredBy" label="触发人" width="130" />
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED'"
              link
              type="primary"
              :loading="rerunningId === row.id"
              @click="doRerun(row)"
            >
              重新运行
            </el-button>
            <el-button link type="primary" @click="openRunDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-if="dagId"
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next, sizes"
        :page-sizes="[10, 20, 50]"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadRuns"
        @size-change="
          () => {
            page = 1
            loadRuns()
          }
        "
      />
    </el-card>

    <!-- 补数据弹窗 -->
    <el-dialog v-model="backfillVisible" title="补数据（Backfill）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="DAG ID">
          <el-input :model-value="dagId" disabled />
        </el-form-item>
        <el-form-item label="起止日期" required>
          <el-date-picker
            v-model="backfillRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="间隔天数">
          <el-input-number v-model="intervalDays" :min="1" :max="30" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="backfillVisible = false">取消</el-button>
        <el-button type="primary" :loading="backfilling" @click="doBackfill">生成实例</el-button>
      </template>
    </el-dialog>

    <!-- 运行详情弹窗 -->
    <el-dialog v-model="detailVisible" title="运行详情" width="640px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="RunId">{{ detail?.id }}</el-descriptions-item>
        <el-descriptions-item label="DAG ID">{{ detail?.dagId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail?.status }}</el-descriptions-item>
        <el-descriptions-item label="运行类型">{{ detail?.runType }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ formatTime(detail?.startTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ formatTime(detail?.endTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="错误信息">
          <span style="color: #f56c6c; white-space: pre-wrap">
            {{ detail?.errorMessage || '—' }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import {
  listDagRuns,
  rerunDagRun,
  backfillDag,
  type DagRunRecord,
  type DagRunPage
} from '@/api/streamBatch'

const dagId = ref('')
const activeStatus = ref('')
const page = ref(1)
const size = ref(20)

// DAG 运行历史：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: runsPage,
  loading,
  error,
  execute: loadRuns
} = useApi<DagRunPage>(() =>
  listDagRuns(dagId.value, {
    status: activeStatus.value || undefined,
    page: page.value - 1,
    size: size.value
  })
)

const runs = computed<DagRunRecord[]>(() => runsPage.value?.content ?? [])
const total = computed<number>(() => runsPage.value?.totalElements ?? 0)

const rerunningId = ref<number | null>(null)
const backfillVisible = ref(false)
const backfillRange = ref<[string, string] | null>(null)
const intervalDays = ref(1)
const backfilling = ref(false)
const detailVisible = ref(false)
const detail = ref<DagRunRecord | null>(null)

async function handleQuery() {
  page.value = 1
  await doQuery()
}

/** 查询入口：仅当 dagId 有值时触发 */
async function doQuery() {
  if (!dagId.value) return
  await loadRuns()
}

async function doRerun(row: DagRunRecord) {
  try {
    await ElMessageBox.confirm(
      `确认重新运行 DAG「${row.dagId}」的实例 #${row.id} 吗？将复原原参数重新执行。`,
      '失败重跑',
      { type: 'warning', confirmButtonText: '重跑', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  rerunningId.value = row.id
  try {
    await rerunDagRun(row.dagId, row.id)
    ElMessage.success('已触发重新运行')
    await loadRuns()
  } catch (e) {
    ElMessage.error(`重跑失败: ${e}`)
  } finally {
    rerunningId.value = null
  }
}

function openBackfill() {
  if (!dagId.value) return
  backfillRange.value = null
  intervalDays.value = 1
  backfillVisible.value = true
}

async function doBackfill() {
  if (!backfillRange.value || backfillRange.value.length !== 2) {
    ElMessage.warning('请选择起止日期')
    return
  }
  backfilling.value = true
  try {
    const res = await backfillDag(dagId.value, {
      startDate: backfillRange.value[0],
      endDate: backfillRange.value[1],
      intervalDays: intervalDays.value
    })
    ElMessage.success(`补数据完成，共生成 ${res.created} 个实例`)
    backfillVisible.value = false
    await loadRuns()
  } catch (e) {
    ElMessage.error(`补数据失败: ${e}`)
  } finally {
    backfilling.value = false
  }
}

function openRunDetail(row: DagRunRecord) {
  detail.value = row
  detailVisible.value = true
}

/* ---------------- 展示辅助 ---------------- */
function runTypeLabel(t: string): string {
  return { MANUAL: '手动', SCHEDULED: '调度', RERUN: '重跑', BACKFILL: '补数据' }[t] ?? t
}
function runTypeTagType(t: string): 'primary' | 'warning' | 'success' | 'info' {
  if (t === 'RERUN') return 'warning'
  if (t === 'BACKFILL') return 'primary'
  if (t === 'MANUAL') return 'success'
  return 'info'
}
function statusTagType(s: string): 'success' | 'danger' | 'warning' | 'info' {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING' || s === 'PENDING') return 'warning'
  return 'info'
}
function formatDuration(ms?: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
function formatTime(t?: string | null): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.scheduler-ops-page {
  padding: 8px;
}
.sub {
  color: var(--ds-text-muted, var(--ds-text-secondary));
  margin-bottom: 12px;
  font-size: 13px;
}
.page-card {
  border-radius: 8px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.spacer {
  flex: 1;
}
</style>
