<template>
  <div>
    <h1>LLMOps</h1>
    <div class="sub">L4.5 · 从微调、评估到部署的一体化大模型运营；基座模型与领域模型统一纳管。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建微调任务</button>
      <div class="spacer"></div>
      <span class="pill p">{{ deployedCount }} 个部署端点</span>
    </div>
    <div class="card">
      <h3>模型注册表</h3>
      <div v-if="loading" style="text-align: center; padding: 24px; color: #888">正在加载模型列表...</div>
      <div v-else-if="error" style="text-align: center; padding: 24px; color: #d4380d">
        加载失败：{{ error }}
        <button class="btn ghost sm" style="margin-left: 8px" @click="loadModels">重试</button>
      </div>
      <table v-else>
        <tr><th>模型</th><th>类型</th><th>基座</th><th>状态</th><th>端点</th></tr>
        <tr v-for="m in models" :key="m.id">
          <td>{{ m.name }}</td>
          <td>{{ typeLabel(m.type) }}</td>
          <td>{{ m.baseModel || '—' }}</td>
          <td><span class="pill" :class="statusClass(m.status)">{{ statusLabel(m.status) }}</span></td>
          <td>{{ m.endpoint || '—' }}</td>
        </tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>评估</h3>
      <div v-if="metricsLoading" style="color: #888">加载中...</div>
      <template v-else>
        <div v-for="m in evalMetrics" :key="m.modelName">
          <div class="kv"><span>{{ m.modelName }} / 准确率</span><span>{{ m.accuracy.toFixed(2) }}</span></div>
          <div class="kv"><span>{{ m.modelName }} / 幻觉率</span><span>{{ (m.hallucinationRate * 100).toFixed(1) }}%</span></div>
          <div class="kv"><span>对比基座提升</span><span>+{{ m.baseLiftPt.toFixed(1) }}pt</span></div>
        </div>
        <button class="btn ghost sm" style="margin-top: 8px" @click="triggerHumanEval">发起人工评估</button>
      </template>
    </div>

    <Modal :visible="modalVisible" title="新建微调任务" @close="modalVisible = false">
      <label>模型名</label><input v-model="finetuneForm.modelName" placeholder="如 营销-领域-3B" />
      <label>基座</label>
      <select v-model="finetuneForm.baseModel"><option>qiong-7B</option></select>
      <label>训练数据</label><input v-model="finetuneForm.trainingData" placeholder="如 营销话术-2026.parquet" />
      <label>显存/卡</label><input v-model="finetuneForm.gpuConfig" value="2×GPU" />
      <label>epochs</label><input v-model.number="finetuneForm.epochs" type="number" value="3" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="submitFinetune">提交</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'
import * as llmopsApi from '@/api/llmops'
import type { ModelRegistry, EvalMetric, ModelType, ModelStatus } from '@/api/llmops'

const store = useAppStore()
const modalVisible = ref(false)

const models = ref<ModelRegistry[]>([])
const evalMetrics = ref<EvalMetric[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const metricsLoading = ref(false)

const finetuneForm = ref({
  modelName: '',
  baseModel: 'qiong-7B',
  trainingData: '',
  gpuConfig: '2×GPU',
  epochs: 3,
})

const deployedCount = computed(() => models.value.filter((m) => m.status === 'deployed').length)

function typeLabel(t: ModelType): string {
  const map: Record<ModelType, string> = {
    base: '基座',
    finetuned: '微调',
  }
  return map[t] || t
}

function statusLabel(s: ModelStatus): string {
  const map: Record<ModelStatus, string> = {
    deployed: '已部署',
    training: '训练中',
    draft: '草稿',
    failed: '失败',
  }
  return map[s] || s
}

function statusClass(s: ModelStatus): string {
  const map: Record<ModelStatus, string> = {
    deployed: 'g',
    training: 'a',
    draft: '',
    failed: 'p',
  }
  return map[s] || ''
}

async function loadModels() {
  loading.value = true
  error.value = null
  try {
    models.value = await llmopsApi.listModels()
  } catch (e) {
    error.value = (e as Error).message || '加载模型列表失败'
  } finally {
    loading.value = false
  }
}

async function loadEvalMetrics() {
  metricsLoading.value = true
  try {
    evalMetrics.value = await llmopsApi.getEvalMetrics()
  } catch (e) {
    store.showToast(`加载评估指标失败：${(e as Error).message}`)
  } finally {
    metricsLoading.value = false
  }
}

async function submitFinetune() {
  if (!finetuneForm.value.modelName) {
    store.showToast('请填写模型名')
    return
  }
  try {
    await llmopsApi.submitFinetune({
      modelName: finetuneForm.value.modelName,
      baseModel: finetuneForm.value.baseModel,
      trainingData: finetuneForm.value.trainingData,
      gpuConfig: finetuneForm.value.gpuConfig,
      epochs: finetuneForm.value.epochs,
    })
    modalVisible.value = false
    store.showToast('微调任务已提交')
    await loadModels()
  } catch (e) {
    store.showToast(`提交失败：${(e as Error).message}`)
  }
}

async function triggerHumanEval() {
  const modelName = evalMetrics.value[0]?.modelName
  if (!modelName) {
    store.showToast('暂无模型可评估')
    return
  }
  try {
    await llmopsApi.triggerHumanEval(modelName)
    store.showToast('已发起人工评估任务')
  } catch (e) {
    store.showToast(`发起失败：${(e as Error).message}`)
  }
}

onMounted(() => {
  loadModels()
  loadEvalMetrics()
})
</script>
