<template>
  <div class="svc-panel">
    <div class="toolbar">
      <el-select
        v-model="localFilter"
        placeholder="状态筛选"
        clearable
        style="width: 130px"
        @change="applyFilter"
      >
        <el-option label="部署中" value="DEPLOYING" />
        <el-option label="运行中" value="RUNNING" />
        <el-option label="已停止" value="STOPPED" />
        <el-option label="失败" value="FAILED" />
        <el-option label="扩缩容" value="SCALING" />
      </el-select>
      <div class="spacer" />
      <el-button :icon="Refresh" circle @click="$emit('load')" />
    </div>
    <el-table
      v-loading="loading"
      :data="services"
      stripe
      border
      :empty-text="error ? '加载失败' : '暂无数据'"
    >
      <el-table-column prop="serviceName" label="服务名" min-width="170" />
      <el-table-column prop="modelName" label="模型" min-width="150" />
      <el-table-column prop="modelVersion" label="版本" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="副本" width="110" align="center">
        <template #default="{ row }">
          {{ row.replicas ?? 0 }}
          <span v-if="row.desiredReplicas !== row.replicas">/ {{ row.desiredReplicas ?? 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="qps" label="QPS" width="90">
        <template #default="{ row }">{{ row.qps ?? '--' }}</template>
      </el-table-column>
      <el-table-column label="延迟" width="110">
        <template #default="{ row }">
          {{ row.latencyMs !== undefined ? row.latencyMs + 'ms' : '--' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canScale(row.status)" link type="primary" @click="$emit('scale', row)">
            扩缩容
          </el-button>
          <el-button
            v-if="canStopSvc(row.status)"
            link
            type="danger"
            :loading="stoppingId === row.id"
            @click="$emit('stop', row)"
          >
            停止
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { InferenceService } from '@/api/dev-ml'

const props = defineProps<{
  services: InferenceService[]
  loading: boolean
  error: boolean
  statusFilter: string
  statusLabel: (s: string) => string
  statusType: (s: string) => string
  canStopSvc: (s: string) => boolean
  canScale: (s: string) => boolean
}>()
const emit = defineEmits<{
  load: []
  scale: [row: InferenceService]
  stop: [row: InferenceService]
  filter: [s: string]
}>()

const localFilter = ref('')
watch(
  () => props.statusFilter,
  (v) => {
    localFilter.value = v
  },
  { immediate: true }
)
function applyFilter() {
  emit('filter', localFilter.value)
}
const stoppingId = ref('')
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.toolbar .spacer {
  flex: 1;
}
</style>
