/**
 * 对比度采样 harness（从 sidebar-contrast.spec.ts 抽出，供多个 spec 复用）
 *
 * 方法：把目标的前景文字临时设为 transparent → 截图 → 对文字包围盒内**每一个像素**
 * 做 alpha 合成 → 取最小对比度。这样得到的是**真实栅格结果**，天然包含
 * backdrop-filter 的合成、渐变、伪元素光晕与父级 opacity。
 *
 * 注意：这套方法只有在「浏览器真的栅格化了 backdrop-filter」时才有效。
 * 若 headless / 软件光栅化丢弃了模糊，采样到的会是降级实色，测试会"永远绿"。
 * 因此使用本 helper 的 spec **必须**同时跑 glass-harness-control.spec.ts 的正面控制。
 */
import type { Locator, Page } from '@playwright/test'

export async function settled(page: Page) {
  // Wait for finite CSS transitions (including hover) without changing production styles.
  await page.waitForFunction(() =>
    document.getAnimations().every(
      (a) => a.effect?.getTiming().iterations === Infinity || a.playState === 'finished'
    )
  )
}

export interface ContrastSample {
  state: string
  text?: string
  color: string
  opacity: number
  background: string
  backgroundImage: string
  parentBackground: string
  parentBackgroundImage: string
  fontSize: string
  rect: { x: number; y: number; width: number; height: number }
  minContrast: number
  worstBackground: number[]
}

export type SampleRow = ContrastSample & { threshold?: number }

export interface SampleOptions {
  /**
   * 目标内部需要临时隐藏的**装饰性**后代（逗号分隔选择器），例如状态胶囊里的
   * 彩色状态点。它们不是文字，却落在文字的包围盒里，会把"最小对比度"拉到 1.x
   * 造成假阴性。隐藏用 `visibility:hidden`（保留布局，文字位置不变）。
   */
  hide?: string
  /** 采样内缩像素，默认 2。圆角胶囊的描边弧线会侵入 2px 内缩区，可加大到 4。 */
  inset?: number
}

export async function sample(
  page: Page,
  target: Locator,
  state: string,
  file?: string,
  opts?: SampleOptions
): Promise<SampleRow> {
  await target.scrollIntoViewIfNeeded()
  await settled(page)
  const result = await target.evaluate((el) => {
    const cs = getComputedStyle(el)
    const r = el.getBoundingClientRect()
    let opacity = 1
    for (let p: Element | null = el; p; p = p.parentElement) {
      opacity *= Number(getComputedStyle(p).opacity)
    }
    const parent = getComputedStyle(el.parentElement!)
    return {
      state: '',
      text: el.textContent?.trim(),
      color: cs.color,
      opacity,
      background: cs.backgroundColor,
      backgroundImage: cs.backgroundImage,
      parentBackground: parent.backgroundColor,
      parentBackgroundImage: parent.backgroundImage,
      fontSize: cs.fontSize,
      rect: { x: r.x, y: r.y, width: r.width, height: r.height },
      minContrast: Number.NaN,
      worstBackground: [] as number[],
    }
  })
  result.state = state
  if (file) await page.screenshot({ path: file, animations: 'disabled' })

  // Capture the actual raster background, including gradients, pseudo-element glow and opacity.
  // Only this target's foreground is hidden temporarily, never a production source edit.
  const previous = await target.getAttribute('style')
  const inset = opts?.inset ?? 2
  await target.evaluate(
    (el, o) => {
      const h = el as HTMLElement
      h.style.setProperty('color', 'transparent', 'important')
      h.style.setProperty('-webkit-text-fill-color', 'transparent', 'important')
      h.style.setProperty('transition', 'none', 'important')
      if (o.hide) {
        el.querySelectorAll(o.hide).forEach((n) => {
          const e = n as HTMLElement
          e.dataset.probePrevVisibility = e.style.visibility
          e.style.setProperty('visibility', 'hidden', 'important')
        })
      }
    },
    { hide: opts?.hide ?? null }
  )
  let png: Buffer
  try {
    png = await page.screenshot({ animations: 'disabled' })
  } finally {
    await target.evaluate(
      (el, old) => {
        if (old === null) el.removeAttribute('style')
        else el.setAttribute('style', old)
        el.querySelectorAll('[data-probe-prev-visibility]').forEach((n) => {
          const e = n as HTMLElement
          e.style.visibility = e.dataset.probePrevVisibility ?? ''
          delete e.dataset.probePrevVisibility
        })
      },
      previous
    )
  }
  const contrast = await page.evaluate(
    async ({ image, row, insetPx }) => {
      const img = new Image()
      img.src = `data:image/png;base64,${image}`
      await img.decode()
      const canvas = document.createElement('canvas')
      canvas.width = img.width
      canvas.height = img.height
      const ctx = canvas.getContext('2d')!
      ctx.drawImage(img, 0, 0)
      const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data
      const fg = row.color.match(/[\d.]+/g)!.map(Number)
      const alpha = (fg[3] ?? 1) * row.opacity
      const lum = (rgb: number[]) =>
        rgb.slice(0, 3).reduce((sum, c, i) => {
          const s = c / 255
          return sum + (s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4) * [0.2126, 0.7152, 0.0722][i]
        }, 0)
      let min = Infinity
      let worstBackground: number[] = []
      const r = row.rect
      // Inset excludes borders/rounded corners; sample every raster pixel below the text box.
      for (let y = Math.max(0, Math.ceil(r.y + insetPx)); y < Math.min(canvas.height, r.y + r.height - insetPx); y++) {
        for (let x = Math.max(0, Math.ceil(r.x + insetPx)); x < Math.min(canvas.width, r.x + r.width - insetPx); x++) {
          const pos = (y * canvas.width + x) * 4
          const bg = [pixels[pos], pixels[pos + 1], pixels[pos + 2]]
          const ink = fg.slice(0, 3).map((c, i) => c * alpha + bg[i] * (1 - alpha))
          const a = lum(ink)
          const b = lum(bg)
          const ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
          if (ratio < min) {
            min = ratio
            worstBackground = bg
          }
        }
      }
      return { minContrast: min, worstBackground }
    },
    { image: png!.toString('base64'), row: result, insetPx: inset }
  )
  return { ...result, ...contrast }
}

/** 从 `rgb()/rgba()` 字符串里取 alpha；返回 1 表示完全不透明。 */
export function alphaOf(color: string): number {
  const m = color.match(/[\d.]+/g)
  if (!m) return 1
  return m.length >= 4 ? Number(m[3]) : 1
}

/* ------------------------------------------------------------------ *
 * 颜色工具
 * ------------------------------------------------------------------ */

/** `#rgb` / `#rrggbb` / `rgb()` / `rgba()` → [r, g, b] */
export function parseRgb(color: string): number[] {
  const hex = color.trim().match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i)
  if (hex) {
    const h = hex[1]
    const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
    return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16))
  }
  const nums = color.match(/[\d.]+/g)
  if (!nums) throw new Error(`无法解析颜色: ${color}`)
  return nums.slice(0, 3).map(Number)
}

export function toHex(rgb: number[]): string {
  return '#' + rgb.slice(0, 3).map((c) => Math.round(c).toString(16).padStart(2, '0')).join('')
}

/** WCAG 相对亮度 */
export function luminance(rgb: number[]): number {
  return rgb.slice(0, 3).reduce((sum, c, i) => {
    const s = c / 255
    return sum + (s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4) * [0.2126, 0.7152, 0.0722][i]
  }, 0)
}

/** WCAG 对比度（1 ~ 21） */
export function contrastRatio(a: number[], b: number[]): number {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/* ------------------------------------------------------------------ *
 * canvas 文字采样
 *
 * 为什么需要单独一套：ECharts 把轴标签 / 图例画在 **canvas** 上，DOM 里根本没有
 * 对应的文本节点，上面那套"隐藏前景文字 → 截图量像素"的 harness 完全测不到它们
 * —— 这正是上一轮"侧栏 12 点全绿却漏掉图表文字太浅"的原因。
 *
 * 方法：截取 canvas 位图 → 在给定的**文字区域**内
 *   ① 求众数色 = 该区域真实底色 bg（含渐变 / 叠加的真实栅格结果）；
 *   ② 统计与"预期文字色"接近的像素数 inkPresence —— 用于证明 ECharts 确实按我们
 *      配置的颜色绘制了（若颜色非法被回退成出厂 #333，这里会≈0）；
 *   ③ 计算 contrast(预期文字色, bg) —— 与 DOM harness 同口径（用声明色而非抗锯齿
 *      边缘像素），并额外回报"实测最远像素"供透明核对。
 * ------------------------------------------------------------------ */

/** 区域用 canvas 尺寸的**比例**表示，与 devicePixelRatio / 元素像素尺寸解耦 */
export interface CanvasRegion {
  x0: number
  y0: number
  x1: number
  y1: number
}

export interface CanvasTextSample {
  state: string
  region: string
  bg: number[]
  bgHex: string
  expected: number[]
  expectedHex: string
  /** 区域内与 expected 接近（容差内）的像素数 */
  inkPresence: number
  regionPixels: number
  /** contrast(expected, bg) */
  contrast: number
  /** 区域内离底色最远的像素（透明回报，不参与判定） */
  empiricalInk: number[]
  empiricalInkHex: string
  empiricalContrast: number
  threshold: number
  pass: boolean
}

/**
 * 采样 canvas 内某块区域上的文字对比度。
 *
 * @param canvas    图表的 canvas（或承载 canvas 的容器）
 * @param region    文字区域（比例坐标）
 * @param expected  预期文字色（真实色值，来自 chartPalette.ts）
 * @param state     采样点名称
 * @param threshold 达标阈值（默认 4.5）
 */
export async function sampleCanvasText(
  page: Page,
  canvas: Locator,
  region: CanvasRegion,
  expected: string,
  state: string,
  threshold = 4.5
): Promise<CanvasTextSample> {
  await canvas.scrollIntoViewIfNeeded()
  await settled(page)
  const png = await canvas.screenshot({ animations: 'disabled' })
  const raw = await page.evaluate(
    async ({ image, region, expectedHex }) => {
      const img = new Image()
      img.src = `data:image/png;base64,${image}`
      await img.decode()
      const cv = document.createElement('canvas')
      cv.width = img.width
      cv.height = img.height
      const ctx = cv.getContext('2d')!
      ctx.drawImage(img, 0, 0)
      const x0 = Math.max(0, Math.floor(region.x0 * img.width))
      const y0 = Math.max(0, Math.floor(region.y0 * img.height))
      const x1 = Math.min(img.width, Math.ceil(region.x1 * img.width))
      const y1 = Math.min(img.height, Math.ceil(region.y1 * img.height))
      const data = ctx.getImageData(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)).data
      const lum = (r: number, g: number, b: number) => {
        const f = (c: number) => {
          const s = c / 255
          return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
        }
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
      }
      const exp = expectedHex.match(/[0-9a-f]{2}/gi)!.map((h) => parseInt(h, 16))
      const hist = new Map<string, { n: number; rgb: number[] }>()
      let inkPresence = 0
      let empInk: number[] = [0, 0, 0]
      let empContrast = -1
      let total = 0
      for (let i = 0; i < data.length; i += 4) {
        const r = data[i]
        const g = data[i + 1]
        const b = data[i + 2]
        total++
        const key = `${r},${g},${b}`
        const e = hist.get(key)
        if (e) e.n++
        else hist.set(key, { n: 1, rgb: [r, g, b] })
        // 与预期文字色的距离（容差 24/通道 ≈ 抗锯齿之外的"实心字"像素）
        if (Math.abs(r - exp[0]) <= 24 && Math.abs(g - exp[1]) <= 24 && Math.abs(b - exp[2]) <= 24) {
          inkPresence++
        }
      }
      let bg: number[] = [0, 0, 0]
      let best = -1
      for (const { n, rgb } of hist.values()) {
        if (n > best) {
          best = n
          bg = rgb
        }
      }
      const lb = lum(bg[0], bg[1], bg[2])
      for (const { rgb } of hist.values()) {
        const l = lum(rgb[0], rgb[1], rgb[2])
        const ratio = (Math.max(l, lb) + 0.05) / (Math.min(l, lb) + 0.05)
        if (ratio > empContrast) {
          empContrast = ratio
          empInk = rgb
        }
      }
      return { bg, inkPresence, total, empiricalInk: empInk, empiricalContrast: empContrast }
    },
    { image: png.toString('base64'), region, expectedHex: expected }
  )
  const expectedRgb = parseRgb(expected)
  const contrast = contrastRatio(expectedRgb, raw.bg)
  return {
    state,
    region: `${region.x0},${region.y0} → ${region.x1},${region.y1}`,
    bg: raw.bg,
    bgHex: toHex(raw.bg),
    expected: expectedRgb,
    expectedHex: expected,
    inkPresence: raw.inkPresence,
    regionPixels: raw.total,
    contrast,
    empiricalInk: raw.empiricalInk,
    empiricalInkHex: toHex(raw.empiricalInk),
    empiricalContrast: raw.empiricalContrast,
    threshold,
    pass: contrast >= threshold
  }
}
