<template>
  <div class="failover-policies">
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
              <div class="card-title">故障迁移策略</div>
              <el-button type="primary" @click="showCreateDialog = true">新建策略</el-button>
            </div>
            <el-table :data="policies" stripe style="width: 100%">
              <el-table-column prop="name" label="策略名" min-width="160" />
              <el-table-column prop="namespace" label="命名空间" width="120" />
              <el-table-column prop="primaryCluster" label="主集群" width="160" />
              <el-table-column label="备用集群" min-width="200">
                <template #default="{ row }">{{ formatBackups(row.backupClusters) }}</template>
              </el-table-column>
              <el-table-column label="检测窗口" width="100">
                <template #default="{ row }">{{ row.detectionWindowSeconds }}s</template>
              </el-table-column>
              <el-table-column label="迁移超时" width="100">
                <template #default="{ row }">{{ row.migrationTimeoutSeconds }}s</template>
              </el-table-column>
              <el-table-column label="检查间隔" width="100">
                <template #default="{ row }">{{ row.healthCheckIntervalSeconds }}s</template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="180" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="handleEdit(row)">编辑</el-button>
                  <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 创建对话框 -->
        <el-dialog v-model="showCreateDialog" title="新建故障迁移策略" width="600px">
          <el-form :model="createForm" label-width="140px">
            <el-form-item label="策略名" required>
              <el-input v-model="createForm.name" placeholder="default-failover" />
            </el-form-item>
            <el-form-item label="命名空间" required>
              <el-input v-model="createForm.namespace" placeholder="default" />
            </el-form-item>
            <el-form-item label="主集群" required>
              <el-input v-model="createForm.primaryCluster" placeholder="xinchang-cluster" />
            </el-form-item>
            <el-form-item label="备用集群" required>
              <el-input v-model="backupsInput" placeholder="local-cluster,cce-cluster" />
            </el-form-item>
            <el-form-item label="检测窗口(秒)">
              <el-input-number v-model="createForm.detectionWindowSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="迁移超时(秒)">
              <el-input-number v-model="createForm.migrationTimeoutSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="检查间隔(秒)">
              <el-input-number v-model="createForm.healthCheckIntervalSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="createForm.enabled" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showCreateDialog = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建</el-button>
          </template>
        </el-dialog>

        <!-- 编辑对话框 -->
        <el-dialog v-model="showEditDialog" title="编辑故障迁移策略" width="600px">
          <el-form :model="editForm" label-width="140px">
            <el-form-item label="主集群">
              <el-input v-model="editForm.primaryCluster" />
            </el-form-item>
            <el-form-item label="备用集群">
              <el-input v-model="editBackupsInput" />
            </el-form-item>
            <el-form-item label="检测窗口(秒)">
              <el-input-number v-model="editForm.detectionWindowSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="迁移超时(秒)">
              <el-input-number v-model="editForm.migrationTimeoutSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="检查间隔(秒)">
              <el-input-number v-model="editForm.healthCheckIntervalSeconds" :min="1" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="editForm.enabled" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showEditDialog = false">取消</el-button>
            <el-button type="primary" @click="handleUpdate">保存</el-button>
          </template>
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
  listFailoverPolicies,
  createFailoverPolicy,
  updateFailoverPolicy,
  deleteFailoverPolicy,
  type FailoverPolicy,
} from '@/api/multiCluster'

const route = useRoute()
const activeMenu = ref(route.path)

const policies = ref<FailoverPolicy[]>([])
const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const backupsInput = ref('local-cluster,cce-cluster')
const editBackupsInput = ref('')

const createForm = ref({
  name: '',
  namespace: 'default',
  primaryCluster: 'xinchang-cluster',
  detectionWindowSeconds: 30,
  migrationTimeoutSeconds: 60,
  healthCheckIntervalSeconds: 10,
  enabled: true,
})

const editForm = ref({
  name: '',
  namespace: 'default',
  primaryCluster: '',
  detectionWindowSeconds: 30,
  migrationTimeoutSeconds: 60,
  healthCheckIntervalSeconds: 10,
  enabled: true,
})

function formatBackups(json: string): string {
  try {
    return JSON.parse(json).join(', ')
  } catch {
    return '-'
  }
}

async function loadPolicies() {
  try {
    const resp = await listFailoverPolicies({ limit: 100 })
    policies.value = resp.data.items || []
  } catch (e) {
    console.error('加载策略失败:', e)
  }
}

async function handleCreate() {
  try {
    const backups = backupsInput.value.split(',').map((s) => s.trim()).filter(Boolean)
    await createFailoverPolicy({
      name: createForm.value.name,
      namespace: createForm.value.namespace,
      primaryCluster: createForm.value.primaryCluster,
      backupClusters: backups,
      detectionWindowSeconds: createForm.value.detectionWindowSeconds,
      migrationTimeoutSeconds: createForm.value.migrationTimeoutSeconds,
      healthCheckIntervalSeconds: createForm.value.healthCheckIntervalSeconds,
      enabled: createForm.value.enabled,
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    loadPolicies()
  } catch (e) {
    ElMessage.error('创建失败')
  }
}

function handleEdit(policy: FailoverPolicy) {
  editForm.value = {
    name: policy.name,
    namespace: policy.namespace,
    primaryCluster: policy.primaryCluster,
    detectionWindowSeconds: policy.detectionWindowSeconds,
    migrationTimeoutSeconds: policy.migrationTimeoutSeconds,
    healthCheckIntervalSeconds: policy.healthCheckIntervalSeconds,
    enabled: policy.enabled,
  }
  try {
    editBackupsInput.value = JSON.parse(policy.backupClusters).join(', ')
  } catch {
    editBackupsInput.value = ''
  }
  showEditDialog.value = true
}

async function handleUpdate() {
  try {
    const backups = editBackupsInput.value.split(',').map((s) => s.trim()).filter(Boolean)
    await updateFailoverPolicy(editForm.value.name, {
      primaryCluster: editForm.value.primaryCluster,
      backupClusters: backups,
      detectionWindowSeconds: editForm.value.detectionWindowSeconds,
      migrationTimeoutSeconds: editForm.value.migrationTimeoutSeconds,
      healthCheckIntervalSeconds: editForm.value.healthCheckIntervalSeconds,
      enabled: editForm.value.enabled,
    }, editForm.value.namespace)
    ElMessage.success('保存成功')
    showEditDialog.value = false
    loadPolicies()
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

async function handleDelete(policy: FailoverPolicy) {
  try {
    await ElMessageBox.confirm(`确认删除策略 ${policy.name}?`, '提示', { type: 'warning' })
    await deleteFailoverPolicy(policy.name, policy.namespace)
    ElMessage.success('删除成功')
    loadPolicies()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  loadPolicies()
})
</script>