import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatCard from '../StatCard.vue'

describe('StatCard', () => {
  it('renders value and label', () => {
    const wrapper = mount(StatCard, { props: { value: '42', label: 'Jobs' } })
    expect(wrapper.text()).toContain('42')
    expect(wrapper.text()).toContain('Jobs')
  })

  it('applies trend class', () => {
    const wrapper = mount(StatCard, {
      props: { value: 99, label: 'L', trend: 'up' as const, trendValue: '+3%' }
    })
    expect(wrapper.find('.stat-card__trend--up').exists()).toBe(true)
    expect(wrapper.text()).toContain('+3%')
  })

  it('hides trend block when trend not provided', () => {
    const wrapper = mount(StatCard, { props: { value: 1, label: 'L' } })
    expect(wrapper.find('.stat-card__trend').exists()).toBe(false)
  })
})
