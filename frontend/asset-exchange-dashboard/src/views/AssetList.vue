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
              <div class="card-title">资产市场</div>
              <el-button type="primary" @click="$router.push('/register')">登记新资产</el-button>
            </div>
            <el-table :data="assets" stripe style="width: 100%">
              <el-table-column prop="name" label="名称" min-width="150" />
              <el-table-column prop="type" label="类型" width="100" />
              <el-table-column prop="tenantId" label="提供方" min-width="120" />
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="qualityScore" label="质量评分" width="100" />
              <el-table-column prop="securityLevel" label="分级" width="100" />
              <el-table-column label="定价" width="150">
                <template #default="{ row }">
                  {{ row.pricing?.mode }} / ¥{{ row.pricing?.price }}
                </template>
              </el-table-column>
              <el-table-column prop="subscriberCount" label="订阅数" width="80" />
              <el-table-column label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="viewAsset(row)">详情</el-button>
                  <el-button size="small" type="success" @click="subscribe(row)" v-if="row.status === 'listed'">订阅</el-button>
                </template>
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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAssets, subscribeAsset, type Asset } from '@/api/assetExchange'

const route = useRoute()
const router = useRouter()
const activeMenu = ref(route.path)
const assets = ref<Asset[]>([])

async function loadAssets() {
  try {
    const resp = await listAssets({ limit: 100 })
    assets.value = resp.data
  } catch (e) {
    ElMessage.error('加载资产列表失败')
  }
}

function statusTagType(status: string) {
  const map: Record<string, string> = {
    listed: 'success',
    draft: 'info',
    pending_audit: 'warning',
    offline: 'info',
    rejected: 'danger',
  }
  return map[status] || 'info'
}

function viewAsset(asset: Asset) {
  ElMessageBox.alert(JSON.stringify(asset, null, 2), '资产详情', {
    confirmButtonText: '关闭',
  })
}

async function subscribe(asset: Asset) {
  try {
    const { value: subscriberId } = await ElMessageBox.prompt('请输入订阅方租户 ID', '订阅资产', {
      confirmButtonText: '订阅',
      cancelButtonText: '取消',
    })
    if (!subscriberId) return
    await subscribeAsset(asset.id, { subscriberId, period: 'monthly', durationDays: 30 })
    ElMessage.success('订阅成功，等待审批')
    loadAssets()
  } catch {
    // 用户取消
  }
}

onMounted(() => {
  loadAssets()
})
</script>