<template>
  <div class="version-management-page">
    <el-card>
      <template #header>版本管理</template>

      <el-tabs v-model="activeTab">
        <!-- Adapter 版本管理 -->
        <el-tab-pane label="Adapter 版本" name="adapter">
          <el-form :inline="true" class="filter-form">
            <el-form-item label="基座模型">
              <el-input v-model="adapterFilter.baseModel" placeholder="meta-llama/Llama-2-7b-hf" />
            </el-form-item>
            <el-form-item label="微调方式">
              <el-select v-model="adapterFilter.method" placeholder="全部" clearable>
                <el-option label="LoRA" value="lora" />
                <el-option label="QLoRA" value="qlora" />
                <el-option label="全参" value="full" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadAdapterVersions">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="adapterVersions" v-loading="loadingAdapter" stripe>
            <el-table-column prop="version" label="版本号" width="120" />
            <el-table-column prop="baseModel" label="基座模型" width="220" />
            <el-table-column prop="method" label="微调方式" width="100" />
            <el-table-column prop="framework" label="框架" width="120" />
            <el-table-column prop="adapterPath" label="Adapter 路径" width="300" />
            <el-table-column prop="loopTaskId" label="闭环任务 ID" width="180" />
            <el-table-column label="是否激活" width="100">
              <template #default="{ row }">
                <el-tag :type="row.isActive ? 'success' : 'info'">{{ row.isActive ? '激活' : '未激活' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="showCompareDialog(row)">对比</el-button>
                <el-button size="small" type="warning" :disabled="row.isActive" @click="handleRollback(row)">回滚</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 评测报告版本 -->
        <el-tab-pane label="评测报告" name="report">
          <el-form :inline="true" class="filter-form">
            <el-form-item label="Adapter 版本">
              <el-input v-model="reportFilter.adapterVersion" placeholder="如 0.1.0" />
            </el-form-item>
            <el-form-item label="数据集">
              <el-select v-model="reportFilter.dataset" placeholder="全部" clearable>
                <el-option label="CMMLU" value="cmmlu" />
                <el-option label="MMLU" value="mmlu" />
                <el-option label="CEval" value="ceval" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadReportVersions">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="reportVersions" v-loading="loadingReport" stripe>
            <el-table-column prop="version" label="报告版本" width="100" />
            <el-table-column prop="adapterVersion" label="Adapter 版本" width="120" />
            <el-table-column prop="dataset" label="数据集" width="100" />
            <el-table-column prop="accuracy" label="准确率" width="100">
              <template #default="{ row }">{{ row.accuracy?.toFixed(4) }}</template>
            </el-table-column>
            <el-table-column prop="recall" label="召回率" width="100">
              <template #default="{ row }">{{ row.recall?.toFixed(4) }}</template>
            </el-table-column>
            <el-table-column prop="f1" label="F1" width="100">
              <template #default="{ row }">{{ row.f1?.toFixed(4) }}</template>
            </el-table-column>
            <el-table-column prop="latencyP95" label="P95 延迟" width="120">
              <template #default="{ row }">{{ row.latencyP95?.toFixed(2) }} ms</template>
            </el-table-column>
            <el-table-column prop="hallucination" label="幻觉率" width="100">
              <template #default="{ row }">{{ row.hallucination?.toFixed(4) }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="showReportCompareDialog(row)">对比</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 版本对比对话框 -->
    <el-dialog v-model="compareDialogVisible" title="版本对比" width="700px">
      <el-form :inline="true">
        <el-form-item label="版本 A">
          <el-input v-model="compareForm.versionA" />
        </el-form-item>
        <el-form-item label="版本 B">
          <el-input v-model="compareForm.versionB" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleCompare">对比</el-button>
        </el-form-item>
      </el-form>
      <pre v-if="compareResult">{{ JSON.stringify(compareResult, null, 2) }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAdapterVersions,
  listReportVersions,
  compareAdapterVersions,
  compareReportVersions,
  rollbackAdapter
} from '@/api/loop'
import type { AdapterVersion, ReportVersion } from '@/types'

const activeTab = ref('adapter')

// Adapter 版本
const adapterVersions = ref<AdapterVersion[]>([])
const loadingAdapter = ref(false)
const adapterFilter = ref({
  baseModel: 'meta-llama/Llama-2-7b-hf',
  method: '',
  framework: ''
})

// 报告版本
const reportVersions = ref<ReportVersion[]>([])
const loadingReport = ref(false)
const reportFilter = ref({
  adapterVersion: '',
  dataset: ''
})

// 对比对话框
const compareDialogVisible = ref(false)
const compareForm = ref({ versionA: '', versionB: '' })
const compareResult = ref<any>(null)
const compareType = ref<'adapter' | 'report'>('adapter')

// 加载 Adapter 版本
async function loadAdapterVersions() {
  if (!adapterFilter.value.baseModel) {
    ElMessage.warning('请输入基座模型')
    return
  }
  loadingAdapter.value = true
  try {
    const resp = await listAdapterVersions({
      baseModel: adapterFilter.value.baseModel,
      method: adapterFilter.value.method || undefined,
      framework: adapterFilter.value.framework || undefined
    })
    adapterVersions.value = resp.versions
  } catch (e) {
    ElMessage.error('加载版本列表失败')
  } finally {
    loadingAdapter.value = false
  }
}

// 加载报告版本
async function loadReportVersions() {
  loadingReport.value = true
  try {
    const resp = await listReportVersions({
      adapterVersion: reportFilter.value.adapterVersion || undefined,
      dataset: reportFilter.value.dataset || undefined
    })
    reportVersions.value = resp.versions
  } catch (e) {
    ElMessage.error('加载报告版本失败')
  } finally {
    loadingReport.value = false
  }
}

// 显示对比对话框
function showCompareDialog(row: AdapterVersion) {
  compareType.value = 'adapter'
  compareForm.value = { versionA: row.version, versionB: '' }
  compareResult.value = null
  compareDialogVisible.value = true
}

function showReportCompareDialog(row: ReportVersion) {
  compareType.value = 'report'
  compareForm.value = { versionA: row.version, versionB: '' }
  compareResult.value = null
  compareDialogVisible.value = true
}

// 执行对比
async function handleCompare() {
  try {
    if (compareType.value === 'adapter') {
      compareResult.value = await compareAdapterVersions({
        baseModel: adapterFilter.value.baseModel,
        versionA: compareForm.value.versionA,
        versionB: compareForm.value.versionB
      })
    } else {
      compareResult.value = await compareReportVersions({
        versionA: compareForm.value.versionA,
        versionB: compareForm.value.versionB
      })
    }
  } catch (e) {
    ElMessage.error('对比失败')
  }
}

// 回滚
async function handleRollback(row: AdapterVersion) {
  try {
    await rollbackAdapter({
      baseModel: row.baseModel,
      version: row.version,
      method: row.method,
      framework: row.framework
    })
    ElMessage.success(`已回滚到版本 ${row.version}`)
    loadAdapterVersions()
  } catch (e) {
    ElMessage.error('回滚失败')
  }
}

function formatTime(t: string): string {
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(() => {
  loadAdapterVersions()
  loadReportVersions()
})
</script>

<style scoped>
.version-management-page {
  padding: 20px;
}
.filter-form {
  margin-bottom: 16px;
}
pre {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  max-height: 400px;
  overflow: auto;
}
</style>