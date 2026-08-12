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
            <div class="card-title">资产登记</div>
            <el-form :model="form" label-width="120px" style="max-width: 600px">
              <el-form-item label="资产名称" required>
                <el-input v-model="form.name" placeholder="请输入资产名称" />
              </el-form-item>
              <el-form-item label="资产类型" required>
                <el-select v-model="form.type" placeholder="请选择">
                  <el-option label="数据集 (table)" value="table" />
                  <el-option label="API 服务 (api)" value="api" />
                  <el-option label="ML 模型 (model)" value="model" />
                  <el-option label="仪表盘 (dashboard)" value="dashboard" />
                  <el-option label="数据流 (stream)" value="stream" />
                </el-select>
              </el-form-item>
              <el-form-item label="租户 ID" required>
                <el-input v-model="form.tenantId" placeholder="提供方租户 ID" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="form.description" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="安全分级">
                <el-select v-model="form.securityLevel">
                  <el-option label="公开 (public)" value="public" />
                  <el-option label="内部 (internal)" value="internal" />
                  <el-option label="敏感 (sensitive)" value="sensitive" />
                </el-select>
              </el-form-item>
              <el-form-item label="质量评分">
                <el-input-number v-model="form.qualityScore" :min="0" :max="100" />
              </el-form-item>
              <el-form-item label="定价方式">
                <el-select v-model="form.pricing.mode">
                  <el-option label="按次 (by_call)" value="by_call" />
                  <el-option label="按量 (by_data)" value="by_data" />
                  <el-option label="订阅 (subscription)" value="subscription" />
                  <el-option label="按时间 (by_time)" value="by_time" />
                  <el-option label="一次性 (one_time)" value="one_time" />
                </el-select>
              </el-form-item>
              <el-form-item label="单价（元）">
                <el-input-number v-model="form.pricing.price" :min="0" :precision="2" />
              </el-form-item>
              <el-form-item label="计价单位">
                <el-input v-model="form.pricing.unit" placeholder="如：次/千行/月" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="submitRegister">登记</el-button>
                <el-button @click="resetForm">重置</el-button>
              </el-form-item>
            </el-form>
          </div>

          <div class="card" v-if="registeredAsset">
            <div class="card-title">登记结果</div>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="资产 ID">{{ registeredAsset.id }}</el-descriptions-item>
              <el-descriptions-item label="名称">{{ registeredAsset.name }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ registeredAsset.status }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ registeredAsset.type }}</el-descriptions-item>
            </el-descriptions>
            <div style="margin-top: 16px">
              <el-button type="success" @click="publish" :disabled="registeredAsset.status !== 'draft'">上架</el-button>
              <el-button @click="audit('approved')" :disabled="registeredAsset.status === 'listed'">审核通过</el-button>
              <el-button type="danger" @click="audit('rejected')" :disabled="registeredAsset.status === 'rejected'">审核驳回</el-button>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  registerAsset,
  publishAsset,
  auditAsset,
  type Asset,
} from '@/api/assetExchange'

const route = useRoute()
const activeMenu = ref(route.path)

const form = reactive({
  name: '',
  type: 'table',
  tenantId: '',
  description: '',
  securityLevel: 'internal',
  qualityScore: 80,
  pricing: {
    mode: 'by_call',
    price: 1.0,
    unit: '次',
  },
})

const registeredAsset = ref<Asset | null>(null)

async function submitRegister() {
  if (!form.name || !form.tenantId) {
    ElMessage.warning('请填写资产名称和租户 ID')
    return
  }
  try {
    const resp = await registerAsset(form)
    registeredAsset.value = resp.data
    ElMessage.success('登记成功')
  } catch (e: any) {
    ElMessage.error('登记失败: ' + (e?.message || ''))
  }
}

async function publish() {
  if (!registeredAsset.value) return
  try {
    const resp = await publishAsset(registeredAsset.value.id)
    registeredAsset.value = resp.data
    ElMessage.success('上架成功')
  } catch (e: any) {
    ElMessage.error('上架失败: ' + (e?.message || ''))
  }
}

async function audit(result: 'approved' | 'rejected') {
  if (!registeredAsset.value) return
  try {
    const resp = await auditAsset(registeredAsset.value.id, {
      result,
      auditorId: 'admin-001',
    })
    registeredAsset.value = resp.data
    ElMessage.success(`审核${result === 'approved' ? '通过' : '驳回'}`)
  } catch (e: any) {
    ElMessage.error('审核失败: ' + (e?.message || ''))
  }
}

function resetForm() {
  form.name = ''
  form.description = ''
  form.qualityScore = 80
  registeredAsset.value = null
}
</script>