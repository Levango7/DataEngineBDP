<template>
  <div class="page">
    <h2>成本明细</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-card shadow="never">
      <template #header>资源成本明细</template>
      <el-table :data="details" stripe style="width: 100%">
        <el-table-column prop="resourceId" label="资源ID" width="180" />
        <el-table-column prop="resourceType" label="类型" width="80" />
        <el-table-column prop="tenant" label="租户" />
        <el-table-column prop="namespace" label="namespace" />
        <el-table-column prop="workspace" label="工作空间" />
        <el-table-column prop="totalCost" label="总成本（元）" sortable />
        <el-table-column label="维度成本">
          <template #default="{ row }">
            <span v-for="(v, k) in row.dimensionCosts" :key="k" style="margin-right: 8px">
              {{ k }}: {{ v }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="gpuModel" label="GPU型号" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { getCostDetails } from '@/api/finops'
import type { ResourceCostDetail } from '@/types'

const details = ref<ResourceCostDetail[]>([])

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  try {
    const resp = await getCostDetails(params)
    details.value = resp.items
  } catch (e) {
    console.error('查明细失败', e)
  }
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
</style>