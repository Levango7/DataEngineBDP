<template>
  <div>
    <h1>账户与配额</h1>
    <div class="sub">套餐即容量边界；超额自动扩容或升级套餐，费用清晰可核算。</div>
    <div class="card">
      <h3>当前套餐：企业版</h3>
      <div class="row"><span>CPU 配额</span><span>800 核 / 已用 464</span></div>
      <div class="bar"><i style="width: 58%"></i></div>
      <div class="row" style="margin-top: 12px"><span>内存配额</span><span>3 TB / 已用 2.1</span></div>
      <div class="bar"><i class="a" style="width: 71%"></i></div>
      <div class="row" style="margin-top: 12px"><span>存储配额</span><span>1 PB / 已用 486 TB</span></div>
      <div class="bar"><i style="width: 43%"></i></div>
      <button class="btn ghost sm" style="margin-top: 10px" @click="modalVisible = true">升级套餐</button>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>本月计费明细</h3>
      <table>
        <tr><th>项</th><th>用量</th><th>费用</th></tr>
        <tr><td>计算(CPU·时)</td><td>38,400</td><td>¥ 19,200</td></tr>
        <tr><td>存储(TB·月)</td><td>486</td><td>¥ 9,720</td></tr>
        <tr><td>调用(API·万)</td><td>1,280</td><td>¥ 6,400</td></tr>
        <tr><td><b>合计</b></td><td></td><td><b>¥ 35,320</b></td></tr>
      </table>
      <div class="note">套餐由 ResourceQuota + 节点池租约实现，与 SKE 发行版解耦，客户仅见套餐概念。</div>
    </div>

    <Modal :visible="modalVisible" title="升级套餐" @close="modalVisible = false">
      <label>目标套餐</label>
      <select><option>旗舰版</option><option>企业版+扩容包</option></select>
      <label>预计月费</label><input value="¥ 58,000" />
      <div class="note">升级后经 NodePoolLease 自动扩容，客户无感知停机。</div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('套餐升级已提交')">确认升级</button>
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