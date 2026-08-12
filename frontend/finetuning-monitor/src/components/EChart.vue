<template>
  <div ref="chartRef" :style="{ width: width, height: height }"></div>
</template>

<script setup lang="ts">
// ECharts 通用组件
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'

const props = withDefaults(
  defineProps<{
    option: any
    width?: string
    height?: string
  }>(),
  {
    width: '100%',
    height: '300px'
  }
)

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function initChart() {
  if (chartRef.value) {
    chart = echarts.init(chartRef.value)
    chart.setOption(props.option)
  }
}

function resizeChart() {
  chart?.resize()
}

onMounted(() => {
  initChart()
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})

watch(
  () => props.option,
  (newOption) => {
    chart?.setOption(newOption, true)
  },
  { deep: true }
)
</script>