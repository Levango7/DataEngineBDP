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
              <div class="card-title">结算与分账</div>
              <div>
                <el-input v-model="assetId" placeholder="资产 ID" style="width: 300px; margin-right: 8px" />
                <el-button type="primary" @click="loadData">查询</el-button>
                <el-button type="success" @click="settle" :disabled="!assetId">结算</el-button>
                <el-button type="warning" @click="allocate" :disabled="!assetId">分账</el-button>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-title">结算明细</div>
            <el-table :data="settlements" stripe style="width: 100%">
              <el-table-column prop="id" label="结算 ID" min-width="120">
                <template #default="{ row }">{{ row.id.slice(0, 8) }}</template>
              </el-table-column>
              <el-table-column prop="period" label="周期" width="100" />
              <el-table-column prop="status" label="状态" width="100" />
              <el-table-column prop="totalAmount" label="总金额" width="100">
                <template #default="{ row }">{{ row.totalAmount.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="providerRevenue" label="提供方收益" width="120">
                <template #default="{ row }">{{ row.providerRevenue.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="platformRevenue" label="平台抽成" width="100">
                <template #default="{ row }">{{ row.platformRevenue.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="providerShare" label="分成比例" width="120">
                <template #default="{ row }">{{ (row.providerShare * 100).toFixed(0) }}% : {{ (row.platformShare * 100).toFixed(0) }}%</template>
              </el-table-column>
              <el-table-column prop="settledAt" label="结算时间" min-width="160">
                <template #default="{ row }">{{ row.settledAt || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div class="card">
            <div class="card-title">分账明细</div>
            <el-table :data="allocations" stripe style="width: 100%">
              <el-table-column prop="id" label="分账 ID" min-width="120">
                <template #default="{ row }">{{ row.id.slice(0, 8) }}</template>
              </el-table-column>
              <el-table-column prop="settlementId" label="结算 ID" min-width="120">
                <template #default="{ row }">{{ row.settlementId.slice(0, 8) }}</template>
              </el-table-column>
              <el-table-column prop="providerAmount" label="提供方金额" width="120">
                <template #default="{ row }">{{ row.providerAmount.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="platformAmount" label="平台金额" width="100">
                <template #default="{ row }">{{ row.platformAmount.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="providerAccountId" label="提供方账户" min-width="120" />
              <el-table-column prop="platformAccountId" label="平台账户" min-width="120" />
              <el-table-column prop="status" label="状态" width="100" />
              <el-table-column prop="allocatedAt" label="分账时间" min-width="160">
                <template #default="{ row }">{{ row.allocatedAt || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listSettlements,
  listAllocations,
  settleAsset,
  allocateAsset,
  type Settlement,
  type Allocation,
} from '@/api/assetExchange'

const route = useRoute()
const activeMenu = ref(route.path)
const assetId = ref('')
const settlements = ref<Settlement[]>([])
const allocations = ref<Allocation[]>([])

async function loadData() {
  if (!assetId.value) {
    ElMessage.warning('请输入资产 ID')
    return
  }
  try {
    const [settleResp, allocResp] = await Promise.all([
      listSettlements(assetId.value),
      listAllocations(assetId.value),
    ])
    settlements.value = settleResp.data
    allocations.value = allocResp.data
  } catch (e: any) {
    ElMessage.error('查询失败: ' + (e?.message || ''))
  }
}

async function settle() {
  if (!assetId.value) return
  try {
    await settleAsset(assetId.value)
    ElMessage.success('结算成功')
    loadData()
  } catch (e: any) {
    ElMessage.error('结算失败: ' + (e?.message || ''))
  }
}

async function allocate() {
  if (!assetId.value) return
  try {
    await allocateAsset(assetId.value)
    ElMessage.success('分账成功')
    loadData()
  } catch (e: any) {
    ElMessage.error('分账失败: ' + (e?.message || ''))
  }
}
</script>