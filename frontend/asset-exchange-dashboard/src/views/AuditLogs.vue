<template>
  <div class="dashboard">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">流通看板</el-menu-item>
          <el-menu-item index="/assets">资产市场</el-menu-item>
          <el-menu-item index="/register">资产登记</el-menu-item>
          <el-menu-item index="/settlements">结算分账</el-menu-item>
          <el-menu-item index="/audit-logs">审计日志</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <div class="page-container">
          <div class="card">
            <div class="toolbar">
              <div class="card-title">审计日志（全过程留痕，不可篡改）</div>
              <div>
                <el-button type="primary" @click="checkIntegrity">完整性校验</el-button>
                <el-button @click="loadLogs">刷新</el-button>
              </div>
            </div>

            <el-form :inline="true" :model="filter" style="margin-bottom: 16px">
              <el-form-item label="资产 ID">
                <el-input v-model="filter.assetId" placeholder="按资产过滤" clearable />
              </el-form-item>
              <el-form-item label="动作">
                <el-select v-model="filter.action" placeholder="全部" clearable style="width: 140px">
                  <el-option label="登记" value="register" />
                  <el-option label="审核" value="audit" />
                  <el-option label="上架" value="publish" />
                  <el-option label="订阅" value="subscribe" />
                  <el-option label="下载" value="download" />
                  <el-option label="调用" value="invoke" />
                  <el-option label="交付" value="deliver" />
                  <el-option label="结算" value="settle" />
                  <el-option label="分账" value="allocate" />
                  <el-option label="下架" value="offline" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadLogs">查询</el-button>
              </el-form-item>
            </el-form>

            <el-table :data="logs" stripe style="width: 100%">
              <el-table-column prop="action" label="动作" width="100">
                <template #default="{ row }">
                  <el-tag :type="actionTagType(row.action)">{{ row.action }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="actorId" label="操作者" min-width="120" />
              <el-table-column prop="result" label="结果" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.result === 'success' ? 'success' : 'danger'" size="small">{{ row.result }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="assetId" label="资产 ID" min-width="120">
                <template #default="{ row }">{{ row.assetId ? row.assetId.slice(0, 8) : '-' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" min-width="180" />
              <el-table-column prop="hash" label="哈希" min-width="120">
                <template #default="{ row }">{{ row.hash ? row.hash.slice(0, 16) + '...' : '-' }}</template>
              </el-table-column>
              <el-table-column label="详情" width="80">
                <template #default="{ row }">
                  <el-button size="small" link @click="viewDetail(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="card" v-if="integrityReport">
            <div class="card-title">完整性校验报告</div>
            <el-alert
              :title="integrityReport.verified ? '校验通过' : '校验失败'"
              :type="integrityReport.verified ? 'success' : 'error'"
              :description="integrityReport.message"
              show-icon
            />
            <el-descriptions :column="2" border style="margin-top: 16px">
              <el-descriptions-item label="日志总数">{{ integrityReport.totalLogs }}</el-descriptions-item>
              <el-descriptions-item label="断裂点">{{ integrityReport.brokenAt || '无' }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAuditLogs,
  verifyIntegrity,
  type AuditLog,
} from '@/api/assetExchange'

const route = useRoute()
const activeMenu = ref(route.path)
const logs = ref<AuditLog[]>([])
const integrityReport = ref<{ totalLogs: number; verified: boolean; brokenAt?: string; message: string } | null>(null)

const filter = reactive({
  assetId: '',
  action: '',
})

function actionTagType(action: string) {
  const map: Record<string, string> = {
    register: 'primary',
    audit: 'warning',
    publish: 'success',
    subscribe: 'info',
    download: 'info',
    invoke: 'info',
    deliver: 'info',
    settle: 'success',
    allocate: 'success',
    offline: 'danger',
  }
  return map[action] || 'info'
}

async function loadLogs() {
  try {
    const params: Record<string, any> = {}
    if (filter.assetId) params.assetId = filter.assetId
    if (filter.action) params.action = filter.action
    const resp = await listAuditLogs(params)
    logs.value = resp.data
  } catch (e: any) {
    ElMessage.error('加载审计日志失败: ' + (e?.message || ''))
  }
}

async function checkIntegrity() {
  try {
    const resp = await verifyIntegrity()
    integrityReport.value = resp.data
    if (resp.data.verified) {
      ElMessage.success(`完整性校验通过，共 ${resp.data.totalLogs} 条日志`)
    } else {
      ElMessage.error('完整性校验失败：哈希链断裂')
    }
  } catch (e: any) {
    ElMessage.error('校验失败: ' + (e?.message || ''))
  }
}

function viewDetail(log: AuditLog) {
  ElMessageBox.alert(JSON.stringify(log, null, 2), '审计日志详情', {
    confirmButtonText: '关闭',
  })
}

onMounted(() => {
  loadLogs()
})
</script>