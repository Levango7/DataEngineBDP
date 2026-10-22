/**
 * deliveryMethod 这条字段"从装饰件变真字段"的消费侧守卫（台账 #81 第二项）。
 *
 * 曾经的状态：登记表单在发这个键，后端模型里没有它、请求 DTO 又是 `extra='ignore'`
 * ⇒ 值被静默丢弃，页面上选了什么都一样。后端已经补上模型 + 表列（含幂等 ALTER）
 * + 两个请求 DTO，并由 `platform/asset-exchange/tests/test_asset_delivery_method.py`
 * 钉住"响应里真带这个键、非法值 422、老库补列"。
 *
 * 本文件钉消费侧三件事，都是**确定性判据**：
 * 1. 视图模板里详情区真的绑到了 `detailAsset.deliveryMethod`（剥掉 HTML 注释后再找，
 *    否则一行注释就能骗过判据）；
 * 2. 中英两份语言包都有这个标签键，且三档取值键集齐全（缺键时 vue-i18n 会回落成
 *    打印键名本身，肉眼在小弹窗里不易发现）；
 * 3. `Asset` 接口声明了这个字段（不声明则读 `a.deliveryMethod` 在 TS 侧直接红，
 *    也就没人能在页面上用它）。
 *
 * 为什么不用 DOM 断言"弹窗里看得见"：详情区在 `@/components/Modal.vue` 里由
 * `v-if="visible"` 控制，实测在本仓 jsdom 夹具下 `trigger('click')` 打不开它
 * （跑过一版 DOM 断言，收到的文本只有卡片列表）。既然入口本身不可靠，
 * 就把判据放在"结构 + 语言包"这两件能真证的事上，不假称做过的渲染验证。
 */
import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// 本仓 vitest 里 `import.meta.url` 不是 file: 协议（实测 `fileURLToPath` 抛
// "The URL must be of scheme file"），所以按 `process.cwd()`（= frontend/）取路径，
// 并且**文件不存在就直接抛**——判据绝不能对着一段空内容"通过"。
const ROOT = process.cwd()
const read = (rel: string): string => {
  const p = resolve(ROOT, rel)
  if (!existsSync(p)) throw new Error(`夹具文件不存在：${p}（cwd=${ROOT}）`)
  return readFileSync(p, 'utf-8')
}

/**
 * 剥 HTML 注释用下标扫描而不是 `replace(/<!--...-->/g)`：后者会被 CodeQL 的
 * js/incomplete-multi-character-sanitization 判为"仍可能残留 `<!--`"（即使这里
 * 只处理仓库内只读的 SFC 文本）。下标扫描语义等价、也没有可被误读的净化意图。
 */
function stripHtmlComments(text: string): string {
  let out = ''
  let i = 0
  for (;;) {
    const start = text.indexOf('<!--', i)
    if (start < 0) return out + text.slice(i)
    out += text.slice(i, start)
    const end = text.indexOf('-->', start + 4)
    if (end < 0) return out
    i = end + 3
  }
}

const view = read('src/views/AssetMarket.vue')
const zh = read('src/i18n/locales/modules/assetMarket.zh-CN.json')
const en = read('src/i18n/locales/modules/assetMarket.en-US.json')
const apiClient = read('src/api/assetMarket.ts')

describe('AssetMarket 的交付方式消费链（台账 #81）', () => {
  it('详情区把 deliveryMethod 绑进标签行（注释里的不算）', () => {
    const template = stripHtmlComments(view)
    const labelIdx = template.indexOf(`t('assetMarket.detail.fields.delivery')`)
    expect(labelIdx, '视图里没有"交付方式"这个详情标签').toBeGreaterThan(-1)
    const block = template.slice(labelIdx, labelIdx + 260)
    expect(block).toContain('detailAsset.deliveryMethod')
    expect(block).toContain('assetMarket.deliveryMethod.')
  })

  it('中英语言包都有标签键与三档取值', () => {
    const labelOf = (text: string): string => {
      const doc = JSON.parse(text) as {
        assetMarket: {
          detail: { fields: Record<string, string> }
          deliveryMethod: Record<string, string>
        }
      }
      const delivery = doc.assetMarket.detail.fields.delivery
      expect(delivery, '缺 detail.fields.delivery').toBeTruthy()
      for (const k of ['api', 'file', 'database_direct']) {
        expect(doc.assetMarket.deliveryMethod[k], `缺 deliveryMethod.${k}`).toBeTruthy()
      }
      return delivery
    }
    const zhLabel = labelOf(zh)
    const enLabel = labelOf(en)
    expect(zhLabel).not.toBe(enLabel) // 两份都填了同一段中文＝en-US 没真译
  })

  it('Asset 接口声明了 deliveryMethod（后端响应现在一定带）', () => {
    const iface = /export interface Asset \{[\s\S]*?\n\}/.exec(apiClient)
    const body = iface ? iface[0] : ''
    expect(body, '找不到 Asset 接口').not.toBe('')
    expect(body).toMatch(/deliveryMethod:\s*DeliveryMethod/)
  })
})
