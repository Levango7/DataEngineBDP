/**
 * 收编进主应用的多集群（Karmada）五页。
 *
 * 覆盖四类真实风险：
 *  1. 加载失败必须进错误态并给重试入口，不得回落成"暂无集群/暂无事件"（后者会让
 *     "服务挂了"和"确实没有数据"长得一模一样）；
 *  2. 表单必须在本地拦住后端会 400 的请求（空 overrideRules / 空 backupClusters /
 *     权重和为 0），且拦下时一个请求都不发；
 *  3. 发出去的载荷字段名与类型必须与 Go binding 一致（backupClusters 是字符串数组、
 *     weights 是 int、enabled 是布尔）；
 *  4. 页面文本全部来自 i18n，渲染结果里不许出现裸 key。
 *
 * 注：Element Plus 在测试里是 slotStub，ElTableColumn 不渲染 scoped slot
 * （src/test-setup.ts），所以表格行内的"值渲染"不在这里断言——那部分由
 * format.test.ts 直接钉 helper；这里只测页面级行为（统计卡、校验、请求载荷）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import rootZh from '@/i18n/locales/zh-CN.json'
import karmadaZh from '@/i18n/locales/modules/karmada.zh-CN.json'
import MultiClusterOverview from '@/views/karmada/MultiClusterOverview.vue'
import FailoverHistory from '@/views/karmada/FailoverHistory.vue'
import FailoverPolicies from '@/views/karmada/FailoverPolicies.vue'
import OverridePolicies from '@/views/karmada/OverridePolicies.vue'
import ReplicaPlans from '@/views/karmada/ReplicaPlans.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { ...(rootZh as object), ...(karmadaZh as object) } as never },
  missingWarn: false,
  fallbackWarn: false
})

const {
  listClusterHealthMock,
  listFailoverEventsMock,
  triggerFailoverMock,
  listFailoverPoliciesMock,
  createFailoverPolicyMock,
  updateFailoverPolicyMock,
  deleteFailoverPolicyMock,
  listOverridePoliciesMock,
  createOverridePolicyMock,
  getOverridePolicyMock,
  deleteOverridePolicyMock,
  listReplicaPlansMock,
  createReplicaPlanMock,
  updateReplicaPlanMock
} = vi.hoisted(() => ({
  listClusterHealthMock: vi.fn(),
  listFailoverEventsMock: vi.fn(),
  triggerFailoverMock: vi.fn(),
  listFailoverPoliciesMock: vi.fn(),
  createFailoverPolicyMock: vi.fn(),
  updateFailoverPolicyMock: vi.fn(),
  deleteFailoverPolicyMock: vi.fn(),
  listOverridePoliciesMock: vi.fn(),
  createOverridePolicyMock: vi.fn(),
  getOverridePolicyMock: vi.fn(),
  deleteOverridePolicyMock: vi.fn(),
  listReplicaPlansMock: vi.fn(),
  createReplicaPlanMock: vi.fn(),
  updateReplicaPlanMock: vi.fn()
}))

vi.mock('@/api/karmada', async (importOriginal) => {
  // 常量（FAILOVER_EVENT_STATUSES 等）用真实导出：状态闭包若与后端脱节，
  // 测试要跟着红，而不是被一份测试内抄件掩盖。
  const actual = await importOriginal<typeof import('@/api/karmada')>()
  return {
    ...actual,
    listClusterHealth: listClusterHealthMock,
    listFailoverEvents: listFailoverEventsMock,
    triggerFailover: triggerFailoverMock,
    listFailoverPolicies: listFailoverPoliciesMock,
    createFailoverPolicy: createFailoverPolicyMock,
    updateFailoverPolicy: updateFailoverPolicyMock,
    deleteFailoverPolicy: deleteFailoverPolicyMock,
    listOverridePolicies: listOverridePoliciesMock,
    createOverridePolicy: createOverridePolicyMock,
    getOverridePolicy: getOverridePolicyMock,
    deleteOverridePolicy: deleteOverridePolicyMock,
    listReplicaPlans: listReplicaPlansMock,
    createReplicaPlan: createReplicaPlanMock,
    updateReplicaPlan: updateReplicaPlanMock
  }
})

type Wrapper = ReturnType<typeof mount>
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const vm = (w: Wrapper) => w.vm as unknown as Record<string, any>

function mountView(component: unknown): Wrapper {
  return mount(component as never, { global: { plugins: [i18n] } })
}

function buttonByText(w: Wrapper, text: string) {
  return w.findAll('.el-button').find((b) => b.text().includes(text))
}

/** el-input 是 stub，只能按 v-model 事件喂值；index 依模板渲染顺序 */
function fillInput(w: Wrapper, index: number, value: string) {
  const inputs = w.findAllComponents({ name: 'el-input' })
  const target = inputs[index]
  if (!target) throw new Error(`页面上第 ${index + 1} 个 el-input 不存在（共 ${inputs.length} 个）`)
  return target.vm.$emit('update:modelValue', value)
}

const CLUSTER_ROWS = [
  {
    id: 1,
    clusterName: 'cluster-a',
    tenantId: 't',
    status: 'healthy',
    ready: true,
    syncable: true,
    cpuLoad: 10,
    memoryLoad: 20,
    podCount: 3,
    nodeCount: 2,
    availableReplicas: 3,
    maxReplicas: 5,
    checkSource: 'karmada_api',
    detail: '{}',
    checkedAt: '2026-10-20T00:00:00Z',
    createdAt: '2026-10-20T00:00:00Z'
  },
  {
    id: 2,
    clusterName: 'cluster-b',
    tenantId: 't',
    status: 'down',
    ready: false,
    syncable: false,
    cpuLoad: 0,
    memoryLoad: 0,
    podCount: 0,
    nodeCount: 0,
    availableReplicas: 0,
    maxReplicas: 0,
    checkSource: 'karmada_api',
    detail: '{}',
    checkedAt: '2026-10-20T00:00:00Z',
    createdAt: '2026-10-20T00:00:00Z'
  }
]

const POLICY_ROW = {
  id: 1,
  tenantId: 't',
  name: 'policy-1',
  namespace: 'default',
  primaryCluster: 'cluster-a',
  backupClusters: '["cluster-b","cluster-c"]',
  detectionWindowSeconds: 30,
  migrationTimeoutSeconds: 60,
  healthCheckIntervalSeconds: 10,
  enabled: true,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z'
}

const PLAN_ROW = {
  id: 1,
  tenantId: 't',
  policyName: 'plan-1',
  workload: 'deploy/app',
  totalReplicas: 6,
  allocation: '{"cluster-a":4,"cluster-b":2}',
  weights: '{"cluster-a":2,"cluster-b":1}',
  reason: 'initial',
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z'
}

beforeEach(() => {
  vi.clearAllMocks()
  listClusterHealthMock.mockResolvedValue({ items: CLUSTER_ROWS, total: 2 })
  listFailoverEventsMock.mockResolvedValue({ items: [], total: 0 })
  listFailoverPoliciesMock.mockResolvedValue({ items: [POLICY_ROW], total: 1 })
  listOverridePoliciesMock.mockResolvedValue({ items: [], total: 0 })
  listReplicaPlansMock.mockResolvedValue({ items: [PLAN_ROW], total: 1 })
  createFailoverPolicyMock.mockResolvedValue(POLICY_ROW)
  createOverridePolicyMock.mockResolvedValue({})
  createReplicaPlanMock.mockResolvedValue({ policyName: 'plan-1' })
  updateReplicaPlanMock.mockResolvedValue({ policyName: 'plan-1' })
  getOverridePolicyMock.mockResolvedValue({
    id: 1,
    name: 'op-1',
    namespace: 'default',
    spec: '{"overrideRules":[{"overriders":{"imageOverrider":[]}}]}'
  })
})

describe('karmada/MultiClusterOverview.vue', () => {
  it('统计卡由健康记录算出：总数 2 / 健康 1 / 异常 1', async () => {
    const w = mountView(MultiClusterOverview)
    await flushPromises()
    expect(listClusterHealthMock).toHaveBeenCalledTimes(1)
    expect(w.findAll('.stat-card__value').map((n) => n.text())).toEqual(['2', '1', '1'])
    expect(w.text()).toContain('多集群健康总览')
    expect(w.text()).not.toMatch(/karmada\.[a-z]/)
  })

  it('加载失败进错误态并摊出原因，统计卡归零而不是留着旧值', async () => {
    listClusterHealthMock.mockRejectedValue(new Error('failover-api down'))
    const w = mountView(MultiClusterOverview)
    await flushPromises()
    expect(w.find('.km-error').exists()).toBe(true)
    expect(w.find('.km-error').text()).toContain('加载失败：failover-api down')
    expect(w.findAll('.stat-card__value').map((n) => n.text())).toEqual(['0', '0', '0'])
  })
})

describe('karmada/FailoverHistory.vue', () => {
  it('源/目标集群为空时本地拦下，不发触发请求', async () => {
    const w = mountView(FailoverHistory)
    await flushPromises()
    await buttonByText(w, '触发迁移')!.trigger('click')
    await flushPromises()
    expect(triggerFailoverMock).not.toHaveBeenCalled()
    expect(w.text()).toContain('源集群与目标集群都不能为空')
  })

  it('触发载荷按 triggerFailoverRequest 给，成功后重拉列表', async () => {
    triggerFailoverMock.mockResolvedValue({ eventId: 'e-1', status: 'pending' })
    const w = mountView(FailoverHistory)
    await flushPromises()
    const before = listFailoverEventsMock.mock.calls.length
    fillInput(w, 2, ' cluster-a ')
    fillInput(w, 3, 'cluster-b')
    await buttonByText(w, '触发迁移')!.trigger('click')
    await flushPromises()
    expect(triggerFailoverMock).toHaveBeenCalledWith({
      sourceCluster: 'cluster-a',
      targetCluster: 'cluster-b',
      policyName: undefined,
      reason: 'manual'
    })
    expect(listFailoverEventsMock.mock.calls.length).toBe(before + 1)
    expect(w.text()).toContain('迁移事件已创建（状态 pending')
  })

  it('列表请求把 limit 钳在 1..100：后端对越界值会静默回落 20', async () => {
    const w = mountView(FailoverHistory)
    await flushPromises()
    expect(listFailoverEventsMock).toHaveBeenLastCalledWith({ limit: 50 })
    fillInput(w, 1, '500')
    await buttonByText(w, '查询')!.trigger('click')
    await flushPromises()
    expect(listFailoverEventsMock).toHaveBeenLastCalledWith({ limit: 100 })
  })

  it('加载失败进错误态', async () => {
    listFailoverEventsMock.mockRejectedValue(new Error('403'))
    const w = mountView(FailoverHistory)
    await flushPromises()
    expect(w.find('.km-error').text()).toContain('加载失败：403')
  })
})

describe('karmada/FailoverPolicies.vue', () => {
  it('策略名/主集群/备用集群的三道本地校验各拦一次，且不发请求', async () => {
    const w = mountView(FailoverPolicies)
    await flushPromises()

    await buttonByText(w, '创建策略')!.trigger('click')
    expect(w.text()).toContain('策略名不能为空')

    fillInput(w, 0, 'policy-2')
    await buttonByText(w, '创建策略')!.trigger('click')
    expect(w.text()).toContain('主集群不能为空')

    fillInput(w, 2, 'cluster-a')
    await buttonByText(w, '创建策略')!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('备用集群至少填一个')
    expect(createFailoverPolicyMock).not.toHaveBeenCalled()
  })

  it('创建载荷：backupClusters 是字符串数组、enabled 是布尔、时间字段是数字', async () => {
    const w = mountView(FailoverPolicies)
    await flushPromises()
    fillInput(w, 0, 'policy-2')
    fillInput(w, 2, 'cluster-a')
    fillInput(w, 3, 'cluster-b, cluster-c')
    await buttonByText(w, '创建策略')!.trigger('click')
    await flushPromises()
    expect(createFailoverPolicyMock).toHaveBeenCalledTimes(1)
    expect(createFailoverPolicyMock.mock.calls[0][0]).toEqual({
      name: 'policy-2',
      namespace: 'default',
      primaryCluster: 'cluster-a',
      backupClusters: ['cluster-b', 'cluster-c'],
      detectionWindowSeconds: 30,
      migrationTimeoutSeconds: 60,
      healthCheckIntervalSeconds: 10,
      enabled: true
    })
  })

  it('删除确认被取消时不发 DELETE、不重拉列表', async () => {
    const w = mountView(FailoverPolicies)
    await flushPromises()
    const before = listFailoverPoliciesMock.mock.calls.length
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await vm(w).remove(POLICY_ROW)
    await flushPromises()
    expect(deleteFailoverPolicyMock).not.toHaveBeenCalled()
    expect(listFailoverPoliciesMock.mock.calls.length).toBe(before)
  })
})

describe('karmada/OverridePolicies.vue', () => {
  it('策略名为空 / 一条覆盖规则都没有时本地拦下，不发请求', async () => {
    const w = mountView(OverridePolicies)
    await flushPromises()

    await buttonByText(w, '创建策略')!.trigger('click')
    expect(w.text()).toContain('策略名不能为空')

    fillInput(w, 0, 'op-1')
    await buttonByText(w, '创建策略')!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('请至少填写镜像或环境变量其中一项')
    expect(createOverridePolicyMock).not.toHaveBeenCalled()
  })

  it('创建载荷把镜像覆盖器与目标集群组装进 spec（Go 侧 spec 为结构化请求体）', async () => {
    const w = mountView(OverridePolicies)
    await flushPromises()
    fillInput(w, 0, 'op-1')
    fillInput(w, 2, 'cluster-a, cluster-b')
    fillInput(w, 3, 'registry.example.com')
    await buttonByText(w, '创建策略')!.trigger('click')
    await flushPromises()
    expect(createOverridePolicyMock).toHaveBeenCalledTimes(1)
    expect(createOverridePolicyMock.mock.calls[0][0]).toEqual({
      name: 'op-1',
      namespace: 'default',
      spec: {
        overrideRules: [
          {
            targetCluster: { clusterNames: ['cluster-a', 'cluster-b'] },
            overriders: {
              imageOverrider: [
                { component: 'Registry', operator: 'replace', value: 'registry.example.com' }
              ]
            }
          }
        ]
      }
    })
  })

  it('详情按 spec 原文美化展示（spec 是 text 列里的 JSON 字符串）', async () => {
    const w = mountView(OverridePolicies)
    await flushPromises()
    await vm(w).openDetail('op-1', 'default')
    await flushPromises()
    expect(getOverridePolicyMock).toHaveBeenCalledWith('op-1', 'default')
    expect(w.find('.km-pre').text()).toContain('"overrideRules"')
  })
})

describe('karmada/ReplicaPlans.vue', () => {
  it('权重之和为 0 / 必填为空时本地拦下，不发请求', async () => {
    const w = mountView(ReplicaPlans)
    await flushPromises()

    await buttonByText(w, '创建方案')!.trigger('click')
    expect(w.text()).toContain('策略名不能为空')

    fillInput(w, 0, 'plan-2')
    await buttonByText(w, '创建方案')!.trigger('click')
    expect(w.text()).toContain('工作负载不能为空')

    fillInput(w, 1, 'deploy/app')
    await buttonByText(w, '创建方案')!.trigger('click')
    expect(w.text()).toContain('请至少填写一个集群的权重')

    fillInput(w, 3, 'cluster-a')
    fillInput(w, 4, '0')
    await buttonByText(w, '创建方案')!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('权重之和必须大于 0')
    expect(createReplicaPlanMock).not.toHaveBeenCalled()
  })

  it('创建载荷：weights 是整数对象，totalReplicas 是数字', async () => {
    const w = mountView(ReplicaPlans)
    await flushPromises()
    fillInput(w, 0, 'plan-2')
    fillInput(w, 1, 'deploy/app')
    fillInput(w, 3, 'cluster-a')
    fillInput(w, 4, '2.9')
    await buttonByText(w, '创建方案')!.trigger('click')
    await flushPromises()
    expect(createReplicaPlanMock).toHaveBeenCalledTimes(1)
    const payload = createReplicaPlanMock.mock.calls[0][0]
    expect(payload.policyName).toBe('plan-2')
    expect(payload.workload).toBe('deploy/app')
    expect(payload.totalReplicas).toBe(6)
    expect(payload.weights).toEqual({ 'cluster-a': 2 })
    // 后端 reason 留空时写 manual；这里不发空串
    expect(payload.reason).toBeUndefined()
  })

  it('调整：总副本数为 0 时省略该字段（Go 是指针，传 0 会被判 400）', async () => {
    const w = mountView(ReplicaPlans)
    await flushPromises()
    await vm(w).openAdjust(PLAN_ROW)
    await vm(w).update()
    await flushPromises()
    expect(updateReplicaPlanMock).toHaveBeenCalledTimes(1)
    expect(updateReplicaPlanMock.mock.calls[0][0]).toBe('plan-1')
    expect(updateReplicaPlanMock.mock.calls[0][1]).toEqual({
      totalReplicas: 6,
      weights: { 'cluster-a': 2, 'cluster-b': 1 },
      reason: 'manual'
    })
  })
})
