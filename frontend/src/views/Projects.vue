<template>
  <div>
    <h1>数据项目</h1>
    <div class="sub">工作空间：华东生产集群 ｜ 项目是数据加工与消费的组织单元。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建项目</button>
      <select><option>全部状态</option></select>
      <div class="spacer"></div>
      <input style="width: 200px" placeholder="搜索项目…" />
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，<a href="javascript:void(0)" @click="loadProjects">重试</a>
      </div>
      <table v-else>
        <tr><th>项目</th><th>域</th><th>数据集</th><th>作业</th><th>负责人</th><th>状态</th></tr>
        <tr class="click" v-for="p in projects" :key="p.id" @click="openDrawer(p)">
          <td>{{ p.name }}</td>
          <td>{{ p.domain }}</td>
          <td>{{ p.datasets }}</td>
          <td>{{ p.jobs }}</td>
          <td>{{ p.owner }}</td>
          <td><span class="pill" :class="statusPillClass(p.status)">{{ statusPillText(p.status) }}</span></td>
        </tr>
        <tr v-if="projects.length === 0">
          <td colspan="6" style="text-align: center; color: var(--muted)">暂无项目</td>
        </tr>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        数据项目：{{ current?.name }}
        <span class="pill g">运行中</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">概览</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">数据集</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">作业</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">成员</div>
        <div class="t" :class="{ on: tab === 4 }" @click="tab = 4">设置</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>业务域</span><span>{{ current?.domain }}</span></div>
        <div class="kv"><span>数据集</span><span>{{ current?.datasets }}</span></div>
        <div class="kv"><span>作业</span><span>{{ current?.jobs }}</span></div>
        <div class="kv"><span>负责人</span><span>{{ current?.owner }}</span></div>
      </div>
      <div v-if="tab === 1">
        <div v-if="datasetsLoading" style="color: var(--muted)">加载…</div>
        <table v-else>
          <tr><th>数据集</th><th>类型</th><th>字段</th></tr>
          <tr v-for="d in datasets" :key="d.name">
            <td>{{ d.name }}</td>
            <td>{{ d.type }}</td>
            <td>{{ d.fieldCount }}</td>
          </tr>
          <tr v-if="datasets.length === 0">
            <td colspan="3" style="text-align: center; color: var(--muted)">暂无数据集</td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 2">
        <div v-if="jobsLoading" style="color: var(--muted)">加载…</div>
        <table v-else>
          <tr><th>作业</th><th>引擎</th><th>状态</th></tr>
          <tr v-for="j in projJobs" :key="j.name">
            <td>{{ j.name }}</td>
            <td>{{ j.engine }}</td>
            <td><span class="pill" :class="j.status === 'running' ? 'a' : j.status === 'success' ? 'g' : 'r'">{{ j.status === 'running' ? '运行中' : j.status === 'success' ? '成功' : '失败' }}</span></td>
          </tr>
          <tr v-if="projJobs.length === 0">
            <td colspan="3" style="text-align: center; color: var(--muted)">暂无作业</td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 3">
        <div v-if="membersLoading" style="color: var(--muted)">加载…</div>
        <table v-else>
          <tr><th>成员</th><th>角色</th></tr>
          <tr v-for="m in members" :key="m.name">
            <td>{{ m.name }}</td>
            <td>{{ m.role }}</td>
          </tr>
          <tr v-if="members.length === 0">
            <td colspan="2" style="text-align: center; color: var(--muted)">暂无成员</td>
          </tr>
        </table>
      </div>
      <div v-if="tab === 4">
        <label>项目名</label><input :value="current?.name" />
        <label>描述</label><textarea rows="3" :value="current?.description || ''"></textarea>
        <button class="btn sm" style="margin-top: 10px" @click="store.showToast('已保存（待接入）')">保存</button>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="新建数据项目" @close="modalVisible = false">
      <label>项目名</label><input v-model="form.name" placeholder="如 供应链域" />
      <label>业务域</label><input v-model="form.domain" placeholder="运营" />
      <label>描述</label><textarea rows="3" v-model="form.description"></textarea>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">{{ submitting ? '创建中…' : '创建' }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as projectApi from '@/api/project'
import type { Project, ProjectDataset, ProjectJob, ProjectMember, ProjectStatus } from '@/api/project'
import type { PagedResult } from '@/api/types'

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
      return '运行中'
    case 'stopped':
      return '已停止'
    case 'failed':
      return '异常'
    case 'creating':
      return '创建中'
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
} = useApi<[ProjectDataset[], ProjectJob[], ProjectMember[]], [string]>(
  (id: string) =>
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
    store.showToast('请填写项目名')
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
    store.showToast('数据项目已创建')
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
