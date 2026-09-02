import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN.json'
import enUS from './locales/en-US.json'
import dashboardZh from './locales/modules/dashboard.zh-CN.json'
import dashboardEn from './locales/modules/dashboard.en-US.json'
import workspacesZh from './locales/modules/workspaces.zh-CN.json'
import workspacesEn from './locales/modules/workspaces.en-US.json'
import projectsZh from './locales/modules/projects.zh-CN.json'
import projectsEn from './locales/modules/projects.en-US.json'
import analyzeZh from './locales/modules/analyze.zh-CN.json'
import analyzeEn from './locales/modules/analyze.en-US.json'
import qualityZh from './locales/modules/quality.zh-CN.json'
import qualityEn from './locales/modules/quality.en-US.json'
import standardZh from './locales/modules/standard.zh-CN.json'
import standardEn from './locales/modules/standard.en-US.json'
import governZh from './locales/modules/govern.zh-CN.json'
import governEn from './locales/modules/govern.en-US.json'

/**
 * 国际化插件（vue-i18n v10，legacy=false 组合式 API）。
 *
 * 语言优先级：
 * - localStorage `sq_locale`（用户手动选择，Sidebar 语言切换器写入）
 * - navigator.language 自动检测（zh* → zh-CN，否则 en-US）
 * - 默认 zh-CN（政企客户为主）
 *
 * 词条组织：
 * - 框架级（nav/login/common/app）：根级 locales/*.json
 * - 页面级（按模块逐个词条化）：locales/modules/{module}.{locale}.json
 *   （spread 合并进 messages，key 路径即模块名，避免框架级冲突）
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
    'zh-CN': {
      ...zhCN,
      ...dashboardZh,
      ...workspacesZh,
      ...projectsZh,
      ...analyzeZh,
      ...qualityZh,
      ...standardZh,
      ...governZh
    },
    'en-US': {
      ...enUS,
      ...dashboardEn,
      ...workspacesEn,
      ...projectsEn,
      ...analyzeEn,
      ...qualityEn,
      ...standardEn,
      ...governEn
    }
  },
  // 未翻译的 key 回退显示 key 本身（开发期可见，生产期不至于空白）
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV
})
