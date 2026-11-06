<!--
  ChartView.vue — ECharts 图表渲染（AI 助手内部组件）

  功能：
  - 接受 ChartConfig，使用 ECharts 渲染
  - 文字 / 轴线 / 图例色跟随主题（主题色板合并进外部 option）
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
import { useChartPalette, type ChartPalette } from '@/composables/useChartTheme'

interface Props {
  config: ChartConfig
}
const props = defineProps<Props>()

const chartRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

// canvas 不解析 CSS 变量：option 由后端 / AI 生成，往往不设文字色，
// ECharts 会静默回落到出厂默认 #333 / #ccc，在暗色画布上不可读。
// 这里只**补**没设的项，上游显式写过的颜色一律保持不动。
const palette = useChartPalette()

type Any = Record<string, unknown>

/** 把主题色板里的文字 / 轴线 / 网格色补进外部 option（不覆盖已有值） */
function withPalette(opt: Any): Any {
  const c = palette.value
  const out: Any = { ...opt }

  out.textStyle = { color: c.axisText, ...((opt.textStyle as Any) ?? {}) }
  out.tooltip = {
    textStyle: { color: c.tooltipTitle },
    backgroundColor: c.tooltipBg,
    borderColor: c.tooltipBorder,
    ...((opt.tooltip as Any) ?? {})
  }

  const axes = Array.isArray(opt.xAxis) ? (opt.xAxis as Any[]) : [opt.xAxis as Any]
  const yAxes = Array.isArray(opt.yAxis) ? (opt.yAxis as Any[]) : [opt.yAxis as Any]
  // 原 option 没写某个轴时不要凭空造一个轴对象出来
  if (opt.xAxis !== undefined) out.xAxis = axes.map((ax) => paintAxis(ax, c))
  if (opt.yAxis !== undefined) out.yAxis = yAxes.map((ax) => paintAxis(ax, c))

  const legend = opt.legend
  if (Array.isArray(legend)) {
    out.legend = legend.map((l: Any) => ({
      ...l,
      textStyle: { color: c.legendText, ...((l.textStyle as Any) ?? {}) }
    }))
  } else if (legend) {
    const l = legend as Any
    out.legend = { ...l, textStyle: { color: c.legendText, ...((l.textStyle as Any) ?? {}) } }
  }

  const series = opt.series
  if (Array.isArray(series)) {
    out.series = series.map((s: Any) =>
      s && typeof s === 'object'
        ? { ...s, label: { color: c.axisText, ...((s.label as Any) ?? {}) } }
        : s
    )
  }
  return out
}

/** 给单个坐标轴补轴标签 / 轴线 / 网格线颜色（已有值不动） */
function paintAxis(ax: Any | undefined, c: ChartPalette): Any | undefined {
  if (!ax || typeof ax !== 'object') return ax
  return {
    ...ax,
    axisLabel: { color: c.axisText, ...((ax.axisLabel as Any) ?? {}) },
    axisLine: { lineStyle: { color: c.axisLine, ...((ax.axisLine as Any)?.lineStyle ?? {}) } },
    splitLine: { lineStyle: { color: c.gridLine, ...((ax.splitLine as Any)?.lineStyle ?? {}) } }
  }
}

function render(): void {
  if (!chartRef.value || !props.config) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption(withPalette(props.config.option), true)
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

// 切主题时重绘：颜色是 setOption 时写进 canvas 的，不重绘不跟随主题
watch(palette, () => render())
</script>

<style scoped>
.chart-view {
  width: 100%;
  height: 320px;
  min-height: 280px;
}
</style>
