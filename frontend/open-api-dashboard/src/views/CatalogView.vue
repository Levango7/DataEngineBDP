<template>
  <div class="catalog-view">
    <!-- 搜索栏 -->
    <el-card class="search-card" shadow="never">
      <el-form :inline="true" :model="searchForm" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="搜索 API 名称/描述/标签"
            clearable
            style="width: 280px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select
            v-model="searchForm.category"
            placeholder="全部分类"
            clearable
            style="width: 160px"
          >
            <el-option label="SQL 查询" value="sql" />
            <el-option label="模型推理" value="model" />
            <el-option label="函数计算" value="function" />
            <el-option label="数据查询" value="data" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="searchForm.status"
            placeholder="全部状态"
            clearable
            style="width: 140px"
          >
            <el-option label="草稿" value="draft" />
            <el-option label="运行中" value="running" />
            <el-option label="已废弃" value="deprecated" />
            <el-option label="已归档" value="archived" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- API 列表 -->
    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>API 列表（{{ catalogStore.total }} 个）</span>
          <el-button type="primary" @click="$router.push('/generate')">
            <el-icon><Plus /></el-icon>
            一键生成 API
          </el-button>
        </div>
      </template>

      <el-table
        :data="catalogStore.apis"
        v-loading="catalogStore.loading"
        stripe
        @row-click="handleRowClick"
      >
        <el-table-column prop="name" label="API 名称" min-width="180">
          <template #default="{ row }">
            <div class="api-name">
              <span class="name-text">{{ row.name }}</span>
              <span class="version-tag">v{{ row.version }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="categoryTagType(row.category)">
              {{ row.category }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(row.method)">
              {{ row.method }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sla" label="SLA" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="slaTagType(row.sla)">
              {{ row.sla }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costStrategy" label="计费" width="120">
          <template #default="{ row }">
            {{ costStrategyLabel(row.costStrategy) }}
          </template>
        </el-table-column>
        <el-table-column prop="callCount" label="调用量" width="100" align="right" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="handleViewDetail(row)">详情</el-button>
            <el-button size="small" type="primary" @click.stop="handleSubscribe(row)">
              订阅
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 订阅对话框 -->
    <el-dialog v-model="subscribeDialogVisible" title="申请订阅" width="500px">
      <el-form :model="subscribeForm" label-width="100px">
        <el-form-item label="API">
          <el-input :value="subscribeForm.apiName" disabled />
        </el-form-item>
        <el-form-item label="订阅者 ID">
          <el-input v-model="subscribeForm.subscriberId" placeholder="输入订阅者 ID" />
        </el-form-item>
        <el-form-item label="租户 ID">
          <el-input v-model="subscribeForm.subscriberTenantId" placeholder="输入租户 ID" />
        </el-form-item>
        <el-form-item label="用途">
          <el-input
            v-model="subscribeForm.purpose"
            type="textarea"
            placeholder="说明订阅用途"
          />
        </el-form-item>
        <el-form-item label="期望配额">
          <el-input-number
            v-model="subscribeForm.quotaExpect"
            :min="1"
            :max="100000"
          />
          <span style="margin-left: 8px; color: #909399">次/分钟</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subscribeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubscribeSubmit">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { useCatalogStore } from '@/stores/catalog'
import { subscribe as subscribeApi } from '@/api/subscription'

const router = useRouter()
const catalogStore = useCatalogStore()

const searchForm = reactive({
  keyword: '',
  category: '',
  status: '',
})

const subscribeDialogVisible = ref(false)
const subscribeForm = reactive({
  apiId: '',
  apiName: '',
  subscriberId: '',
  subscriberTenantId: '',
  purpose: '',
  quotaExpect: 100,
})

// 加载数据
async function loadData() {
  await catalogStore.loadApis({
    keyword: searchForm.keyword || undefined,
    category: searchForm.category || undefined,
    status: searchForm.status || undefined,
  })
}

onMounted(loadData)

function handleSearch() {
  loadData()
}

function handleReset() {
  searchForm.keyword = ''
  searchForm.category = ''
  searchForm.status = ''
  loadData()
}

function handleRowClick(row) {
  router.push(`/api-detail/${row.id}`)
}

function handleViewDetail(row) {
  router.push(`/api-detail/${row.id}`)
}

function handleSubscribe(row) {
  subscribeForm.apiId = row.id
  subscribeForm.apiName = row.name
  subscribeForm.subscriberId = ''
  subscribeForm.subscriberTenantId = ''
  subscribeForm.purpose = ''
  subscribeForm.quotaExpect = 100
  subscribeDialogVisible.value = true
}

async function handleSubscribeSubmit() {
  try {
    await subscribeApi(subscribeForm.apiId, {
      subscriberId: subscribeForm.subscriberId,
      subscriberTenantId: subscribeForm.subscriberTenantId,
      purpose: subscribeForm.purpose,
      quotaExpect: subscribeForm.quotaExpect,
    })
    ElMessage.success('订阅申请已提交，等待审批')
    subscribeDialogVisible.value = false
  } catch (err) {
    ElMessage.error(err.message || '订阅失败')
  }
}

// 标签样式
function categoryTagType(category) {
  const map = { sql: 'primary', model: 'success', function: 'warning', data: 'info' }
  return map[category] || 'info'
}

function methodTagType(method) {
  const map = { GET: 'success', POST: 'primary', PUT: 'warning', DELETE: 'danger' }
  return map[method] || 'info'
}

function statusTagType(status) {
  const map = {
    draft: 'info',
    reviewing: 'warning',
    approved: 'success',
    published: 'success',
    running: 'success',
    deprecated: 'warning',
    archived: 'info',
    offline: 'danger',
  }
  return map[status] || 'info'
}

function statusLabel(status) {
  const map = {
    draft: '草稿',
    reviewing: '审核中',
    approved: '已批准',
    published: '已发布',
    running: '运行中',
    deprecated: '已废弃',
    archived: '已归档',
    offline: '已下线',
  }
  return map[status] || status
}

function slaTagType(sla) {
  const map = { platinum: 'danger', gold: 'warning', silver: 'info' }
  return map[sla] || 'info'
}

function costStrategyLabel(strategy) {
  const map = {
    by_call: '按次',
    by_bytes: '按量',
    monthly_package: '月包',
  }
  return map[strategy] || strategy
}
</script>

<style scoped>
.catalog-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.search-card :deep(.el-card__body) {
  padding: 18px 20px 0;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.api-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.name-text {
  font-weight: 500;
}
.version-tag {
  font-size: 12px;
  color: #909399;
  background: #f4f4f5;
  padding: 2px 6px;
  border-radius: 4px;
}
</style>