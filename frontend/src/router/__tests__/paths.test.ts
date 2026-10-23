/**
 * 路由路径的结构守卫：页面路径不得撞 `/api` 前缀。
 *
 * 这条不是洁癖，是 C3 实测出来的：路由用 hash history（`createWebHashHistory`），
 * 所以点导航与 `#/xxx` 深链都不经过服务端；但**不带 `#` 的裸路径**要真发一次请求，
 * 而 vite 的 `'/api'` 代理条目与 `frontend/nginx.conf` 的 `location /api` 都是前缀匹配——
 * 实测 `GET /api-generate` 在 dev 下回 **500**（转发到 :8080），同页改名后 `GET /ops-api-generate` 回 **200**，
 * 其余 73 条裸路径也都回 200。也就是说只有 `/api` 开头这一族会被当成 API 请求吞掉，
 * 手输地址、外部链接、以及任何按裸路径写的用例都会撞上，且报错长得像后端故障。
 *
 * 判据读源码而不是 import routes：`routes` 在 `src/router/index.ts` 里没有导出，
 * 为了测试去导出内部数组反而是扩大接口面。
 */
import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const ROOT = process.cwd()
const file = resolve(ROOT, 'src/router/index.ts')
if (!existsSync(file)) throw new Error(`夹具不存在：${file}（cwd=${ROOT}）`)
const src = readFileSync(file, 'utf-8')

const paths = [...src.matchAll(/path:\s*'([^']+)'/g)].map((m) => m[1])

describe('路由路径不撞 /api 前缀（vite 代理与 nginx location 都是前缀匹配）', () => {
  it('路由表能解析出路径，且数量在合理区间（当前 74 条）', () => {
    expect(paths.length).toBeGreaterThan(50)
    expect(paths.length).toBeLessThan(200)
  })

  it('没有以 /api、/api/、/api- 开头的页面路径', () => {
    const bad = paths.filter((p) => p === '/api' || p.startsWith('/api/') || p.startsWith('/api-'))
    expect(bad, `撞 /api 前缀的路径：${bad.join(', ')}`).toEqual([])
  })

  it('C3 两页走 ops-api-* 命名（防回退到 /api-generate）', () => {
    expect(paths).toContain('/ops-api-generate')
    expect(paths).toContain('/ops-api-subscriptions')
  })

  it('导航与 i18n 注册表用同一路径字符串（改名漏改任何一侧都会白屏或裸 key）', () => {
    const nav = readFileSync(resolve(ROOT, 'src/composables/useNavGroups.ts'), 'utf-8')
    const i18n = readFileSync(resolve(ROOT, 'src/i18n/index.ts'), 'utf-8')
    for (const p of ['/ops-api-generate', '/ops-api-subscriptions']) {
      expect(nav, `useNavGroups 里没有 ${p}`).toContain(`'${p}'`)
      expect(i18n, `ROUTE_MODULE_MAP 里没有 ${p}`).toContain(`'${p}'`)
    }
  })
})
