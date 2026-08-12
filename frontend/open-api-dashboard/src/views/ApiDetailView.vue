<template>
  <div class="api-detail-view">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>API 详情</span>
          <div>
            <el-button @click="$router.back()">返回</el-button>
            <el-button type="primary" @click="handlePublish" :loading="publishing">
              发布
            </el-button>
          </div>
        </div>
      </template>

      <el-descriptions v-if="api" :column="2" border>
        <el-descriptions-item label="API ID">{{ api.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ api.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ api.version }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small">{{ api.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="方法">
          <el-tag size="small">{{ api.method }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="路径">{{ api.path }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ api.category }}</el-descriptions-item>
        <el-descriptions-item label="SLA">{{ api.sla }}</el-descriptions-item>
        <el-descriptions-item label="计费策略">{{ api.costStrategy }}</el-descriptions-item>
        <el-descriptions-item label="单价">{{ api.costUnitPrice }}</el-descriptions-item>
        <el-descriptions-item label="上游类型">{{ api.upstream?.type }}</el-descriptions-item>
        <el-descriptions-item label="上游 URL">{{ api.upstream?.url }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ api.description }}</el-descriptions-item>
        <el-descriptions-item label="标签" :span="2">
          <el-tag v-for="tag in api.tags" :key="tag" size="small" style="margin-right: 4px">
            {{ tag }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span>参数列表</span>
          </template>
          <el-table :data="api?.params || []" stripe>
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="location" label="位置" width="80" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="required" label="必填" width="60">
              <template #default="{ row }">
                {{ row.required ? '是' : '否' }}
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <span>调用统计</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="调用次数">{{ api?.callCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="错误次数">{{ api?.errorCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="平均延迟">
              {{ api ? (api.callCount ? (api.totalLatencyMs / api.callCount).toFixed(1) : '0') : '0' }} ms
            </el-descriptions-item>
            <el-descriptions-item label="总流量">
              {{ formatBytes(api?.totalTrafficBytes || 0) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <span>APISIX 路由配置</span>
      </template>
      <el-button type="primary" @click="loadApisixConfig" :loading="loadingConfig">
        加载配置
      </el-button>
      <pre v-if="apisixConfig" class="config-preview">{{ JSON.stringify(apisixConfig, null, 2) }}</pre>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getApi,
  submitReview,
  approveApi,
  publishApi,
  getApisixConfig,
} from '@/api/catalog'

const route = useRoute()
const api = ref(null)
const apisixConfig = ref(null)
const publishing = ref(false)
const loadingConfig = ref(false)

async function loadApi() {
  try {
    api.value = await getApi(route.params.id)
  } catch (err) {
    ElMessage.error(err.message || '加载失败')
  }
}

async function handlePublish() {
  publishing.value = true
  try {
    await submitReview(route.params.id)
    await approveApi(route.params.id)
    api.value = await publishApi(route.params.id)
    ElMessage.success('API 已发布')
  } catch (err) {
    ElMessage.error(err.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

async function loadApisixConfig() {
  loadingConfig.value = true
  try {
    apisixConfig.value = await getApisixConfig(route.params.id)
  } catch (err) {
    ElMessage.error(err.message || '加载失败')
  } finally {
    loadingConfig.value = false
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}

onMounted(loadApi)
</script>

<style scoped>
.api-detail-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.config-preview {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 13px;
  overflow-x: auto;
  margin-top: 16px;
}
</style>