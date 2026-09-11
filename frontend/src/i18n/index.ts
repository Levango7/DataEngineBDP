import { createI18n } from 'vue-i18n'
// 框架级词条（nav/login/common/app）保持同步导入：首屏即需，体积小
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
 * 词条组织（懒加载优化）：
 * - 框架级（nav/login/common/app）：根级 locales/*.json，同步导入（首屏即需）
 * - 页面级（按模块逐个词条化）：locales/modules/{module}.{locale}.json
 *   初始不加载，由 loadModuleI18n 动态 import 按需加载，路由 afterEach 预加载
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
  // 初始仅包含框架级词条；模块级词条由 loadModuleI18n 动态合并
  messages: {
    'zh-CN': { ...zhCN },
    'en-US': { ...enUS }
  },
  // 未翻译的 key 回退显示 key 本身（开发期可见，生产期不至于空白）
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV
})

/* ------------------------------ 模块级懒加载 ------------------------------ */

/**
 * 模块名 → 语言包文件名映射（不含 locale 后缀）。
 *
 * <p>每个模块对应两个文件：{name}.zh-CN.json 和 {name}.en-US.json。
 * Vite 会为每个动态 import() 生成独立 chunk，实现按需加载。</p>
 */
const MODULE_FILES: Record<string, () => Promise<{ default: Record<string, unknown> }>> = {
  dashboard: () => import('./locales/modules/dashboard.zh-CN.json'),
  workspaces: () => import('./locales/modules/workspaces.zh-CN.json'),
  projects: () => import('./locales/modules/projects.zh-CN.json'),
  analyze: () => import('./locales/modules/analyze.zh-CN.json'),
  quality: () => import('./locales/modules/quality.zh-CN.json'),
  standard: () => import('./locales/modules/standard.zh-CN.json'),
  govern: () => import('./locales/modules/govern.zh-CN.json'),
  integrate: () => import('./locales/modules/integrate.zh-CN.json'),
  develop: () => import('./locales/modules/develop.zh-CN.json'),
  sql: () => import('./locales/modules/sql.zh-CN.json'),
  lineage: () => import('./locales/modules/lineage.zh-CN.json'),
  sec: () => import('./locales/modules/sec.zh-CN.json'),
  ops: () => import('./locales/modules/ops.zh-CN.json'),
  jobmgmt: () => import('./locales/modules/jobmgmt.zh-CN.json'),
  scheduler: () => import('./locales/modules/scheduler.zh-CN.json'),
  vector: () => import('./locales/modules/vector.zh-CN.json'),
  kb: () => import('./locales/modules/kb.zh-CN.json'),
  llmops: () => import('./locales/modules/llmops.zh-CN.json'),
  gateway: () => import('./locales/modules/gateway.zh-CN.json'),
  account: () => import('./locales/modules/account.zh-CN.json'),
  admin: () => import('./locales/modules/admin.zh-CN.json'),
  templateMarket: () => import('./locales/modules/templateMarket.zh-CN.json'),
  businessPortal: () => import('./locales/modules/businessPortal.zh-CN.json'),
  apiMarket: () => import('./locales/modules/apiMarket.zh-CN.json'),
  assetMarket: () => import('./locales/modules/assetMarket.zh-CN.json'),
  sqlWorkbench: () => import('./locales/modules/sqlWorkbench.zh-CN.json'),
  searchPortal: () => import('./locales/modules/searchPortal.zh-CN.json'),
  dataSourceManagement: () => import('./locales/modules/dataSourceManagement.zh-CN.json'),
  tenantManagement: () => import('./locales/modules/tenantManagement.zh-CN.json'),
  workspaceManagement: () => import('./locales/modules/workspaceManagement.zh-CN.json'),
  quotaManagement: () => import('./locales/modules/quotaManagement.zh-CN.json'),
  clusterOverview: () => import('./locales/modules/clusterOverview.zh-CN.json'),
  dataLineage: () => import('./locales/modules/dataLineage.zh-CN.json'),
  engines: () => import('./locales/modules/engines.zh-CN.json'),
  infraK8s: () => import('./locales/modules/infraK8s.zh-CN.json'),
  infraMachine: () => import('./locales/modules/infraMachine.zh-CN.json'),
  infraSched: () => import('./locales/modules/infraSched.zh-CN.json'),
  engIotdb: () => import('./locales/modules/engIotdb.zh-CN.json'),
  engMmg: () => import('./locales/modules/engMmg.zh-CN.json'),
  engStorage: () => import('./locales/modules/engStorage.zh-CN.json'),
  infraNet: () => import('./locales/modules/infraNet.zh-CN.json'),
  infraStore: () => import('./locales/modules/infraStore.zh-CN.json'),
  devMl: () => import('./locales/modules/devMl.zh-CN.json'),
  devSched: () => import('./locales/modules/devSched.zh-CN.json'),
  devTag: () => import('./locales/modules/devTag.zh-CN.json'),
  approvals: () => import('./locales/modules/approvals.zh-CN.json'),
  register: () => import('./locales/modules/register.zh-CN.json'),
  orchestrator: () => import('./locales/modules/orchestrator.zh-CN.json'),
  aiAssistant: () => import('./locales/modules/aiAssistant.zh-CN.json'),
  engFlink: () => import('./locales/modules/engFlink.zh-CN.json')
}

/** 已加载的模块集合（避免重复加载） */
const loadedModules = new Set<string>()

/**
 * 动态加载指定模块的语言包并合并到 i18n messages。
 *
 * <p>同时加载 zh-CN 和 en-US 两个语种（体积小，避免切换语言时二次请求）。
 * 已加载的模块会跳过（幂等）。</p>
 *
 * @param module 模块名（见 MODULE_FILES）
 */
export async function loadModuleI18n(module: string): Promise<void> {
  if (!MODULE_FILES[module]) return
  const cacheKey = module
  if (loadedModules.has(cacheKey)) return
  loadedModules.add(cacheKey)

  try {
    // 动态 import 两个语种；Vite 按模块+语种分 chunk
    const [zhMod, enMod] = await Promise.all([
      import(`./locales/modules/${module}.zh-CN.json`),
      import(`./locales/modules/${module}.en-US.json`)
    ])
    // 合并到全局 messages（spread 浅合并，模块级 key 不与框架级冲突）
    // 用 getLocaleMessage / setLocaleMessage 替代直接索引访问，兼容 vue-i18n 类型系统
    const zhMessages = i18n.global.getLocaleMessage('zh-CN') as Record<string, unknown>
    const enMessages = i18n.global.getLocaleMessage('en-US') as Record<string, unknown>
    i18n.global.setLocaleMessage('zh-CN', { ...zhMessages, ...zhMod.default })
    i18n.global.setLocaleMessage('en-US', { ...enMessages, ...enMod.default })
  } catch {
    // 加载失败（如模块词条文件缺失）：移除标记，允许后续重试
    loadedModules.delete(cacheKey)
    if (import.meta.env.DEV) {
      console.warn(`[i18n] 模块 "${module}" 语言包加载失败`)
    }
  }
}

/**
 * 路由 path → 模块名映射（用于 afterEach 预加载）。
 *
 * <p>部分路由共享同一模块词条（如 /eng-spark、/eng-doris、/eng-kafka 共用 engines）。</p>
 */
const ROUTE_MODULE_MAP: Record<string, string[]> = {
  '/dashboard': ['dashboard'],
  '/workspaces': ['workspaces'],
  '/projects': ['projects'],
  '/integrate': ['integrate'],
  '/develop': ['develop'],
  '/sql': ['sql'],
  '/govern': ['govern'],
  '/standard': ['standard'],
  '/quality': ['quality'],
  '/lineage': ['lineage'],
  '/data-lineage': ['dataLineage'],
  '/sec': ['sec'],
  '/vector': ['vector'],
  '/kb': ['kb'],
  '/llmops': ['llmops'],
  '/gateway': ['gateway'],
  '/analyze': ['analyze'],
  '/ops': ['ops'],
  '/account': ['account'],
  '/admin': ['admin'],
  '/tenants': ['tenantManagement'],
  '/approvals': ['approvals'],
  '/register': ['register'],
  '/cluster': ['clusterOverview'],
  '/datasources': ['dataSourceManagement'],
  '/jobs': ['jobmgmt'],
  '/scheduler-ops': ['scheduler'],
  '/workspace-management': ['workspaceManagement'],
  '/quota-management': ['quotaManagement'],
  '/sql-workbench': ['sqlWorkbench'],
  '/search': ['searchPortal'],
  '/ops-tpl': ['templateMarket'],
  '/ops-portal': ['businessPortal'],
  '/ops-api': ['apiMarket'],
  '/ops-flow': ['assetMarket'],
  '/ai-assistant': ['aiAssistant'],
  '/infra-machine': ['infraMachine'],
  '/infra-k8s': ['infraK8s'],
  '/infra-net': ['infraNet'],
  '/infra-store': ['infraStore'],
  '/infra-sched': ['infraSched'],
  '/eng-storage': ['engStorage'],
  '/eng-spark': ['engines'],
  '/eng-flink': ['engFlink'],
  '/eng-doris': ['engines'],
  '/eng-kafka': ['engines'],
  '/eng-iotdb': ['engIotdb'],
  '/eng-mmg': ['engMmg'],
  '/govern-meta': ['govern'],
  '/dev-sched': ['devSched'],
  '/dev-tag': ['devTag'],
  '/dev-ml': ['devMl'],
  '/orchestrator/dag': ['orchestrator']
}

/**
 * 根据路由 path 预加载对应模块的语言包。
 *
 * <p>在 router.afterEach 中调用，实现路由切换时按需加载词条。
 * 不阻塞导航（异步加载，加载完成后词条自动生效）。</p>
 *
 * @param path 路由 path
 */
export function preloadRouteI18n(path: string): void {
  const modules = ROUTE_MODULE_MAP[path]
  if (!modules) return
  // 并行加载，不 await（不阻塞导航）
  for (const m of modules) {
    void loadModuleI18n(m)
  }
}
