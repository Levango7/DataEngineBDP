import {
  createRouter,
  createWebHashHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw
} from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { i18n, preloadRouteI18n } from '@/i18n'

// 路由懒加载
const Dashboard = () => import('@/views/Dashboard.vue')
const Login = () => import('@/views/Login.vue')
const Workspaces = () => import('@/views/Workspaces.vue')
const Projects = () => import('@/views/Projects.vue')
const Integrate = () => import('@/views/Integrate.vue')
const Develop = () => import('@/views/Develop.vue')
const Sql = () => import('@/views/Sql.vue')
const Govern = () => import('@/views/Govern.vue')
const Standard = () => import('@/views/Standard.vue')
const Quality = () => import('@/views/Quality.vue')
const Lineage = () => import('@/views/Lineage.vue')
const DataLineage = () => import('@/views/DataLineage.vue')
const Sec = () => import('@/views/Sec.vue')
const Vector = () => import('@/views/Vector.vue')
const Kb = () => import('@/views/Kb.vue')
const Llmops = () => import('@/views/Llmops.vue')
const Gateway = () => import('@/views/Gateway.vue')
const Analyze = () => import('@/views/Analyze.vue')
const Ops = () => import('@/views/Ops.vue')
const Account = () => import('@/views/Account.vue')
const Admin = () => import('@/views/Admin.vue')

// 批次4新增：核心功能页面
const TenantManagement = () => import('@/views/TenantManagement.vue')
const Register = () => import('@/views/Register.vue')
const Approvals = () => import('@/views/Approvals.vue')
const ClusterOverview = () => import('@/views/ClusterOverview.vue')
const DataSourceManagement = () => import('@/views/DataSourceManagement.vue')
const JobManagement = () => import('@/views/JobManagement.vue')
const SchedulerOps = () => import('@/views/SchedulerOps.vue')
// 批次5新增：Workspace 管理（封装层 K8s 翻译）
const WorkspaceManagement = () => import('@/views/WorkspaceManagement.vue')
// 批次6新增：Quota 管理（封装层 K8s ResourceQuota + LimitRange 翻译）
const QuotaManagement = () => import('@/views/QuotaManagement.vue')
// 批次7新增：SQL 工作台（跨源归并引擎前端）
const SqlWorkbench = () => import('@/views/SqlWorkbench.vue')
// 批次8新增：行业应用模板市场（L5.3）
const TemplateMarket = () => import('@/views/TemplateMarket.vue')
// 批次8新增：业务线门户（L5.4）
const BusinessPortal = () => import('@/views/BusinessPortal.vue')
// 批次8新增：数据资产流通市场（L5.6 AssetExchange 前端）
const AssetMarket = () => import('@/views/AssetMarket.vue')
// 批次8新增：开放 API 服务目录（L5.5）
const APIMarket = () => import('@/views/APIMarket.vue')
// 批次9新增：检索门户（T007 前端集成增强）
const SearchPortal = () => import('@/views/SearchPortal.vue')
// 批次10新增：编排 DAG 可视化（T007 viz）
const DagVisualizer = () => import('@/views/orchestrator/DagVisualizer.vue')
// 批次11新增：AI 助手（T011 自然语言→SQL→图表→解读 全链路）
const AiAssistant = () => import('@/views/ai-assistant/AiAssistant.vue')

// 批次12新增：基础设施层 5 个页面
const InfraMachine = () => import('@/views/infra/InfraMachine.vue')
const InfraK8s = () => import('@/views/infra/InfraK8s.vue')
const InfraNet = () => import('@/views/infra/InfraNet.vue')
const InfraStore = () => import('@/views/infra/InfraStore.vue')
const InfraSched = () => import('@/views/infra/InfraSched.vue')
// 批次12新增：引擎层 7 个页面
const EngStorage = () => import('@/views/engine/EngStorage.vue')
const EngSpark = () => import('@/views/engine/EngSpark.vue')
const EngFlink = () => import('@/views/engine/EngFlink.vue')
const EngDoris = () => import('@/views/engine/EngDoris.vue')
const EngKafka = () => import('@/views/engine/EngKafka.vue')
const EngIotdb = () => import('@/views/engine/EngIotdb.vue')
const EngMmg = () => import('@/views/engine/EngMmg.vue')
// 批次12新增：治理/开发层 4 个页面
const GovernMeta = () => import('@/views/govern/GovernMeta.vue')
const DevSched = () => import('@/views/dev/DevSched.vue')
const DevTag = () => import('@/views/dev/DevTag.vue')
const DevMl = () => import('@/views/dev/DevMl.vue')

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: { titleKey: 'login.title', public: true }
  },

  // 有实际内容的页面（批次1-3：补充 meta.title 与 meta.icon，统一路由元信息）
  {
    path: '/dashboard',
    name: 'dashboard',
    component: Dashboard,
    meta: { titleKey: 'nav.items.dashboard', icon: 'Odometer', group: 'operations' }
  },
  {
    path: '/workspaces',
    name: 'workspaces',
    component: Workspaces,
    meta: { titleKey: 'nav.items.workspaces', icon: 'Grid', group: 'tenant' }
  },
  {
    path: '/projects',
    name: 'projects',
    component: Projects,
    meta: { titleKey: 'nav.items.projects', icon: 'Folder', group: 'tenant' }
  },
  {
    path: '/integrate',
    name: 'integrate',
    component: Integrate,
    meta: { titleKey: 'nav.items.integrate', icon: 'Connection', group: 'devtools' }
  },
  {
    path: '/develop',
    name: 'develop',
    component: Develop,
    meta: { titleKey: 'nav.items.develop', icon: 'Edit', group: 'devtools' }
  },
  // SQL 工作空间（/sql）已合并至 SQL 工作台（/sql-workbench），二者功能重叠。
  // /sql-workbench 为批次7新增的跨源归并引擎前端，功能更完善，/sql 重定向至此。
  { path: '/sql', redirect: '/sql-workbench' },
  {
    path: '/govern',
    name: 'govern',
    component: Govern,
    meta: { titleKey: 'nav.items.govern', icon: 'Setting', group: 'governance' }
  },
  {
    path: '/standard',
    name: 'standard',
    component: Standard,
    meta: { titleKey: 'nav.items.standard', icon: 'List', group: 'governance' }
  },
  // C-2 导航合并：/quality 已合并至 /standard（数据标准页用 tab 切换"标准/质量"）
  { path: '/quality', redirect: '/standard' },
  {
    path: '/lineage',
    name: 'lineage',
    component: Lineage,
    meta: { titleKey: 'nav.items.lineage', icon: 'Share', group: 'governance' }
  },
  // C-2 导航合并：/data-lineage 已合并至 /lineage（血缘页用 tab 切换"任务血缘/数据血缘"）
  { path: '/data-lineage', redirect: '/lineage' },
  {
    path: '/sec',
    name: 'sec',
    component: Sec,
    meta: { titleKey: 'nav.items.sec', icon: 'Lock', group: 'governance' }
  },
  {
    path: '/vector',
    name: 'vector',
    component: Vector,
    meta: { titleKey: 'nav.items.vector', icon: 'Box', group: 'intelligent' }
  },
  // C-2 导航合并：/kb 已合并至 /vector（向量页用 tab 切换"向量检索/知识库"）
  { path: '/kb', redirect: '/vector' },
  {
    path: '/llmops',
    name: 'llmops',
    component: Llmops,
    meta: { titleKey: 'nav.items.llmops', icon: 'Cpu', group: 'intelligent' }
  },
  {
    path: '/gateway',
    name: 'gateway',
    component: Gateway,
    meta: { titleKey: 'nav.items.gateway', icon: 'Position', group: 'intelligent' }
  },
  {
    path: '/analyze',
    name: 'analyze',
    component: Analyze,
    meta: { titleKey: 'nav.items.analyze', icon: 'TrendCharts', group: 'devtools' }
  },
  {
    path: '/ops',
    name: 'ops',
    component: Ops,
    meta: { titleKey: 'nav.items.ops', icon: 'Monitor', group: 'operations' }
  },
  {
    path: '/account',
    name: 'account',
    component: Account,
    meta: { titleKey: 'nav.items.account', icon: 'User', group: 'tenant' }
  },
  {
    path: '/admin',
    name: 'admin',
    component: Admin,
    meta: { titleKey: 'nav.items.admin', icon: 'Tools', group: 'operations' }
  },

  // 批次4新增：核心功能页面
  {
    path: '/tenants',
    name: 'TenantManagement',
    component: TenantManagement,
    meta: { titleKey: 'nav.items.tenants', icon: 'Management', group: 'operations' }
  },
  {
    path: '/approvals',
    name: 'Approvals',
    component: Approvals,
    meta: { titleKey: 'nav.items.approvals', icon: 'CircleCheck', group: 'operations' }
  },
  {
    path: '/register',
    name: 'Register',
    component: Register,
    meta: { titleKey: 'register.title', icon: 'EditPen', public: true }
  },
  {
    path: '/cluster',
    name: 'ClusterOverview',
    component: ClusterOverview,
    meta: { titleKey: 'nav.items.cluster', icon: 'Monitor', group: 'infra' }
  },
  {
    path: '/datasources',
    name: 'DataSourceManagement',
    component: DataSourceManagement,
    meta: { titleKey: 'nav.items.datasources', icon: 'Connection', group: 'infra' }
  },
  {
    path: '/jobs',
    name: 'JobManagement',
    component: JobManagement,

    meta: { titleKey: 'nav.items.jobs', icon: 'Tickets', group: 'devtools' }
  },

  {
    path: '/scheduler-ops',
    name: 'SchedulerOps',
    component: SchedulerOps,
    meta: { titleKey: 'nav.items.scheduler-ops', icon: 'AlarmClock', group: 'devtools' }
  },

  // 批次5新增：Workspace 管理（封装层 K8s 翻译）
  {
    path: '/workspace-management',
    name: 'WorkspaceManagement',
    component: WorkspaceManagement,
    meta: { titleKey: 'nav.items.workspace-management', icon: 'Grid', group: 'tenant' }
  },

  // 批次6新增：Quota 管理（封装层 K8s ResourceQuota + LimitRange 翻译）
  {
    path: '/quota-management',
    name: 'QuotaManagement',
    component: QuotaManagement,
    meta: { titleKey: 'nav.items.quota-management', icon: 'Histogram', group: 'tenant' }
  },

  // 批次7新增：SQL 工作台（跨源归并引擎前端）
  {
    path: '/sql-workbench',
    name: 'SqlWorkbench',
    component: SqlWorkbench,
    meta: { titleKey: 'nav.items.sql-workbench', icon: 'EditPen', group: 'devtools' }
  },

  // 批次8新增：行业应用模板市场（L5.3）——入口为 /ops-tpl（见 /ops-tpl 路由）
  // 原 /template-market 与 /ops-tpl 指向同一组件（重复路由，已合并至 /ops-tpl）

  // 批次8新增：开放 API 服务目录（L5.5）——入口为 /ops-api（见 /ops-api 路由）
  // 原 /api-market 与 /ops-api 指向同一组件（重复路由，已合并至 /ops-api）

  // 批次9新增：检索门户（T007 前端集成增强）
  {
    path: '/search',
    name: 'SearchPortal',
    component: SearchPortal,
    meta: { titleKey: 'nav.items.search', icon: 'Search', group: 'operations' }
  },

  // 批次10新增：编排 DAG 可视化（T007 viz）
  {
    path: '/orchestrator/dag',
    name: 'DagVisualizer',
    component: DagVisualizer,
    meta: { titleKey: 'nav.items.orchestrator-dag', icon: 'Share', group: 'intelligent' }
  },

  // 批次11新增：AI 助手（T011 自然语言→SQL→图表→解读 全链路）
  {
    path: '/ai-assistant',
    name: 'AiAssistant',
    component: AiAssistant,
    meta: { titleKey: 'nav.items.ai-assistant', icon: 'ChatDotRound', group: 'intelligent' }
  },

  // 批次12新增：基础设施层 5 个页面（替换原 Roadmap 占位，补充 name 以支持编程式导航）
  {
    path: '/infra-machine',
    name: 'InfraMachine',
    component: InfraMachine,
    meta: { titleKey: 'nav.items.infra-machine', icon: 'Monitor', group: 'infra' }
  },
  {
    path: '/infra-k8s',
    name: 'InfraK8s',
    component: InfraK8s,
    meta: { titleKey: 'nav.items.infra-k8s', icon: 'Cpu', group: 'infra' }
  },
  {
    path: '/infra-net',
    name: 'InfraNet',
    component: InfraNet,
    meta: { titleKey: 'nav.items.infra-net', icon: 'Connection', group: 'infra' }
  },
  {
    path: '/infra-store',
    name: 'InfraStore',
    component: InfraStore,
    meta: { titleKey: 'nav.items.infra-store', icon: 'Files', group: 'infra' }
  },
  {
    path: '/infra-sched',
    name: 'InfraSched',
    component: InfraSched,
    meta: { titleKey: 'nav.items.infra-sched', icon: 'Operation', group: 'infra' }
  },
  // 批次12新增：引擎层 7 个页面（替换原 Roadmap 占位，补充 name）
  {
    path: '/eng-storage',
    name: 'EngStorage',
    component: EngStorage,
    meta: { titleKey: 'nav.items.eng-storage', icon: 'FolderOpened', group: 'engine' }
  },
  {
    path: '/eng-spark',
    name: 'EngSpark',
    component: EngSpark,
    meta: { titleKey: 'nav.items.eng-spark', icon: 'Histogram', group: 'engine' }
  },
  {
    path: '/eng-flink',
    name: 'EngFlink',
    component: EngFlink,
    meta: { titleKey: 'nav.items.eng-flink', icon: 'DataLine', group: 'engine' }
  },
  {
    path: '/eng-doris',
    name: 'EngDoris',
    component: EngDoris,
    meta: { titleKey: 'nav.items.eng-doris', icon: 'Grid', group: 'engine' }
  },
  {
    path: '/eng-kafka',
    name: 'EngKafka',
    component: EngKafka,
    meta: { titleKey: 'nav.items.eng-kafka', icon: 'ChatLineSquare', group: 'engine' }
  },
  {
    path: '/eng-iotdb',
    name: 'EngIotdb',
    component: EngIotdb,
    meta: { titleKey: 'nav.items.eng-iotdb', icon: 'Timer', group: 'engine' }
  },
  {
    path: '/eng-mmg',
    name: 'EngMmg',
    component: EngMmg,
    meta: { titleKey: 'nav.items.eng-mmg', icon: 'Box', group: 'engine' }
  },
  // 批次12新增：治理/开发层 4 个页面（替换原 Roadmap 占位，补充 name）
  {
    path: '/govern-meta',
    name: 'GovernMeta',
    component: GovernMeta,
    meta: { titleKey: 'nav.items.govern-meta', icon: 'Collection', group: 'governance' }
  },
  {
    path: '/dev-sched',
    name: 'DevSched',
    component: DevSched,
    meta: { titleKey: 'nav.items.dev-sched', icon: 'Calendar', group: 'devtools' }
  },
  {
    path: '/dev-tag',
    name: 'DevTag',
    component: DevTag,
    meta: { titleKey: 'nav.items.dev-tag', icon: 'PriceTag', group: 'devtools' }
  },
  {
    path: '/dev-ml',
    name: 'DevMl',
    component: DevMl,
    meta: { titleKey: 'nav.items.dev-ml', icon: 'Cpu', group: 'devtools' }
  },
  {
    path: '/ops-tpl',
    name: 'TemplateMarket',
    component: TemplateMarket,
    meta: { titleKey: 'nav.items.ops-tpl', group: 'operations' }
  },
  {
    path: '/ops-portal',
    name: 'BusinessPortal',
    component: BusinessPortal,
    meta: { titleKey: 'nav.items.ops-portal', icon: 'Grid', group: 'operations' }
  },
  {
    path: '/ops-api',
    name: 'APIMarket',
    component: APIMarket,
    meta: { titleKey: 'nav.items.ops-api', icon: 'Connection', group: 'operations' }
  },
  {
    path: '/ops-flow',
    name: 'AssetMarket',
    component: AssetMarket,
    meta: { titleKey: 'nav.items.ops-flow', icon: 'ShoppingCart', group: 'operations' }
  },

  // 兜底：独立 404 页（替代静默跳转 dashboard，用户可明确感知路径错误）
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { titleKey: 'notFound.title', public: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// ============================================================
// 鉴权闭环（修复评估报告 §5.7：无路由守卫，任何人可直接访问全部页面）
// 白名单：/login 及无需认证的公开页
// 提取为纯函数以便单元测试（避免 jsdom 下懒加载组件挂起）
// ============================================================
const PUBLIC_PATHS = new Set(['/login', '/register'])

/**
 * 解析 JWT payload 的 exp 字段（过期时间，Unix 秒）。
 *
 * <p>JWT 结构为 header.payload.signature，三段均为 base64url 编码。
 * 仅解析 payload 中的 exp，不验证签名（签名验证由后端完成）。</p>
 *
 * @param token JWT token
 * @returns 过期时间戳（秒），无效 token 返回 null
 */
function getJwtExp(token: string): number | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    // base64url → base64 → UTF-8 JSON
    const payloadB64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payloadJson = decodeURIComponent(
      atob(payloadB64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    const payload = JSON.parse(payloadJson) as { exp?: unknown }
    return typeof payload.exp === 'number' ? payload.exp : null
  } catch {
    return null
  }
}

/**
 * 检查 JWT 是否已过期。
 *
 * <p>无法解析 exp 字段时返回 false（不拦截），交由后端 401 兜底处理，
 * 避免非标准 JWT（如 opaque token）被误拦截。</p>
 *
 * @param token JWT token
 * @returns 已过期返回 true，未过期或无法解析返回 false
 */
export function isTokenExpired(token: string): boolean {
  const exp = getJwtExp(token)
  if (exp === null) return false
  // 预留 30 秒缓冲，避免时钟偏差导致临界过期
  return Date.now() / 1000 >= exp - 30
}

/**
 * 鉴权守卫（纯函数）。
 *
 * @param to            目标路由
 * @param isAuthenticated 是否已登录（测试可注入）
 * @param token        当前 token（用于 JWT 过期检查，可选）
 * @returns 放行 true，或重定向目标
 */
export function authGuard(
  to: RouteLocationNormalized,
  isAuthenticated: boolean,
  token: string | null = null
): boolean | Record<string, unknown> {
  if (PUBLIC_PATHS.has(to.path)) {
    if (isAuthenticated) {
      return { path: '/dashboard' }
    }
    return true
  }
  if (!isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已认证但 JWT 可能已过期：前端预检查 exp，过期则跳登录页（避免带过期 token 请求后端再 401）
  if (token && isTokenExpired(token)) {
    return { path: '/login', query: { redirect: to.fullPath, reason: 'expired' } }
  }
  return true
}

router.beforeEach((to) => {
  const authStore = useAuthStore()
  return authGuard(to, authStore.isAuthenticated, authStore.token)
})

router.afterEach((to) => {
  // 预加载当前路由对应的模块级 i18n 词条（问题6：懒加载优化）
  preloadRouteI18n(to.path)

  const t = i18n.global.t
  const titleKey = to.meta.titleKey
  if (titleKey) {
    document.title = `${t(titleKey)} · ${t('nav.brand')}`
  } else {
    document.title = t('nav.brand')
  }
})

export default router
