<template>
  <div>
    <h1>安全脱敏</h1>
    <div class="sub">字段级脱敏策略 + 权限申请审批流；密评合规（国密可插拔）。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建脱敏策略</button>
      <div class="spacer"></div>
      <span class="pill r">{{ approvals.length }} 待审批</span>
    </div>
    <div class="card">
      <div v-if="policiesLoading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <div v-else-if="policiesError" style="padding: 16px; color: var(--red)">
        {{ policiesError.message }}，<a href="javascript:void(0)" @click="loadPolicies">重试</a>
      </div>
      <table v-else>
        <tr><th>字段</th><th>所属资产</th><th>策略</th><th>算法</th><th>状态</th></tr>
        <tr v-for="p in policies" :key="p.id">
          <td>{{ p.fieldName }}</td>
          <td>{{ p.assetName }}</td>
          <td>{{ strategyLabel(p.strategy) }}</td>
          <td>{{ p.algorithm }}</td>
          <td><span class="pill" :class="statusPillClass(p.status)">{{ statusPillText(p.status) }}</span></td>
        </tr>
        <tr v-if="policies.length === 0">
          <td colspan="5" style="text-align: center; color: var(--muted)">暂无脱敏策略</td>
        </tr>
      </table>
    </div>
    <div class="section-title">权限申请审批流</div>
    <div class="card">
      <div v-if="approvalsLoading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <table v-else>
        <tr><th>申请人</th><th>资产</th><th>权限</th><th></th></tr>
        <tr v-for="a in approvals" :key="a.id">
          <td>{{ a.applicant }}</td>
          <td>{{ a.asset }}</td>
          <td>{{ a.permission }}</td>
          <td>
            <button class="btn sm" @click="handleApprove(a.id)">批准</button>
            <button class="btn ghost sm" @click="handleReject(a.id)">驳回</button>
          </td>
        </tr>
        <tr v-if="approvals.length === 0">
          <td colspan="4" style="text-align: center; color: var(--muted)">暂无待审批</td>
        </tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建脱敏策略" @close="modalVisible = false">
      <label>字段</label><input v-model="form.fieldName" placeholder="如 real_name" />
      <label>所属资产</label><input v-model="form.assetName" />
      <label>策略</label>
      <select v-model="form.strategy"><option value="mask">掩码</option><option value="hash">哈希</option><option value="authorized_only">仅授权可见</option></select>
      <label>算法</label>
      <select v-model="form.algorithm"><option value="SM3">SM3(国密)</option><option value="SHA256">SHA256</option><option value="AES">AES</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">{{ submitting ? '提交中…' : '提交' }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as secApi from '@/api/sec'
import type { MaskPolicy, PermissionApproval, MaskStrategy, MaskAlgorithm, StrategyStatus } from '@/api/sec'

const store = useAppStore()
const modalVisible = ref(false)
const submitting = ref(false)

// 脱敏策略列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: policiesData,
  loading: policiesLoading,
  error: policiesError,
  execute: loadPolicies
} = useApi<MaskPolicy[]>(() => secApi.listMaskPolicies(), { initialData: [] })
const policies = computed<MaskPolicy[]>(() => policiesData.value ?? [])

// 审批列表：通过 useApi 包装，失败时不阻塞页面
const {
  data: approvalsData,
  loading: approvalsLoading,
  execute: loadApprovals
} = useApi<PermissionApproval[]>(() => secApi.listApprovals('pending'), { initialData: [] })
const approvals = computed<PermissionApproval[]>(() => approvalsData.value ?? [])

/** 批准申请 */
async function handleApprove(id: string) {
  try {
    await secApi.approveApproval(id)
    store.showToast('已批准')
    await loadApprovals()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 驳回申请 */
async function handleReject(id: string) {
  try {
    await secApi.rejectApproval(id)
    store.showToast('已驳回')
    await loadApprovals()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 策略 → 中文 */
function strategyLabel(s: MaskStrategy): string {
  const map: Record<MaskStrategy, string> = {
    mask: '掩码',
    hash: '哈希',
    authorized_only: '仅授权可见',
    plain: '明文'
  }
  return map[s] || s
}

/** 状态 → pill 样式 */
function statusPillClass(s: StrategyStatus): string {
  switch (s) {
    case 'active':
      return 'g'
    case 'pending':
      return 'a'
    default:
      return 'b'
  }
}

/** 状态 → pill 文案 */
function statusPillText(s: StrategyStatus): string {
  switch (s) {
    case 'active':
      return '生效'
    case 'pending':
      return '待审批'
    case 'disabled':
      return '已禁用'
    default:
      return s
  }
}

// 新建表单
const form = reactive<{
  fieldName: string
  assetName: string
  strategy: MaskStrategy
  algorithm: MaskAlgorithm
}>({
  fieldName: '',
  assetName: '',
  strategy: 'mask',
  algorithm: 'SM3'
})

/** 提交创建策略 */
async function handleSubmit() {
  if (!form.fieldName.trim()) {
    store.showToast('请填写字段名')
    return
  }
  submitting.value = true
  try {
    await secApi.createMaskPolicy({
      fieldName: form.fieldName,
      assetName: form.assetName,
      strategy: form.strategy,
      algorithm: form.algorithm
    })
    modalVisible.value = false
    store.showToast('脱敏策略已提交审批')
    await loadPolicies()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadPolicies()
  void loadApprovals()
})
</script>