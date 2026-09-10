/**
 * Drawer.vue 单元测试
 *
 * 测试重写后的 Drawer 组件：
 * - visible=true 时渲染 drawer 和 overlay
 * - 点击 overlay 关闭
 * - ESC 键关闭
 * - role="dialog" 和 aria-modal="true"
 * - body scroll lock
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Drawer from '../Drawer.vue'

describe('components/Drawer.vue', () => {
  let originalOverflow: string

  beforeEach(() => {
    originalOverflow = document.body.style.overflow
    document.body.style.overflow = ''
    document.body.innerHTML = ''
  })

  afterEach(() => {
    document.body.style.overflow = originalOverflow
    document.body.innerHTML = ''
  })

  function mountDrawer(props: Record<string, unknown> = {}, slots: Record<string, unknown> = {}) {
    return mount(Drawer, {
      props: { visible: false, ...props },
      slots
    })
  }

  it('visible=true 时应渲染 drawer 容器', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const drawer = document.body.querySelector('.drawer')
    expect(drawer).not.toBeNull()
    wrapper.unmount()
  })

  it('visible=true 时应渲染 overlay', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const overlay = document.body.querySelector('.overlay')
    expect(overlay).not.toBeNull()
    wrapper.unmount()
  })

  it('visible=false 时不应渲染 drawer', async () => {
    const wrapper = mountDrawer({ visible: false })
    await flushPromises()
    const drawer = document.body.querySelector('.drawer')
    expect(drawer).toBeNull()
    wrapper.unmount()
  })

  it('点击 overlay 应触发 close 事件', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const overlay = document.body.querySelector('.overlay') as HTMLElement
    expect(overlay).not.toBeNull()

    overlay.click()
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('点击关闭按钮应触发 close 事件', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const closeBtn = document.body.querySelector('.x') as HTMLElement
    expect(closeBtn).not.toBeNull()

    closeBtn.click()
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('按下 ESC 键应触发 close 事件', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()

    const event = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true
    })
    document.dispatchEvent(event)
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('应包含 role="dialog" 属性', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('role')).toBe('dialog')
    wrapper.unmount()
  })

  it('应包含 aria-modal="true" 属性', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    wrapper.unmount()
  })

  it('打开时 body overflow 应为 hidden', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    expect(document.body.style.overflow).toBe('hidden')
    wrapper.unmount()
  })

  it('关闭时 body overflow 应恢复', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    expect(document.body.style.overflow).toBe('hidden')

    await wrapper.setProps({ visible: false })
    await flushPromises()
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('关闭按钮应使用 el-button 而非原生 span.x', async () => {
    const wrapper = mountDrawer({ visible: true })
    await flushPromises()
    const closeBtn = document.body.querySelector('.x.el-button')
    expect(closeBtn).not.toBeNull()
    const spanX = document.body.querySelector('span.x')
    expect(spanX).toBeNull()
    wrapper.unmount()
  })

  it('header slot 应正确渲染', async () => {
    const wrapper = mountDrawer(
      { visible: true },
      { header: '<span class="custom-header">自定义头部</span>' }
    )
    await flushPromises()

    const header = document.body.querySelector('.custom-header')
    expect(header).not.toBeNull()
    expect(header?.textContent).toBe('自定义头部')
    wrapper.unmount()
  })
})
