<template>
  <div class="model-panel">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="按名称搜索"
        clearable
        style="width: 220px"
        @change="onSearch"
      />
      <div class="spacer" />
      <el-button :icon="Refresh" circle @click="$emit('load')" />
    </div>
    <el-table
      v-loading="loading"
      :data="models"
      stripe
      border
      :empty-text="error ? '加载失败' : '暂无数据'"
    >
      <el-table-column prop="name" label="模型名" min-width="170" />
      <el-table-column prop="algorithm" label="算法" width="130">
        <template #default="{ row }">
          <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="latestVersion" label="最新版本" width="110" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="指标" min-width="180">
        <template #default="{ row }">{{ row.metrics ? fmtMetrics(row.metrics) : '--' }}</template>
      </el-table-column>
      <el-table-column prop="registeredAt" label="注册时间" width="170">
        <template #default="{ row }">{{ row.registeredAt || '--' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$emit('versions', row)">版本</el-button>
          <el-button link type="success" @click="$emit('deploy', row)">部署推理</el-button>
          <el-button link type="danger" @click="$emit('delete', row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { MlModel } from '@/api/dev-ml'

defineProps<{
  models: MlModel[]
  loading: boolean
  error: boolean
  statusLabel: (s?: string) => string
  statusType: (s?: string) => string
}>()
const emit = defineEmits<{
  load: []
  search: [k: string]
  delete: [row: MlModel]
  deploy: [row: MlModel]
  versions: [row: MlModel]
}>()

const keyword = ref('')
function onSearch() {
  emit('search', keyword.value)
}
function fmtMetrics(m: Record<string, number>) {
  return Object.entries(m)
    .map(([k, v]) => `${k}=${typeof v === 'number' ? v.toFixed(4) : v}`)
    .join(' · ')
}
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
