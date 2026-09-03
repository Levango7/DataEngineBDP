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
import integrateZh from './locales/modules/integrate.zh-CN.json'
import integrateEn from './locales/modules/integrate.en-US.json'
import developZh from './locales/modules/develop.zh-CN.json'
import developEn from './locales/modules/develop.en-US.json'
import sqlZh from './locales/modules/sql.zh-CN.json'
import sqlEn from './locales/modules/sql.en-US.json'
import lineageZh from './locales/modules/lineage.zh-CN.json'
import lineageEn from './locales/modules/lineage.en-US.json'
import secZh from './locales/modules/sec.zh-CN.json'
import secEn from './locales/modules/sec.en-US.json'
import opsZh from './locales/modules/ops.zh-CN.json'
import opsEn from './locales/modules/ops.en-US.json'
import jobmgmtZh from './locales/modules/jobmgmt.zh-CN.json'
import jobmgmtEn from './locales/modules/jobmgmt.en-US.json'
import schedulerZh from './locales/modules/scheduler.zh-CN.json'
import schedulerEn from './locales/modules/scheduler.en-US.json'
import vectorZh from './locales/modules/vector.zh-CN.json'
import vectorEn from './locales/modules/vector.en-US.json'
import kbZh from './locales/modules/kb.zh-CN.json'
import kbEn from './locales/modules/kb.en-US.json'
import llmopsZh from './locales/modules/llmops.zh-CN.json'
import llmopsEn from './locales/modules/llmops.en-US.json'
import gatewayZh from './locales/modules/gateway.zh-CN.json'
import gatewayEn from './locales/modules/gateway.en-US.json'
import accountZh from './locales/modules/account.zh-CN.json'
import accountEn from './locales/modules/account.en-US.json'
import adminZh from './locales/modules/admin.zh-CN.json'
import adminEn from './locales/modules/admin.en-US.json'
import templateMarketZh from './locales/modules/templateMarket.zh-CN.json'
import templateMarketEn from './locales/modules/templateMarket.en-US.json'
import businessPortalZh from './locales/modules/businessPortal.zh-CN.json'
import businessPortalEn from './locales/modules/businessPortal.en-US.json'
import apiMarketZh from './locales/modules/apiMarket.zh-CN.json'
import apiMarketEn from './locales/modules/apiMarket.en-US.json'
import assetMarketZh from './locales/modules/assetMarket.zh-CN.json'
import assetMarketEn from './locales/modules/assetMarket.en-US.json'
import sqlWorkbenchZh from './locales/modules/sqlWorkbench.zh-CN.json'
import sqlWorkbenchEn from './locales/modules/sqlWorkbench.en-US.json'
import searchPortalZh from './locales/modules/searchPortal.zh-CN.json'
import searchPortalEn from './locales/modules/searchPortal.en-US.json'
import dataSourceManagementZh from './locales/modules/dataSourceManagement.zh-CN.json'
import dataSourceManagementEn from './locales/modules/dataSourceManagement.en-US.json'
import tenantManagementZh from './locales/modules/tenantManagement.zh-CN.json'
import tenantManagementEn from './locales/modules/tenantManagement.en-US.json'
import workspaceManagementZh from './locales/modules/workspaceManagement.zh-CN.json'
import workspaceManagementEn from './locales/modules/workspaceManagement.en-US.json'
import quotaManagementZh from './locales/modules/quotaManagement.zh-CN.json'
import quotaManagementEn from './locales/modules/quotaManagement.en-US.json'
import clusterOverviewZh from './locales/modules/clusterOverview.zh-CN.json'
import clusterOverviewEn from './locales/modules/clusterOverview.en-US.json'
import dataLineageZh from './locales/modules/dataLineage.zh-CN.json'
import dataLineageEn from './locales/modules/dataLineage.en-US.json'
import enginesZh from './locales/modules/engines.zh-CN.json'
import enginesEn from './locales/modules/engines.en-US.json'
import infraK8sZh from './locales/modules/infraK8s.zh-CN.json'
import infraK8sEn from './locales/modules/infraK8s.en-US.json'
import infraMachineZh from './locales/modules/infraMachine.zh-CN.json'
import infraMachineEn from './locales/modules/infraMachine.en-US.json'
import infraSchedZh from './locales/modules/infraSched.zh-CN.json'
import infraSchedEn from './locales/modules/infraSched.en-US.json'
import engIotdbZh from './locales/modules/engIotdb.zh-CN.json'
import engIotdbEn from './locales/modules/engIotdb.en-US.json'

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
      ...governZh,
      ...integrateZh,
      ...developZh,
      ...sqlZh,
      ...lineageZh,
      ...secZh,
      ...opsZh,
      ...jobmgmtZh,
      ...schedulerZh,
      ...vectorZh,
      ...kbZh,
      ...llmopsZh,
      ...gatewayZh,
      ...accountZh,
      ...adminZh,
      ...templateMarketZh,
      ...businessPortalZh,
      ...apiMarketZh,
      ...assetMarketZh,
      ...sqlWorkbenchZh,
      ...searchPortalZh,
      ...dataSourceManagementZh,
      ...tenantManagementZh,
      ...workspaceManagementZh,
      ...quotaManagementZh,
      ...clusterOverviewZh,
      ...dataLineageZh,
      ...enginesZh,
      ...infraK8sZh,
      ...infraMachineZh,
      ...infraSchedZh,
      ...engIotdbZh
    },
    'en-US': {
      ...enUS,
      ...dashboardEn,
      ...workspacesEn,
      ...projectsEn,
      ...analyzeEn,
      ...qualityEn,
      ...standardEn,
      ...governEn,
      ...integrateEn,
      ...developEn,
      ...sqlEn,
      ...lineageEn,
      ...secEn,
      ...opsEn,
      ...jobmgmtEn,
      ...schedulerEn,
      ...vectorEn,
      ...kbEn,
      ...llmopsEn,
      ...gatewayEn,
      ...accountEn,
      ...adminEn,
      ...templateMarketEn,
      ...businessPortalEn,
      ...apiMarketEn,
      ...assetMarketEn,
      ...sqlWorkbenchEn,
      ...searchPortalEn,
      ...dataSourceManagementEn,
      ...tenantManagementEn,
      ...workspaceManagementEn,
      ...quotaManagementEn,
      ...clusterOverviewEn,
      ...dataLineageEn,
      ...enginesEn,
      ...infraK8sEn,
      ...infraMachineEn,
      ...infraSchedEn,
      ...engIotdbEn
    }
  },
  // 未翻译的 key 回退显示 key 本身（开发期可见，生产期不至于空白）
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV
})
