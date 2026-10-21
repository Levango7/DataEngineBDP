/**
 * Karmada 页面的 ECharts 取色器。
 *
 * 为什么需要它：图表色不能写死十六进制（scripts/check-hardcoded-colors.py 是棘轮门禁，
 * 新文件必须贡献 0 处裸色），但 ECharts 的 series.itemStyle.color 是 JS 字符串、
 * 走不了 CSS var()。所以只能在运行时把 --ds-* 令牌的值读出来喂给 ECharts。
 *
 * 第二个坑：读一次就缓存的话，切到暗色主题后图表仍是亮色配色（主题在
 * documentElement 的 data-theme 上，CSS 变量随之整组换值）。所以这里挂
 * MutationObserver 盯 data-theme，变化时回调重绘。
 */
import { onBeforeUnmount, ref } from 'vue'

/** 图表用到的令牌名 → 语义。全部是 design-tokens.css 里亮/暗两套都定义过的键。 */
const CHART_TOKENS = [
  '--ds-success',
  '--ds-warning',
  '--ds-danger',
  '--ds-info',
  '--ds-accent',
  '--ds-border-subtle',
  '--ds-text-secondary'
] as const

export type ChartTokenName = (typeof CHART_TOKENS)[number]
export type ChartPalette = Record<ChartTokenName, string>

/**
 * 从当前主题读一遍令牌值。
 *
 * 读不到（jsdom 里样式表未加载、或令牌被改名）时回落到 CSS 关键字，
 * 至少保证图表有色可画且不引入任何十六进制字面量。
 */
export function readChartPalette(): ChartPalette {
  const cs = getComputedStyle(document.documentElement)
  const out = {} as Record<ChartTokenName, string>
  for (const name of CHART_TOKENS) {
    out[name] = cs.getPropertyValue(name).trim() || 'currentColor'
  }
  return out
}

/**
 * 组合式：返回当前调色板 ref + 一个注册重绘回调的函数。
 *
 * 用法（视图里）：
 *   const { palette, onThemeChange } = useChartPalette()
 *   onThemeChange(() => renderChart())   // data-theme 变化时重绘
 *   renderChart()                        // 首屏自己调
 */
export function useChartPalette() {
  const palette = ref<ChartPalette>(readChartPalette())
  let observer: MutationObserver | null = null
  const redraws: Array<() => void> = []

  function refresh(): void {
    palette.value = readChartPalette()
    for (const fn of redraws) fn()
  }

  function onThemeChange(fn: () => void): void {
    redraws.push(fn)
  }

  function start(): void {
    if (observer || typeof MutationObserver === 'undefined') return
    observer = new MutationObserver((mutations) => {
      if (mutations.some((m) => m.attributeName === 'data-theme')) refresh()
    })
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme']
    })
  }

  start()

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    redraws.length = 0
  })

  return { palette, onThemeChange, refresh }
}
