import { createRouter, createWebHistory } from 'vue-router'

// 路由配置 - 开放 API 服务目录前端
const routes = [
  {
    path: '/',
    redirect: '/catalog',
  },
  {
    path: '/catalog',
    name: 'Catalog',
    component: () => import('@/views/CatalogView.vue'),
    meta: { title: 'API 目录' },
  },
  {
    path: '/generate',
    name: 'Generate',
    component: () => import('@/views/GenerateView.vue'),
    meta: { title: '一键生成' },
  },
  {
    path: '/subscriptions',
    name: 'Subscriptions',
    component: () => import('@/views/SubscriptionsView.vue'),
    meta: { title: '订阅管理' },
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '用量看板' },
  },
  {
    path: '/api-detail/:id',
    name: 'ApiDetail',
    component: () => import('@/views/ApiDetailView.vue'),
    meta: { title: 'API 详情' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router