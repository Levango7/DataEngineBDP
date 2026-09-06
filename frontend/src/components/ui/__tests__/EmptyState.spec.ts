import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from '../EmptyState.vue'
import { realElGlobal as global } from './real-ui'

describe('EmptyState', () => {
  it('renders default message', () => {
    const wrapper = mount(EmptyState, { global })
    expect(wrapper.text()).toContain('No data available')
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
