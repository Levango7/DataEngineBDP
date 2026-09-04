<template>
  <div class="train-panel">
    <div class="kpi-row">
      <div class="kpi-card">
        <h4>{{ t('devMl.trainPanel.kpiTotal') }}</h4>
        <span class="num">{{ kpi.total }}</span>
        <span class="lbl">{{ t('devMl.trainPanel.kpiTotalLbl') }}</span>
      </div>
      <div class="kpi-card">
        <h4>{{ t('devMl.trainPanel.kpiRunning') }}</h4>
        <span class="num running">{{ kpi.running }}</span>
        <span class="lbl">{{ t('devMl.trainPanel.kpiRunningLbl') }}</span>
      </div>
    </div>
    <div class="toolbar">
      <el-button type="primary" @click="emit('openTrain')">
        {{ t('devMl.trainPanel.newJob') }}
      </el-button>
      <el-select
        v-model="localFilter"
        :placeholder="t('devMl.trainPanel.statusFilter')"
        clearable
        style="width: 130px"
        @change="applyFilter"
      >
        <el-option :label="t('devMl.status.train.PENDING')" value="PENDING" />
        <el-option :label="t('devMl.status.train.RUNNING')" value="RUNNING" />
        <el-option :label="t('devMl.status.train.SUCCEEDED')" value="SUCCEEDED" />
        <el-option :label="t('devMl.status.train.FAILED')" value="FAILED" />
        <el-option :label="t('devMl.status.train.KILLED')" value="KILLED" />
        <el-option :label="t('devMl.status.train.SCHEDULED')" value="SCHEDULED" />
      </el-select>
      <div class="spacer" />
      <el-button :icon="Refresh" circle @click="emit('load')" />
    </div>
    <el-table
      v-loading="loading"
      :data="jobs"
      stripe
      border
      :empty-text="error ? t('devMl.trainPanel.loadFailed') : t('devMl.trainPanel.empty')"
    >
      <el-table-column prop="name" :label="t('devMl.trainPanel.columns.name')" min-width="180" />
      <el-table-column
        prop="algorithm"
        :label="t('devMl.trainPanel.columns.algorithm')"
        width="130"
      >
        <template #default="{ row }">
          <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="dataset"
        :label="t('devMl.trainPanel.columns.dataset')"
        min-width="160"
      />
      <el-table-column :label="t('devMl.trainPanel.columns.status')" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('devMl.trainPanel.columns.metrics')" min-width="180">
        <template #default="{ row }">{{ row.metrics ? fmtMetrics(row.metrics) : '--' }}</template>
      </el-table-column>
      <el-table-column prop="owner" :label="t('devMl.trainPanel.columns.owner')" width="110">
        <template #default="{ row }">{{ row.owner || '--' }}</template>
      </el-table-column>
      <el-table-column
        prop="submittedAt"
        :label="t('devMl.trainPanel.columns.submittedAt')"
        width="170"
      >
        <template #default="{ row }">{{ row.submittedAt || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('devMl.trainPanel.columns.actions')" width="220" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'SUCCEEDED'"
            link
            type="success"
            @click="emit('openRegister', row)"
          >
            {{ t('devMl.trainPanel.actions.register') }}
          </el-button>
          <el-button
            v-if="['PENDING', 'RUNNING', 'SCHEDULED'].includes(row.status)"
            link
            type="warning"
            :loading="stoppingId === row.id"
            @click="emit('stop', row)"
          >
            {{ t('devMl.trainPanel.actions.stop') }}
          </el-button>
          <el-button link @click="emit('openLog', row)">
            {{ t('devMl.trainPanel.actions.log') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination">
      <el-pagination
        v-model:current-page="localPage"
        v-model:page-size="localSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        background
        @size-change="applyPage"
        @current-change="applyPage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import type { TrainJob } from '@/api/dev-ml'

const { t } = useI18n()

const props = defineProps<{
  jobs: TrainJob[]
  total: number
  page: number
  size: number
  statusFilter: string
  loading: boolean
  error: boolean
  statusLabel: (s: string) => string
  statusType: (s: string) => string
  kpi: { total: number; running: number }
}>()
const emit = defineEmits<{
  load: []
  openTrain: []
  openRegister: [row: TrainJob]
  openLog: [row: TrainJob]
  stop: [row: TrainJob]
  filter: [page: number, size: number, status: string]
}>()

const localPage = ref(1),
  localSize = ref(20),
  localFilter = ref('')
watch(
  () => props.page,
  (v) => {
    localPage.value = v
  },
  { immediate: true }
)
watch(
  () => props.size,
  (v) => {
    localSize.value = v
  },
  { immediate: true }
)
watch(
  () => props.statusFilter,
  (v) => {
    localFilter.value = v
  },
  { immediate: true }
)

function applyPage() {
  emit('filter', localPage.value, localSize.value, localFilter.value)
}
function applyFilter() {
  localPage.value = 1
  emit('filter', 1, localSize.value, localFilter.value)
}
const stoppingId = ref('')
function fmtMetrics(m: Record<string, number>) {
  return Object.entries(m)
    .map(([k, v]) => `${k}=${typeof v === 'number' ? v.toFixed(4) : v}`)
    .join(' · ')
}
</script>

<style scoped>
.kpi-row {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}
.kpi-card {
  flex: 1;
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 14px;
  text-align: center;
}
.kpi-card h4 {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--ds-text-secondary);
}
.kpi-card .num {
  display: block;
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi-card .num.running {
  color: var(--ds-color-success-600);
}
.kpi-card .lbl {
  font-size: 11px;
  color: var(--ds-text-muted, var(--ds-text-secondary));
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.toolbar .spacer {
  flex: 1;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
