<template>
  <div class="page">
    <h2>Top10 成本资源</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>Top10 成本资源柱状图</template>
          <EChart v-if="barOption" :option="barOption" height="400px" />
          <el-empty v-else description="暂无数据" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>Top10 成本资源饼图</template>
          <EChart v-if="pieOption" :option="pieOption" height="400px" />
          <el-empty v-else description="暂无数据" />
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>Top10 成本资源明细表</template>
      <el-table :data="resources" stripe>
        <el-table-column type="index" label="排名" width="80" />
        <el-table-column prop="resourceId" label="资源ID" />
        <el-table-column prop="resourceType" label="类型" width="100" />
        <el-table-column prop="tenant" label="租户" />
        <el-table-column prop="namespace" label="namespace" />
        <el-table-column prop="workspace" label="工作空间" />
        <el-table-column prop="totalCost" label="总成本（元）" sortable />
        <el-table-column prop="percentage" label="占比（%）" sortable />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChart from '@/components/EChart.vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { getTop10 } from '@/api/finops'
import type { TopCostResource } from '@/types'

const resources = ref<TopCostResource[]>([])

const barOption = computed<EChartsOption | null>(() => {
  if (resources.value.length === 0) return null
  return {
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: resources.value.map((r) => r.resourceId),
      axisLabel: { rotate: 30 }
    },
    yAxis: { type: 'value', name: '成本（元）' },
    series: [
      {
        name: '总成本',
        type: 'bar',
        data: resources.value.map((r) => r.totalCost),
        itemStyle: { color: '#5470c6' }
      }
    ]
  }
})

const pieOption = computed<EChartsOption | null>(() => {
  if (resources.value.length === 0) return null
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 元 ({d}%)' },
    legend: { orient: 'vertical', left: 'left' },
    series: [
      {
        name: '成本占比',
        type: 'pie',
        radius: '60%',
        data: resources.value.map((r) => ({
          name: r.resourceId,
          value: r.totalCost
        }))
      }
    ]
  }
})

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  try {
    const resp = await getTop10(params)
    resources.value = resp.items
  } catch (e) {
    console.error('查询 Top10 失败', e)
  }
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
</style>