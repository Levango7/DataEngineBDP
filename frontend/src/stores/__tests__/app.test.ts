/**
 * stores/app.ts 单元测试
 *
 * 重点覆盖：审批列表不预置假数据；fetchSecApprovals 成功才落数据、失败置 error 态
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { listApprovalsMock } = vi.hoisted(() => ({
  listApprovalsMock: vi.fn()
}))

vi.mock('@/api/sec', () => ({
  listApprovals: listApprovalsMock,
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
  listMaskPolicies: vi.fn(() => Promise.resolve([])),
  createMaskPolicy: vi.fn(),
  updateMaskPolicy: vi.fn(),
  deleteMaskPolicy: vi.fn()
}))

import { useAppStore } from '../app'

describe('stores/app 审批数据语义', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('初始 state 不应预置假审批单', () => {
    const store = useAppStore()
    expect(store.todos).toEqual([])
    expect(store.secApprovals).toEqual([])
    expect(store.todoCount).toBe(0)
    expect(store.secApprovalsLoaded).toBe(false)
    expect(store.secApprovalsError).toBeNull()
  })

  it('fetchSecApprovals 成功时才落数据并置 loaded', async () => {
    const store = useAppStore()
    listApprovalsMock.mockResolvedValue([
      {
        id: 'a1',
        applicant: '张工',
        asset: 'dws.user_profile',
        permission: '读',
        status: 'pending',
        createdAt: '2026-01-01'
      }
    ])

    await store.fetchSecApprovals()

    expect(listApprovalsMock).toHaveBeenCalledWith('pending')
    expect(store.secApprovals).toEqual([
      { id: 'a1', applicant: '张工', asset: 'dws.user_profile', perm: '读' }
    ])
    expect(store.todoCount).toBe(1)
    expect(store.secApprovalsLoaded).toBe(true)
    expect(store.secApprovalsError).toBeNull()
  })

  it('fetchSecApprovals 失败时应置 error 态且不得保留任何旧值', async () => {
    const store = useAppStore()
    listApprovalsMock.mockRejectedValue(new Error('network down'))

    await store.fetchSecApprovals()

    expect(store.secApprovals).toEqual([])
    expect(store.todoCount).toBe(0)
    expect(store.secApprovalsLoaded).toBe(false)
    expect(store.secApprovalsError).toBeInstanceOf(Error)
    expect((store.secApprovalsError as Error).message).toBe('network down')
  })

  it('失败后再次成功应清除 error 态', async () => {
    const store = useAppStore()
    listApprovalsMock.mockRejectedValueOnce(new Error('network down'))
    await store.fetchSecApprovals()
    expect(store.secApprovalsError).not.toBeNull()

    listApprovalsMock.mockResolvedValueOnce([])
    await store.fetchSecApprovals()
    expect(store.secApprovalsLoaded).toBe(true)
    expect(store.secApprovalsError).toBeNull()
  })
})
