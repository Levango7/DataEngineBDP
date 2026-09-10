/**
 * useFocusTrap 组合式函数单元测试
 *
 * 测试焦点陷阱四项基础能力：
 * - activate 时 body overflow = hidden
 * - deactivate 时 body overflow 恢复
 * - ESC 键调用 onEscape 回调
 * - Tab 循环焦点
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { useFocusTrap } from '../useFocusTrap'

describe('composables/useFocusTrap.ts', () => {
  let originalOverflow: string

  beforeEach(() => {
    originalOverflow = document.body.style.overflow
    document.body.style.overflow = ''
  })

  afterEach(() => {
    document.body.style.overflow = originalOverflow
  })

  describe('滚动锁定', () => {
    it('activate 时 body overflow 应设为 hidden', () => {
      const { trapRef, activate } = useFocusTrap()
      // 模拟容器元素
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      expect(document.body.style.overflow).toBe('hidden')
    })

    it('deactivate 时 body overflow 应恢复原值', () => {
      const { trapRef, activate, deactivate } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      document.body.style.overflow = 'scroll'
      activate()
      expect(document.body.style.overflow).toBe('hidden')

      deactivate()
      expect(document.body.style.overflow).toBe('scroll')
    })

    it('deactivate 时若原值为空字符串应恢复为空字符串', () => {
      const { trapRef, activate, deactivate } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      document.body.style.overflow = ''
      activate()
      deactivate()
      expect(document.body.style.overflow).toBe('')
    })
  })

  describe('ESC 键关闭', () => {
    it('按下 ESC 应调用 onEscape 回调', () => {
      const onEscape = vi.fn()
      const { trapRef, activate } = useFocusTrap({ onEscape })
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()

      const event = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        cancelable: true
      })
      document.dispatchEvent(event)

      expect(onEscape).toHaveBeenCalledTimes(1)
    })

    it('未 activate 时按下 ESC 不应调用 onEscape', () => {
      const onEscape = vi.fn()
      const { trapRef } = useFocusTrap({ onEscape })
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      const event = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        cancelable: true
      })
      document.dispatchEvent(event)

      expect(onEscape).not.toHaveBeenCalled()
    })

    it('deactivate 后按下 ESC 不应调用 onEscape', () => {
      const onEscape = vi.fn()
      const { trapRef, activate, deactivate } = useFocusTrap({ onEscape })
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      deactivate()

      const event = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        cancelable: true
      })
      document.dispatchEvent(event)

      expect(onEscape).not.toHaveBeenCalled()
    })
  })

  describe('激活状态', () => {
    it('初始 isActive 应为 false', () => {
      const { isActive } = useFocusTrap()
      expect(isActive.value).toBe(false)
    })

    it('activate 后 isActive 应为 true', () => {
      const { trapRef, activate, isActive } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      expect(isActive.value).toBe(true)
    })

    it('deactivate 后 isActive 应为 false', () => {
      const { trapRef, activate, deactivate, isActive } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      deactivate()
      expect(isActive.value).toBe(false)
    })

    it('重复调用 activate 应为幂等', () => {
      const { trapRef, activate, isActive } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      activate()
      expect(isActive.value).toBe(true)
      expect(document.body.style.overflow).toBe('hidden')
    })
  })

  describe('Tab 循环焦点', () => {
    it('容器内仅有一个可聚焦元素时 Tab 应被阻止逃逸', async () => {
      const { trapRef, activate } = useFocusTrap()
      const el = document.createElement('div')
      const btn = document.createElement('button')
      btn.textContent = 'btn'
      el.appendChild(btn)
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      await nextTick()

      btn.focus()
      const event = new KeyboardEvent('keydown', {
        key: 'Tab',
        bubbles: true,
        cancelable: true
      })
      document.dispatchEvent(event)

      // Tab 被处理（事件被 preventDefault）
      expect(event.defaultPrevented).toBe(true)
    })

    it('容器内无可聚焦元素时 Tab 应被阻止', async () => {
      const { trapRef, activate } = useFocusTrap()
      const el = document.createElement('div')
      document.body.appendChild(el)
      trapRef.value = el

      activate()
      await nextTick()

      const event = new KeyboardEvent('keydown', {
        key: 'Tab',
        bubbles: true,
        cancelable: true
      })
      document.dispatchEvent(event)

      expect(event.defaultPrevented).toBe(true)
    })
  })
})