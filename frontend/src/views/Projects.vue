<template>
  <div>
    <h1>{{ t('projects.title') }}</h1>
    <div class="sub">{{ t('projects.subtitle', { workspace: '华东生产集群' }) }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">{{ t('projects.newProject') }}</button>
      <select>
        <option>{{ t('projects.allStatus') }}</option>
      </select>
      <div class="spacer"></div>
      <input style="width: 200px" :placeholder="t('projects.searchPlaceholder')" />
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">{{ t('common.loading') }}</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadProjects">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <tr>
          <th>{{ t('projects.cols.project') }}</th>
          <th>{{ t('projects.cols.domain') }}</th>
          <th>{{ t('projects.cols.datasets') }}</th>
          <th>{{ t('projects.cols.jobs') }}</th>
          <th>{{ t('projects.cols.owner') }}</th>
          <th>{{ t('projects.cols.status') }}</th>
        </tr>
        <tr v-for="p in projects" :key="p.id" class="click" @click="openDrawer(p)">
          <td>{{ p.name }}</td>
          <td>{{ p.domain }}</td>
          <td>{{ p.datasets }}</td>
          <td>{{ p.jobs }}</td>
          <td>{{ p.owner }}</td>
          <td>
            <span class="pill" :class="statusPillClass(p.status)">
              {{ statusPillText(p.status) }}
            </span>
          </td>
        </tr>
        <tr v-if="projects.length === 0">
          <td colspan="6" style="text-align: center; color: var(--muted)">
            {{ t('projects.empty') }}
          </td>
        </tr>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        {{ t('projects.drawerTitle', { name: current?.name }) }}
        <span class="pill g">{{ t('projects.running') }}</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">
          {{ t('projects.tabs.overview') }}
        </div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">
          {{ t('projects.tabs.datasets') }}
        </div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">
          {{ t('projects.tabs.jobs') }}
        </div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">
          {{ t('projects.tabs.members') }}
        </div>
        <div class="t" :class="{ on: tab === 4 }" @click="tab = 4">
          {{ t('projects.tabs.settings') }}
        </div>
      </div>
      <div v-if="tab === 0">
        <div class="kv">
          <span>{{ t('projects.overview.domain') }}</span>
          <span>{{ current?.domain }}</span>
        </div>
        <div class="kv">
          <span>{{ t('projects.overview.datasets') }}</span>
          <span>{{ current?.datasets }}</span>
        </div>
        <div class="kv">
          <span>{{ t('projects.overview.jobs') }}</span>
          <span>{{ current?.jobs }}</span>
        </div>
        <div class="kv">
          <span>{{ t('projects.overview.owner') }}</span>
          <span>{{ current?.owner }}</span>
        </div>
      </div>
      <div v-if="tab === 1">
        <div v-if="datasetsLoading" style="color: var(--muted)">
          {{ t('projects.datasets.loading') }}
        </div>
        <table v-else>
          <tr>
            <th>{{ t('projects.datasets.colName') }}</th>
            <th>{{ t('projects.datasets.colType') }}</th>
            <th>{{ t('projects.datasets.colFields') }}</th>
          </tr>
          <tr v-for="d in datasets" :key="d.name">
            <td>{{ d.name }}</td>
            <td>{{ d.type }}</td>
            <td>{{ d.fieldCount }}</td>
          </tr>
          <tr v-if="datasets.length === 0">
            <td colspan="3" style="text-align: center; color: var(--muted)">
              {{ t('projects.datasets.empty') }}
            </td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 2">
        <div v-if="jobsLoading" style="color: var(--muted)">{{ t('projects.jobs.loading') }}</div>
        <table v-else>
          <tr>
            <th>{{ t('projects.jobs.colName') }}</th>
            <th>{{ t('projects.jobs.colEngine') }}</th>
            <th>{{ t('projects.jobs.colStatus') }}</th>
          </tr>
          <tr v-for="j in projJobs" :key="j.name">
            <td>{{ j.name }}</td>
            <td>{{ j.engine }}</td>
            <td>
              <span
                class="pill"
                :class="j.status === 'running' ? 'a' : j.status === 'success' ? 'g' : 'r'"
              >
                {{
                  j.status === 'running'
                    ? t('projects.jobs.running')
                    : j.status === 'success'
                      ? t('projects.jobs.success')
                      : t('projects.jobs.failed')
                }}
              </span>
            </td>
          </tr>
          <tr v-if="projJobs.length === 0">
            <td colspan="3" style="text-align: center; color: var(--muted)">
              {{ t('projects.jobs.empty') }}
            </td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 3">
        <div v-if="membersLoading" style="color: var(--muted)">
          {{ t('projects.members.loading') }}
        </div>
        <table v-else>
          <tr>
            <th>{{ t('projects.members.colName') }}</th>
            <th>{{ t('projects.members.colRole') }}</th>
          </tr>
          <tr v-for="m in members" :key="m.name">
            <td>{{ m.name }}</td>
            <td>{{ m.role }}</td>
          </tr>
          <tr v-if="members.length === 0">
            <td colspan="2" style="text-align: center; color: var(--muted)">
              {{ t('projects.members.empty') }}
            </td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 4">
        <label>{{ t('projects.settings.name') }}</label>
        <input :value="current?.name" />
        <label>{{ t('projects.settings.description') }}</label>
        <textarea rows="3" :value="current?.description || ''"></textarea>
        <button
          class="btn sm"
          style="margin-top: 10px"
          @click="store.showToast(t('projects.settings.saveTodo'))"
        >
          {{ t('common.save') }}
        </button>
      </div>
    </Drawer>

    <Modal
      :visible="modalVisible"
      :title="t('projects.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('projects.createModal.name') }}</label>
      <input v-model="form.name" :placeholder="t('projects.createModal.namePlaceholder')" />
      <label>{{ t('projects.createModal.domain') }}</label>
      <input v-model="form.domain" :placeholder="t('projects.createModal.domainPlaceholder')" />
      <label>{{ t('projects.createModal.description') }}</label>
      <textarea v-model="form.description" rows="3"></textarea>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? t('projects.createModal.creating') : t('common.create') }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as projectApi from '@/api/project'
import type {
  Project,
  ProjectDataset,
  ProjectJob,
  ProjectMember,
  ProjectStatus
} from '@/api/project'
import type { PagedResult } from '@/api/types'

const { t } = useI18n()
const store = useAppStore()

// 项目列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: paged,
  loading,
  error,
  execute: loadProjects
} = useApi<PagedResult<Project>>(() => projectApi.listProjects({ page: 1, pageSize: 100 }))

// 项目列表（从 paged 中提取）
const projects = computed<Project[]>(() => paged.value?.list ?? [])

/** 状态 → pill 样式 */
function statusPillClass(s: ProjectStatus): string {
  switch (s) {
    case 'running':
      return 'g'
    case 'failed':
      return 'r'
    case 'stopped':
      return 'b'
    default:
      return 'a'
  }
}

/** 状态 → pill 文案 */
function statusPillText(s: ProjectStatus): string {
  switch (s) {
    case 'running':
      return t('projects.status.running')
    case 'stopped':
      return t('projects.status.stopped')
    case 'failed':
      return t('projects.status.failed')
    case 'creating':
      return t('projects.status.creating')
    default:
      return s
  }
}

const drawerVisible = ref(false)
const modalVisible = ref(false)
const tab = ref(0)
const current = ref<Project | null>(null)

// 项目详情：数据集、作业、成员 —— 通过 useApi 包装并行加载
const {
  data: detailData,
  loading: detailLoading,
  execute: loadDetail
} = useApi<[ProjectDataset[], ProjectJob[], ProjectMember[]], [string]>((id: string) =>
  Promise.all([
    projectApi.listDatasets(id).catch(() => [] as ProjectDataset[]),
    projectApi.listJobs(id).catch(() => [] as ProjectJob[]),
    projectApi.listMembers(id).catch(() => [] as ProjectMember[])
  ])
)

// 数据集列表
const datasets = computed<ProjectDataset[]>(() => detailData.value?.[0] ?? [])
// 项目作业列表
const projJobs = computed<ProjectJob[]>(() => detailData.value?.[1] ?? [])
// 成员列表
const members = computed<ProjectMember[]>(() => detailData.value?.[2] ?? [])
// 各 tab 的 loading 状态（统一由 detailLoading 控制）
const datasetsLoading = computed(() => detailLoading.value)
const jobsLoading = computed(() => detailLoading.value)
const membersLoading = computed(() => detailLoading.value)

/** 打开抽屉并加载详情 */
async function openDrawer(p: Project) {
  current.value = p
  tab.value = 0
  drawerVisible.value = true
  await loadDetail(p.id)
}

// 新建表单
const submitting = ref(false)
const form = reactive<{
  name: string
  domain: string
  description: string
}>({
  name: '',
  domain: '',
  description: ''
})

/** 提交创建项目 */
async function handleSubmit() {
  if (!form.name.trim()) {
    store.showToast(t('projects.createModal.nameRequired'))
    return
  }
  submitting.value = true
  try {
    await projectApi.createProject({
      name: form.name,
      domain: form.domain,
      description: form.description || undefined
    })
    modalVisible.value = false
    store.showToast(t('projects.createModal.created'))
    await loadProjects()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadProjects()
})
</script>
