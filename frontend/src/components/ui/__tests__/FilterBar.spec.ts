import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import FilterBar, { type FilterConfig } from '../FilterBar.vue'

const filters: FilterConfig[] = [
  {
    key: 'status',
    placeholder: 'Status',
    options: [{ label: 'Active', value: 'active' }]
  }
]

describe('FilterBar', () => {
  it('renders a select per filter', () => {
    const wrapper = mount(FilterBar, { props: { filters, modelValue: {} } })
    expect(wrapper.findAll('.el-select')).toHaveLength(1)
  })

  it('shows clear button when showClear', () => {
    const wrapper = mount(FilterBar, {
      props: { filters, modelValue: {}, showClear: true, clearLabel: 'Reset' }
    })
    expect(wrapper.text()).toContain('Reset')
  })

  it('hides search input without searchPlaceholder', () => {
    const wrapper = mount(FilterBar, { props: { filters, modelValue: {} } })
    expect(wrapper.find('.el-input').exists()).toBe(false)
  })
})
