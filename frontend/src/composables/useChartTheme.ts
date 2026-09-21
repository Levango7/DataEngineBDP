/**
 * ECharts 主题色板的响应式封装。
 *
 * 色值表本身在 `chartPalette.ts`（纯常量，测试侧共用）；
 * 这里只负责把它接到主题 store 上，随亮 / 暗切换返回不同色板。
 *
 * 用法：
 * ```ts
 * const palette = useChartPalette()
 * function render() { chart.setOption({ axisLabel: { color: palette.value.axisText } }) }
 * watch(palette, () => render())  // 切主题时重绘
 * ```
 *
 * 背景与完整说明见 `chartPalette.ts` 顶部注释。
 */
import { computed, type ComputedRef } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { CHART_PALETTE_LIGHT, CHART_PALETTE_DARK, type ChartPalette } from './chartPalette'

export type { ChartPalette } from './chartPalette'
export { CHART_PALETTE_LIGHT, CHART_PALETTE_DARK } from './chartPalette'

/** 返回随当前主题切换的 ECharts 色板（computed：切主题后自动重新求值）。 */
export function useChartPalette(): ComputedRef<ChartPalette> {
  const theme = useThemeStore()
  return computed<ChartPalette>(() => (theme.isDark ? CHART_PALETTE_DARK : CHART_PALETTE_LIGHT))
}
