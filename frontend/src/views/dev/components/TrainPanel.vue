<template>
  <div class="train-panel">
    <div class="kpi-row">
      <div class="kpi-card">
        <h4>训练作业</h4>
        <span class="num">{{ kpi.total }}</span>
        <span class="lbl">全部</span>
      </div>
      <div class="kpi-card">
        <h4>运行中</h4>
        <span class="num running">{{ kpi.running }}</span>
        <span class="lbl">RUNNING</span>
      </div>
    </div>
    <div class="toolbar">
      <el-button type="primary" @click="emit('openTrain')">+ 提交训练</el-button>
      <el-select
        v-model="localFilter"
        placeholder="状态筛选"
        clearable
        style="width: 130px"
        @change="applyFilter"
      >
        <el-option label="等待中" value="PENDING" />
        <el-option label="运行中" value="RUNNING" />
        <el-option label="成功" value="SUCCEEDED" />
        <el-option label="失败" value="FAILED" />
        <el-option label="已取消" value="KILLED" />
        <el-option label="已调度" value="SCHEDULED" />
      </el-select>
      <div class="spacer" />
      <el-button :icon="Refresh" circle @click="emit('load')" />
    </div>
    <el-table
      v-loading="loading"
      :data="jobs"
      stripe
      border
      :empty-text="error ? '加载失败' : '暂无数据'"
    >
      <el-table-column prop="name" label="实验名" min-width="180" />
      <el-table-column prop="algorithm" label="算法" width="130">
        <template #default="{ row }">
          <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dataset" label="数据集" min-width="160" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="指标" min-width="180">
        <template #default="{ row }">{{ row.metrics ? fmtMetrics(row.metrics) : '--' }}</template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="110">
        <template #default="{ row }">{{ row.owner || '--' }}</template>
      </el-table-column>
      <el-table-column prop="submittedAt" label="提交时间" width="170">
        <template #default="{ row }">{{ row.submittedAt || '--' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="success"
            @click="emit('openRegister', row)"
            v-if="row.status === 'SUCCEEDED'"
          >
            注册模型
          </el-button>
          <el-button
            link
            type="warning"
            :loading="stoppingId === row.id"
            @click="emit('stop', row)"
            v-if="['PENDING', 'RUNNING', 'SCHEDULED'].includes(row.status)"
          >
            停止
          </el-button>
          <el-button link @click="emit('openLog', row)">日志</el-button>
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
import { Refresh } from '@element-plus/icons-vue'
import type { TrainJob } from '@/api/dev-ml'

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
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 14px;
  text-align: center;
}
.kpi-card h4 {
  margin: 0 0 4px;
  font-size: 12px;
  color: #717a80;
}
.kpi-card .num {
  display: block;
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi-card .num.running {
  color: #2f9e6f;
}
.kpi-card .lbl {
  font-size: 11px;
  color: #909399;
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
