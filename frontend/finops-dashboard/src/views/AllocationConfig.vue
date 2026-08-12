<template>
  <div class="page">
    <h2>分账配置</h2>
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>新建/编辑分账配置</template>
      <el-form :model="form" label-width="120px">
        <el-form-item label="配置ID">
          <el-input v-model="form.id" placeholder="如 ws-team1-split" />
        </el-form-item>
        <el-form-item label="父工作空间">
          <el-input v-model="form.parentWorkspace" placeholder="如 ns-team1" />
        </el-form-item>
        <el-form-item label="分账维度">
          <el-select v-model="form.dimension" style="width: 200px">
            <el-option label="namespace" value="namespace" />
            <el-option label="工作空间标签" value="workspace_label" />
          </el-select>
        </el-form-item>
        <el-form-item label="分账比例">
          <div v-for="(r, i) in ratioList" :key="i" class="ratio-row">
            <el-input v-model="r.key" placeholder="子工作空间名" style="width: 200px" />
            <el-input-number v-model="r.value" :min="0" :max="1" :step="0.1" :precision="2" style="margin: 0 8px" />
            <el-button type="danger" size="small" @click="ratioList.splice(i, 1)">删除</el-button>
          </div>
          <el-button type="primary" size="small" @click="ratioList.push({ key: '', value: 0 })">
            添加比例
          </el-button>
          <span class="ratio-sum">合计：{{ ratioSum.toFixed(2) }}</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="never">
      <template #header>已有分账配置</template>
      <el-table :data="configs" stripe>
        <el-table-column prop="id" label="配置ID" />
        <el-table-column prop="parentWorkspace" label="父工作空间" />
        <el-table-column prop="dimension" label="维度" />
        <el-table-column label="比例">
          <template #default="{ row }">
            <span v-for="(v, k) in row.ratios" :key="k" style="margin-right: 8px">
              {{ k }}={{ v }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="executeAllocation(row.id)">执行分账</el-button>
            <el-button size="small" type="danger" @click="deleteConfig(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="resultVisible" title="分账结果" width="80%">
      <el-table :data="allocationResults" stripe>
        <el-table-column prop="parentWorkspace" label="父工作空间" />
        <el-table-column prop="subWorkspace" label="子工作空间" />
        <el-table-column prop="ratio" label="比例" />
        <el-table-column prop="originalCost" label="原成本（元）" />
        <el-table-column prop="allocatedCost" label="分账后成本（元）" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import {
  listAllocationConfigs,
  saveAllocationConfig,
  deleteAllocationConfig,
  executeAllocation
} from '@/api/finops'
import type { AllocationConfig, AllocationItem } from '@/types'

const form = reactive({
  id: '',
  parentWorkspace: '',
  dimension: 'namespace',
  enabled: true,
  remark: ''
})

const ratioList = ref<{ key: string; value: number }[]>([{ key: 'default', value: 1.0 }])
const configs = ref<AllocationConfig[]>([])
const saving = ref(false)
const resultVisible = ref(false)
const allocationResults = ref<AllocationItem[]>([])

const ratioSum = computed(() =>
  ratioList.value.reduce((sum, r) => sum + (r.value || 0), 0)
)

async function loadConfigs() {
  try {
    configs.value = await listAllocationConfigs()
  } catch (e) {
    console.error('加载分账配置失败', e)
  }
}

async function saveConfig() {
  if (Math.abs(ratioSum.value - 1.0) > 0.001) {
    alert('分账比例合计必须 = 1.0，当前合计 = ' + ratioSum.value.toFixed(2))
    return
  }
  const ratios: Record<string, number> = {}
  for (const r of ratioList.value) {
    if (r.key) {
      ratios[r.key] = r.value
    }
  }
  const config: AllocationConfig = {
    id: form.id,
    parentWorkspace: form.parentWorkspace,
    dimension: form.dimension,
    ratios,
    enabled: form.enabled,
    remark: form.remark
  }
  saving.value = true
  try {
    await saveAllocationConfig(config)
    await loadConfigs()
    alert('保存成功')
  } catch (e) {
    alert('保存失败：' + e)
  } finally {
    saving.value = false
  }
}

async function deleteConfig(id: string) {
  try {
    await deleteAllocationConfig(id)
    await loadConfigs()
  } catch (e) {
    alert('删除失败：' + e)
  }
}

async function executeAlloc(configId: string) {
  const now = new Date()
  const start = new Date(now.getTime() - 24 * 3600 * 1000)
  try {
    const resp = await executeAllocation({
      configId,
      start: start.toISOString(),
      end: now.toISOString()
    })
    allocationResults.value = resp.items
    resultVisible.value = true
  } catch (e) {
    alert('执行分账失败：' + e)
  }
}

onMounted(loadConfigs)
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
.ratio-row {
  margin-bottom: 8px;
}
.ratio-sum {
  margin-left: 16px;
  color: #909399;
  font-size: 13px;
}
</style>