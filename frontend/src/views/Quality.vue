<template>
  <div>
    <h1>数据质量</h1>
    <div class="sub">规则配置即校验，异常自动阻断下游并告警，保障湖仓集数据可信。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建规则</button>
      <div class="spacer"></div>
      <span class="pill g">通过率 94%</span>
    </div>
    <div class="card">
      <table>
        <tr><th>规则</th><th>对象</th><th>校验</th><th>阈值</th><th>最近</th><th>状态</th></tr>
        <tr><td>订单ID非空</td><td>dwd.order_wide</td><td>非空</td><td>100%</td><td>04:00</td><td><span class="pill g">通过</span></td></tr>
        <tr><td>金额非负</td><td>dws.pay_summary</td><td>范围</td><td>≥0</td><td>04:05</td><td><span class="pill g">通过</span></td></tr>
        <tr><td>用户数波动</td><td>ads.user_profile</td><td>波动</td><td>±15%</td><td>04:10</td><td><span class="pill r">告警</span></td></tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建质量规则" @close="modalVisible = false">
      <label>对象表</label><input placeholder="如 dwd.order_wide" />
      <label>字段</label><input placeholder="如 order_id" />
      <label>校验类型</label>
      <select><option>非空</option><option>唯一</option><option>范围</option><option>波动</option></select>
      <label>阈值</label><input value="100%" />
      <label>异常动作</label>
      <select><option>告警</option><option>阻断下游</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('规则已创建')">创建</button>
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