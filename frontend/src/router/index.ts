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
    meta: { title: '登录', public: true }
  },

  // 有实际内容的页面（批次1-3：补充 meta.title 与 meta.icon，统一路由元信息）
  {
    path: '/dashboard',
    name: 'dashboard',
    component: Dashboard,
    meta: { title: '仪表盘', icon: 'Odometer' }
  },
  {
    path: '/workspaces',
    name: 'workspaces',
    component: Workspaces,
    meta: { title: '工作空间', icon: 'Grid' }
  },
  {
    path: '/projects',
    name: 'projects',
    component: Projects,
    meta: { title: '项目管理', icon: 'Folder' }
  },
  {
    path: '/integrate',
    name: 'integrate',
    component: Integrate,
    meta: { title: '数据集成', icon: 'Connection' }
  },
  {
    path: '/develop',
    name: 'develop',
    component: Develop,
    meta: { title: '数据开发', icon: 'Edit' }
  },
  { path: '/sql', name: 'sql', component: Sql, meta: { title: 'SQL查询', icon: 'Document' } },
  {
    path: '/govern',
    name: 'govern',
    component: Govern,
    meta: { title: '数据治理', icon: 'Setting' }
  },
  {
    path: '/standard',
    name: 'standard',
    component: Standard,
    meta: { title: '数据标准', icon: 'List' }
  },
  {
    path: '/quality',
    name: 'quality',
    component: Quality,
    meta: { title: '数据质量', icon: 'CircleCheck' }
  },
  {
    path: '/lineage',
    name: 'lineage',
    component: Lineage,
    meta: { title: '数据血缘', icon: 'Share' }
  },
  {
    path: '/data-lineage',
    name: 'dataLineage',
    component: DataLineage,
    meta: { title: '血缘分析', icon: 'Histogram' }
  },
  { path: '/sec', name: 'sec', component: Sec, meta: { title: '安全策略', icon: 'Lock' } },
  { path: '/vector', name: 'vector', component: Vector, meta: { title: '向量引擎', icon: 'Box' } },
  { path: '/kb', name: 'kb', component: Kb, meta: { title: '知识库', icon: 'Reading' } },
  { path: '/llmops', name: 'llmops', component: Llmops, meta: { title: 'LLM运维', icon: 'Cpu' } },
  {
    path: '/gateway',
    name: 'gateway',
    component: Gateway,
    meta: { title: 'API网关', icon: 'Position' }
  },
  {
    path: '/analyze',
    name: 'analyze',
    component: Analyze,
    meta: { title: '数据分析', icon: 'TrendCharts' }
  },
  { path: '/ops', name: 'ops', component: Ops, meta: { title: '运维监控', icon: 'Monitor' } },
  {
    path: '/account',
    name: 'account',
    component: Account,
    meta: { title: '账户中心', icon: 'User' }
  },
  { path: '/admin', name: 'admin', component: Admin, meta: { title: '系统管理', icon: 'Tools' } },

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

  // 批次8新增：行业应用模板市场（L5.3）——入口为 /ops-tpl（见 /ops-tpl 路由）
  // 原 /template-market 与 /ops-tpl 指向同一组件（重复路由，已合并至 /ops-tpl）

  // 批次8新增：开放 API 服务目录（L5.5）——入口为 /ops-api（见 /ops-api 路由）
  // 原 /api-market 与 /ops-api 指向同一组件（重复路由，已合并至 /ops-api）

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

  // 批次12新增：基础设施层 5 个页面（替换原 Roadmap 占位，补充 name 以支持编程式导航）
  {
    path: '/infra-machine',
    name: 'InfraMachine',
    component: InfraMachine,
    meta: { title: '机器供应', icon: 'Monitor' }
  },
  {
    path: '/infra-k8s',
    name: 'InfraK8s',
    component: InfraK8s,
    meta: { title: 'K8s 集群', icon: 'Cpu' }
  },
  {
    path: '/infra-net',
    name: 'InfraNet',
    component: InfraNet,
    meta: { title: '容器网络', icon: 'Connection' }
  },
  {
    path: '/infra-store',
    name: 'InfraStore',
    component: InfraStore,
    meta: { title: '容器存储', icon: 'Files' }
  },
  {
    path: '/infra-sched',
    name: 'InfraSched',
    component: InfraSched,
    meta: { title: '弹性调度', icon: 'Operation' }
  },
  // 批次12新增：引擎层 7 个页面（替换原 Roadmap 占位，补充 name）
  {
    path: '/eng-storage',
    name: 'EngStorage',
    component: EngStorage,
    meta: { title: '统一存储', icon: 'FolderOpened' }
  },
  {
    path: '/eng-spark',
    name: 'EngSpark',
    component: EngSpark,
    meta: { title: '批计算（Spark）', icon: 'Histogram' }
  },
  {
    path: '/eng-flink',
    name: 'EngFlink',
    component: EngFlink,
    meta: { title: '流计算（Flink）', icon: 'DataLine' }
  },
  {
    path: '/eng-doris',
    name: 'EngDoris',
    component: EngDoris,
    meta: { title: 'OLAP（Doris）', icon: 'Grid' }
  },
  {
    path: '/eng-kafka',
    name: 'EngKafka',
    component: EngKafka,
    meta: { title: '消息流接入（Kafka）', icon: 'ChatLineSquare' }
  },
  {
    path: '/eng-iotdb',
    name: 'EngIotdb',
    component: EngIotdb,
    meta: { title: '时序引擎（IoTDB）', icon: 'Timer' }
  },
  {
    path: '/eng-mmg',
    name: 'EngMmg',
    component: EngMmg,
    meta: { title: '多模型引擎', icon: 'Box' }
  },
  // 批次12新增：治理/开发层 4 个页面（替换原 Roadmap 占位，补充 name）
  {
    path: '/govern-meta',
    name: 'GovernMeta',
    component: GovernMeta,
    meta: { title: '元数据管理', icon: 'Collection' }
  },
  {
    path: '/dev-sched',
    name: 'DevSched',
    component: DevSched,
    meta: { title: '调度编排', icon: 'Calendar' }
  },
  {
    path: '/dev-tag',
    name: 'DevTag',
    component: DevTag,
    meta: { title: '标签画像', icon: 'PriceTag' }
  },
  { path: '/dev-ml', name: 'DevMl', component: DevMl, meta: { title: '机器学习', icon: 'Cpu' } },
  {
    path: '/ops-tpl',
    name: 'TemplateMarket',
    component: TemplateMarket,
    meta: { title: '行业应用模板' }
  },
  {
    path: '/ops-portal',
    name: 'BusinessPortal',
    component: BusinessPortal,
    meta: { title: '业务线门户', icon: 'Grid' }
  },
  {
    path: '/ops-api',
    name: 'APIMarket',
    component: APIMarket,
    meta: { title: '开放 API', icon: 'Connection' }
  },
  {
    path: '/ops-flow',
    name: 'AssetMarket',
    component: AssetMarket,
    meta: { title: '数据资产流通', icon: 'ShoppingCart' }
  },

  // 兜底：独立 404 页（替代静默跳转 dashboard，用户可明确感知路径错误）
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在', public: true }
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
const PUBLIC_PATHS = new Set(['/login'])

/**
 * 鉴权守卫（纯函数）。
 *
 * @param to            目标路由
 * @param isAuthenticated 是否已登录（测试可注入）
 * @returns 放行 true，或重定向目标
 */
export function authGuard(
  to: RouteLocationNormalized,
  isAuthenticated: boolean
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
  return true
}

router.beforeEach((to) => {
  const authStore = useAuthStore()
  return authGuard(to, authStore.isAuthenticated)
})

export default router
