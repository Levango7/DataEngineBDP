<template>
  <div class="override-policy">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">集群健康看板</el-menu-item>
          <el-menu-item index="/override-policies">OverridePolicy 管理</el-menu-item>
          <el-menu-item index="/failover-history">迁移历史</el-menu-item>
          <el-menu-item index="/replica-plans">副本权重分配</el-menu-item>
          <el-menu-item index="/failover-policies">故障迁移策略</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <div class="page-container">
          <div class="card">
            <div class="toolbar">
              <div class="card-title">OverridePolicy 集群本地化配置</div>
              <el-button type="primary" @click="showCreateDialog = true">新建策略</el-button>
            </div>
            <el-table :data="policies" stripe style="width: 100%">
              <el-table-column prop="name" label="策略名" min-width="180" />
              <el-table-column prop="namespace" label="命名空间" width="120" />
              <el-table-column label="覆盖规则数" width="110">
                <template #default="{ row }">{{ countOverrideRules(row.spec) }}</template>
              </el-table-column>
              <el-table-column label="目标集群" min-width="200">
                <template #default="{ row }">{{ formatTargetClusters(row.spec) }}</template>
              </el-table-column>
              <el-table-column label="创建时间" width="180">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="180" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="viewPolicy(row)">查看</el-button>
                  <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 创建对话框 -->
        <el-dialog v-model="showCreateDialog" title="新建 OverridePolicy" width="700px">
          <el-form :model="createForm" label-width="140px">
            <el-form-item label="策略名" required>
              <el-input v-model="createForm.name" placeholder="如 xinchang-image-override" />
            </el-form-item>
            <el-form-item label="命名空间" required>
              <el-input v-model="createForm.namespace" placeholder="default" />
            </el-form-item>
            <el-form-item label="目标集群" required>
              <el-input v-model="targetClusterInput" placeholder="xinchang-cluster" />
            </el-form-item>
            <el-form-item label="镜像 Registry">
              <el-input v-model="createForm.registry" placeholder="registry.kylin.local" />
            </el-form-item>
            <el-form-item label="镜像 Tag">
              <el-input v-model="createForm.tag" placeholder="arm64-v3.5.0" />
            </el-form-item>
            <el-form-item label="环境变量">
              <div v-for="(env, i) in createForm.envs" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px;">
                <el-input v-model="env.name" placeholder="变量名" style="width: 200px;" />
                <el-input v-model="env.value" placeholder="变量值" style="width: 200px;" />
                <el-button type="danger" @click="createForm.envs.splice(i, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="createForm.envs.push({ name: '', value: '' })">添加环境变量</el-button>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showCreateDialog = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建</el-button>
          </template>
        </el-dialog>

        <!-- 查看对话框 -->
        <el-dialog v-model="showViewDialog" title="OverridePolicy 详情" width="700px">
          <pre v-if="viewingPolicy" style="background: #f5f5f5; padding: 16px; border-radius: 4px; overflow: auto;">{{ formatSpec(viewingPolicy.spec) }}</pre>
        </el-dialog>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listOverridePolicies,
  createOverridePolicy,
  deleteOverridePolicy,
  type OverridePolicy,
  type OverridePolicySpec,
} from '@/api/multiCluster'

const route = useRoute()
const activeMenu = ref(route.path)

const policies = ref<OverridePolicy[]>([])
const showCreateDialog = ref(false)
const showViewDialog = ref(false)
const viewingPolicy = ref<OverridePolicy | null>(null)
const targetClusterInput = ref('xinchang-cluster')

const createForm = ref({
  name: '',
  namespace: 'default',
  registry: '',
  tag: '',
  envs: [] as Array<{ name: string; value: string }>,
})

function formatTime(t: string): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function countOverrideRules(specJson: string): number {
  try {
    const spec = JSON.parse(specJson)
    return spec.overrideRules?.length || 0
  } catch {
    return 0
  }
}

function formatTargetClusters(specJson: string): string {
  try {
    const spec = JSON.parse(specJson) as OverridePolicySpec
    const clusters: string[] = []
    for (const rule of spec.overrideRules || []) {
      if (rule.targetCluster?.clusterNames) {
        clusters.push(...rule.targetCluster.clusterNames)
      }
    }
    return [...new Set(clusters)].join(', ') || '-'
  } catch {
    return '-'
  }
}

function formatSpec(specJson: string): string {
  try {
    return JSON.stringify(JSON.parse(specJson), null, 2)
  } catch {
    return specJson
  }
}

async function loadPolicies() {
  try {
    const resp = await listOverridePolicies({ limit: 100 })
    policies.value = resp.data.items || []
  } catch (e) {
    console.error('加载策略失败:', e)
  }
}

function viewPolicy(policy: OverridePolicy) {
  viewingPolicy.value = policy
  showViewDialog.value = true
}

async function handleDelete(policy: OverridePolicy) {
  try {
    await ElMessageBox.confirm(`确认删除策略 ${policy.name}?`, '提示', { type: 'warning' })
    await deleteOverridePolicy(policy.name, policy.namespace)
    ElMessage.success('删除成功')
    loadPolicies()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleCreate() {
  try {
    const targetClusters = targetClusterInput.value.split(',').map((s) => s.trim()).filter(Boolean)
    const overrideRules: any[] = []

    // 镜像覆盖规则
    if (createForm.value.registry || createForm.value.tag) {
      const imageOverrider: any[] = []
      if (createForm.value.registry) {
        imageOverrider.push({ component: 'Registry', operator: 'replace', value: createForm.value.registry })
      }
      if (createForm.value.tag) {
        imageOverrider.push({ component: 'Tag', operator: 'replace', value: createForm.value.tag })
      }
      overrideRules.push({
        targetCluster: { clusterNames: targetClusters },
        overriders: { imageOverrider },
      })
    }

    // 环境变量覆盖规则
    if (createForm.value.envs.length > 0) {
      overrideRules.push({
        targetCluster: { clusterNames: targetClusters },
        overriders: {
          envOverrider: [
            {
              containerName: 'app',
              operator: 'add',
              value: createForm.value.envs.filter((e) => e.name),
            },
          ],
        },
      })
    }

    if (overrideRules.length === 0) {
      ElMessage.warning('请至少配置一项覆盖规则')
      return
    }

    await createOverridePolicy({
      name: createForm.value.name,
      namespace: createForm.value.namespace,
      spec: { overrideRules },
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    createForm.value = {
      name: '',
      namespace: 'default',
      registry: '',
      tag: '',
      envs: [],
    }
    loadPolicies()
  } catch (e) {
    ElMessage.error('创建失败')
  }
}

onMounted(() => {
  loadPolicies()
})
</script>