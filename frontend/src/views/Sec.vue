<template>
  <div>
    <h1>安全脱敏</h1>
    <div class="sub">字段级脱敏策略 + 权限申请审批流；密评合规（国密可插拔）。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建脱敏策略</button>
      <div class="spacer"></div>
      <span class="pill r">{{ store.secApprovals.length }} 待审批</span>
    </div>
    <div class="card">
      <table>
        <tr><th>字段</th><th>所属资产</th><th>策略</th><th>算法</th><th>状态</th></tr>
        <tr><td>user_phone</td><td>dim.user</td><td>掩码</td><td>138****8000</td><td><span class="pill g">生效</span></td></tr>
        <tr><td>id_card</td><td>dwd.order_wide</td><td>哈希</td><td>SM3</td><td><span class="pill g">生效</span></td></tr>
        <tr><td>real_name</td><td>ads.user_profile</td><td>脱敏</td><td>仅授权可见</td><td><span class="pill a">待审批</span></td></tr>
      </table>
    </div>
    <div class="section-title">权限申请审批流</div>
    <div class="card">
      <table>
        <tr><th>申请人</th><th>资产</th><th>权限</th><th></th></tr>
        <tr v-for="s in store.secApprovals" :key="s.id">
          <td>{{ s.applicant }}</td>
          <td>{{ s.asset }}</td>
          <td>{{ s.perm }}</td>
          <td>
            <button class="btn sm" @click="store.approve(s.id)">批准</button>
            <button class="btn ghost sm" @click="store.reject(s.id)">驳回</button>
          </td>
        </tr>
        <tr v-if="store.secApprovals.length === 0">
          <td colspan="4" style="text-align: center; color: var(--muted)">暂无待审批</td>
        </tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建脱敏策略" @close="modalVisible = false">
      <label>字段</label><input placeholder="如 real_name" />
      <label>所属资产</label><input />
      <label>策略</label>
      <select><option>掩码</option><option>哈希</option><option>仅授权可见</option></select>
      <label>算法</label>
      <select><option>SM3(国密)</option><option>SHA256</option><option>AES</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('脱敏策略已提交审批')">提交</button>
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