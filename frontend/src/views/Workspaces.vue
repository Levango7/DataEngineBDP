<template>
  <div>
    <h1>工作空间</h1>
    <div class="sub">
      顶层隔离边界，底层基于自研 SKE 发行版自动映射为 Namespace + 配额 + 网络策略，客户无需关心容器与编排。
    </div>
    <div class="toolbar">
      <button class="btn sm" @click="openCreateModal">+ 新建工作空间</button>
      <input style="width: 220px" placeholder="搜索…" v-model="keyword" @keyup.enter="reloadList" />
      <div class="spacer"></div>
      <span class="pill b">配额独立</span>
      <span class="pill p">网络隔离</span>
    </div>

    <!-- 工作空间列表：loading / error / empty / data 四态 -->
    <div class="grid g3">
      <template v-if="listLoading">
        <div class="card" v-for="i in 3" :key="`sk-${i}`">
          <b>加载中…</b>
          <div class="meta">正在拉取工作空间列表</div>
        </div>
      </template>
      <template v-else-if="listError">
        <div class="card" style="grid-column: span 3">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ listError.message }}，<a href="javascript:void(0)" @click="reloadList">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="workspaces.length === 0">
        <div class="card" style="grid-column: span 3">
          <h3>暂无工作空间</h3>
          <div class="meta" style="color: var(--muted)">
            当前租户下还没有工作空间，点击右上角「+ 新建工作空间」创建第一个。
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card" v-for="ws in workspaces" :key="ws.id">
          <div class="row">
            <b>{{ ws.name }}</b>
            <span class="pill" :class="statusPillClass(ws.status)">{{ statusPillText(ws.status) }}</span>
          </div>
          <div class="meta">{{ planLabel(ws.plan) }} · {{ ws.tenantName || '默认租户' }}</div>
          <div class="row" style="margin-top: 8px">
            <span>CPU {{ ws.cpuUsage }}%</span>
            <span>内存 {{ ws.memUsage }}%</span>
          </div>
          <div class="bar"><i :style="{ width: ws.cpuUsage + '%' }"></i></div>
          <button class="btn ghost sm" style="margin-top: 10px" @click="openDrawer(ws)">查看详情</button>
        </div>
      </template>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        工作空间：{{ current?.name }}
        <span class="pill g">运行中</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">概览</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">成员</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">配额</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">项目</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>租户</span><span>{{ current?.tenantName || '外部客户A' }}</span></div>
        <div class="kv"><span>套餐</span><span>{{ planLabel(current?.plan) }}</span></div>
        <div class="kv"><span>环境</span><span>{{ envLabel(current?.env) }}</span></div>
        <div class="kv"><span>创建时间</span><span>{{ current?.createdAt || '--' }}</span></div>
        <div class="note">底层自动映射为 Namespace + ResourceQuota + NetworkPolicy(deny-all)。</div>
      </div>
      <div v-if="tab === 1">
        <table>
          <tr><th>成员</th><th>角色</th></tr>
          <tr><td>张工</td><td>空间管理员</td></tr>
          <tr><td>李工</td><td>开发</td></tr>
          <tr><td>王工</td><td>开发</td></tr>
        </table>
        <button class="btn ghost sm" style="margin-top: 8px" @click="store.showToast('已邀请成员（mock）（待接入）')">+ 邀请</button>
      </div>
      <div v-if="tab === 2">
        <div class="row"><span>CPU</span><span>{{ current?.cpuUsage ?? 0 }}%</span></div>
        <div class="bar"><i :style="{ width: (current?.cpuUsage || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px"><span>内存</span><span>{{ current?.memUsage ?? 0 }}%</span></div>
        <div class="bar"><i class="a" :style="{ width: (current?.memUsage || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px"><span>存储</span><span>{{ current?.storageUsage ?? 0 }}%</span></div>
        <div class="bar"><i :style="{ width: (current?.storageUsage || 0) + '%' }"></i></div>
      </div>
      <div v-if="tab === 3">
        <table>
          <tr><th>项目</th><th>状态</th></tr>
          <tr><td>交易域</td><td><span class="pill g">运行中</span></td></tr>
          <tr><td>营销域</td><td><span class="pill g">运行中</span></td></tr>
          <tr><td>风控域</td><td><span class="pill a">运行中</span></td></tr>
        </table>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="新建工作空间" @close="modalVisible = false">
      <label>名称</label><input v-model="form.name" placeholder="如 华南生产集群" />
      <label>租户</label>
      <select v-model="form.tenantId"><option value="t-external">外部客户A</option><option value="t-internal">内部业务线</option></select>
      <label>套餐</label>
      <select v-model="form.plan"><option value="standard">标准版</option><option value="enterprise">企业版</option><option value="flagship">旗舰版</option></select>
      <label>环境</label>
      <select v-model="form.env"><option value="xinchuang">信创</option><option value="onprem">本地数据中心</option><option value="public-cloud">公有云 VM</option><option value="private-cloud">私有云</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="creating" @click="handleCreate">{{ creating ? '创建中…' : '创建' }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as workspaceApi from '@/api/workspace'
import type { Workspace, PlanTier, DeployEnv, WorkspaceStatus } from '@/api/types'

const store = useAppStore()

// 列表数据：通过 useApi 包装 listWorkspaces 调用
const keyword = ref('')
const {
  data: paged,
  loading: listLoading,
  error: listError,
  execute: loadList
} = useApi(() => workspaceApi.listWorkspaces({ keyword: keyword.value || undefined }))

// 当前页工作空间列表
const workspaces = ref<Workspace[]>([])

// 重新拉取列表
async function reloadList() {
  const result = await loadList()
  workspaces.value = result?.list ?? []
}

/* ------------------------------ 状态映射辅助 ------------------------------ */

const WS_STATUS_MAP: Record<WorkspaceStatus, { cls: string; text: string }> = {
  running: { cls: 'g', text: '运行中' },
  limited: { cls: 'a', text: '受限' },
  stopped: { cls: 'r', text: '已停止' },
  creating: { cls: '', text: '创建中' },
  failed: { cls: '', text: '失败' },
}

function statusPillClass(status: WorkspaceStatus): string {
  return WS_STATUS_MAP[status]?.cls ?? ''
}

function statusPillText(status: WorkspaceStatus): string {
  return WS_STATUS_MAP[status]?.text ?? status
}

const PLAN_LABELS: Record<PlanTier, string> = {
  standard: '标准版',
  enterprise: '企业版',
  flagship: '旗舰版',
  internal: '内部无限',
}

function planLabel(plan?: PlanTier): string {
  return plan ? (PLAN_LABELS[plan] ?? '--') : '--'
}

const ENV_LABELS: Record<DeployEnv, string> = {
  xinchuang: '信创',
  onprem: '本地数据中心',
  'public-cloud': '公有云 VM',
  'private-cloud': '私有云',
}

function envLabel(env?: DeployEnv): string {
  return env ? (ENV_LABELS[env] ?? '--') : '--'
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const drawerVisible = ref(false)
const tab = ref(0)
const current = ref<Workspace | null>(null)

function openDrawer(ws: Workspace) {
  current.value = ws
  tab.value = 0
  drawerVisible.value = true
}

/* ------------------------------ 新建工作空间 ------------------------------ */

const modalVisible = ref(false)
const creating = ref(false)

/** 新建表单 */
const form = ref<{
  name: string
  tenantId: string
  plan: PlanTier
  env: DeployEnv
}>({
  name: '',
  tenantId: 't-external',
  plan: 'enterprise',
  env: 'xinchuang'
})

function openCreateModal() {
  form.value = { name: '', tenantId: 't-external', plan: 'enterprise', env: 'xinchuang' }
  modalVisible.value = true
}

/** 提交创建工作空间 */
async function handleCreate() {
  if (!form.value.name.trim()) {
    store.showToast('请填写工作空间名称')
    return
  }
  creating.value = true
  try {
    await workspaceApi.createWorkspace({
      name: form.value.name.trim(),
      tenantId: form.value.tenantId,
      plan: form.value.plan,
      env: form.value.env
    })
    modalVisible.value = false
    store.showToast('工作空间已创建')
    // 刷新列表
    await reloadList()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void reloadList()
})
</script>
