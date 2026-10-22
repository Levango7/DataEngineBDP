/**
 * C3 收编新增的 11 条客户端调用，钉的是"动词 + 路径 + 载荷形状"。
 *
 * 三条真实踩点风险（都对应后端源码，不是猜的）：
 *   - 按 API 的计费端点挂在 `billing.py` 的 **api_billing_router（prefix=/apis）** 上，
 *     而 AK/SK、限流挂在同文件的 **subscriptions_billing_router（prefix=/subscriptions）** 上；
 *     写串一个前缀就是 404，而页面把 404 渲染成空态（台账 #78 续 2 的假绿教训）。
 *   - `monthlyQuota` 与 `grantedQuota` 都是 `int | None = Field(ge=...)`：传 0 与不传不等价。
 *   - `operator` / `approver` 是后端必填（`Field(...)`），漏了直接 422。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { getMock, postMock, putMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn()
}))

vi.mock('@/api/client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock
}))

import {
  deployApisixRoute,
  generateFromFunction,
  generateFromModel,
  generateFromSql,
  getApisixConfig,
  getBillingConfig,
  getGenerateOptions,
  getRateLimit,
  getSubscription,
  getSubscriptionKeys,
  issueSubscriptionKey,
  setBillingConfig,
  setRateLimit,
  undeployApisixRoute
} from '@/api/apiCatalog'

describe('apiCatalog：一键生成（generate.py，prefix=/apis/generate）', () => {
  beforeEach(() => {
    getMock.mockReset().mockResolvedValue({})
    postMock.mockReset().mockResolvedValue({})
  })

  it('GET /apis/generate/options', async () => {
    await getGenerateOptions()
    expect(getMock).toHaveBeenCalledWith('/apis/generate/options')
  })

  it('三条生成端点各自的路径与载荷', async () => {
    await generateFromSql({
      name: 'a',
      sql: 'SELECT 1',
      datasource: 'trino',
      providerTenantId: 't'
    })
    expect(postMock).toHaveBeenLastCalledWith('/apis/generate/sql', {
      name: 'a',
      sql: 'SELECT 1',
      datasource: 'trino',
      providerTenantId: 't'
    })

    await generateFromModel({ name: 'b', modelId: 'm', modelType: 'llm', providerTenantId: 't' })
    expect(postMock).toHaveBeenLastCalledWith('/apis/generate/model', {
      name: 'b',
      modelId: 'm',
      modelType: 'llm',
      providerTenantId: 't'
    })

    await generateFromFunction({
      name: 'c',
      functionName: 'f',
      runtime: 'python',
      providerTenantId: 't',
      timeout: 30000,
      memoryMB: 512
    })
    expect(postMock).toHaveBeenLastCalledWith('/apis/generate/function', {
      name: 'c',
      functionName: 'f',
      runtime: 'python',
      providerTenantId: 't',
      timeout: 30000,
      memoryMB: 512
    })
  })
})

describe('apiCatalog：计费、密钥与限流的两套前缀', () => {
  beforeEach(() => {
    getMock.mockReset().mockResolvedValue({})
    postMock.mockReset().mockResolvedValue({})
    putMock.mockReset().mockResolvedValue({})
    delMock.mockReset().mockResolvedValue({})
  })

  it('按 API 计费走 /apis/{id}/billing，读写动词分别是 GET/PUT', async () => {
    await getBillingConfig('api-1')
    expect(getMock).toHaveBeenCalledWith('/apis/api-1/billing')
    await setBillingConfig('api-1', {
      costStrategy: 'by_call',
      costUnitPrice: 0.5,
      monthlyQuota: null
    })
    expect(putMock).toHaveBeenCalledWith('/apis/api-1/billing', {
      costStrategy: 'by_call',
      costUnitPrice: 0.5,
      monthlyQuota: null
    })
  })

  it('AK/SK 与限流走 /subscriptions/{id}/...，不是 /apis/', async () => {
    await getSubscriptionKeys('sub-1')
    expect(getMock).toHaveBeenCalledWith('/subscriptions/sub-1/keys')
    await issueSubscriptionKey('sub-1', { operator: 'admin' })
    expect(postMock).toHaveBeenCalledWith('/subscriptions/sub-1/keys', { operator: 'admin' })
    await setRateLimit('sub-1', { qps: 50, concurrent: 0, burst: 0 })
    expect(putMock).toHaveBeenCalledWith('/subscriptions/sub-1/rate-limit', {
      qps: 50,
      concurrent: 0,
      burst: 0
    })
    await getRateLimit('sub-1')
    expect(getMock).toHaveBeenCalledWith('/subscriptions/sub-1/rate-limit')
    await getSubscription('sub-1')
    expect(getMock).toHaveBeenCalledWith('/subscriptions/sub-1')
  })

  it('卸载路由用 DELETE（原客户端只有部署，后端 metrics_docs.py:102 是 delete）', async () => {
    await undeployApisixRoute('api-1')
    expect(delMock).toHaveBeenCalledWith('/apis/api-1/apisix-deploy')
    await deployApisixRoute('api-1')
    expect(postMock).toHaveBeenCalledWith('/apis/api-1/apisix-deploy')
    await getApisixConfig('api-1')
    expect(getMock).toHaveBeenCalledWith('/apis/api-1/apisix-config')
  })
})
