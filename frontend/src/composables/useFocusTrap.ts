/**
 * 焦点陷阱组合式函数
 *
 * 提供可访问性（a11y）所需的四项基础能力：
 * 1. ESC 键关闭：监听 keydown，按下 ESC 时调用 onEscape 回调
 * 2. 焦点陷阱：Tab / Shift+Tab 在容器内循环，不逃逸到外部 DOM
 * 3. 滚动锁定：activate 时设置 `document.body.style.overflow = 'hidden'`，deactivate 时恢复
 * 4. 焦点恢复：关闭时焦点回到打开前拥有焦点的元素
 *
 * 用法：
 * ```ts
 * const { trapRef, activate, deactivate } = useFocusTrap({ onEscape: () => emit('close') })
 * // 模板：<div :ref="trapRef" role="dialog" aria-modal="true">...</div>
 * watch(visible, (v) => (v ? activate() : deactivate()))
 * ```
 */
import { ref, onScopeDispose, type Ref } from 'vue'

/** 焦点陷阱支持的键盘选择器（按 Tab 顺序可聚焦元素） */
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

export interface UseFocusTrapOptions {
  /** ESC 键回调 */
  onEscape?: () => void
  /** 是否在作用域销毁时自动 deactivate（默认 true） */
  autoDispose?: boolean
}

export interface UseFocusTrapReturn {
  /** 绑定到陷阱容器的 ref */
  trapRef: Ref<HTMLElement | null>
  /** 激活陷阱：锁定滚动、保存焦点、添加监听 */
  activate: () => void
  /** 解除陷阱：恢复滚动、恢复焦点、移除监听 */
  deactivate: () => void
  /** 当前是否处于激活状态 */
  isActive: Ref<boolean>
}

/**
 * 创建一个焦点陷阱
 * @param options 配置项
 */
export function useFocusTrap(options: UseFocusTrapOptions = {}): UseFocusTrapReturn {
  const { onEscape, autoDispose = true } = options

  const trapRef = ref<HTMLElement | null>(null)
  const isActive = ref(false)

  /** 激活前拥有焦点的元素，用于关闭时恢复 */
  let previouslyFocused: HTMLElement | null = null
  /** 激活前的 body overflow 值，用于关闭时恢复 */
  let previousOverflow: string = ''

  /** 检查元素是否可见（覆盖 position:fixed/sticky 场景） */
  function isElementVisible(node: HTMLElement): boolean {
    // offsetParent !== null 可判断常规可见性，但 position:fixed 元素的 offsetParent 为 null
    if (node.offsetParent !== null) return true
    // 当前焦点元素即使 offsetParent 为 null 也应保留（如 fixed 定位的模态框）
    if (node === document.activeElement) return true
    // position:fixed/sticky 元素：用 getComputedStyle + getBoundingClientRect 判断
    const style = window.getComputedStyle(node)
    if (style.position === 'fixed' || style.position === 'sticky') {
      const rect = node.getBoundingClientRect()
      // 宽高 > 0 且非 display:none / visibility:hidden / opacity:0
      return (
        rect.width > 0 &&
        rect.height > 0 &&
        style.display !== 'none' &&
        style.visibility !== 'hidden' &&
        Number(style.opacity) > 0
      )
    }
    return false
  }

  /** 在容器内获取所有可聚焦元素（按 DOM 顺序） */
  function getFocusableElements(): HTMLElement[] {
    const el = trapRef.value
    if (!el) return []
    return Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(isElementVisible)
  }

  /** keydown 事件处理：ESC 关闭 + Tab 循环 */
  function handleKeydown(event: KeyboardEvent): void {
    if (!isActive.value) return

    // ESC 关闭
    if (event.key === 'Escape') {
      event.preventDefault()
      onEscape?.()
      return
    }

    // Tab 循环焦点
    if (event.key === 'Tab') {
      const focusable = getFocusableElements()
      if (focusable.length === 0) {
        // 容器内无可聚焦元素，阻止 Tab 逃逸
        event.preventDefault()
        return
      }

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement as HTMLElement | null

      if (event.shiftKey) {
        // Shift+Tab：从第一个跳到最后一个
        if (active === first || !trapRef.value?.contains(active)) {
          event.preventDefault()
          last.focus()
        }
      } else {
        // Tab：从最后一个跳到第一个
        if (active === last || !trapRef.value?.contains(active)) {
          event.preventDefault()
          first.focus()
        }
      }
    }
  }

  /** 激活陷阱 */
  function activate(): void {
    if (isActive.value) return
    isActive.value = true

    // 保存当前焦点元素
    previouslyFocused = document.activeElement as HTMLElement | null

    // 锁定 body 滚动
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // 添加键盘监听（capture 阶段，确保先于子元素处理）
    document.addEventListener('keydown', handleKeydown, true)

    // 将焦点移入容器（首个可聚焦元素，否则容器自身）
    const focusable = getFocusableElements()
    if (focusable.length > 0) {
      focusable[0].focus()
    } else if (trapRef.value) {
      trapRef.value.focus()
    }
  }

  /** 解除陷阱 */
  function deactivate(): void {
    if (!isActive.value) return
    isActive.value = false

    // 移除键盘监听
    document.removeEventListener('keydown', handleKeydown, true)

    // 恢复 body 滚动
    document.body.style.overflow = previousOverflow

    // 恢复焦点到打开前的元素
    if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
      previouslyFocused.focus()
    }
    previouslyFocused = null
  }

  // 作用域销毁时自动清理
  if (autoDispose) {
    onScopeDispose(() => {
      deactivate()
    })
  }

  return { trapRef, activate, deactivate, isActive }
}
