import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from '../ConfirmDialog.vue'
import { realElGlobal as global } from './real-ui'

describe('ConfirmDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders dialog when visible', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { visible: true, title: 'Confirm?', message: 'Are you sure?' },
      global,
      attachTo: document.body
    })
    await wrapper.vm.$nextTick()
    expect(document.body.textContent).toContain('Are you sure?')
    wrapper.unmount()
  })

  it('emits confirm on confirm click', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { visible: true, title: 'T', message: 'M', confirmText: 'OK' },
      global,
      attachTo: document.body
    })
    await wrapper.vm.$nextTick()
    const buttons = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>('.el-dialog .el-button')
    )
    const ok = buttons.find((b) => b.textContent?.includes('OK'))
    expect(ok).toBeTruthy()
    ok!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('confirm')).toBeTruthy()
    wrapper.unmount()
  })

  it('hides dialog when not visible', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { visible: false, title: 'Hidden', message: 'Invisible msg' },
      global,
      attachTo: document.body
    })
    await wrapper.vm.$nextTick()
    // 真实 el-dialog 在 visible=false 时不向 body 挂载任何内容
    expect(document.body.querySelector('.el-dialog')).toBeNull()
    wrapper.unmount()
  })
})
