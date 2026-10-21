/**
 * Karmada 客户端与 failover-api 的契约直测。
 *
 * 这里钉死的是"动词 + 路径 + 参数形状"三件事。它们错了不会有编译报错：
 *   - 动词错（如 DELETE 写成 POST）→ 网关回 404/405，页面把它渲染成"暂无数据"；
 *   - namespace 走 query 还是 body，Go 侧一边 DefaultQuery 一边 binding:"required"；
 *   - 列表分页参数漏传/传 0：后端对 limit<=0 静默回落 20（failover.go:96-98），
 *     页面看起来"只有 20 条"，没人会怀疑参数。
 *
 * 端点归属见 docs/KNOWN-FAILURES.md #89 与 scripts/gen-apisix-routes.py 的
 * PREFIX_CHART_FIXUPS：/clusters/health、/override-policies、/failover-events、
 * /replica-plans、/failover-policies 五组都路由到 failover-api。
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
  createFailoverPolicy,
  createOverridePolicy,
  createReplicaPlan,
  deleteFailoverPolicy,
  deleteOverridePolicy,
  getFailoverEvent,
  getFailoverPolicy,
  getOverridePolicy,
  getReplicaPlan,
  listClusterHealth,
  listFailoverEvents,
  listFailoverPolicies,
  listOverridePolicies,
  listReplicaPlans,
  triggerFailover,
  updateFailoverPolicy,
  updateOverridePolicy,
  updateReplicaPlan
} from '@/api/karmada'

describe('api/karmada.ts — 路径与动词', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue({ items: [], total: 0 })
    postMock.mockResolvedValue({})
    putMock.mockResolvedValue({})
    delMock.mockResolvedValue(undefined)
  })

  it('健康总览：GET /clusters/health，且不传分页（后端该端点不接分页）', async () => {
    await listClusterHealth()
    expect(getMock).toHaveBeenCalledTimes(1)
    const call = getMock.mock.calls[0]
    expect(call[0]).toBe('/clusters/health')
    expect(call).toHaveLength(1)
  })

  it('迁移事件：列表/单条/触发分别走 GET、GET、POST，路径参数做 encode', async () => {
    await listFailoverEvents({ limit: 50 })
    expect(getMock).toHaveBeenLastCalledWith('/failover-events', { limit: 50 })

    await getFailoverEvent('evt/1')
    expect(getMock).toHaveBeenLastCalledWith('/failover-events/evt%2F1')

    const payload = {
      sourceCluster: 'c1',
      targetCluster: 'c2',
      policyName: 'p',
      reason: 'manual',
      workloads: ['w1']
    }
    await triggerFailover(payload)
    expect(postMock).toHaveBeenCalledWith('/failover-events', payload)
  })

  it('副本方案：GET 列表 / GET 单条 / POST 创建 / PUT 更新（路径 encode）', async () => {
    await listReplicaPlans()
    expect(getMock).toHaveBeenLastCalledWith('/replica-plans', undefined)

    await getReplicaPlan('plan/a')
    expect(getMock).toHaveBeenLastCalledWith('/replica-plans/plan%2Fa')

    const create = { policyName: 'p', workload: 'w', totalReplicas: 6, weights: { c1: 1 } }
    await createReplicaPlan(create)
    expect(postMock).toHaveBeenCalledWith('/replica-plans', create)

    const update = { totalReplicas: 8, weights: { c1: 2, c2: 6 }, reason: 'manual' }
    await updateReplicaPlan('p 1', update)
    expect(putMock).toHaveBeenCalledWith('/replica-plans/p%201', update)
  })

  it('迁移策略：namespace 走 query，创建走 POST，更新用 PUT，删除走 DELETE', async () => {
    await listFailoverPolicies({ limit: 100, namespace: 'ns' })
    expect(getMock).toHaveBeenLastCalledWith('/failover-policies', {
      limit: 100,
      namespace: 'ns'
    })

    await getFailoverPolicy('policy-1')
    expect(getMock).toHaveBeenLastCalledWith('/failover-policies/policy-1', {
      namespace: 'default'
    })

    const create = {
      name: 'p',
      namespace: 'default',
      primaryCluster: 'c1',
      backupClusters: ['c2']
    }
    await createFailoverPolicy(create)
    expect(postMock).toHaveBeenCalledWith('/failover-policies', create)

    const update = { enabled: false }
    await updateFailoverPolicy('p', update, 'ns')
    expect(putMock).toHaveBeenCalledWith('/failover-policies/p', update, {
      params: { namespace: 'ns' }
    })

    await deleteFailoverPolicy('p', 'ns')
    expect(delMock).toHaveBeenCalledWith('/failover-policies/p', { namespace: 'ns' })
  })

  it('覆盖策略：创建体是 {name, namespace, spec}（Go opCreateRequest），更新只发 spec', async () => {
    await listOverridePolicies({ limit: 100 })
    expect(getMock).toHaveBeenLastCalledWith('/override-policies', { limit: 100 })

    await getOverridePolicy('op-1', 'ns')
    expect(getMock).toHaveBeenLastCalledWith('/override-policies/op-1', { namespace: 'ns' })

    const spec = {
      overrideRules: [
        { targetCluster: { clusterNames: ['c1'] }, overriders: { imageOverrider: [] } }
      ]
    }
    await createOverridePolicy({ name: 'op', namespace: 'ns', spec })
    expect(postMock).toHaveBeenCalledWith('/override-policies', {
      name: 'op',
      namespace: 'ns',
      spec
    })

    await updateOverridePolicy('op', spec, 'ns')
    expect(putMock).toHaveBeenCalledWith(
      '/override-policies/op',
      { spec },
      { params: { namespace: 'ns' } }
    )

    await deleteOverridePolicy('op', 'ns')
    expect(delMock).toHaveBeenCalledWith('/override-policies/op', { namespace: 'ns' })
  })
})
