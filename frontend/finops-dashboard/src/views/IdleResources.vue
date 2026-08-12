<template>
  <div class="page">
    <h2>闲置清单</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>5 类闲置模式分布</template>
          <EChart v-if="pieOption" :option="pieOption" height="300px" />
          <el-empty v-else description="暂无数据" />
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>闲置资源统计</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="闲置资源总数">{{ idleResources.length }}</el-descriptions-item>
            <el-descriptions-item label="估算月度节约（元）">{{ totalSaving.toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="低利用率 CPU">{{ countByPattern.LOW_CPU_UTILIZATION }}</el-descriptions-item>
            <el-descriptions-item label="低利用率内存">{{ countByPattern.LOW_MEMORY_UTILIZATION }}</el-descriptions-item>
            <el-descriptions-item label="未挂载存储">{{ countByPattern.UNMOUNTED_STORAGE }}</el-descriptions-item>
            <el-descriptions-item label="空闲 GPU">{{ countByPattern.IDLE_GPU }}</el-descriptions-item>
            <el-descriptions-item label="低流量负载">{{ countByPattern.LOW_NETWORK_TRAFFIC }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>闲置资源清单</template>
      <el-table :data="idleResources" stripe>
        <el-table-column prop="resourceId" label="资源ID" />
        <el-table-column prop="resourceType" label="类型" width="80" />
        <el-table-column label="闲置模式" width="140">
          <template #default="{ row }">
            <el-tag :color="getColor(row.pattern)" effect="dark">
              {{ getLabel(row.pattern) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="avgUtilization" label="平均利用率" sortable />
        <el-table-column prop="sustainedHours" label="持续时长（h）" />
        <el-table-column prop="estimatedSaving" label="估算节约（元/月）" sortable />
        <el-table-column prop="suggestion" label="优化建议" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChart from '@/components/EChart.vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { getIdleResources } from '@/api/finops'
import type { IdleResource, IdlePattern } from '@/types'
import { IDLE_PATTERN_LABELS, IDLE_PATTERN_COLORS } from '@/types'

const idleResources = ref<IdleResource[]>([])

const totalSaving = computed(() =>
  idleResources.value.reduce((sum, r) => sum + r.estimatedSaving, 0)
)

const countByPattern = computed(() => {
  const counts: Record<IdlePattern, number> = {
    LOW_CPU_UTILIZATION: 0,
    LOW_MEMORY_UTILIZATION: 0,
    UNMOUNTED_STORAGE: 0,
    IDLE_GPU: 0,
    LOW_NETWORK_TRAFFIC: 0
  }
  for (const r of idleResources.value) {
    counts[r.pattern]++
  }
  return counts
})

const pieOption = computed<EChartsOption | null>(() => {
  const counts = countByPattern.value
  const data = (Object.keys(counts) as IdlePattern[]).map((p) => ({
    name: IDLE_PATTERN_LABELS[p],
    value: counts[p],
    itemStyle: { color: IDLE_PATTERN_COLORS[p] }
  }))
  if (data.every((d) => d.value === 0)) return null
  return {
    tooltip: { trigger: 'item' },
    series: [
      {
        name: '闲置模式分布',
        type: 'pie',
        radius: '60%',
        data
      }
    ]
  }
})

function getLabel(pattern: IdlePattern): string {
  return IDLE_PATTERN_LABELS[pattern]
}

function getColor(pattern: IdlePattern): string {
  return IDLE_PATTERN_COLORS[pattern]
}

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  try {
    const resp = await getIdleResources(params)
    idleResources.value = resp.items
  } catch (e) {
    console.error('查闲置清单失败', e)
  }
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
</style>