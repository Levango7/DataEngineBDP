import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const FinOpsDashboard = () => import('@/views/FinOpsDashboard.vue')
const Top10Resources = () => import('@/views/Top10Resources.vue')
const CostTrend = () => import('@/views/CostTrend.vue')
const CostDetails = () => import('@/views/CostDetails.vue')
const IdleResources = () => import('@/views/IdleResources.vue')
const OptimizationSuggestions = () => import('@/views/OptimizationSuggestions.vue')
const BillExport = () => import('@/views/BillExport.vue')
const AllocationConfig = () => import('@/views/AllocationConfig.vue')

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'FinOpsDashboard',
    component: FinOpsDashboard,
    meta: { title: 'FinOps 看板总览' }
  },
  {
    path: '/top10',
    name: 'Top10Resources',
    component: Top10Resources,
    meta: { title: 'Top10 成本资源' }
  },
  {
    path: '/trend',
    name: 'CostTrend',
    component: CostTrend,
    meta: { title: '成本趋势' }
  },
  {
    path: '/details',
    name: 'CostDetails',
    component: CostDetails,
    meta: { title: '成本明细' }
  },
  {
    path: '/idle',
    name: 'IdleResources',
    component: IdleResources,
    meta: { title: '闲置清单' }
  },
  {
    path: '/suggestions',
    name: 'OptimizationSuggestions',
    component: OptimizationSuggestions,
    meta: { title: '优化建议' }
  },
  {
    path: '/bill-export',
    name: 'BillExport',
    component: BillExport,
    meta: { title: '账单导出' }
  },
  {
    path: '/allocation',
    name: 'AllocationConfig',
    component: AllocationConfig,
    meta: { title: '分账配置' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

export default router