/**
 * Modal.vue 单元测试
 *
 * 测试重写后的 Modal 组件：
 * - visible=true 时渲染 dialog 和 overlay
 * - 点击 overlay 关闭
 * - 点击关闭按钮关闭
 * - ESC 键关闭
 * - role="dialog" 和 aria-modal="true" 存在
 * - body overflow 在打开时为 hidden，关闭时恢复
 * - 底部默认渲染取消按钮
 * - footer slot 自定义内容
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import Modal from '../Modal.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': { common: { cancel: '取消' } },
    'en-US': { common: { cancel: 'Cancel' } }
  }
})

describe('components/Modal.vue', () => {
  let originalOverflow: string

  beforeEach(() => {
    originalOverflow = document.body.style.overflow
    document.body.style.overflow = ''
    // 清理 body 中可能残留的 teleport 内容
    document.body.innerHTML = ''
  })

  afterEach(() => {
    document.body.style.overflow = originalOverflow
    document.body.innerHTML = ''
  })

  function mountModal(props: Record<string, unknown> = {}, slots: Record<string, unknown> = {}) {
    return mount(Modal, {
      props: { visible: false, title: '测试标题', ...props },
      slots,
      global: {
        plugins: [i18n]
      }
    })
  }

  it('visible=true 时应渲染 dialog 容器', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    // Teleport to body，需在 document.body 中查找
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    wrapper.unmount()
  })

  it('visible=true 时应渲染 overlay', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    const overlay = document.body.querySelector('.overlay')
    expect(overlay).not.toBeNull()
    wrapper.unmount()
  })

  it('visible=false 时不应渲染 dialog', async () => {
    const wrapper = mountModal({ visible: false })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).toBeNull()
    wrapper.unmount()
  })

  it('点击 overlay 应触发 close 事件', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    const overlay = document.body.querySelector('.overlay') as HTMLElement
    expect(overlay).not.toBeNull()

    // 模拟点击 overlay 自身（非冒泡）
    overlay.click()
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('点击关闭按钮应触发 close 事件', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    // 关闭按钮是 .x class 的 el-button
    const closeBtn = document.body.querySelector('.x') as HTMLElement
    expect(closeBtn).not.toBeNull()

    closeBtn.click()
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('按下 ESC 键应触发 close 事件', async () => {
    const wrapper = mountModal({ visible: true })
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
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('role')).toBe('dialog')
    wrapper.unmount()
  })

  it('应包含 aria-modal="true" 属性', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    wrapper.unmount()
  })

  it('应包含 aria-labelledby 指向标题元素', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]') as HTMLElement
    const labelledBy = dialog.getAttribute('aria-labelledby')
    expect(labelledBy).toBeTruthy()

    const titleEl = document.body.querySelector(`#${labelledBy}`)
    expect(titleEl).not.toBeNull()
    expect(titleEl?.textContent).toBe('测试标题')
    wrapper.unmount()
  })

  it('打开时 body overflow 应为 hidden', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    expect(document.body.style.overflow).toBe('hidden')
    wrapper.unmount()
  })

  it('关闭时 body overflow 应恢复', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    expect(document.body.style.overflow).toBe('hidden')

    await wrapper.setProps({ visible: false })
    await flushPromises()
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('底部默认应渲染取消按钮', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    // footer 区域 .mf 内应有 el-button
    const footer = document.body.querySelector('.mf')
    expect(footer).not.toBeNull()
    const btn = footer?.querySelector('.el-button')
    expect(btn).not.toBeNull()
    expect(btn?.textContent).toContain('取消')
    wrapper.unmount()
  })

  it('footer slot 自定义内容应覆盖默认按钮', async () => {
    const wrapper = mountModal(
      { visible: true },
      { footer: '<button class="custom-footer-btn">自定义</button>' }
    )
    await flushPromises()

    const customBtn = document.body.querySelector('.custom-footer-btn')
    expect(customBtn).not.toBeNull()
    expect(customBtn?.textContent).toBe('自定义')
    wrapper.unmount()
  })

  it('关闭按钮应使用 el-button 而非原生 span.x', async () => {
    const wrapper = mountModal({ visible: true })
    await flushPromises()
    // 关闭按钮应有 .el-button class（来自 stub）
    const closeBtn = document.body.querySelector('.x.el-button')
    expect(closeBtn).not.toBeNull()
    // 不应有原生的 span.x（旧实现）
    const spanX = document.body.querySelector('span.x')
    expect(spanX).toBeNull()
    wrapper.unmount()
  })
})
