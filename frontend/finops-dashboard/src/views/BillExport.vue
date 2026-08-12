<template>
  <div class="page">
    <h2>账单导出</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-card shadow="never">
      <template #header>账单导出</template>
      <el-form :inline="true" :model="form">
        <el-form-item label="导出格式">
          <el-radio-group v-model="form.format">
            <el-radio value="csv">CSV</el-radio>
            <el-radio value="excel">Excel</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="导出类型">
          <el-select v-model="form.type" style="width: 160px">
            <el-option label="明细（按资源）" value="details" />
            <el-option label="汇总（按维度）" value="summary" />
            <el-option label="完整（明细+汇总）" value="full" />
          </el-select>
        </el-form-item>
        <el-form-item label="汇总维度">
          <el-select v-model="form.groupBy" style="width: 160px">
            <el-option label="按租户" value="TENANT" />
            <el-option label="按 namespace" value="NAMESPACE" />
            <el-option label="按工作空间" value="WORKSPACE" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="doExport">导出</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="message"
        :title="message"
        :type="messageType"
        style="margin-top: 16px"
        closable
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { exportBillCsv, exportBillExcel } from '@/api/finops'

const form = reactive({
  format: 'csv' as 'csv' | 'excel',
  type: 'details' as 'details' | 'summary' | 'full',
  groupBy: 'TENANT' as 'TENANT' | 'NAMESPACE' | 'WORKSPACE'
})

const loading = ref(false)
const message = ref('')
const messageType = ref<'success' | 'error'>('success')
const lastParams = ref<{ start: string; end: string; namespace?: string } | null>(null)

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  lastParams.value = params
}

async function doExport() {
  if (!lastParams.value) {
    message.value = '请先选择时间窗口并点击查询'
    messageType.value = 'error'
    return
  }
  loading.value = true
  message.value = ''
  try {
    const params = { ...lastParams.value, type: form.type, groupBy: form.groupBy }
    const blob = form.format === 'csv' ? await exportBillCsv(params) : await exportBillExcel(params)
    const ext = form.format === 'csv' ? 'csv' : 'xlsx'
    const filename = `bill-${form.type}-${Date.now()}.${ext}`
    downloadBlob(blob, filename)
    message.value = `导出成功：${filename}`
    messageType.value = 'success'
  } catch (e) {
    message.value = `导出失败：${e}`
    messageType.value = 'error'
  } finally {
    loading.value = false
  }
}

function downloadBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
</style>