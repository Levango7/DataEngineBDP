import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusTag from '../StatusTag.vue'
import { realElGlobal as global } from './real-ui'

describe('StatusTag', () => {
  it('maps status to tag type via statusMap', () => {
    const wrapper = mount(StatusTag, {
      props: {
        status: 'connected',
        label: 'Connected',
        statusMap: { connected: 'success' as const }
      },
      global
    })
    expect(wrapper.find('.el-tag').classes()).toContain('el-tag--success')
    expect(wrapper.text()).toContain('Connected')
  })

  it('falls back to info for unknown status', () => {
    const wrapper = mount(StatusTag, { props: { status: 'mystery' }, global })
    expect(wrapper.find('.el-tag').classes()).toContain('el-tag--info')
  })

  it('shows status text when label omitted', () => {
    const wrapper = mount(StatusTag, { props: { status: 'active' }, global })
    expect(wrapper.text()).toContain('active')
  })
})
