<template>
  <div class="page">
    <h2>查询计费账单</h2>

    <el-card shadow="never" style="margin-bottom: 16px">
      <el-form :inline="true">
        <el-form-item label="起始日期">
          <el-date-picker
            v-model="startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="开始日期"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker
            v-model="endDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束日期"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadBilling">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>本月总成本</template>
          <div class="cost-number">¥ {{ formattedCost }}</div>
          <div class="cost-meta">租户: {{ billing.tenant || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>查询窗口</template>
          <div class="cost-meta">
            <div>{{ billing.start || '—' }}</div>
            <div>→</div>
            <div>{{ billing.end || '—' }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>SCANNED_DATA 用量 (TB)</template>
          <div class="cost-number">{{ usagesTb?.toFixed(3) ?? '—' }}</div>
          <div class="cost-meta">估算+真实汇总</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>账单明细说明</template>
      <el-descriptions :column="1" border v-if="billing.note">
        <el-descriptions-item label="计费说明">{{ billing.note }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else :image-size="60" description="暂无账单数据，请选择日期查询" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getQueryBilling } from '@/api/finops'

const startDate = ref<string | null>(null)
const endDate = ref<string | null>(null)
const loading = ref(false)
const billing = ref<{
  tenant?: string
  start?: string
  end?: string
  totalCost?: number
  usages?: Record<string, number>
  note?: string
}>({})

const formattedCost = computed(() =>
  billing.value.totalCost == null ? '—' : Number(billing.value.totalCost).toFixed(4)
)

const usagesTb = computed(() => {
  const u = billing.value.usages
  if (!u || !('SCANNED_DATA' in u)) return undefined
  return Number(u['SCANNED_DATA'])
})

async function loadBilling() {
  loading.value = true
  try {
    const params: { start?: string; end?: string } = {}
    if (startDate.value) params.start = startDate.value
    if (endDate.value) params.end = endDate.value
    billing.value = await getQueryBilling(params)
  } catch (e) {
    billing.value = { note: `账单查询失败: ${(e as Error)?.message ?? e}` }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadBilling()
})
</script>

<style scoped>
.cost-number {
  font-size: 28px;
  font-weight: 600;
  color: #409eff;
}
.cost-meta {
  margin-top: 8px;
  font-size: 13px;
  color: #909399;
}
</style>