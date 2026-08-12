import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

// 路由配置：微调过程监控
const LoopTasks = () => import('@/views/LoopTasks.vue')
const LoopTaskDetail = () => import('@/views/LoopTaskDetail.vue')
const VersionManagement = () => import('@/views/VersionManagement.vue')
const DeploymentManagement = () => import('@/views/DeploymentManagement.vue')

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/loop-tasks' },
  {
    path: '/loop-tasks',
    name: 'LoopTasks',
    component: LoopTasks,
    meta: { title: '闭环任务列表' }
  },
  {
    path: '/loop-tasks/:taskId',
    name: 'LoopTaskDetail',
    component: LoopTaskDetail,
    meta: { title: '闭环任务详情' }
  },
  {
    path: '/versions',
    name: 'VersionManagement',
    component: VersionManagement,
    meta: { title: '版本管理' }
  },
  {
    path: '/deployments',
    name: 'DeploymentManagement',
    component: DeploymentManagement,
    meta: { title: '部署管理' }
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