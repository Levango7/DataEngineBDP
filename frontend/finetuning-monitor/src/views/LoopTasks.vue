<template>
  <div class="loop-tasks-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>闭环任务列表</span>
          <el-button type="primary" @click="showSubmitDialog = true">
            提交闭环任务
          </el-button>
        </div>
      </template>

      <!-- 筛选栏 -->
      <el-form :inline="true" class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="filterStatus" placeholder="全部" clearable @change="loadTasks">
            <el-option label="待执行" value="pending" />
            <el-option label="微调中" value="finetuning" />
            <el-option label="评测中" value="evaluating" />
            <el-option label="部署中" value="deploying" />
            <el-option label="已完成" value="completed" />
            <el-option label="失败" value="failed" />
            <el-option label="已取消" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item label="租户">
          <el-input v-model="filterTenant" placeholder="租户 ID" @change="loadTasks" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadTasks">刷新</el-button>
        </el-form-item>
      </el-form>

      <!-- 任务表格 -->
      <el-table :data="tasks" v-loading="loading" stripe>
        <el-table-column prop="taskId" label="任务 ID" width="180" />
        <el-table-column prop="taskName" label="任务名称" width="200" />
        <el-table-column prop="baseModel" label="基座模型" width="220" />
        <el-table-column prop="method" label="微调方式" width="100" />
        <el-table-column prop="framework" label="框架" width="120" />
        <el-table-column prop="adapterVersion" label="Adapter 版本" width="120" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ getStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步骤" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewDetail(row.taskId)">详情</el-button>
            <el-button
              size="small"
              type="danger"
              :disabled="isTerminal(row.status)"
              @click="handleCancel(row.taskId)"
            >取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 提交闭环任务对话框 -->
    <el-dialog v-model="showSubmitDialog" title="提交闭环任务" width="700px">
      <el-form :model="submitForm" label-width="120px">
        <el-form-item label="任务名称">
          <el-input v-model="submitForm.taskName" />
        </el-form-item>
        <el-form-item label="基座模型">
          <el-input v-model="submitForm.baseModel" />
        </el-form-item>
        <el-form-item label="训练数据集名">
          <el-input v-model="submitForm.trainDataset.name" />
        </el-form-item>
        <el-form-item label="训练数据集路径">
          <el-input v-model="submitForm.trainDataset.path" />
        </el-form-item>
        <el-form-item label="评测数据集">
          <el-select v-model="submitForm.evalDataset">
            <el-option label="CMMLU" value="cmmlu" />
            <el-option label="MMLU" value="mmlu" />
            <el-option label="CEval" value="ceval" />
          </el-select>
        </el-form-item>
        <el-form-item label="微调方式">
          <el-select v-model="submitForm.finetune.method">
            <el-option label="LoRA" value="lora" />
            <el-option label="QLoRA" value="qlora" />
            <el-option label="全参" value="full" />
          </el-select>
        </el-form-item>
        <el-form-item label="框架">
          <el-select v-model="submitForm.finetune.framework">
            <el-option label="PEFT" value="peft" />
            <el-option label="LLaMA-Factory" value="llama_factory" />
            <el-option label="DeepSpeed" value="deepspeed" />
          </el-select>
        </el-form-item>
        <el-form-item label="LoRA Rank">
          <el-input-number v-model="submitForm.finetune.lora!.rank" :min="1" :max="64" />
        </el-form-item>
        <el-form-item label="训练轮数">
          <el-input-number v-model="submitForm.finetune.hyperparams.epochs" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="学习率">
          <el-input-number v-model="submitForm.finetune.hyperparams.learningRate" :step="0.0001" :precision="6" />
        </el-form-item>
        <el-form-item label="GPU 数量">
          <el-input-number v-model="submitForm.gpu.count" :min="1" :max="32" />
        </el-form-item>
        <el-form-item label="跳过部署">
          <el-switch v-model="submitForm.skipDeploy" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSubmitDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listLoopTasks, submitLoopTask, cancelLoopTask } from '@/api/loop'
import type { LoopTaskResponse, LoopTaskRequest, LoopStatus } from '@/types'

const router = useRouter()

const tasks = ref<LoopTaskResponse[]>([])
const loading = ref(false)
const filterStatus = ref<LoopStatus | ''>('')
const filterTenant = ref('')

const showSubmitDialog = ref(false)
const submitting = ref(false)

// 提交表单默认值
const submitForm = ref<LoopTaskRequest>({
  taskName: 'lora-eval-deploy-demo',
  baseModel: 'meta-llama/Llama-2-7b-hf',
  trainDataset: {
    name: 'alpaca-zh',
    path: '/data/datasets/alpaca-zh.json',
    format: 'alpaca'
  },
  evalDataset: 'cmmlu',
  finetune: {
    method: 'lora',
    framework: 'peft',
    lora: { rank: 16, alpha: 32, dropout: 0.05, targetModules: ['q_proj', 'k_proj', 'v_proj', 'o_proj'] },
    hyperparams: { epochs: 1, batchSize: 4, learningRate: 0.0002, maxSeqLength: 1024, loggingSteps: 5 }
  },
  eval: { dataset: 'cmmlu', mode: 'rule', metrics: ['accuracy', 'recall', 'f1', 'latency_p95', 'cost', 'hallucination'], limit: 0 },
  deploy: { runtime: 'vllm', port: 8000, replicas: 1, gpuCount: 1, autoRollback: false, minAccuracy: 0.0 },
  gpu: { count: 1, type: 'any', memoryGB: 0 },
  outputDir: '/tmp/finetune-loop/output',
  tenantId: 'default',
  skipDeploy: false
})

// 加载任务列表
async function loadTasks() {
  loading.value = true
  try {
    const resp = await listLoopTasks({
      status: filterStatus.value || undefined,
      tenantId: filterTenant.value || undefined,
      limit: 50
    })
    tasks.value = resp.data
  } catch (e) {
    ElMessage.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

// 查看详情
function viewDetail(taskId: string) {
  router.push(`/loop-tasks/${taskId}`)
}

// 取消任务
async function handleCancel(taskId: string) {
  try {
    await ElMessageBox.confirm('确认取消此闭环任务？', '提示', { type: 'warning' })
    await cancelLoopTask(taskId)
    ElMessage.success('任务已取消')
    loadTasks()
  } catch (e) {
    // 用户取消或操作失败
  }
}

// 提交闭环任务
async function handleSubmit() {
  submitting.value = true
  try {
    await submitLoopTask(submitForm.value)
    ElMessage.success('闭环任务已提交')
    showSubmitDialog.value = false
    loadTasks()
  } catch (e) {
    ElMessage.error('提交失败')
  } finally {
    submitting.value = false
  }
}

// 状态相关
function getStatusType(status: LoopStatus): string {
  const map: Record<string, string> = {
    pending: 'info',
    finetuning: 'warning',
    evaluating: 'warning',
    deploying: 'warning',
    completed: 'success',
    failed: 'danger',
    cancelled: 'info'
  }
  return map[status] || 'info'
}

function getStatusLabel(status: LoopStatus): string {
  const map: Record<string, string> = {
    pending: '待执行',
    finetuning: '微调中',
    evaluating: '评测中',
    deploying: '部署中',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消'
  }
  return map[status] || status
}

function isTerminal(status: LoopStatus): boolean {
  return ['completed', 'failed', 'cancelled'].includes(status)
}

function formatTime(t: string): string {
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(() => {
  loadTasks()
})
</script>

<style scoped>
.loop-tasks-page {
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