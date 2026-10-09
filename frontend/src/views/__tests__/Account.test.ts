/**
 * Account.vue 单元测试（台账 #62：档位口径收口，方案 B）
 *
 * 两条判据：
 * 1. 升级弹窗的档位选项与月费来自后端 GET /account/plans（前端不再硬编码 58,000/35,000）；
 * 2. 默认档位 = 当前档位的下一档，必属后端认可的三档（此前默认 flagship 必被 400 拒）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import accountZh from '@/i18n/locales/modules/account.zh-CN.json'
import accountEn from '@/i18n/locales/modules/account.en-US.json'
import Account from '../Account.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    // Account.vue 还用到框架级词条（货币符号/重试/取消），此处补最小集
    'zh-CN': { ...accountZh, common: { currency: '¥', retry: '重试', cancel: '取消' } },
    'en-US': { ...accountEn, common: { currency: '¥', retry: 'Retry', cancel: 'Cancel' } }
  }
})

const mockPlans = {
  plans: [
    { key: 'free', name: '免费版', monthlyFee: 0, cpu: '4', memory: '8Gi' },
    { key: 'pro', name: '专业版', monthlyFee: 1999, cpu: '16', memory: '32Gi' },
    { key: 'enterprise', name: '企业版', monthlyFee: 9999, cpu: '64', memory: '128Gi' }
  ]
}

vi.mock('@/api/account', () => ({
  getAccountPlan: vi.fn(() => Promise.resolve({ plan: 'free', planName: '免费版', quotas: [] })),
  getBillingDetail: vi.fn(() => Promise.resolve({ items: [], totalCost: 0 })),
  getAccountPlans: vi.fn(() => Promise.resolve(mockPlans)),
  upgradePlan: vi.fn(() => Promise.resolve({ estimatedMonthlyFee: 1999, status: 'submitted' }))
}))

describe('Account.vue（台账 #62 档位口径）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(Account, { global: { plugins: [i18n] } })
  }

  it('档位选项与月费来自后端 /account/plans（非硬编码）', async () => {
    const api = await import('@/api/account')
    const wrapper = mountComponent()
    await flushPromises()
    expect(api.getAccountPlans).toHaveBeenCalled()
    const vm = wrapper.vm as unknown as {
      plans: { plans: Array<{ key: string; monthlyFee: number }> }
      estimatedFee: string
    }
    expect(vm.plans.plans.map((p) => p.key)).toEqual(['free', 'pro', 'enterprise'])
    // 当前档 free → 默认目标 pro → 月费取自后端（1,999），而非旧的 hardcode 58,000/35,000
    expect(vm.estimatedFee).toMatch(/1[,.]?999/)
  })

  it('默认档位为当前档位的下一档（free→pro），不再是后端不认的 flagship', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as unknown as { upgradeForm: { targetPlan: string } }
    expect(vm.upgradeForm.targetPlan).toBe('pro')
  })

  it('提交升级发送的 targetPlan 属后端认可集合', async () => {
    const api = await import('@/api/account')
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as unknown as { submitUpgrade: () => Promise<void> }
    await vm.submitUpgrade()
    await flushPromises()
    expect(api.upgradePlan).toHaveBeenCalledWith({ targetPlan: 'pro' })
  })
})
