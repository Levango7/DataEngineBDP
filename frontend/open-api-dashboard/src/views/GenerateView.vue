<template>
  <div class="generate-view">
    <el-card shadow="never">
      <template #header>
        <span>一键生成 RESTful API</span>
      </template>

      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- SQL 一键生成 -->
        <el-tab-pane label="SQL 查询" name="sql">
          <el-form :model="sqlForm" label-width="120px" class="gen-form">
            <el-form-item label="API 名称" required>
              <el-input v-model="sqlForm.name" placeholder="如：weather-query" />
            </el-form-item>
            <el-form-item label="SQL 查询" required>
              <el-input
                v-model="sqlForm.sql"
                type="textarea"
                :rows="6"
                placeholder="SELECT * FROM weather WHERE city = :city AND date >= :start_date"
              />
              <div class="form-tip">
                支持 :param、${param}、@param 三种参数占位符。仅允许 SELECT 查询。
              </div>
            </el-form-item>
            <el-form-item label="数据源" required>
              <el-select v-model="sqlForm.datasource" placeholder="选择数据源">
                <el-option
                  v-for="ds in options.datasources"
                  :key="ds"
                  :label="ds"
                  :value="ds"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="提供方租户" required>
              <el-input v-model="sqlForm.providerTenantId" placeholder="如：tenant-provider" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="sqlForm.description" placeholder="API 描述（可选）" />
            </el-form-item>
            <el-form-item label="计费策略">
              <el-select v-model="sqlForm.costStrategy" style="width: 160px">
                <el-option label="按次" value="by_call" />
                <el-option label="按量" value="by_bytes" />
                <el-option label="月包" value="monthly_package" />
              </el-select>
              <el-input-number
                v-model="sqlForm.costUnitPrice"
                :min="0"
                :precision="4"
                style="margin-left: 12px; width: 140px"
              />
              <span class="form-tip-inline">元/{{ costUnitLabel(sqlForm.costStrategy) }}</span>
            </el-form-item>
            <el-form-item label="SLA 等级">
              <el-radio-group v-model="sqlForm.sla">
                <el-radio value="silver">银</el-radio>
                <el-radio value="gold">金</el-radio>
                <el-radio value="platinum">铂金</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="generating" @click="handleGenerateSql">
                生成 API
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 模型一键生成 -->
        <el-tab-pane label="模型推理" name="model">
          <el-form :model="modelForm" label-width="120px" class="gen-form">
            <el-form-item label="API 名称" required>
              <el-input v-model="modelForm.name" placeholder="如：llm-chat-completion" />
            </el-form-item>
            <el-form-item label="模型 ID" required>
              <el-input v-model="modelForm.modelId" placeholder="如：gpt-4o-mini" />
            </el-form-item>
            <el-form-item label="模型类型" required>
              <el-select v-model="modelForm.modelType" placeholder="选择模型类型">
                <el-option
                  v-for="mt in options.modelTypes"
                  :key="mt"
                  :label="mt"
                  :value="mt"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="提供方租户" required>
              <el-input v-model="modelForm.providerTenantId" placeholder="如：tenant-provider" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="modelForm.description" placeholder="API 描述（可选）" />
            </el-form-item>
            <el-form-item label="计费策略">
              <el-select v-model="modelForm.costStrategy" style="width: 160px">
                <el-option label="按次" value="by_call" />
                <el-option label="按量" value="by_bytes" />
                <el-option label="月包" value="monthly_package" />
              </el-select>
              <el-input-number
                v-model="modelForm.costUnitPrice"
                :min="0"
                :precision="4"
                style="margin-left: 12px; width: 140px"
              />
              <span class="form-tip-inline">元/{{ costUnitLabel(modelForm.costStrategy) }}</span>
            </el-form-item>
            <el-form-item label="SLA 等级">
              <el-radio-group v-model="modelForm.sla">
                <el-radio value="silver">银</el-radio>
                <el-radio value="gold">金</el-radio>
                <el-radio value="platinum">铂金</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="generating" @click="handleGenerateModel">
                生成 API
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 函数一键生成 -->
        <el-tab-pane label="函数计算" name="function">
          <el-form :model="functionForm" label-width="120px" class="gen-form">
            <el-form-item label="API 名称" required>
              <el-input v-model="functionForm.name" placeholder="如：data-transform" />
            </el-form-item>
            <el-form-item label="函数名" required>
              <el-input v-model="functionForm.functionName" placeholder="如：transform_handler" />
            </el-form-item>
            <el-form-item label="运行时" required>
              <el-select v-model="functionForm.runtime" placeholder="选择运行时">
                <el-option
                  v-for="rt in options.runtimes"
                  :key="rt"
                  :label="rt"
                  :value="rt"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="提供方租户" required>
              <el-input v-model="functionForm.providerTenantId" placeholder="如：tenant-provider" />
            </el-form-item>
            <el-form-item label="超时(ms)">
              <el-input-number v-model="functionForm.timeout" :min="1000" :max="900000" :step="1000" />
            </el-form-item>
            <el-form-item label="内存(MB)">
              <el-input-number v-model="functionForm.memoryMB" :min="128" :max="32768" :step="128" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="functionForm.description" placeholder="API 描述（可选）" />
            </el-form-item>
            <el-form-item label="计费策略">
              <el-select v-model="functionForm.costStrategy" style="width: 160px">
                <el-option label="按次" value="by_call" />
                <el-option label="按量" value="by_bytes" />
                <el-option label="月包" value="monthly_package" />
              </el-select>
              <el-input-number
                v-model="functionForm.costUnitPrice"
                :min="0"
                :precision="4"
                style="margin-left: 12px; width: 140px"
              />
              <span class="form-tip-inline">元/{{ costUnitLabel(functionForm.costStrategy) }}</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="generating" @click="handleGenerateFunction">
                生成 API
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 生成结果 -->
    <el-card v-if="generatedApi" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>生成结果</span>
          <el-tag type="success">成功</el-tag>
        </div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="API ID">{{ generatedApi.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ generatedApi.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ generatedApi.version }}</el-descriptions-item>
        <el-descriptions-item label="路径">
          <el-tag size="small">{{ generatedApi.method }}</el-tag>
          {{ generatedApi.path }}
        </el-descriptions-item>
        <el-descriptions-item label="分类">{{ generatedApi.category }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ generatedApi.status }}</el-descriptions-item>
        <el-descriptions-item label="上游类型">{{ generatedApi.upstream?.type }}</el-descriptions-item>
        <el-descriptions-item label="上游 URL">{{ generatedApi.upstream?.url }}</el-descriptions-item>
        <el-descriptions-item label="参数数量">{{ generatedApi.params?.length }} 个</el-descriptions-item>
        <el-descriptions-item label="标签">
          <el-tag v-for="tag in generatedApi.tags" :key="tag" size="small" style="margin-right: 4px">
            {{ tag }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 16px">
        <el-button type="primary" @click="$router.push(`/api-detail/${generatedApi.id}`)">
          查看详情
        </el-button>
        <el-button @click="generatedApi = null">继续生成</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getGenerateOptions,
  generateFromSql,
  generateFromModel,
  generateFromFunction,
} from '@/api/catalog'

const activeTab = ref('sql')
const generating = ref(false)
const generatedApi = ref(null)

const options = reactive({
  datasources: [],
  modelTypes: [],
  runtimes: [],
})

const sqlForm = reactive({
  name: '',
  sql: '',
  datasource: 'trino',
  providerTenantId: 'tenant-provider',
  description: '',
  costStrategy: 'by_call',
  costUnitPrice: 0.01,
  sla: 'silver',
})

const modelForm = reactive({
  name: '',
  modelId: '',
  modelType: 'llm',
  providerTenantId: 'tenant-provider',
  description: '',
  costStrategy: 'by_call',
  costUnitPrice: 0.10,
  sla: 'gold',
})

const functionForm = reactive({
  name: '',
  functionName: '',
  runtime: 'python',
  providerTenantId: 'tenant-provider',
  timeout: 30000,
  memoryMB: 512,
  description: '',
  costStrategy: 'by_call',
  costUnitPrice: 0.001,
})

onMounted(async () => {
  try {
    const data = await getGenerateOptions()
    options.datasources = data.datasources || []
    options.modelTypes = data.modelTypes || []
    options.runtimes = data.runtimes || []
  } catch (err) {
    options.datasources = ['trino', 'doris', 'hive', 'mysql', 'postgresql']
    options.modelTypes = ['llm', 'embedding', 'rerank', 'classification', 'image']
    options.runtimes = ['python', 'nodejs', 'java', 'go']
  }
})

function handleTabChange() {
  generatedApi.value = null
}

function costUnitLabel(strategy) {
  const map = { by_call: '次', by_bytes: 'KB', monthly_package: '月' }
  return map[strategy] || '次'
}

async function handleGenerateSql() {
  if (!sqlForm.name || !sqlForm.sql) {
    ElMessage.warning('请填写 API 名称和 SQL 查询')
    return
  }
  generating.value = true
  try {
    generatedApi.value = await generateFromSql({ ...sqlForm })
    ElMessage.success('API 生成成功')
  } catch (err) {
    ElMessage.error(err.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function handleGenerateModel() {
  if (!modelForm.name || !modelForm.modelId) {
    ElMessage.warning('请填写 API 名称和模型 ID')
    return
  }
  generating.value = true
  try {
    generatedApi.value = await generateFromModel({ ...modelForm })
    ElMessage.success('API 生成成功')
  } catch (err) {
    ElMessage.error(err.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function handleGenerateFunction() {
  if (!functionForm.name || !functionForm.functionName) {
    ElMessage.warning('请填写 API 名称和函数名')
    return
  }
  generating.value = true
  try {
    generatedApi.value = await generateFromFunction({ ...functionForm })
    ElMessage.success('API 生成成功')
  } catch (err) {
    ElMessage.error(err.message || '生成失败')
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.generate-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.gen-form {
  max-width: 800px;
  padding: 20px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.form-tip-inline {
  margin-left: 8px;
  color: #909399;
  font-size: 13px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.result-card {
  border: 1px solid #67c23a;
}
</style>
