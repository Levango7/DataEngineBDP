import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PageCard from '../PageCard.vue'
import { realElGlobal as global } from './real-ui'

describe('PageCard', () => {
  it('renders default slot content', () => {
    const wrapper = mount(PageCard, {
      slots: { default: '<div class="inner">body</div>' },
      global
    })
    expect(wrapper.find('.inner').exists()).toBe(true)
  })

  it('renders title and subtitle in header slot', () => {
    const wrapper = mount(PageCard, {
      props: { title: 'Card Title', subtitle: 'sub' },
      global
    })
    expect(wrapper.text()).toContain('Card Title')
    expect(wrapper.text()).toContain('sub')
  })

  it('renders header-actions slot', () => {
    const wrapper = mount(PageCard, {
      props: { title: 'T' },
      slots: { 'header-actions': '<button class="ha">X</button>' },
      global
    })
    expect(wrapper.find('.ha').exists()).toBe(true)
  })
})
