<template>
  <el-card class="time-window-card" shadow="never">
    <el-form :inline="true" :model="form">
      <el-form-item label="起始时间">
        <el-date-picker
          v-model="form.start"
          type="datetime"
          placeholder="选择起始时间"
          format="YYYY-MM-DDTHH:mm:ssZ"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
        />
      </el-form-item>
      <el-form-item label="结束时间">
        <el-date-picker
          v-model="form.end"
          type="datetime"
          placeholder="选择结束时间"
          format="YYYY-MM-DDTHH:mm:ssZ"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
        />
      </el-form-item>
      <el-form-item label="namespace">
        <el-input v-model="form.namespace" placeholder="可选" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="emitQuery">查询</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { reactive } from 'vue'

const emit = defineEmits<{
  (e: 'query', params: { start: string; end: string; namespace?: string }): void
}>()

const form = reactive({
  start: defaultStart(),
  end: defaultEnd(),
  namespace: ''
})

function defaultStart(): string {
  const d = new Date()
  d.setHours(d.getHours() - 24)
  return d.toISOString()
}

function defaultEnd(): string {
  return new Date().toISOString()
}

function emitQuery() {
  const params: { start: string; end: string; namespace?: string } = {
    start: form.start,
    end: form.end
  }
  if (form.namespace) {
    params.namespace = form.namespace
  }
  emit('query', params)
}
</script>

<style scoped>
.time-window-card {
  margin-bottom: 16px;
}
</style>