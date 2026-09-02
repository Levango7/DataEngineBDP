import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN.json'
import enUS from './locales/en-US.json'

/**
 * 国际化插件（vue-i18n v10，legacy=false 组合式 API）。
 *
 * 语言优先级：
 * - localStorage `sq_locale`（用户手动选择，Sidebar 语言切换器写入）
 * - navigator.language 自动检测（zh* → zh-CN，否则 en-US）
 * - 默认 zh-CN（政企客户为主）
 *
 * 词条组织：按域分文件（nav/login/common），后续页面级词条按模块平铺扩展。
 */

export type SupportedLocale = 'zh-CN' | 'en-US'

export const SUPPORTED_LOCALES: SupportedLocale[] = ['zh-CN', 'en-US']

const STORAGE_KEY = 'sq_locale'

function detectInitial(): SupportedLocale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh-CN' || saved === 'en-US') return saved
  } catch {
    // 私密模式读不到 localStorage
  }
  const nav = (typeof navigator !== 'undefined' ? navigator.language : '') || ''
  return nav.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

export function persistLocale(locale: SupportedLocale): void {
  try {
    localStorage.setItem(STORAGE_KEY, locale)
  } catch {
    // 写失败不影响当前会话
  }
}

export const i18n = createI18n({
  legacy: false,
  locale: detectInitial(),
  fallbackLocale: 'zh-CN',
  globalInjection: true,
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  },
  // 未翻译的 key 回退显示 key 本身（开发期可见，生产期不至于空白）
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV
})
