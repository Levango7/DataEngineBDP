import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from '../EmptyState.vue'
import zhCN from '@/i18n/locales/zh-CN.json'
import { realElGlobal as global } from './real-ui'

describe('EmptyState', () => {
  it('renders default message', () => {
    const wrapper = mount(EmptyState, { global })
    // 无 message prop 时回退到 i18n 默认文案；断言直接取生产词条，
    // 避免把译文硬编码在测试里（原断言写的是 'No data available'，
    // 而 common.empty 的实际译文是 '暂无数据' / 'No data'，从未匹配过）
    expect(wrapper.text()).toContain(zhCN.common.empty)
  })

  it('renders custom message', () => {
    const wrapper = mount(EmptyState, { props: { message: 'Nothing here' }, global })
    expect(wrapper.text()).toContain('Nothing here')
  })

  it('emits action when action button clicked', async () => {
    const wrapper = mount(EmptyState, {
      props: { actionLabel: 'Add' },
      global
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('action')).toBeTruthy()
  })

  it('hides action button without actionLabel', () => {
    const wrapper = mount(EmptyState, { global })
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
