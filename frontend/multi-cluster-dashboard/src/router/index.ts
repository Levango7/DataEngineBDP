import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/Dashboard.vue'
import OverridePolicy from '@/views/OverridePolicy.vue'
import FailoverHistory from '@/views/FailoverHistory.vue'
import ReplicaPlans from '@/views/ReplicaPlans.vue'
import FailoverPolicies from '@/views/FailoverPolicies.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard, meta: { title: '集群健康看板' } },
    { path: '/override-policies', name: 'override-policies', component: OverridePolicy, meta: { title: 'OverridePolicy 管理' } },
    { path: '/failover-history', name: 'failover-history', component: FailoverHistory, meta: { title: '迁移历史' } },
    { path: '/replica-plans', name: 'replica-plans', component: ReplicaPlans, meta: { title: '副本权重分配' } },
    { path: '/failover-policies', name: 'failover-policies', component: FailoverPolicies, meta: { title: '故障迁移策略' } },
  ],
})

export default router