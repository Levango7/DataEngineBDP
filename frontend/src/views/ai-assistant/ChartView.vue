<!--
  ChartView.vue — ECharts 图表渲染（AI 助手内部组件）

  功能：
  - 接收 ChartConfig，使用 ECharts 渲染
  - 自适应容器尺寸（ResizeObserver）
  - 卸载时释放 ECharts 实例
-->
<template>
  <div ref="chartRef" class="chart-view"></div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'
import type { ChartConfig } from '@/types/ai-assistant'

interface Props {
  config: ChartConfig
}
const props = defineProps<Props>()

const chartRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function render(): void {
  if (!chartRef.value || !props.config) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption(props.config.option, true)
}

onMounted(() => {
  render()
  if (chartRef.value) {
    resizeObserver = new ResizeObserver(() => {
      chartInstance?.resize()
    })
    resizeObserver.observe(chartRef.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chartInstance?.dispose()
  chartInstance = null
})

watch(
  () => props.config,
  () => render(),
  { deep: true }
)
</script>

<style scoped>
.chart-view {
  width: 100%;
  height: 320px;
  min-height: 280px;
}
</style>
