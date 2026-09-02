<template>
  <div class="scheduler-ops-page">
    <h1>{{ t('scheduler.title') }}</h1>
    <div class="sub">{{ t('scheduler.subtitle') }}</div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar">
        <el-input
          v-model="dagId"
          :placeholder="t('scheduler.dagIdPlaceholder')"
          clearable
          style="width: 280px"
          @keyup.enter="handleQuery"
        />
        <el-button type="primary" @click="handleQuery">{{ t('scheduler.query') }}</el-button>
        <div class="spacer"></div>
        <el-button type="success" plain :disabled="!dagId" @click="openBackfill">
          {{ t('scheduler.backfill') }}
        </el-button>
        <el-button :icon="Refresh" circle @click="handleQuery" />
      </div>

      <!-- 状态筛选 tabs -->
      <el-tabs v-model="activeStatus" @tab-change="handleQuery">
        <el-tab-pane :label="t('scheduler.tabs.all')" name="" />
        <el-tab-pane :label="t('scheduler.tabs.success')" name="SUCCESS" />
        <el-tab-pane :label="t('scheduler.tabs.failed')" name="FAILED" />
        <el-tab-pane :label="t('scheduler.tabs.running')" name="RUNNING" />
      </el-tabs>

      <!-- 运行历史表格 -->
      <el-table
        v-loading="loading"
        :data="runs"
        stripe
        border
        :empty-text="
          dagId ? (error ? t('scheduler.emptyError') : t('scheduler.empty')) : t('scheduler.needDagId')
        "
      >
        <el-table-column prop="id" label="RunId" width="90" />
        <el-table-column :label="t('scheduler.cols.runType')" width="110">
          <template #default="{ row }">
            <el-tag :type="runTypeTagType(row.runType)" effect="plain" size="small">
              {{ runTypeLabel(row.runType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('scheduler.cols.status')" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('scheduler.cols.bizTime')" width="130">
          <template #default="{ row }">
            {{ row.bizTime ? row.bizTime.slice(0, 10) : '—' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('scheduler.cols.duration')" width="110">
          <template #default="{ row }">
            {{ formatDuration(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column prop="triggeredBy" :label="t('scheduler.cols.triggeredBy')" width="130" />
        <el-table-column :label="t('scheduler.cols.startTime')" width="180">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column :label="t('scheduler.cols.actions')" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED'"
              link
              type="primary"
              :loading="rerunningId === row.id"
              @click="doRerun(row)"
            >
              {{ t('scheduler.rerun') }}
            </el-button>
            <el-button link type="primary" @click="openRunDetail(row)">
              {{ t('scheduler.detail') }}
            </el-button>
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
    <el-dialog v-model="backfillVisible" :title="t('scheduler.backfillModal.title')" width="480px">
      <el-form label-width="90px">
        <el-form-item label="DAG ID">
          <el-input :model-value="dagId" disabled />
        </el-form-item>
        <el-form-item :label="t('scheduler.backfillModal.range')" required>
          <el-date-picker
            v-model="backfillRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="~"
            :start-placeholder="t('scheduler.backfillModal.start')"
            :end-placeholder="t('scheduler.backfillModal.end')"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('scheduler.backfillModal.intervalDays')">
          <el-input-number v-model="intervalDays" :min="1" :max="30" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="backfillVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="backfilling" @click="doBackfill">
          {{ t('scheduler.backfillModal.generate') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 运行详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="t('scheduler.detailModal.title')" width="640px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="RunId">{{ detail?.id }}</el-descriptions-item>
        <el-descriptions-item label="DAG ID">{{ detail?.dagId }}</el-descriptions-item>
        <el-descriptions-item :label="t('scheduler.detailModal.status')">
          {{ detail?.status }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('scheduler.detailModal.runType')">
          {{ detail?.runType }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('scheduler.detailModal.startTime')">
          {{ formatTime(detail?.startTime) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('scheduler.detailModal.endTime')">
          {{ formatTime(detail?.endTime) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('scheduler.detailModal.error')">
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
import { useI18n } from 'vue-i18n'
import { useApi } from '@/composables/useApi'
import {
  listDagRuns,
  rerunDagRun,
  backfillDag,
  type DagRunRecord,
  type DagRunPage
} from '@/api/streamBatch'

const { t } = useI18n()

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
      t('scheduler.rerunConfirm.message', { dagId: row.dagId, id: row.id }),
      t('scheduler.rerunConfirm.title'),
      { type: 'warning', confirmButtonText: t('scheduler.rerunConfirm.confirm'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    return
  }
  rerunningId.value = row.id
  try {
    await rerunDagRun(row.dagId, row.id)
    ElMessage.success(t('scheduler.rerunConfirm.triggered'))
    await loadRuns()
  } catch (e) {
    ElMessage.error(t('scheduler.rerunConfirm.failed', { msg: e }))
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
    ElMessage.warning(t('scheduler.backfillModal.rangeRequired'))
    return
  }
  backfilling.value = true
  try {
    const res = await backfillDag(dagId.value, {
      startDate: backfillRange.value[0],
      endDate: backfillRange.value[1],
      intervalDays: intervalDays.value
    })
    ElMessage.success(t('scheduler.backfillModal.done', { count: res.created }))
    backfillVisible.value = false
    await loadRuns()
  } catch (e) {
    ElMessage.error(t('scheduler.backfillModal.failed', { msg: e }))
  } finally {
    backfilling.value = false
  }
}

function openRunDetail(row: DagRunRecord) {
  detail.value = row
  detailVisible.value = true
}

/* ---------------- 展示辅助 ---------------- */
const RUN_TYPES = ['MANUAL', 'SCHEDULED', 'RERUN', 'BACKFILL']

function runTypeLabel(rt: string): string {
  return RUN_TYPES.includes(rt) ? t(`scheduler.runTypes.${rt}`) : rt
}
function runTypeTagType(rt: string): 'primary' | 'warning' | 'success' | 'info' {
  if (rt === 'RERUN') return 'warning'
  if (rt === 'BACKFILL') return 'primary'
  if (rt === 'MANUAL') return 'success'
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
function formatTime(ts?: string | null): string {
  return ts ? ts.replace('T', ' ').slice(0, 19) : '—'
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
