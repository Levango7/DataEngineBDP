<template>
  <div class="subscriptions-view">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>订阅管理</span>
          <el-button @click="loadData">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table :data="subscriptions" v-loading="loading" stripe>
        <el-table-column prop="id" label="订阅 ID" min-width="180" show-overflow-tooltip />
        <el-table-column prop="apiId" label="API ID" min-width="180" show-overflow-tooltip />
        <el-table-column prop="subscriberId" label="订阅者" width="120" />
        <el-table-column prop="subscriberTenantId" label="租户" width="140" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="grantedQuota" label="配额(次/分)" width="110" align="right" />
        <el-table-column prop="callCount" label="调用量" width="100" align="right" />
        <el-table-column label="AK" width="120">
          <template #default="{ row }">
            <span v-if="row.accessKey" class="ak-text">{{ maskKey(row.accessKey) }}</span>
            <span v-else class="no-key">未颁发</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleViewKey(row)">查看 Key</el-button>
            <el-button size="small" type="warning" @click="handleIssueKey(row)">
              重新颁发
            </el-button>
            <el-button size="small" @click="handleRateLimit(row)">限流</el-button>
            <el-button
              v-if="row.status === 'active'"
              size="small"
              type="danger"
              @click="handleSuspend(row)"
            >
              暂停
            </el-button>
            <el-button
              v-if="row.status === 'suspended'"
              size="small"
              type="success"
              @click="handleResume(row)"
            >
              恢复
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="keyDialogVisible" title="Key 信息" width="500px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="订阅 ID">{{ currentSub.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentSub.status }}</el-descriptions-item>
        <el-descriptions-item label="Access Key">
          <el-input :value="currentSub.accessKey" readonly>
            <template #append>
              <el-button @click="copyText(currentSub.accessKey)">复制</el-button>
            </template>
          </el-input>
        </el-descriptions-item>
        <el-descriptions-item label="Secret Key">
          <el-input :value="currentSub.secretKey" readonly show-password>
            <template #append>
              <el-button @click="copyText(currentSub.secretKey)">复制</el-button>
            </template>
          </el-input>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="warning"
        title="请妥善保管 Secret Key，关闭后将无法再次查看"
        style="margin-top: 16px"
      />
    </el-dialog>

    <el-dialog v-model="issueDialogVisible" title="重新颁发 AK/SK" width="450px">
      <el-form label-width="80px">
        <el-form-item label="原因">
          <el-input v-model="issueForm.reason" placeholder="如：Key 泄露、定期轮换" />
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="issueForm.operator" placeholder="输入操作人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleIssueSubmit">确认颁发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rateLimitDialogVisible" title="限流配置" width="450px">
      <el-form label-width="100px">
        <el-form-item label="QPS">
          <el-input-number v-model="rateLimitForm.qps" :min="1" :max="100000" />
          <span class="form-tip-inline">次/秒</span>
        </el-form-item>
        <el-form-item label="并发数">
          <el-input-number v-model="rateLimitForm.concurrent" :min="0" :max="10000" />
          <span class="form-tip-inline">0 表示不限</span>
        </el-form-item>
        <el-form-item label="突发容量">
          <el-input-number v-model="rateLimitForm.burst" :min="0" />
          <span class="form-tip-inline">0 表示与 QPS 相同</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rateLimitDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleRateLimitSubmit">保存配置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listSubscriptions,
  getSubscription,
  issueKey,
  configureRateLimit,
  suspendSubscription,
  resumeSubscription,
} from '@/api/subscription'

const subscriptions = ref([])
const loading = ref(false)

const keyDialogVisible = ref(false)
const issueDialogVisible = ref(false)
const rateLimitDialogVisible = ref(false)

const currentSub = reactive({})
const issueForm = reactive({ reason: '重新颁发', operator: '' })
const rateLimitForm = reactive({ qps: 100, concurrent: 0, burst: 0 })

async function loadData() {
  loading.value = true
  try {
    const data = await listSubscriptions({ limit: 100 })
    subscriptions.value = Array.isArray(data) ? data : (data.items || [])
  } catch (err) {
    ElMessage.error(err.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

async function handleViewKey(row) {
  try {
    const sub = await getSubscription(row.id)
    Object.assign(currentSub, sub)
    keyDialogVisible.value = true
  } catch (err) {
    ElMessage.error(err.message || '查询失败')
  }
}

function handleIssueKey(row) {
  Object.assign(currentSub, row)
  issueForm.reason = '重新颁发'
  issueForm.operator = ''
  issueDialogVisible.value = true
}

async function handleIssueSubmit() {
  try {
    const result = await issueKey(currentSub.id, { ...issueForm })
    ElMessage.success('AK/SK 已重新颁发')
    issueDialogVisible.value = false
    keyDialogVisible.value = true
    Object.assign(currentSub, result)
    loadData()
  } catch (err) {
    ElMessage.error(err.message || '颁发失败')
  }
}

function handleRateLimit(row) {
  Object.assign(currentSub, row)
  rateLimitForm.qps = 100
  rateLimitForm.concurrent = 0
  rateLimitForm.burst = 0
  rateLimitDialogVisible.value = true
}

async function handleRateLimitSubmit() {
  try {
    await configureRateLimit(currentSub.id, { ...rateLimitForm })
    ElMessage.success('限流配置已保存')
    rateLimitDialogVisible.value = false
  } catch (err) {
    ElMessage.error(err.message || '配置失败')
  }
}

async function handleSuspend(row) {
  try {
    await ElMessageBox.confirm('确认暂停此订阅？', '提示', { type: 'warning' })
    await suspendSubscription(row.id)
    ElMessage.success('订阅已暂停')
    loadData()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(err.message || '操作失败')
    }
  }
}

async function handleResume(row) {
  try {
    await resumeSubscription(row.id)
    ElMessage.success('订阅已恢复')
    loadData()
  } catch (err) {
    ElMessage.error(err.message || '操作失败')
  }
}

function maskKey(key) {
  if (!key || key.length < 8) return key
  return key.slice(0, 4) + '****' + key.slice(-4)
}

function copyText(text) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  })
}

function statusTagType(status) {
  const map = {
    pending: 'warning',
    approved: 'success',
    active: 'success',
    suspended: 'info',
    rejected: 'danger',
    revoked: 'danger',
  }
  return map[status] || 'info'
}

function statusLabel(status) {
  const map = {
    pending: '待审批',
    approved: '已审批',
    active: '已激活',
    suspended: '已暂停',
    rejected: '已驳回',
    revoked: '已吊销',
  }
  return map[status] || status
}
</script>

<style scoped>
.subscriptions-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ak-text {
  font-family: monospace;
  font-size: 12px;
}
.no-key {
  color: #c0c4cc;
  font-size: 12px;
}
.form-tip-inline {
  margin-left: 8px;
  color: #909399;
  font-size: 13px;
}
</style>
