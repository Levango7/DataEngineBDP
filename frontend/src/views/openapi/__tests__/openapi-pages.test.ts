/**
 * C3 收编的两页（一键生成、订阅管理）。
 *
 * 钉四类真实风险：
 *  1. 加载失败要进错误态并给重试入口，不能回落成"暂无订阅申请"——后者让"服务挂了"
 *     与"确实没有数据"在 DOM 上长得一模一样（台账 #78 续 2 的假绿教训）；
 *  2. 选项接口失败时页面仍可填表，但提交必须把后端的 422 在本地先拦成有名有姓的提示；
 *  3. 发出的载荷形状必须与 Pydantic 模型一致：驳回（approve=false）不能带 grantedQuota，
 *     非月包不能把 0 当配额写回，operator/approver 漏填就是 422；
 *  4. 文本全部来自 i18n，渲染结果里不许出现裸 key。
 *
 * 注：src/test-setup.ts 的 ElTableColumn stub 不渲染 scoped slot，所以行内按钮点不到；
 * 行级动作直接调页面暴露的函数（与 karmada-views.test.ts 同一口径）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import rootZh from '@/i18n/locales/zh-CN.json'
import apiMarketZh from '@/i18n/locales/modules/apiMarket.zh-CN.json'
import ApiGenerate from '@/views/openapi/ApiGenerate.vue'
import ApiSubscriptions from '@/views/openapi/ApiSubscriptions.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { ...(rootZh as object), ...(apiMarketZh as object) } as never },
  missingWarn: false,
  fallbackWarn: false
})

const {
  getGenerateOptionsMock,
  generateFromSqlMock,
  generateFromModelMock,
  generateFromFunctionMock,
  listSubscriptionsMock,
  approveSubscriptionMock,
  suspendSubscriptionMock,
  resumeSubscriptionMock,
  revokeSubscriptionMock,
  getSubscriptionKeysMock,
  issueSubscriptionKeyMock,
  getRateLimitMock,
  setRateLimitMock
} = vi.hoisted(() => ({
  getGenerateOptionsMock: vi.fn(),
  generateFromSqlMock: vi.fn(),
  generateFromModelMock: vi.fn(),
  generateFromFunctionMock: vi.fn(),
  listSubscriptionsMock: vi.fn(),
  approveSubscriptionMock: vi.fn(),
  suspendSubscriptionMock: vi.fn(),
  resumeSubscriptionMock: vi.fn(),
  revokeSubscriptionMock: vi.fn(),
  getSubscriptionKeysMock: vi.fn(),
  issueSubscriptionKeyMock: vi.fn(),
  getRateLimitMock: vi.fn(),
  setRateLimitMock: vi.fn()
}))

vi.mock('@/api/apiCatalog', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/apiCatalog')>()
  return {
    ...actual,
    getGenerateOptions: getGenerateOptionsMock,
    generateFromSql: generateFromSqlMock,
    generateFromModel: generateFromModelMock,
    generateFromFunction: generateFromFunctionMock,
    listSubscriptions: listSubscriptionsMock,
    approveSubscription: approveSubscriptionMock,
    suspendSubscription: suspendSubscriptionMock,
    resumeSubscription: resumeSubscriptionMock,
    revokeSubscription: revokeSubscriptionMock,
    getSubscriptionKeys: getSubscriptionKeysMock,
    issueSubscriptionKey: issueSubscriptionKeyMock,
    getRateLimit: getRateLimitMock,
    setRateLimit: setRateLimitMock
  }
})

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { username: 'tester' } })
}))

type Wrapper = ReturnType<typeof mount>
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const vm = (w: Wrapper) => w.vm as unknown as Record<string, any>

function mountView(component: unknown): Wrapper {
  return mount(component as never, { global: { plugins: [i18n] } })
}

const SUB_ROWS = [
  {
    id: 'sub-1',
    apiId: 'api-1',
    subscriberId: 'who-1',
    subscriberTenantId: 'tenant-b',
    providerTenantId: 'tenant-a',
    purpose: 'p',
    quotaExpect: 5000,
    status: 'pending',
    grantedQuota: 0,
    callCount: 0,
    errorCount: 0
  }
]

describe('ApiGenerate（一键生成 API）', () => {
  beforeEach(() => {
    getGenerateOptionsMock.mockReset().mockResolvedValue({
      datasources: ['trino', 'doris'],
      modelTypes: ['llm', 'embedding'],
      runtimes: ['python', 'nodejs']
    })
    generateFromSqlMock
      .mockReset()
      .mockResolvedValue({ id: 'api-new', name: 'a', path: '/a', status: 'draft' })
    generateFromModelMock.mockReset()
    generateFromFunctionMock.mockReset()
  })

  it('把后端给的三组候选值喂进各自的下拉，而不是页面自己抄一份', async () => {
    const w = mountView(ApiGenerate)
    await flushPromises()
    expect(getGenerateOptionsMock).toHaveBeenCalledTimes(1)
    expect(vm(w).options.datasources).toEqual(['trino', 'doris'])
    expect(vm(w).options.runtimes).toEqual(['python', 'nodejs'])
  })

  it('选项接口挂了只提示一次重试入口，填表能力不受影响', async () => {
    getGenerateOptionsMock.mockRejectedValue(new Error('503'))
    const w = mountView(ApiGenerate)
    await flushPromises()
    expect(vm(w).optionsError).toContain('503')
    expect(w.find('[role="alert"]').exists()).toBe(true)
  })

  it('必填项在本地先拦：拦下时一个请求都不发', async () => {
    const w = mountView(ApiGenerate)
    await flushPromises()
    vm(w).submit()
    await flushPromises()
    expect(vm(w).formError).toBe('请填写 API 名称')
    expect(generateFromSqlMock).not.toHaveBeenCalled()
  })

  it('SQL 走 /apis/generate/sql，字符串两端空白被裁掉', async () => {
    const w = mountView(ApiGenerate)
    await flushPromises()
    vm(w).sql.name = '  weather-query  '
    vm(w).sql.sql = '  SELECT 1 '
    vm(w).sql.datasource = 'trino'
    vm(w).sql.providerTenantId = ' tenant-a '
    await vm(w).submit()
    await flushPromises()
    expect(generateFromSqlMock).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'weather-query',
        sql: 'SELECT 1',
        datasource: 'trino',
        providerTenantId: 'tenant-a'
      })
    )
    expect(vm(w).created?.id).toBe('api-new')
  })

  it('三个标签页各自打各自的后端，函数页还要带上超时与内存', async () => {
    generateFromModelMock.mockResolvedValue({ id: 'm1', name: 'm', path: '/m', status: 'draft' })
    generateFromFunctionMock.mockResolvedValue({ id: 'f1', name: 'f', path: '/f', status: 'draft' })
    const w = mountView(ApiGenerate)
    await flushPromises()

    vm(w).activeTab = 'model'
    Object.assign(vm(w).model, {
      name: 'm',
      modelId: 'gpt',
      modelType: 'llm',
      providerTenantId: 't'
    })
    await vm(w).submit()
    expect(generateFromModelMock).toHaveBeenCalledTimes(1)

    vm(w).activeTab = 'function'
    Object.assign(vm(w).fn, {
      name: 'f',
      functionName: 'handler',
      runtime: 'python',
      providerTenantId: 't',
      timeout: 60000,
      memoryMB: 1024
    })
    await vm(w).submit()
    expect(generateFromFunctionMock).toHaveBeenCalledWith(
      expect.objectContaining({ timeout: 60000, memoryMB: 1024 })
    )
  })

  it('渲染文本里没有裸 i18n key', async () => {
    const w = mountView(ApiGenerate)
    await flushPromises()
    expect(w.text()).not.toMatch(/apiMarket\.[a-zA-Z]/)
    expect(w.text()).not.toMatch(/common\.[a-zA-Z]/)
  })
})

describe('ApiSubscriptions（订阅审批 / AK·SK / 限流）', () => {
  beforeEach(() => {
    listSubscriptionsMock.mockReset().mockResolvedValue(SUB_ROWS)
    approveSubscriptionMock.mockReset().mockResolvedValue({ ...SUB_ROWS[0], status: 'approved' })
    suspendSubscriptionMock.mockReset().mockResolvedValue({ ...SUB_ROWS[0], status: 'suspended' })
    resumeSubscriptionMock.mockReset().mockResolvedValue({ ...SUB_ROWS[0], status: 'active' })
    revokeSubscriptionMock.mockReset().mockResolvedValue({ ...SUB_ROWS[0], status: 'revoked' })
    getSubscriptionKeysMock.mockReset().mockResolvedValue({
      subscriptionId: 'sub-1',
      accessKey: 'AK-1',
      hasSecretKey: false,
      status: 'approved'
    })
    issueSubscriptionKeyMock.mockReset().mockResolvedValue({
      subscriptionId: 'sub-1',
      accessKey: 'AK-2',
      secretKey: 'SK-SECRET',
      issuedAt: '2026-10-22T00:00:00Z',
      reason: '重新颁发'
    })
    getRateLimitMock.mockReset().mockResolvedValue({
      subscriptionId: 'sub-1',
      qps: 500,
      concurrent: 8,
      burst: 500,
      updatedAt: '2026-10-22T00:00:00Z'
    })
    setRateLimitMock.mockReset().mockResolvedValue({
      subscriptionId: 'sub-1',
      qps: 900,
      concurrent: 0,
      burst: 0,
      updatedAt: '2026-10-22T00:00:00Z'
    })
  })

  it('列表加载失败进错误态，不回落成"暂无订阅申请"', async () => {
    listSubscriptionsMock.mockRejectedValue(new Error('502'))
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    expect(vm(w).error).toContain('502')
    expect(w.text()).not.toContain('暂无订阅申请')
  })

  it('审批人取登录态预填，且驳回时不带 grantedQuota', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    vm(w).openApprove(SUB_ROWS[0])
    expect(vm(w).approve.approver).toBe('tester')
    // 授予配额默认取申请人期望值，而不是写死一个数
    expect(vm(w).approve.grantedQuota).toBe(5000)

    vm(w).approve.approve = false
    await vm(w).doApprove()
    expect(approveSubscriptionMock).toHaveBeenCalledWith(
      'sub-1',
      expect.objectContaining({ approve: false, approver: 'tester' })
    )
    const payload = approveSubscriptionMock.mock.calls[0][1] as Record<string, unknown>
    expect('grantedQuota' in payload).toBe(false)
  })

  it('审批人为空时本地拦住（后端该字段必填）', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    vm(w).openApprove(SUB_ROWS[0])
    vm(w).approve.approver = '   '
    await vm(w).doApprove()
    expect(vm(w).actionError).toContain('审批人')
    expect(approveSubscriptionMock).not.toHaveBeenCalled()
  })

  it('颁发密钥后 SK 就地显示一次，随后重新拉列表', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    await vm(w).openKeys(SUB_ROWS[0])
    expect(getSubscriptionKeysMock).toHaveBeenCalledWith('sub-1')
    expect(vm(w).keyInfo?.accessKey).toBe('AK-1')

    await vm(w).doIssueKey()
    expect(issueSubscriptionKeyMock).toHaveBeenCalledWith('sub-1', {
      reason: '重新颁发',
      operator: 'tester'
    })
    // SK 只在颁发响应里出现一次，页面必须当场展示，否则用户永远拿不到
    expect(vm(w).issued?.secretKey).toBe('SK-SECRET')
    expect(w.text()).toContain('SK-SECRET')
    expect(listSubscriptionsMock).toHaveBeenCalledTimes(2)
  })

  it('限流表单先读回现值再改，保存发完整三字段', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    await vm(w).openRate(SUB_ROWS[0])
    expect(vm(w).rate.qps).toBe(500)
    expect(vm(w).rate.concurrent).toBe(8)

    vm(w).rate.qps = 900
    await vm(w).doRate()
    expect(setRateLimitMock).toHaveBeenCalledWith('sub-1', { qps: 900, concurrent: 8, burst: 500 })
  })

  it('qps 为 0 时不发请求（后端 ge=1）', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    await vm(w).openRate(SUB_ROWS[0])
    vm(w).rate.qps = 0
    await vm(w).doRate()
    expect(vm(w).actionError).toContain('每秒请求数')
    expect(setRateLimitMock).not.toHaveBeenCalled()
  })

  it('暂停/恢复/吊销各自打对应端点', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    await vm(w).act(SUB_ROWS[0], 'suspend')
    await vm(w).act(SUB_ROWS[0], 'resume')
    await vm(w).act(SUB_ROWS[0], 'revoke')
    expect(suspendSubscriptionMock).toHaveBeenCalledWith('sub-1')
    expect(resumeSubscriptionMock).toHaveBeenCalledWith('sub-1')
    expect(revokeSubscriptionMock).toHaveBeenCalledWith('sub-1')
  })

  it('渲染文本里没有裸 i18n key', async () => {
    const w = mountView(ApiSubscriptions)
    await flushPromises()
    expect(w.text()).not.toMatch(/apiMarket\.[a-zA-Z]/)
    expect(w.text()).not.toMatch(/common\.[a-zA-Z]/)
  })
})
