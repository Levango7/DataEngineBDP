<template>
  <div>
    <h1>大模型网关</h1>
    <div class="sub">L4.5 · 统一 API 入口，路由多模型、限流、计费、审计，屏蔽底层部署差异。</div>
    <div class="grid g4">
      <div class="card"><h3>今日调用</h3><div class="kpi s">128K</div><div class="meta">请求数</div></div>
      <div class="card"><h3>平均时延</h3><div class="kpi s">320ms</div></div>
      <div class="card"><h3>成功率</h3><div class="kpi s">99.6%</div></div>
      <div class="card"><h3>活跃 Key</h3><div class="kpi s">14</div></div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>API Key 与路由</h3>
      <table>
        <tr><th>Key 名称</th><th>路由模型</th><th>限流</th><th>状态</th></tr>
        <tr><td>prod-portal</td><td>qiong-7B</td><td>100/s</td><td><span class="pill g">启用</span></td></tr>
        <tr><td>risk-svc</td><td>风控-领域-1.3B</td><td>50/s</td><td><span class="pill g">启用</span></td></tr>
        <tr><td>mkt-exp</td><td>营销-领域-3B</td><td>20/s</td><td><span class="pill a">待上线</span></td></tr>
      </table>
      <button class="btn ghost sm" style="margin-top: 8px" @click="modalVisible = true">+ 新建 Key</button>
    </div>

    <Modal :visible="modalVisible" title="新建 API Key" @close="modalVisible = false">
      <label>Key 名称</label><input placeholder="如 mkt-exp" />
      <label>路由模型</label>
      <select><option>qiong-7B</option><option>风控-领域-1.3B</option><option>营销-领域-3B</option></select>
      <label>限流(/s)</label><input value="20" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('API Key 已生成')">生成</button>
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