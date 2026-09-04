<template>
  <div class="svc-panel">
    <div class="toolbar">
      <el-select
        v-model="localFilter"
        :placeholder="t('devMl.svcPanel.statusFilter')"
        clearable
        style="width: 130px"
        @change="applyFilter"
      >
        <el-option :label="t('devMl.status.svc.DEPLOYING')" value="DEPLOYING" />
        <el-option :label="t('devMl.status.svc.RUNNING')" value="RUNNING" />
        <el-option :label="t('devMl.status.svc.STOPPED')" value="STOPPED" />
        <el-option :label="t('devMl.status.svc.FAILED')" value="FAILED" />
        <el-option :label="t('devMl.status.svc.SCALING')" value="SCALING" />
      </el-select>
      <div class="spacer" />
      <el-button :icon="Refresh" circle @click="$emit('load')" />
    </div>
    <el-table
      v-loading="loading"
      :data="services"
      stripe
      border
      :empty-text="error ? t('devMl.svcPanel.loadFailed') : t('devMl.svcPanel.empty')"
    >
      <el-table-column
        prop="serviceName"
        :label="t('devMl.svcPanel.columns.serviceName')"
        min-width="170"
      />
      <el-table-column
        prop="modelName"
        :label="t('devMl.svcPanel.columns.modelName')"
        min-width="150"
      />
      <el-table-column
        prop="modelVersion"
        :label="t('devMl.svcPanel.columns.modelVersion')"
        width="90"
      />
      <el-table-column :label="t('devMl.svcPanel.columns.status')" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('devMl.svcPanel.columns.replicas')" width="110" align="center">
        <template #default="{ row }">
          {{ row.replicas ?? 0 }}
          <span v-if="row.desiredReplicas !== row.replicas">/ {{ row.desiredReplicas ?? 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="qps" :label="t('devMl.svcPanel.columns.qps')" width="90">
        <template #default="{ row }">{{ row.qps ?? '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('devMl.svcPanel.columns.latency')" width="110">
        <template #default="{ row }">
          {{
            row.latencyMs !== undefined
              ? t('devMl.svcPanel.columns.latencyFmt', { ms: row.latencyMs })
              : '--'
          }}
        </template>
      </el-table-column>
      <el-table-column :label="t('devMl.svcPanel.columns.actions')" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canScale(row.status)" link type="primary" @click="$emit('scale', row)">
            {{ t('devMl.svcPanel.actions.scale') }}
          </el-button>
          <el-button
            v-if="canStopSvc(row.status)"
            link
            type="danger"
            :loading="stoppingId === row.id"
            @click="$emit('stop', row)"
          >
            {{ t('devMl.svcPanel.actions.stop') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import type { InferenceService } from '@/api/dev-ml'

const { t } = useI18n()

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
