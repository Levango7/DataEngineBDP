/**
 * model-registry E2E（台账 #68 选项① 第 1 步）
 *
 * 该服务此前在 IT 栈以 AUTH_MODE=none 匿名放行，且全仓没有任何针对它的
 * "未认证应被拒"用例（前端 /api/v1/registry 由 vite proxy 精确分流到 :18089）。
 * 本 spec 把契约钉成"带 token 200 / 不带 token 401"，让翻转后的鉴权回归可被门禁看见。
 */
import { test, expect } from '@playwright/test'
import { apiBase, getApiToken } from './helpers'

test.describe('模型仓库（model-registry）', () => {
  test('deployments 带 token 返回 200', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/registry/deployments`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('deployments 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/registry/deployments`)
    expect(resp.status()).toBe(401)
  })
})
