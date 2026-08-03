<template>
  <div>
    <h1>LLMOps</h1>
    <div class="sub">L4.5 · 从微调、评估到部署的一体化大模型运营；基座模型与领域模型统一纳管。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建微调任务</button>
      <div class="spacer"></div>
      <span class="pill p">2 个部署端点</span>
    </div>
    <div class="card">
      <h3>模型注册表</h3>
      <table>
        <tr><th>模型</th><th>类型</th><th>基座</th><th>状态</th><th>端点</th></tr>
        <tr><td>qiong-7B</td><td>基座</td><td>—</td><td><span class="pill g">已部署</span></td><td>/v1/qiong-7b</td></tr>
        <tr><td>风控-领域-1.3B</td><td>微调</td><td>qiong-7B</td><td><span class="pill g">已部署</span></td><td>/v1/risk-1.3b</td></tr>
        <tr><td>营销-领域-3B</td><td>微调</td><td>qiong-7B</td><td><span class="pill a">训练中</span></td><td>—</td></tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>评估</h3>
      <div class="kv"><span>营销-领域-3B / 准确率</span><span>0.86</span></div>
      <div class="kv"><span>营销-领域-3B / 幻觉率</span><span>4.2%</span></div>
      <div class="kv"><span>对比基座提升</span><span>+11.3pt</span></div>
      <button class="btn ghost sm" style="margin-top: 8px" @click="store.showToast('已发起人工评估任务')">发起人工评估</button>
    </div>

    <Modal :visible="modalVisible" title="新建微调任务" @close="modalVisible = false">
      <label>模型名</label><input placeholder="如 营销-领域-3B" />
      <label>基座</label>
      <select><option>qiong-7B</option></select>
      <label>训练数据</label><input placeholder="如 营销话术-2026.parquet" />
      <label>显存/卡</label><input value="2×GPU" />
      <label>epochs</label><input value="3" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('微调任务已提交')">提交</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'

const store = useAppStore()
const modalVisible = ref(false)
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>