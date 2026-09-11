<template>
  <div>
    <PageHeader
      :title="t('projects.title')"
      :subtitle="t('projects.subtitle', { workspace: store.workspace })"
    />
    <Toolbar
      v-model:search-value="searchKeyword"
      :show-create="true"
      :create-label="t('projects.newProject')"
      :create-aria-label="t('projects.newProject')"
      :search-placeholder="t('projects.searchPlaceholder')"
      :search-aria-label="t('projects.searchPlaceholder')"
      :show-refresh="false"
      @create="modalVisible = true"
    >
      <template #filters>
        <el-select :placeholder="t('projects.allStatus')" disabled>
          <el-option :label="t('projects.allStatus')" value="" />
        </el-select>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="loading" class="muted-text">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-text">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadProjects">{{ t('common.retry') }}</a>
      </div>
      <el-table v-else :data="projects" stripe @row-click="openDrawer">
        <el-table-column :label="t('projects.cols.project')" prop="name" />
        <el-table-column :label="t('projects.cols.domain')" prop="domain" />
        <el-table-column :label="t('projects.cols.datasets')" prop="datasets" />
        <el-table-column :label="t('projects.cols.jobs')" prop="jobs" />
        <el-table-column :label="t('projects.cols.owner')" prop="owner" />
        <el-table-column :label="t('projects.cols.status')">
          <template #default="{ row }">
            <span class="pill" :class="statusPillClass(row.status)">
              {{ statusPillText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ t('projects.empty') }}</div>
        </template>
      </el-table>
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
        <div v-if="datasetsLoading" class="muted-text">
          {{ t('projects.datasets.loading') }}
        </div>
        <el-table v-else :data="datasets" stripe>
          <el-table-column :label="t('projects.datasets.colName')" prop="name" />
          <el-table-column :label="t('projects.datasets.colType')" prop="type" />
          <el-table-column :label="t('projects.datasets.colFields')" prop="fieldCount" />
          <template #empty>
            <div class="empty-cell">{{ t('projects.datasets.empty') }}</div>
          </template>
        </el-table>
      </div>
      <div v-if="tab === 2">
        <div v-if="jobsLoading" class="muted-text">{{ t('projects.jobs.loading') }}</div>
        <el-table v-else :data="projJobs" stripe>
          <el-table-column :label="t('projects.jobs.colName')" prop="name" />
          <el-table-column :label="t('projects.jobs.colEngine')" prop="engine" />
          <el-table-column :label="t('projects.jobs.colStatus')">
            <template #default="{ row }">
              <span
                class="pill"
                :class="row.status === 'running' ? 'a' : row.status === 'success' ? 'g' : 'r'"
              >
                {{
                  row.status === 'running'
                    ? t('projects.jobs.running')
                    : row.status === 'success'
                      ? t('projects.jobs.success')
                      : t('projects.jobs.failed')
                }}
              </span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-cell">{{ t('projects.jobs.empty') }}</div>
          </template>
        </el-table>
      </div>
      <div v-if="tab === 3">
        <div v-if="membersLoading" class="muted-text">
          {{ t('projects.members.loading') }}
        </div>
        <el-table v-else :data="members" stripe>
          <el-table-column :label="t('projects.members.colName')" prop="name" />
          <el-table-column :label="t('projects.members.colRole')" prop="role" />
          <template #empty>
            <div class="empty-cell">{{ t('projects.members.empty') }}</div>
          </template>
        </el-table>
      </div>
      <div v-if="tab === 4">
        <label>{{ t('projects.settings.name') }}</label>
        <el-input :model-value="current?.name" disabled />
        <label>{{ t('projects.settings.description') }}</label>
        <el-input type="textarea" :rows="3" :model-value="current?.description || ''" disabled />
        <el-button
          size="small"
          style="margin-top: 10px"
          @click="store.showToast(t('projects.settings.saveTodo'))"
        >
          {{ t('common.save') }}
        </el-button>
      </div>
    </Drawer>

    <Modal
      :visible="modalVisible"
      :title="t('projects.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('projects.createModal.name') }}</label>
      <el-input v-model="form.name" :placeholder="t('projects.createModal.namePlaceholder')" />
      <label>{{ t('projects.createModal.domain') }}</label>
      <el-input v-model="form.domain" :placeholder="t('projects.createModal.domainPlaceholder')" />
      <label>{{ t('projects.createModal.description') }}</label>
      <el-input v-model="form.description" type="textarea" :rows="3" />
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? t('projects.createModal.creating') : t('common.create') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
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
const searchKeyword = ref('')

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
  // 加载租户信息填充 workspace（替代原硬编码业务参数）
  void store.fetchTenantInfo()
  void loadProjects()
})
</script>

<style scoped>
/* 通用文字色（使用 design tokens） */
.muted-text {
  color: var(--ds-text-tertiary);
}
.error-text {
  color: var(--ds-color-error-600);
}
.error-text a {
  color: var(--ds-color-primary-600);
  cursor: pointer;
}

/* 空状态单元格 */
.empty-cell {
  text-align: center;
  color: var(--ds-text-tertiary);
  padding: 16px;
}

/* 响应式断点：中等屏幕紧凑化 */
@media (max-width: 1024px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-sm);
  }
}

/* 响应式断点：小屏幕进一步紧凑 */
@media (max-width: 640px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-xs);
  }
  :deep(.el-table .cell) {
    padding-left: 8px;
    padding-right: 8px;
  }
  .tabbar {
    flex-wrap: wrap;
  }
}
</style>
