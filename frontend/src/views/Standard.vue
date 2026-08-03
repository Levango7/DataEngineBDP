<template>
  <div>
    <h1>数据标准</h1>
    <div class="sub">统一字段命名、类型、码值，治理前置，避免"同义不同名"。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建标准</button>
      <div class="spacer"></div>
      <span class="pill b">已落标 78%</span>
    </div>
    <div class="card">
      <table>
        <tr><th>标准项</th><th>类型</th><th>码值/规则</th><th>引用资产</th></tr>
        <tr><td>user_id</td><td>主键</td><td>bigint, 非空</td><td>42</td></tr>
        <tr><td>order_status</td><td>枚举</td><td>待支付/已支付/已发货/已完成/退款</td><td>18</td></tr>
        <tr><td>city_code</td><td>字典</td><td>GB/T 2260</td><td>31</td></tr>
        <tr><td>amount</td><td>金额</td><td>decimal(18,2), ≥0</td><td>55</td></tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建数据标准" @close="modalVisible = false">
      <label>标准项</label><input placeholder="如 user_id" />
      <label>类型</label>
      <select><option>主键</option><option>枚举</option><option>字典</option><option>金额</option></select>
      <label>规则/码值</label><input placeholder="如 bigint,非空" />
      <label>引用资产数</label><input value="0" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('标准已发布')">发布</button>
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