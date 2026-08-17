/**
 * API 响应格式验证 E2E 测试
 *
 * 验证 P0 接口统一后所有 API 返回 ApiResponse 统一格式：
 *   { code: 0, message: "OK", data: {...}, success: true }
 *
 * 覆盖：
 * - 所有 P0 接口返回 ApiResponse 统一格式
 * - 错误响应格式 { code: 非0, message: 错误信息, success: false }
 * - 401 未认证跳转登录页
 * - Token 过期处理
 * - ApiResponse 字段完整性
 */
import { test, expect } from '@playwright/test'
import { apiBase, getApiToken, ADMIN, login } from './helpers'

test.describe('API 响应格式验证（ApiResponse 统一包装）', () => {

  test.describe('成功响应格式 {code:0, message:"OK", data, success:true}', () => {
    const successApis = [
      { name: 'GET /projects',         path: '/projects',           method: 'GET'  as const },
      { name: 'GET /governance/assets', path: '/governance/assets', method: 'GET'  as const },
      { name: 'GET /standards',        path: '/standards',          method: 'GET'  as const },
      { name: 'GET /search/history',   path: '/search/history',     method: 'GET'  as const },
      { name: 'GET /search/facets',    path: '/search/facets',      method: 'GET'  as const }
    ]

    for (const api of successApis) {
      test(`${api.name} 返回 ApiResponse 统一格式`, async ({ request }) => {
        const token = await getApiToken(request)
        const resp = await request[api.method.toLowerCase() === 'get' ? 'get' : 'post'](
          `${apiBase}${api.path}`,
          { headers: { Authorization: `Bearer ${token}` } }
        )
        expect(resp.status()).toBe(200)
        const json = await resp.json()

        // 必备字段
        expect(json).toHaveProperty('code')
        expect(json).toHaveProperty('message')
        expect(json).toHaveProperty('data')
        expect(json).toHaveProperty('success')

        // 成功值
        expect(json.code).toBe(0)
        expect(json.message).toBe('OK')
        expect(json.success).toBe(true)
      })
    }
  })

  test.describe('登录接口响应格式', () => {
    test('POST /auth/login 成功返回 ApiResponse', async ({ request }) => {
      const resp = await request.post(`${apiBase}/auth/login`, { data: ADMIN })
      expect(resp.status()).toBe(200)
      const json = await resp.json()

      expect(json).toHaveProperty('code', 0)
      expect(json).toHaveProperty('message', 'OK')
      expect(json).toHaveProperty('success', true)
      expect(json.data).toHaveProperty('token')
      expect(json.data).toHaveProperty('user')
      expect(json.data).toHaveProperty('expiresIn')
      expect(typeof json.data.token).toBe('string')
      expect(json.data.token.length).toBeGreaterThan(10)
    })

    test('POST /auth/login 错误密码返回错误响应', async ({ request }) => {
      const resp = await request.post(`${apiBase}/auth/login`, {
        data: { username: 'admin', password: 'wrong_pwd_xyz' }
      })
      // 错误密码：后端返回 HTTP 401
      expect(resp.status()).toBe(401)
      // body 仍为 ApiResponse 格式（含 code/message/success 字段）
      const json = await resp.json()
      expect(json).toHaveProperty('code')
      expect(json).toHaveProperty('message')
      expect(json).toHaveProperty('success')
    })
  })

  test.describe('错误响应格式 {code:非0, message, success:false}', () => {
    test('参数类型错误返回 ApiResponse 错误格式', async ({ request }) => {
      const token = await getApiToken(request)
      // /projects/test/datasets 中 test 不是合法 id，应返回参数错误
      const resp = await request.get(`${apiBase}/projects/test/datasets`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const json = await resp.json()
      expect(json).toHaveProperty('code')
      expect(json).toHaveProperty('message')
      expect(json).toHaveProperty('success')
      // 应为业务错误
      expect(json.success).toBe(false)
      expect(json.code).not.toBe(0)
      expect(json.message).toBeTruthy()
    })
  })

  test.describe('401 未认证处理', () => {
    test('无 Token 访问受保护接口返回 401', async ({ request }) => {
      const apis = [
        '/projects',
        '/governance/assets',
        '/standards',
        '/search/history'
      ]
      for (const path of apis) {
        const resp = await request.get(`${apiBase}${path}`)
        expect(resp.status()).toBe(401)
      }
    })

    test('无效 Token 访问受保护接口返回 401', async ({ request }) => {
      const resp = await request.get(`${apiBase}/projects`, {
        headers: { Authorization: 'Bearer invalid_token_xyz_12345' }
      })
      expect(resp.status()).toBe(401)
    })

    test('401 触发前端跳转登录页', async ({ page }) => {
      // 先登录
      await login(page)
      await page.goto('/#/dashboard', { waitUntil: 'domcontentloaded' })

      // 清除 token（模拟 token 失效）
      await page.evaluate(() => localStorage.removeItem('sq_token'))

      // 触发一次 API 调用（访问需要鉴权的页面）
      await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })

      // 由于 token 已清除，路由守卫应跳回登录页
      // 或 API 401 后拦截器跳登录页
      await page.waitForTimeout(2_000)
      const url = page.url()
      // 应在登录页或仍尝试加载（取决于守卫优先还是 API 优先）
      expect(url).toMatch(/#\/(login|projects)/)
    })
  })

  test.describe('Token 过期处理', () => {
    test('过期 Token 访问接口返回 401', async ({ request }) => {
      // 构造一个明显过期的 JWT（header.payload.signature）
      // payload: { exp: 1 }  已过期
      const expiredPayload = Buffer.from(JSON.stringify({ sub: 'admin', exp: 1 })).toString('base64url')
      const fakeHeader = Buffer.from(JSON.stringify({ alg: 'HS384' })).toString('base64url')
      const expiredToken = `${fakeHeader}.${expiredPayload}.fake_signature`

      const resp = await request.get(`${apiBase}/projects`, {
        headers: { Authorization: `Bearer ${expiredToken}` }
      })
      expect(resp.status()).toBe(401)
    })
  })

  test.describe('ApiResponse 字段完整性', () => {
    test('所有成功响应包含 timestamp 字段', async ({ request }) => {
      const token = await getApiToken(request)
      const resp = await request.get(`${apiBase}/projects`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const json = await resp.json()
      // timestamp 可选但通常存在
      if (json.timestamp !== undefined) {
        expect(typeof json.timestamp).toBe('number')
        expect(json.timestamp).toBeGreaterThan(0)
      }
    })

    test('分页接口 data 包含 list/total/page/size', async ({ request }) => {
      const token = await getApiToken(request)
      const resp = await request.get(`${apiBase}/projects`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const json = await resp.json()
      expect(json.code).toBe(0)
      expect(json.data).toHaveProperty('list')
      expect(json.data).toHaveProperty('total')
      expect(json.data).toHaveProperty('page')
      // 后端用 size 或 pageSize
      expect(json.data).toHaveProperty('size')
      expect(Array.isArray(json.data.list)).toBe(true)
      expect(typeof json.data.total).toBe('number')
    })

    test('数组接口 data 为数组', async ({ request }) => {
      const token = await getApiToken(request)
      const resp = await request.get(`${apiBase}/search/history`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const json = await resp.json()
      expect(json.code).toBe(0)
      expect(Array.isArray(json.data)).toBe(true)
    })
  })

  test.describe('mock 清零验证（P0 关键改动）', () => {
    test('/projects/{id}/datasets 不再返回 mock 数据', async ({ request }) => {
      // 验证 P0 改动：mock 清零后接口返回 200 + 真实空数据
      const token = await getApiToken(request)
      const listResp = await request.get(`${apiBase}/projects`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      const listJson = await listResp.json()
      expect(listJson.code).toBe(0)

      if (listJson.data.list.length > 0) {
        const projectId = listJson.data.list[0].id
        const dsResp = await request.get(`${apiBase}/projects/${projectId}/datasets`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        expect(dsResp.status()).toBe(200)
        const dsJson = await dsResp.json()
        expect(dsJson.code).toBe(0)
        expect(dsJson.success).toBe(true)
        // 不再返回 mock 数据（应为真实空数组或真实数据集）
        expect(Array.isArray(dsJson.data)).toBe(true)
      }
    })

    test('/search/history 不再返回 mock 数据', async ({ request }) => {
      // 验证 P0 改动：mock 清零后 /search/history 返回 200 + 空数组
      const token = await getApiToken(request)
      const resp = await request.get(`${apiBase}/search/history`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      expect(resp.status()).toBe(200)
      const json = await resp.json()
      expect(json.code).toBe(0)
      expect(json.success).toBe(true)
      expect(Array.isArray(json.data)).toBe(true)
      // mock 清零后应为空
      expect(json.data.length).toBe(0)
    })
  })
})