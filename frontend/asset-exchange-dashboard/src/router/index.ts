import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/Dashboard.vue'
import AssetList from '@/views/AssetList.vue'
import RegisterForm from '@/views/RegisterForm.vue'
import SettlementList from '@/views/SettlementList.vue'
import AuditLogs from '@/views/AuditLogs.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard, meta: { title: '流通看板' } },
    { path: '/assets', name: 'assets', component: AssetList, meta: { title: '资产市场' } },
    { path: '/register', name: 'register', component: RegisterForm, meta: { title: '资产登记' } },
    { path: '/settlements', name: 'settlements', component: SettlementList, meta: { title: '结算分账' } },
    { path: '/audit-logs', name: 'audit-logs', component: AuditLogs, meta: { title: '审计日志' } },
  ],
})

export default router