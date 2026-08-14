import {
  createRouter,
  createWebHashHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw
} from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
const Roadmap = () => import('@/views/Roadmap.vue')

// 批次4新增：核心功能页面
const TenantManagement = () => import('@/views/TenantManagement.vue')
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

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: { title: '登录', public: true }
  },

  // 有实际内容的页面
  { path: '/dashboard', name: 'dashboard', component: Dashboard },
  { path: '/workspaces', name: 'workspaces', component: Workspaces },
  { path: '/projects', name: 'projects', component: Projects },
  { path: '/integrate', name: 'integrate', component: Integrate },
  { path: '/develop', name: 'develop', component: Develop },
  { path: '/sql', name: 'sql', component: Sql },
  { path: '/govern', name: 'govern', component: Govern },
  { path: '/standard', name: 'standard', component: Standard },
  { path: '/quality', name: 'quality', component: Quality },
  { path: '/lineage', name: 'lineage', component: Lineage },
  { path: '/data-lineage', name: 'dataLineage', component: DataLineage, meta: { title: '血缘分析引擎', icon: 'Share' } },
  { path: '/sec', name: 'sec', component: Sec },
  { path: '/vector', name: 'vector', component: Vector },
  { path: '/kb', name: 'kb', component: Kb },
  { path: '/llmops', name: 'llmops', component: Llmops },
  { path: '/gateway', name: 'gateway', component: Gateway },
  { path: '/analyze', name: 'analyze', component: Analyze },
  { path: '/ops', name: 'ops', component: Ops },
  { path: '/account', name: 'account', component: Account },
  { path: '/admin', name: 'admin', component: Admin },

  // 批次4新增：核心功能页面
  {
    path: '/tenants',
    name: 'TenantManagement',
    component: TenantManagement,
    meta: { title: '租户管理', icon: 'Management' }
  },
  {
    path: '/cluster',
    name: 'ClusterOverview',
    component: ClusterOverview,
    meta: { title: '集群概览', icon: 'Monitor' }
  },
  {
    path: '/datasources',
    name: 'DataSourceManagement',
    component: DataSourceManagement,
    meta: { title: '数据源管理', icon: 'Connection' }
  },
  {
    path: '/jobs',
    name: 'JobManagement',
    component: JobManagement,

    meta: { title: '作业管理', icon: 'Tickets' }
  },

  {
    path: '/scheduler-ops',
    name: 'SchedulerOps',
    component: SchedulerOps,
    meta: { title: '任务运维中心', icon: 'AlarmClock' }
  },

  // 批次5新增：Workspace 管理（封装层 K8s 翻译）
  {
    path: '/workspace-management',
    name: 'WorkspaceManagement',
    component: WorkspaceManagement,
    meta: { title: '工作空间管理', icon: 'Grid' }
  },

  // 批次6新增：Quota 管理（封装层 K8s ResourceQuota + LimitRange 翻译）
  {
    path: '/quota-management',
    name: 'QuotaManagement',
    component: QuotaManagement,
    meta: { title: '配额管理', icon: 'Histogram' }
  },

  // 批次7新增：SQL 工作台（跨源归并引擎前端）
  {
    path: '/sql-workbench',
    name: 'SqlWorkbench',
    component: SqlWorkbench,
    meta: { title: 'SQL 工作台', icon: 'EditPen' }
  },

  // 批次8新增：行业应用模板市场（L5.3）
  {
    path: '/template-market',
    name: 'TemplateMarket',
    component: TemplateMarket,
    meta: { title: '行业应用模板', icon: 'Files' }
  },

  // 批次8新增：开放 API 服务目录（L5.5）
  {
    path: '/api-market',
    name: 'APIMarket',
    component: APIMarket,
    meta: { title: '开放 API', icon: 'Connection' }
  },

  // 批次9新增：检索门户（T007 前端集成增强）
  {
    path: '/search',
    name: 'SearchPortal',
    component: SearchPortal,
    meta: { title: '检索门户', icon: 'Search' }
  },

  // 批次10新增：编排 DAG 可视化（T007 viz）
  {
    path: '/orchestrator/dag',
    name: 'DagVisualizer',
    component: DagVisualizer,
    meta: { title: '编排 DAG 可视化', icon: 'Share' }
  },

  // 批次11新增：AI 助手（T011 自然语言→SQL→图表→解读 全链路）
  {
    path: '/ai-assistant',
    name: 'AiAssistant',
    component: AiAssistant,
    meta: { title: 'AI 数据助手', icon: 'ChatDotRound' }
  },

  // 占位页（Roadmap），通过 meta.title 传递模块名
  { path: '/infra-machine', component: Roadmap, meta: { title: '机器供应' } },
  { path: '/infra-k8s', component: Roadmap, meta: { title: 'K8s 集群' } },
  { path: '/infra-net', component: Roadmap, meta: { title: '容器网络' } },
  { path: '/infra-store', component: Roadmap, meta: { title: '容器存储' } },
  { path: '/infra-sched', component: Roadmap, meta: { title: '弹性调度' } },
  { path: '/eng-storage', component: Roadmap, meta: { title: '统一存储' } },
  { path: '/eng-spark', component: Roadmap, meta: { title: '批计算（Spark）' } },
  { path: '/eng-flink', component: Roadmap, meta: { title: '流计算（Flink）' } },
  { path: '/eng-doris', component: Roadmap, meta: { title: 'OLAP（Doris）' } },
  { path: '/eng-kafka', component: Roadmap, meta: { title: '消息流接入（Kafka）' } },
  { path: '/eng-iotdb', component: Roadmap, meta: { title: '时序引擎（IoTDB）' } },
  { path: '/eng-mmg', component: Roadmap, meta: { title: '多模型引擎' } },
  { path: '/govern-meta', component: Roadmap, meta: { title: '元数据管理' } },
  { path: '/dev-sched', component: Roadmap, meta: { title: '调度编排（DolphinScheduler）' } },
  { path: '/dev-tag', component: Roadmap, meta: { title: '标签画像' } },
  { path: '/dev-ml', component: Roadmap, meta: { title: '机器学习' } },
  { path: '/ops-tpl', component: TemplateMarket, meta: { title: '行业应用模板' } },
  { path: '/ops-portal', name: 'BusinessPortal', component: BusinessPortal, meta: { title: '业务线门户', icon: 'Grid' } },
  { path: '/ops-api', name: 'APIMarketLegacy', component: APIMarket, meta: { title: '开放 API', icon: 'Connection' } },
  { path: '/ops-flow', name: 'AssetMarket', component: AssetMarket, meta: { title: '数据资产流通', icon: 'ShoppingCart' } },

  // 兜底
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
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
const PUBLIC_PATHS = new Set(['/login'])

/**
 * 鉴权守卫（纯函数）。
 *
 * @param to            目标路由
 * @param isAuthenticated 是否已登录（测试可注入）
 * @returns 放行 true，或重定向目标
 */
export function authGuard(to: RouteLocationNormalized, isAuthenticated: boolean): boolean | Record<string, unknown> {
  if (PUBLIC_PATHS.has(to.path)) {
    if (isAuthenticated) {
      return { path: '/dashboard' }
    }
    return true
  }
  if (!isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
}

router.beforeEach((to) => {
  const authStore = useAuthStore()
  return authGuard(to, authStore.isAuthenticated)
})

export default router
