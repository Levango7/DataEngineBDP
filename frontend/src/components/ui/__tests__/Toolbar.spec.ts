import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Toolbar from '../Toolbar.vue'
import { realElGlobal as global } from './real-ui'

describe('Toolbar', () => {
  it('emits create when create button clicked', async () => {
    const wrapper = mount(Toolbar, {
      props: { showCreate: true, createLabel: 'Add', showRefresh: false },
      global
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('create')).toBeTruthy()
  })

  it('emits refresh when refresh button clicked', async () => {
    const wrapper = mount(Toolbar, {
      props: { showRefresh: true },
      global
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('emits update:searchValue on input', async () => {
    const wrapper = mount(Toolbar, {
      props: { searchPlaceholder: 'Search...', searchValue: '' },
      global
    })
    await wrapper.find('input').setValue('mysql')
    expect(wrapper.emitted('update:searchValue')).toBeTruthy()
    expect(wrapper.emitted('update:searchValue')![0]).toEqual(['mysql'])
  })

  it('renders filters slot', () => {
    const wrapper = mount(Toolbar, {
      slots: { filters: '<select class="f" />' },
      global
    })
    expect(wrapper.find('.f').exists()).toBe(true)
  })
})
