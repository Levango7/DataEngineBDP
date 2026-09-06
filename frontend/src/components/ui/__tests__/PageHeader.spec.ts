import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from '../PageHeader.vue'

describe('PageHeader', () => {
  it('renders title and subtitle', () => {
    const wrapper = mount(PageHeader, {
      props: { title: 'My Title', subtitle: 'my subtitle' }
    })
    expect(wrapper.text()).toContain('My Title')
    expect(wrapper.text()).toContain('my subtitle')
  })

  it('hides subtitle when not provided', () => {
    const wrapper = mount(PageHeader, { props: { title: 'Only Title' } })
    expect(wrapper.find('.page-header__subtitle').exists()).toBe(false)
  })

  it('renders actions slot', () => {
    const wrapper = mount(PageHeader, {
      props: { title: 'T' },
      slots: { actions: '<button>Act</button>' }
    })
    expect(wrapper.find('.page-header__actions button').exists()).toBe(true)
  })
})
