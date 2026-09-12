/**
 * 平台导航配置（Sidebar 与 TopBar 面包屑共用的单一来源）。
 *
 * 从 Sidebar.vue 抽出（2026-09-07 布局重构）：
 * - Sidebar 渲染分组菜单
 * - TopBar 面包屑根据当前路由 path 反查 label
 *
 * 图标策略（2026-09-07 图标去重化）：全部使用 @element-plus/icons-vue
 * 组件名，51 个菜单项图标两两不同——折叠（图标）模式下可辨识。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'

export interface NavItem {
  path: string
  label: string
  /** @element-plus/icons-vue 组件名（PascalCase），经 Sidebar 内图标表解析 */
  icon: string
  badge?: number
}
export interface NavGroup {
  title: string
  items: NavItem[]
}

export function useNavGroups() {
  const { t } = useI18n()
  const store = useAppStore()

  const groups = computed<NavGroup[]>(() => [
    {
      title: t('nav.groups.infra'),
      items: [
        { path: '/infra-machine', label: t('nav.items.infra-machine'), icon: 'Platform' },
        { path: '/infra-k8s', label: t('nav.items.infra-k8s'), icon: 'Share' },
        { path: '/cluster', label: t('nav.items.cluster'), icon: 'Monitor' },
        { path: '/datasources', label: t('nav.items.datasources'), icon: 'Box' },
        { path: '/infra-net', label: t('nav.items.infra-net'), icon: 'Connection' },
        { path: '/infra-store', label: t('nav.items.infra-store'), icon: 'Files' },
        { path: '/infra-sched', label: t('nav.items.infra-sched'), icon: 'Odometer' }
      ]
    },
    {
      title: t('nav.groups.engine'),
      items: [
        { path: '/eng-storage', label: t('nav.items.eng-storage'), icon: 'Folder' },
        { path: '/eng-spark', label: t('nav.items.eng-spark'), icon: 'Lightning' },
        { path: '/eng-flink', label: t('nav.items.eng-flink'), icon: 'DataLine' },

        { path: '/eng-doris', label: t('nav.items.eng-doris'), icon: 'Histogram' },
        { path: '/eng-kafka', label: t('nav.items.eng-kafka'), icon: 'Message' },
        { path: '/eng-iotdb', label: t('nav.items.eng-iotdb'), icon: 'Timer' },
        { path: '/eng-mmg', label: t('nav.items.eng-mmg'), icon: 'Cpu' }
      ]
    },
    {
      title: t('nav.groups.governance'),
      items: [
        { path: '/govern-meta', label: t('nav.items.govern-meta'), icon: 'Notebook' },
        // C-2 导航合并：quality 已合并至 standard（tab 切换），data-lineage 已合并至 lineage
        { path: '/lineage', label: t('nav.items.lineage'), icon: 'Guide' },
        { path: '/govern', label: t('nav.items.govern'), icon: 'Setting' },
        { path: '/standard', label: t('nav.items.standard'), icon: 'Document' },
        { path: '/sec', label: t('nav.items.sec'), icon: 'Lock', badge: store.todoCount }
      ]
    },
    {
      title: t('nav.groups.devtools'),
      items: [
        { path: '/integrate', label: t('nav.items.integrate'), icon: 'Link' },
        { path: '/dev-sched', label: t('nav.items.dev-sched'), icon: 'AlarmClock' },
        { path: '/scheduler-ops', label: t('nav.items.scheduler-ops'), icon: 'Operation' },
        { path: '/jobs', label: t('nav.items.jobs'), icon: 'Memo' },
        { path: '/develop', label: t('nav.items.develop'), icon: 'EditPen' },
        { path: '/sql-workbench', label: t('nav.items.sql-workbench'), icon: 'Tickets' },
        { path: '/analyze', label: t('nav.items.analyze'), icon: 'DataAnalysis' },
        { path: '/dev-tag', label: t('nav.items.dev-tag'), icon: 'PriceTag' },
        { path: '/dev-ml', label: t('nav.items.dev-ml'), icon: 'MagicStick' }
      ]
    },
    {
      title: t('nav.groups.tenant'),
      items: [
        { path: '/workspaces', label: t('nav.items.workspaces'), icon: 'HomeFilled' },
        {
          path: '/workspace-management',
          label: t('nav.items.workspace-management'),
          icon: 'Management'
        },
        { path: '/quota-management', label: t('nav.items.quota-management'), icon: 'Wallet' },
        { path: '/projects', label: t('nav.items.projects'), icon: 'FolderOpened' },
        { path: '/account', label: t('nav.items.account'), icon: 'User' }
      ]
    },
    {
      title: t('nav.groups.intelligent'),
      items: [
        { path: '/ai-assistant', label: t('nav.items.ai-assistant'), icon: 'ChatLineRound' },
        // C-2 导航合并：kb 已合并至 vector（tab 切换"向量检索/知识库"）
        { path: '/vector', label: t('nav.items.vector'), icon: 'Aim' },
        { path: '/llmops', label: t('nav.items.llmops'), icon: 'Promotion' },
        { path: '/orchestrator/dag', label: t('nav.items.orchestrator-dag'), icon: 'Sort' },
        { path: '/gateway', label: t('nav.items.gateway'), icon: 'Switch' }
      ]
    },
    {
      title: t('nav.groups.operations-mgmt'),
      items: [
        { path: '/dashboard', label: t('nav.items.dashboard'), icon: 'PieChart' },
        { path: '/tenants', label: t('nav.items.tenants'), icon: 'OfficeBuilding' },
        { path: '/approvals', label: t('nav.items.approvals'), icon: 'CircleCheck' },
        { path: '/ops', label: t('nav.items.ops'), icon: 'Tools' },
        { path: '/search', label: t('nav.items.search'), icon: 'Search' }
      ]
    },
    {
      title: t('nav.groups.platform-mgmt'),
      items: [
        { path: '/admin', label: t('nav.items.admin'), icon: 'Grid' },
        { path: '/ops-tpl', label: t('nav.items.ops-tpl'), icon: 'Collection' },
        { path: '/ops-portal', label: t('nav.items.ops-portal'), icon: 'View' },
        { path: '/ops-api', label: t('nav.items.ops-api'), icon: 'Goods' },
        { path: '/ops-flow', label: t('nav.items.ops-flow'), icon: 'Finished' }
      ]
    }
  ])

  return groups
}

/** 根据路由 path 反查面包屑（分组 title → 菜单 label），未命中返回 null */
export function useBreadcrumb() {
  const groups = useNavGroups()
  return (path: string): { group: string; label: string } | null => {
    for (const g of groups.value) {
      const hit = g.items.find((i) => i.path === path)
      if (hit) return { group: g.title, label: hit.label }
    }
    return null
  }
}
