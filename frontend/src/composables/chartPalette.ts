/**
 * ECharts 主题色板（**纯常量**，零依赖）
 *
 * 拆出来的原因：E2E 对比度 harness 需要在 Node/Playwright 侧读到同一组色值做断言，
 * 而 `useChartTheme.ts` 依赖 `@/stores/theme`（Vite 别名 + pinia），无法在 Node 里直接 import。
 * 所以色值表放在这个纯 TS 模块，`useChartTheme.ts` 与测试都从这里取，避免两处漂移。
 *
 * ## 为什么 ECharts 需要"真实色值"
 * ECharts 把图元画在 `<canvas>` 上，而 **canvas 不解析 CSS 变量**。把
 * `'var(--ds-text-secondary)'` 塞进 option，ECharts 解析失败后会静默回退到出厂默认
 * （`#333` / `#ccc`），在暗色画布 `#101317` 上几乎不可见 —— 这就是"图表文字太浅"的根因。
 *
 * ## 与 design-tokens.css 的对应关系（改 token 时同步本表）
 * | 用途                        | 亮色                       | 暗色                       |
 * | --------------------------- | -------------------------- | -------------------------- |
 * | 轴标签 / 图例 / tooltip 正文 | `#454d5a` text-secondary   | `#adb5c2` text-secondary   |
 * | tooltip 标题                | `#16181d` text-primary     | `#e7eaf0` text-primary     |
 * | splitLine 网格线            | `#d9dee6` border-default   | `#2f353e` border-default   |
 * | axisLine 轴线               | `#cbd5e1`                  | `#3a4048`                  |
 * | tooltip 底 / 描边           | `#ffffff` / `#d9dee6`      | `#23282f` / `#2f353e`      |
 * | 序列色 success / warning    | `#047857` / `#d97706`      | `#34d399` / `#fbbf24`      |
 *
 * ## 对比度（实测，见 tests/e2e/chart-contrast.spec.ts）
 * 轴标签 / 图例 / tooltip 文字在亮色 `#ffffff`、`#f3f5f9` 与暗色 `#171a20`、`#101317`
 * 四种真实底色上均 ≥ 4.5:1。
 */

export interface ChartPalette {
  /** 轴标签文字 */
  axisText: string
  /** 图例文字 */
  legendText: string
  /** tooltip 正文 */
  tooltipText: string
  /** tooltip 标题 / 强调文字 */
  tooltipTitle: string
  /** tooltip 底色 */
  tooltipBg: string
  /** tooltip 描边 */
  tooltipBorder: string
  /** splitLine 网格线 */
  gridLine: string
  /** axisLine 轴线 */
  axisLine: string
  /** 序列色 */
  series: { success: string; warning: string }
}

export const CHART_PALETTE_LIGHT: ChartPalette = {
  axisText: '#454d5a', // --ds-text-secondary (light)
  legendText: '#454d5a',
  tooltipText: '#16181d', // --ds-text-primary (light)
  tooltipTitle: '#16181d',
  tooltipBg: '#ffffff',
  tooltipBorder: '#d9dee6', // --ds-border-default (light)
  gridLine: '#d9dee6',
  axisLine: '#cbd5e1',
  series: { success: '#047857', warning: '#d97706' }
}

export const CHART_PALETTE_DARK: ChartPalette = {
  axisText: '#adb5c2', // --ds-text-secondary (dark)
  legendText: '#adb5c2',
  tooltipText: '#e7eaf0', // --ds-text-primary (dark)
  tooltipTitle: '#e7eaf0',
  tooltipBg: '#23282f', // --ds-surface-3 (dark)
  tooltipBorder: '#2f353e', // --ds-border-default (dark)
  gridLine: '#2f353e',
  axisLine: '#3a4048',
  series: { success: '#34d399', warning: '#fbbf24' }
}
