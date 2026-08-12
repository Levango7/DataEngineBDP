<template>
  <div class="deployment-management-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>部署管理</span>
          <el-button type="primary" @click="showDeployDialog = true">一键部署</el-button>
        </div>
      </template>

      <!-- 筛选栏 -->
      <el-form :inline="true" class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="filterStatus" placeholder="全部" clearable @change="loadDeployments">
            <el-option label="运行中" value="running" />
            <el-option label="已停止" value="stopped" />
            <el-option label="失败" value="failed" />
            <el-option label="更新中" value="updating" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadDeployments">刷新</el-button>
        </el-form-item>
      </el-form>

      <!-- 部署表格 -->
      <el-table :data="deployments" v-loading="loading" stripe>
        <el-table-column prop="deploymentId" label="部署 ID" width="180" />
        <el-table-column prop="modelName" label="模型名" width="220" />
        <el-table-column prop="version" label="版本" width="100" />
        <el-table-column prop="runtime" label="运行时" width="100" />
        <el-table-column prop="endpoint" label="端点" width="240" />
        <el-table-column prop="replicas" label="副本数" width="80" />
        <el-table-column prop="gpuCount" label="GPU 数" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="健康" width="80">
          <template #default="{ row }">
            <el-tag :type="row.healthy ? 'success' : 'danger'">{{ row.healthy ? '✓' : '✗' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleHealthCheck(row)">健康检查</el-button>
            <el-button size="small" type="warning" :disabled="row.status === 'stopped'" @click="handleStop(row)">停止</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 部署对话框 -->
    <el-dialog v-model="showDeployDialog" title="一键部署" width="600px">
      <el-form :model="deployForm" label-width="120px">
        <el-form-item label="模型名">
          <el-input v-model="deployForm.modelName" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="deployForm.version" placeholder="留空使用最新版本" />
        </el-form-item>
        <el-form-item label="推理运行时">
          <el-select v-model="deployForm.runtime">
            <el-option label="vLLM" value="vllm" />
            <el-option label="Triton" value="triton" />
            <el-option label="简化" value="simple" />
          </el-select>
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="deployForm.port" :min="1024" :max="65535" />
        </el-form-item>
        <el-form-item label="副本数">
          <el-input-number v-model="deployForm.replicas" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="GPU 数">
          <el-input-number v-model="deployForm.gpuCount" :min="1" :max="8" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDeployDialog = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="handleDeploy">部署</el-button>
      </template>
    </el-dialog>

    <!-- 健康检查结果 -->
    <el-dialog v-model="healthDialogVisible" title="健康检查结果" width="500px">
      <el-descriptions :column="1" border v-if="healthResult">
        <el-descriptions-item label="部署 ID">{{ healthResult.deploymentId }}</el-descriptions-item>
        <el-descriptions-item label="健康状态">
          <el-tag :type="healthResult.healthy ? 'success' : 'danger'">{{ healthResult.healthy ? '健康' : '不健康' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="端点">{{ healthResult.endpoint }}</el-descriptions-item>
        <el-descriptions-item label="延迟">{{ healthResult.latencyMs }} ms</el-descriptions-item>
        <el-descriptions-item label="错误">{{ healthResult.error || '无' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import type { DeploymentRecord } from '@/types'

// 部署列表
const deployments = ref<DeploymentRecord[]>([])
const loading = ref(false)
const filterStatus = ref('')

// 部署对话框
const showDeployDialog = ref(false)
const deploying = ref(false)
const deployForm = ref({
  modelName: '',
  version: '',
  runtime: 'vllm',
  port: 8000,
  replicas: 1,
  gpuCount: 1
})

// 健康检查
const healthDialogVisible = ref(false)
const healthResult = ref<any>(null)

// 模型仓库 API 客户端
const registryHttp = axios.create({
  baseURL: import.meta.env.VITE_REGISTRY_BASE || '/api/v1/registry',
  timeout: 30000
})

// 加载部署列表
async function loadDeployments() {
  loading.value = true
  try {
    const resp = await registryHttp.get('/deployments', {
      params: { status: filterStatus.value || undefined }
    })
    deployments.value = resp.data.deployments
  } catch (e) {
    ElMessage.error('加载部署列表失败')
  } finally {
    loading.value = false
  }
}

// 一键部署
async function handleDeploy() {
  if (!deployForm.value.modelName) {
    ElMessage.warning('请输入模型名')
    return
  }
  deploying.value = true
  try {
    await registryHttp.post('/deployments', deployForm.value)
    ElMessage.success('部署已创建')
    showDeployDialog.value = false
    loadDeployments()
  } catch (e) {
    ElMessage.error('部署失败')
  } finally {
    deploying.value = false
  }
}

// 健康检查
async function handleHealthCheck(row: DeploymentRecord) {
  try {
    const resp = await registryHttp.get(`/deployments/${row.deploymentId}/health`)
    healthResult.value = resp.data
    healthDialogVisible.value = true
  } catch (e) {
    ElMessage.error('健康检查失败')
  }
}

// 停止部署
async function handleStop(row: DeploymentRecord) {
  try {
    await ElMessageBox.confirm('确认停止此部署？', '提示', { type: 'warning' })
    await registryHttp.delete(`/deployments/${row.deploymentId}`)
    ElMessage.success('部署已停止')
    loadDeployments()
  } catch (e) {
    // 取消或失败
  }
}

// 删除部署
async function handleDelete(row: DeploymentRecord) {
  try {
    await ElMessageBox.confirm('确认删除此部署？此操作不可恢复。', '警告', { type: 'warning' })
    await registryHttp.delete(`/deployments/${row.deploymentId}`)
    ElMessage.success('部署已删除')
    loadDeployments()
  } catch (e) {
    // 取消或失败
  }
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    running: 'success',
    stopped: 'info',
    failed: 'danger',
    updating: 'warning',
    pending: 'info'
  }
  return map[status] || 'info'
}

function formatTime(t: string): string {
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(() => {
  loadDeployments()
})
</script>

<style scoped>
.deployment-management-page {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filter-form {
  margin-bottom: 16px;
}
</style>