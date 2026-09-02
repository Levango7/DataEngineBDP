<template>
  <div>
    <h1>{{ t('workspaces.title') }}</h1>
    <div class="sub">{{ t('workspaces.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="openCreateModal">{{ t('workspaces.newWorkspace') }}</button>
      <input
        v-model="keyword"
        style="width: 220px"
        :placeholder="t('workspaces.searchPlaceholder')"
        @keyup.enter="reloadList"
      />
      <div class="spacer"></div>
      <span class="pill b">{{ t('workspaces.quotaIndependent') }}</span>
      <span class="pill p">{{ t('workspaces.networkIsolation') }}</span>
    </div>

    <!-- 工作空间列表：loading / error / empty / data 四态 -->
    <div class="grid g3">
      <template v-if="listLoading">
        <div v-for="i in 3" :key="`sk-${i}`" class="card">
          <b>{{ t('common.loading') }}</b>
          <div class="meta">{{ t('workspaces.listLoadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="listError">
        <div class="card" style="grid-column: span 3">
          <h3>{{ t('common.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ listError.message }}，
            <a href="javascript:void(0)" @click="reloadList">{{ t('common.retry') }}</a>
          </div>
        </div>
      </template>
      <template v-else-if="workspaces.length === 0">
        <div class="card" style="grid-column: span 3">
          <h3>{{ t('workspaces.emptyTitle') }}</h3>
          <div class="meta" style="color: var(--muted)">{{ t('workspaces.emptyHint') }}</div>
        </div>
      </template>
      <template v-else>
        <div v-for="ws in workspaces" :key="ws.id" class="card">
          <div class="row">
            <b>{{ ws.name }}</b>
            <span class="pill" :class="statusPillClass(ws.status)">
              {{ statusPillText(ws.status) }}
            </span>
          </div>
          <div class="meta">{{ planLabel(ws.plan) }} · {{ ws.tenantName || t('workspaces.defaultTenant') }}</div>
          <div class="row" style="margin-top: 8px">
            <span>CPU {{ ws.cpuUsage }}%</span>
            <span>{{ t('workspaces.memory') }} {{ ws.memUsage }}%</span>
          </div>
          <div class="bar"><i :style="{ width: ws.cpuUsage + '%' }"></i></div>
          <button class="btn ghost sm" style="margin-top: 10px" @click="openDrawer(ws)">
            {{ t('workspaces.viewDetail') }}
          </button>
        </div>
      </template>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        {{ t('workspaces.drawerTitle', { name: current?.name }) }}
        <span class="pill g">{{ t('workspaces.running') }}</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">
          {{ t('workspaces.tabs.overview') }}
        </div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">
          {{ t('workspaces.tabs.members') }}
        </div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">
          {{ t('workspaces.tabs.quota') }}
        </div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">
          {{ t('workspaces.tabs.projects') }}
        </div>
      </div>
      <div v-if="tab === 0">
        <div class="kv">
          <span>{{ t('workspaces.overview.tenant') }}</span>
          <span>{{ current?.tenantName || t('workspaces.overview.defaultTenantA') }}</span>
        </div>
        <div class="kv">
          <span>{{ t('workspaces.overview.plan') }}</span>
          <span>{{ planLabel(current?.plan) }}</span>
        </div>
        <div class="kv">
          <span>{{ t('workspaces.overview.env') }}</span>
          <span>{{ envLabel(current?.env) }}</span>
        </div>
        <div class="kv">
          <span>{{ t('workspaces.overview.createdAt') }}</span>
          <span>{{ current?.createdAt || '--' }}</span>
        </div>
        <div class="note">{{ t('workspaces.overview.mappingNote') }}</div>
      </div>
      <div v-if="tab === 1">
        <!-- K8s Namespace 实时状态（真实 API；成员管理属规划能力，不造假数据） -->
        <div class="kv">
          <span>{{ t('workspaces.members.k8sStatus') }}</span>
          <span v-if="k8sLoading">{{ t('workspaces.members.querying') }}</span>
          <template v-else-if="k8sError">
            <span style="color: var(--red)">
              {{ k8sError.message }}，
              <a href="javascript:void(0)" @click="loadK8sStatus">{{ t('common.retry') }}</a>
            </span>
          </template>
          <span v-else-if="k8sStatus">
            <span class="pill" :class="k8sStatus.status === 'Active' ? 'g' : 'a'">
              {{ k8sStatus.status }}
            </span>
          </span>
        </div>
        <div class="note" style="margin-top: 8px">{{ t('workspaces.members.maintainNote') }}</div>
        <div class="note" style="margin-top: 4px">{{ t('workspaces.members.planNote') }}</div>
      </div>
      <div v-if="tab === 2">
        <div class="row">
          <span>CPU</span>
          <span>{{ current?.cpuUsage ?? 0 }}%</span>
        </div>
        <div class="bar"><i :style="{ width: (current?.cpuUsage || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px">
          <span>{{ t('workspaces.memory') }}</span>
          <span>{{ current?.memUsage ?? 0 }}%</span>
        </div>
        <div class="bar"><i class="a" :style="{ width: (current?.memUsage || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px">
          <span>{{ t('workspaces.quota.storage') }}</span>
          <span>{{ current?.storageUsage ?? 0 }}%</span>
        </div>
        <div class="bar"><i :style="{ width: (current?.storageUsage || 0) + '%' }"></i></div>
      </div>
      <div v-if="tab === 3">
        <!-- 平台项目（真实 API；按当前空间所属租户过滤，无数据显示空态） -->
        <div v-if="projectsLoading" class="meta">{{ t('workspaces.projects.loading') }}</div>
        <div v-else-if="projectsError" class="meta" style="color: var(--red)">
          {{ projectsError.message }}，
          <a href="javascript:void(0)" @click="loadProjects">{{ t('common.retry') }}</a>
        </div>
        <table v-else-if="tenantProjects.length">
          <tr>
            <th>{{ t('workspaces.projects.colProject') }}</th>
            <th>{{ t('workspaces.projects.colStatus') }}</th>
          </tr>
          <tr v-for="p in tenantProjects" :key="p.id">
            <td>{{ p.name }}</td>
            <td>
              <span
                class="pill"
                :class="p.status === 'running' ? 'g' : p.status === 'failed' ? 'r' : 'a'"
              >
                {{ p.status }}
              </span>
            </td>
          </tr>
        </table>
        <div v-else class="meta">{{ t('workspaces.projects.empty') }}</div>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" :title="t('workspaces.createModal.title')" @close="modalVisible = false">
      <label>{{ t('workspaces.createModal.name') }}</label>
      <input v-model="form.name" :placeholder="t('workspaces.createModal.namePlaceholder')" />
      <label>{{ t('workspaces.createModal.tenant') }}</label>
      <select v-model="form.tenantId">
        <option value="t-external">{{ t('workspaces.createModal.tenantExternal') }}</option>
        <option value="t-internal">{{ t('workspaces.createModal.tenantInternal') }}</option>
      </select>
      <label>{{ t('workspaces.createModal.plan') }}</label>
      <select v-model="form.plan">
        <option value="standard">{{ t('workspaces.plans.standard') }}</option>
        <option value="enterprise">{{ t('workspaces.plans.enterprise') }}</option>
        <option value="flagship">{{ t('workspaces.plans.flagship') }}</option>
      </select>
      <label>{{ t('workspaces.createModal.env') }}</label>
      <select v-model="form.env">
        <option value="xinchuang">{{ t('workspaces.envs.xinchuang') }}</option>
        <option value="onprem">{{ t('workspaces.envs.onprem') }}</option>
        <option value="public-cloud">{{ t('workspaces.envs.publicCloud') }}</option>
        <option value="private-cloud">{{ t('workspaces.envs.privateCloud') }}</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="creating" @click="handleCreate">
          {{ creating ? t('workspaces.createModal.creating') : t('common.create') }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as workspaceApi from '@/api/workspace'
import * as projectApi from '@/api/project'
import type {
  Workspace,
  PlanTier,
  DeployEnv,
  WorkspaceStatus,
  WorkspaceK8sStatus
} from '@/api/types'
import type { Project } from '@/api/project'

const { t } = useI18n()
const store = useAppStore()

// 列表数据：通过 useApi 包装 listWorkspaces 调用
const keyword = ref('')
const {
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

const WS_STATUS_CLS: Record<WorkspaceStatus, string> = {
  running: 'g',
  limited: 'a',
  stopped: 'r',
  creating: '',
  failed: ''
}

function statusPillClass(status: WorkspaceStatus): string {
  return WS_STATUS_CLS[status] ?? ''
}

function statusPillText(status: WorkspaceStatus): string {
  return status in WS_STATUS_CLS ? t(`workspaces.status.${status}`) : status
}

const PLAN_TIERS: PlanTier[] = ['standard', 'enterprise', 'flagship', 'internal']

function planLabel(plan?: PlanTier): string {
  return plan && PLAN_TIERS.includes(plan) ? t(`workspaces.plans.${plan}`) : '--'
}

const ENV_KEY_MAP: Record<DeployEnv, string> = {
  xinchuang: 'xinchuang',
  onprem: 'onprem',
  'public-cloud': 'publicCloud',
  'private-cloud': 'privateCloud'
}

function envLabel(env?: DeployEnv): string {
  return env ? t(`workspaces.envs.${ENV_KEY_MAP[env]}`) : '--'
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const drawerVisible = ref(false)
const tab = ref(0)
const current = ref<Workspace | null>(null)

// 成员 tab：K8s Namespace 实时状态（真实 API）
const k8sStatus = ref<WorkspaceK8sStatus | null>(null)
const k8sLoading = ref(false)
const k8sError = ref<{ message: string } | null>(null)

async function loadK8sStatus(): Promise<void> {
  if (!current.value) return
  k8sLoading.value = true
  k8sError.value = null
  try {
    k8sStatus.value = await workspaceApi.getWorkspaceK8sStatus(current.value.id)
  } catch (e) {
    k8sStatus.value = null
    k8sError.value = { message: e instanceof Error ? e.message : t('workspaces.members.k8sQueryFailed') }
  } finally {
    k8sLoading.value = false
  }
}

// 项目 tab：平台项目列表（真实 API；按当前空间租户过滤）
const projects = ref<Project[]>([])
const projectsLoading = ref(false)
const projectsError = ref<{ message: string } | null>(null)

async function loadProjects(): Promise<void> {
  projectsLoading.value = true
  projectsError.value = null
  try {
    const res = await projectApi.listProjects({ page: 1, pageSize: 100 })
    projects.value = res.list
  } catch (e) {
    projects.value = []
    projectsError.value = { message: e instanceof Error ? e.message : t('workspaces.projects.loadFailed') }
  } finally {
    projectsLoading.value = false
  }
}

/** 项目列表（后端项目模型暂无租户字段，展示全平台项目） */
const tenantProjects = computed(() => projects.value)

function openDrawer(ws: Workspace) {
  current.value = ws
  tab.value = 0
  drawerVisible.value = true
  void loadK8sStatus()
  void loadProjects()
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
    store.showToast(t('workspaces.createModal.nameRequired'))
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
    store.showToast(t('workspaces.createModal.created'))
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
