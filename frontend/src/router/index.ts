import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

// 路由懒加载
const Dashboard = () => import('@/views/Dashboard.vue')
const Workspaces = () => import('@/views/Workspaces.vue')
const Projects = () => import('@/views/Projects.vue')
const Integrate = () => import('@/views/Integrate.vue')
const Develop = () => import('@/views/Develop.vue')
const Sql = () => import('@/views/Sql.vue')
const Govern = () => import('@/views/Govern.vue')
const Standard = () => import('@/views/Standard.vue')
const Quality = () => import('@/views/Quality.vue')
const Lineage = () => import('@/views/Lineage.vue')
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

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },

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
  { path: '/sec', name: 'sec', component: Sec },
  { path: '/vector', name: 'vector', component: Vector },
  { path: '/kb', name: 'kb', component: Kb },
  { path: '/llmops', name: 'llmops', component: Llmops },
  { path: '/gateway', name: 'gateway', component: Gateway },
  { path: '/analyze', name: 'analyze', component: Analyze },
  { path: '/ops', name: 'ops', component: Ops },
  { path: '/account', name: 'account', component: Account },
  { path: '/admin', name: 'admin', component: Admin },

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
  { path: '/ops-tpl', component: Roadmap, meta: { title: '行业应用模板' } },
  { path: '/ops-portal', component: Roadmap, meta: { title: '业务线门户' } },
  { path: '/ops-api', component: Roadmap, meta: { title: '开放 API' } },
  { path: '/ops-flow', component: Roadmap, meta: { title: '数据资产流通' } },

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

export default router