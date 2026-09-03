/**
 * i18n 词条结构与切换器契约测试（2026-09-02 框架级双语）。
 *
 * 保障：
 * - zh-CN / en-US 词条文件结构镜像（同 key 集合，防漏译）
 * - Sidebar 导航 35 项 + 7 组 key 全部存在
 * - 常用词条有值（防空翻译）
 */
import { describe, it, expect } from 'vitest'
import zhCN from '@/i18n/locales/zh-CN.json'
import enUS from '@/i18n/locales/en-US.json'
import dashboardZh from '@/i18n/locales/modules/dashboard.zh-CN.json'
import dashboardEn from '@/i18n/locales/modules/dashboard.en-US.json'
import workspacesZh from '@/i18n/locales/modules/workspaces.zh-CN.json'
import workspacesEn from '@/i18n/locales/modules/workspaces.en-US.json'
import projectsZh from '@/i18n/locales/modules/projects.zh-CN.json'
import projectsEn from '@/i18n/locales/modules/projects.en-US.json'
import analyzeZh from '@/i18n/locales/modules/analyze.zh-CN.json'
import analyzeEn from '@/i18n/locales/modules/analyze.en-US.json'
import qualityZh from '@/i18n/locales/modules/quality.zh-CN.json'
import qualityEn from '@/i18n/locales/modules/quality.en-US.json'
import standardZh from '@/i18n/locales/modules/standard.zh-CN.json'
import standardEn from '@/i18n/locales/modules/standard.en-US.json'
import governZh from '@/i18n/locales/modules/govern.zh-CN.json'
import governEn from '@/i18n/locales/modules/govern.en-US.json'
import integrateZh from '@/i18n/locales/modules/integrate.zh-CN.json'
import integrateEn from '@/i18n/locales/modules/integrate.en-US.json'
import developZh from '@/i18n/locales/modules/develop.zh-CN.json'
import developEn from '@/i18n/locales/modules/develop.en-US.json'
import sqlZh from '@/i18n/locales/modules/sql.zh-CN.json'
import sqlEn from '@/i18n/locales/modules/sql.en-US.json'
import lineageZh from '@/i18n/locales/modules/lineage.zh-CN.json'
import lineageEn from '@/i18n/locales/modules/lineage.en-US.json'
import secZh from '@/i18n/locales/modules/sec.zh-CN.json'
import secEn from '@/i18n/locales/modules/sec.en-US.json'
import opsZh from '@/i18n/locales/modules/ops.zh-CN.json'
import opsEn from '@/i18n/locales/modules/ops.en-US.json'
import jobmgmtZh from '@/i18n/locales/modules/jobmgmt.zh-CN.json'
import jobmgmtEn from '@/i18n/locales/modules/jobmgmt.en-US.json'
import schedulerZh from '@/i18n/locales/modules/scheduler.zh-CN.json'
import schedulerEn from '@/i18n/locales/modules/scheduler.en-US.json'
import vectorZh from '@/i18n/locales/modules/vector.zh-CN.json'
import vectorEn from '@/i18n/locales/modules/vector.en-US.json'
import kbZh from '@/i18n/locales/modules/kb.zh-CN.json'
import kbEn from '@/i18n/locales/modules/kb.en-US.json'
import llmopsZh from '@/i18n/locales/modules/llmops.zh-CN.json'
import llmopsEn from '@/i18n/locales/modules/llmops.en-US.json'
import gatewayZh from '@/i18n/locales/modules/gateway.zh-CN.json'
import gatewayEn from '@/i18n/locales/modules/gateway.en-US.json'
import accountZh from '@/i18n/locales/modules/account.zh-CN.json'
import accountEn from '@/i18n/locales/modules/account.en-US.json'
import adminZh from '@/i18n/locales/modules/admin.zh-CN.json'
import adminEn from '@/i18n/locales/modules/admin.en-US.json'
import templateMarketZh from '@/i18n/locales/modules/templateMarket.zh-CN.json'
import templateMarketEn from '@/i18n/locales/modules/templateMarket.en-US.json'
import businessPortalZh from '@/i18n/locales/modules/businessPortal.zh-CN.json'
import businessPortalEn from '@/i18n/locales/modules/businessPortal.en-US.json'
import apiMarketZh from '@/i18n/locales/modules/apiMarket.zh-CN.json'
import apiMarketEn from '@/i18n/locales/modules/apiMarket.en-US.json'
import assetMarketZh from '@/i18n/locales/modules/assetMarket.zh-CN.json'
import assetMarketEn from '@/i18n/locales/modules/assetMarket.en-US.json'
import sqlWorkbenchZh from '@/i18n/locales/modules/sqlWorkbench.zh-CN.json'
import sqlWorkbenchEn from '@/i18n/locales/modules/sqlWorkbench.en-US.json'
import searchPortalZh from '@/i18n/locales/modules/searchPortal.zh-CN.json'
import searchPortalEn from '@/i18n/locales/modules/searchPortal.en-US.json'
import dataSourceManagementZh from '@/i18n/locales/modules/dataSourceManagement.zh-CN.json'
import dataSourceManagementEn from '@/i18n/locales/modules/dataSourceManagement.en-US.json'
import tenantManagementZh from '@/i18n/locales/modules/tenantManagement.zh-CN.json'
import tenantManagementEn from '@/i18n/locales/modules/tenantManagement.en-US.json'
import workspaceManagementZh from '@/i18n/locales/modules/workspaceManagement.zh-CN.json'
import workspaceManagementEn from '@/i18n/locales/modules/workspaceManagement.en-US.json'
import quotaManagementZh from '@/i18n/locales/modules/quotaManagement.zh-CN.json'
import quotaManagementEn from '@/i18n/locales/modules/quotaManagement.en-US.json'
import clusterOverviewZh from '@/i18n/locales/modules/clusterOverview.zh-CN.json'
import clusterOverviewEn from '@/i18n/locales/modules/clusterOverview.en-US.json'
import dataLineageZh from '@/i18n/locales/modules/dataLineage.zh-CN.json'
import dataLineageEn from '@/i18n/locales/modules/dataLineage.en-US.json'

function flattenKeys(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null
      ? flattenKeys(v as Record<string, unknown>, `${prefix}${k}.`)
      : [`${prefix}${k}`]
  )
}

describe('i18n locales structure', () => {
  const zhKeys = flattenKeys(zhCN as Record<string, unknown>).sort()
  const enKeys = flattenKeys(enUS as Record<string, unknown>).sort()

  it('zh-CN 与 en-US 的 key 集合完全一致（无漏译）', () => {
    expect(enKeys).toEqual(zhKeys)
  })

  it('导航 51 项 + 7 分组全部有中英词条', () => {
    const navItems = Object.keys((zhCN as any).nav.items)
    const navGroups = Object.keys((zhCN as any).nav.groups)
    // 实数：Sidebar groups 共 51 个导航项（7 分组）
    expect(navItems.length).toBe(51)
    expect(navGroups.length).toBe(7)
    for (const k of navItems) {
      expect((enUS as any).nav.items[k]).toBeTruthy()
    }
    for (const g of navGroups) {
      expect((enUS as any).nav.groups[g]).toBeTruthy()
    }
  })

  it('login / common / app 词条均有值（非空字符串）', () => {
    for (const domain of ['login', 'common', 'app']) {
      const entries = Object.entries((zhCN as any)[domain])
      for (const [k, v] of entries) {
        expect(v, `${domain}.${k}`).toBeTruthy()
        expect((enUS as any)[domain][k], `en:${domain}.${k}`).toBeTruthy()
      }
    }
  })

  it('zh-CN 导航项含中文，en-US 导航项为英文', () => {
    expect((zhCN as any).nav.items.quality).toBe('数据质量')
    expect((enUS as any).nav.items.quality).toBe('Data Quality')
    expect((zhCN as any).nav.brand).toContain('数擎')
    expect((enUS as any).nav.brand).toContain('Shuqing')
  })
})

describe('i18n 页面级模块词条（locales/modules）', () => {
  const modules: Array<[string, Record<string, unknown>, Record<string, unknown>]> = [
    ['dashboard', dashboardZh as Record<string, unknown>, dashboardEn as Record<string, unknown>],
    ['workspaces', workspacesZh as Record<string, unknown>, workspacesEn as Record<string, unknown>],
    ['projects', projectsZh as Record<string, unknown>, projectsEn as Record<string, unknown>],
    ['analyze', analyzeZh as Record<string, unknown>, analyzeEn as Record<string, unknown>],
    ['quality', qualityZh as Record<string, unknown>, qualityEn as Record<string, unknown>],
    ['standard', standardZh as Record<string, unknown>, standardEn as Record<string, unknown>],
    ['govern', governZh as Record<string, unknown>, governEn as Record<string, unknown>],
    ['integrate', integrateZh as Record<string, unknown>, integrateEn as Record<string, unknown>],
    ['develop', developZh as Record<string, unknown>, developEn as Record<string, unknown>],
    ['sql', sqlZh as Record<string, unknown>, sqlEn as Record<string, unknown>],
    ['lineage', lineageZh as Record<string, unknown>, lineageEn as Record<string, unknown>],
    ['sec', secZh as Record<string, unknown>, secEn as Record<string, unknown>],
    ['ops', opsZh as Record<string, unknown>, opsEn as Record<string, unknown>],
    ['jobmgmt', jobmgmtZh as Record<string, unknown>, jobmgmtEn as Record<string, unknown>],
    ['scheduler', schedulerZh as Record<string, unknown>, schedulerEn as Record<string, unknown>],
    ['vector', vectorZh as Record<string, unknown>, vectorEn as Record<string, unknown>],
    ['kb', kbZh as Record<string, unknown>, kbEn as Record<string, unknown>],
    ['llmops', llmopsZh as Record<string, unknown>, llmopsEn as Record<string, unknown>],
    ['gateway', gatewayZh as Record<string, unknown>, gatewayEn as Record<string, unknown>],
    ['account', accountZh as Record<string, unknown>, accountEn as Record<string, unknown>],
    ['admin', adminZh as Record<string, unknown>, adminEn as Record<string, unknown>],
    ['templateMarket', templateMarketZh as Record<string, unknown>, templateMarketEn as Record<string, unknown>],
    ['businessPortal', businessPortalZh as Record<string, unknown>, businessPortalEn as Record<string, unknown>],
    ['apiMarket', apiMarketZh as Record<string, unknown>, apiMarketEn as Record<string, unknown>],
    ['assetMarket', assetMarketZh as Record<string, unknown>, assetMarketEn as Record<string, unknown>],
    ['sqlWorkbench', sqlWorkbenchZh as Record<string, unknown>, sqlWorkbenchEn as Record<string, unknown>],
    ['searchPortal', searchPortalZh as Record<string, unknown>, searchPortalEn as Record<string, unknown>],
    ['dataSourceManagement', dataSourceManagementZh as Record<string, unknown>, dataSourceManagementEn as Record<string, unknown>],
    ['tenantManagement', tenantManagementZh as Record<string, unknown>, tenantManagementEn as Record<string, unknown>],
    ['workspaceManagement', workspaceManagementZh as Record<string, unknown>, workspaceManagementEn as Record<string, unknown>],
    ['quotaManagement', quotaManagementZh as Record<string, unknown>, quotaManagementEn as Record<string, unknown>],
    ['clusterOverview', clusterOverviewZh as Record<string, unknown>, clusterOverviewEn as Record<string, unknown>],
    ['dataLineage', dataLineageZh as Record<string, unknown>, dataLineageEn as Record<string, unknown>]
  ]

  it('每个模块 zh/en key 集合完全一致（无漏译）', () => {
    for (const [name, zh, en] of modules) {
      expect(flattenKeys(en).sort(), `模块 ${name}`).toEqual(flattenKeys(zh).sort())
    }
  })

  it('每个模块词条均非空字符串', () => {
    for (const [name, zh, en] of modules) {
      const enKeys = new Set(flattenKeys(en))
      for (const key of flattenKeys(zh)) {
        if (!enKeys.has(key)) {
          throw new Error(`[${name}] zh has key "${key}" but en does not`)
        }
        const zhVal = key.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown>)?.[k], zh)
        const enVal = key.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown>)?.[k], en)
        if (!zhVal || !enVal) {
          throw new Error(`[${name}] empty at key "${key}" (zh=${zhVal} en=${enVal})`)
        }
      }
    }
  })
})
