<template>
  <div class="infra-net-page">
    <h1>容器网络</h1>
    <div class="sub">CNI 配置 · CIDR 规划 · NetworkPolicy · 流量策略管理</div>

    <!-- 集群选择器 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <span class="label">目标集群：</span>
        <el-select
          v-model="selectedClusterKey"
          placeholder="请选择集群"
          style="width: 320px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusterOptions"
            :key="`${c.environment}/${c.clusterId}`"
            :label="`${c.clusterName}（${envLabel(c.environment)}）`"
            :value="`${c.environment}/${c.clusterId}`"
          />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="reloadAll" />
      </div>
    </el-card>

    <!-- KPI 卡片区：三态 -->
    <div class="grid g4" style="margin-top: 16px">
      <template v-if="!selectedCluster">
        <div class="card" style="grid-column: span 4">
          <h3>请先选择目标集群</h3>
          <div class="meta">在上方下拉框中选择需要管理的集群</div>
        </div>
      </template>
      <template v-else-if="configLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="configError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ configError.message }}，
            <a href="javascript:void(0)" @click="loadConfig">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="networkConfig">
        <div class="card">
          <h3>CNI 插件</h3>
          <div class="kpi">{{ networkConfig.cni }}</div>
          <div class="meta">容器网络接口</div>
        </div>
        <div class="card">
          <h3>IP 协议族</h3>
          <div class="kpi">{{ networkConfig.ipFamily }}</div>
          <div class="meta">MTU {{ networkConfig.mtu }}</div>
        </div>
        <div class="card">
          <h3>NetworkPolicy 数</h3>
          <div class="kpi s">{{ policies?.length ?? 0 }}</div>
          <div class="meta">已下发策略</div>
        </div>
        <div class="card">
          <h3>异常策略</h3>
          <div class="kpi d">{{ abnormalPolicyCount }}</div>
          <div class="meta">需复核</div>
        </div>
      </template>
    </div>

    <!-- 网络配置卡片 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>网络配置</span>
          <el-button type="primary" size="small" @click="openEditConfig">编辑</el-button>
        </div>
      </template>
      <template v-if="configLoading">
        <div class="meta">加载中…</div>
      </template>
      <template v-else-if="configError">
        <div class="meta" style="color: var(--muted)">网络配置加载失败</div>
      </template>
      <template v-else-if="networkConfig">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="CNI 插件">
            <el-tag effect="light">{{ networkConfig.cni }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="IP 协议族">
            {{ networkConfig.ipFamily }}
          </el-descriptions-item>
          <el-descriptions-item label="Pod CIDR">{{ networkConfig.podCidr }}</el-descriptions-item>
          <el-descriptions-item label="Service CIDR">
            {{ networkConfig.serviceCidr }}
          </el-descriptions-item>
          <el-descriptions-item label="MTU">{{ networkConfig.mtu }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- NetworkPolicy 列表 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>NetworkPolicy 列表</span>
          <el-button type="primary" size="small" @click="openCreatePolicyDialog">
            + 下发策略
          </el-button>
        </div>
      </template>
      <el-table
        v-loading="policiesLoading"
        :data="policies ?? []"
        stripe
        border
        style="width: 100%"
        :empty-text="policiesError ? '加载失败，请重试' : '暂无策略'"
      >
        <el-table-column prop="name" label="策略名" min-width="180" />
        <el-table-column prop="namespace" label="命名空间" width="160" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="policyTypeTagType(row.type)" effect="light">
              {{ policyTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="端口" width="200">
          <template #default="{ row }">
            <el-tag
              v-for="p in row.ports"
              :key="p"
              size="small"
              effect="plain"
              style="margin-right: 4px"
            >
              {{ p }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="selector" label="Pod 选择器" min-width="200" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeletePolicy(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑网络配置弹窗 -->
    <el-dialog
      v-model="editConfigVisible"
      title="编辑网络配置"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item label="CNI 插件">
          <el-select v-model="editConfig.cni" style="width: 100%">
            <el-option label="calico" value="calico" />
            <el-option label="flannel" value="flannel" />
            <el-option label="cilium" value="cilium" />
            <el-option label="kube-ovn" value="kube-ovn" />
          </el-select>
        </el-form-item>
        <el-form-item label="Pod CIDR">
          <el-input v-model="editConfig.podCidr" />
        </el-form-item>
        <el-form-item label="Service CIDR">
          <el-input v-model="editConfig.serviceCidr" />
        </el-form-item>
        <el-form-item label="IP 协议族">
          <el-select v-model="editConfig.ipFamily" style="width: 100%">
            <el-option label="IPv4" value="IPv4" />
            <el-option label="IPv6" value="IPv6" />
            <el-option label="DualStack" value="DualStack" />
          </el-select>
        </el-form-item>
        <el-form-item label="MTU">
          <el-input-number v-model="editConfig.mtu" :min="1200" :max="9000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editConfigVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="handleSaveConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 下发 NetworkPolicy 弹窗 -->
    <el-dialog
      v-model="createPolicyVisible"
      title="下发 NetworkPolicy"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetPolicyForm"
    >
      <el-form
        ref="policyFormRef"
        :model="policyForm"
        :rules="policyRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="策略名" prop="name">
          <el-input v-model="policyForm.name" placeholder="如 deny-by-default" />
        </el-form-item>
        <el-form-item label="命名空间" prop="namespace">
          <el-input v-model="policyForm.namespace" placeholder="如 default" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="policyForm.type" style="width: 100%">
            <el-option label="入站 (ingress)" value="ingress" />
            <el-option label="出站 (egress)" value="egress" />
            <el-option label="双向 (both)" value="both" />
          </el-select>
        </el-form-item>
        <el-form-item label="端口">
          <el-input v-model="policyPortsInput" placeholder="逗号分隔，如 80,443,3306" />
        </el-form-item>
        <el-form-item label="Pod 选择器" prop="selector">
          <el-input v-model="policyForm.selector" placeholder="如 app=web" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createPolicyVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingPolicy" @click="handleCreatePolicy">
          下发
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as infraApi from '@/api/infra'
import type {
  CrossEnvClusterInfo,
  ClusterEnv,
  NetworkConfig,
  NetworkPolicy,
  NetworkPolicyType
} from '@/api/infra'

/* ------------------------------ 集群选择 ------------------------------ */

const { data: clusterList, execute: loadClusters } = useApi<CrossEnvClusterInfo[]>(() =>
  infraApi.getClusters()
)

const clusterOptions = computed(() => clusterList.value ?? [])
const selectedClusterKey = ref<string>('')
const selectedCluster = ref<CrossEnvClusterInfo | null>(null)

/** 解析选中集群 */
function handleClusterChange(key: string) {
  if (!key) {
    selectedCluster.value = null
    return
  }
  const [env, clusterId] = key.split('/')
  const found = clusterOptions.value.find((c) => c.environment === env && c.clusterId === clusterId)
  selectedCluster.value = found ?? null
}

/** 自动选中第一个集群 */
watch(clusterOptions, (list) => {
  if (list.length > 0 && !selectedCluster.value) {
    const first = list[0]
    selectedClusterKey.value = `${first.environment}/${first.clusterId}`
    selectedCluster.value = first
  }
})

/* ------------------------------ 网络配置 ------------------------------ */

const configLoading = ref(false)
const configError = ref<Error | null>(null)
const networkConfig = ref<NetworkConfig | null>(null)

/** 加载网络配置 */
async function loadConfig() {
  if (!selectedCluster.value) return
  configLoading.value = true
  configError.value = null
  try {
    networkConfig.value = await infraApi.getNetworkConfig(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch (e) {
    configError.value = e instanceof Error ? e : new Error(String(e))
  } finally {
    configLoading.value = false
  }
}

/* ------------------------------ NetworkPolicy 列表 ------------------------------ */

const policiesLoading = ref(false)
const policiesError = ref(false)
const policies = ref<NetworkPolicy[]>([])

/** 加载策略列表 */
async function loadPolicies() {
  if (!selectedCluster.value) return
  policiesLoading.value = true
  policiesError.value = false
  try {
    policies.value = await infraApi.getNetworkPolicies(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch {
    policiesError.value = true
  } finally {
    policiesLoading.value = false
  }
}

/** 异常策略数（演示用：端口含 0 或选择器为空视为异常） */
const abnormalPolicyCount = computed(() => {
  return (policies.value ?? []).filter((p) => p.ports.includes(0) || !p.selector).length
})

/* ------------------------------ 编辑网络配置 ------------------------------ */

const editConfigVisible = ref(false)
const savingConfig = ref(false)
const editConfig = reactive<NetworkConfig>({
  cni: 'calico',
  podCidr: '',
  serviceCidr: '',
  ipFamily: 'IPv4',
  mtu: 1500
})

/** 打开编辑弹窗 */
function openEditConfig() {
  if (!networkConfig.value) return
  editConfig.cni = networkConfig.value.cni
  editConfig.podCidr = networkConfig.value.podCidr
  editConfig.serviceCidr = networkConfig.value.serviceCidr
  editConfig.ipFamily = networkConfig.value.ipFamily
  editConfig.mtu = networkConfig.value.mtu
  editConfigVisible.value = true
}

/** 保存配置 */
async function handleSaveConfig() {
  if (!selectedCluster.value) return
  savingConfig.value = true
  try {
    await infraApi.updateNetworkConfig(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      { ...editConfig }
    )
    ElMessage.success('网络配置已更新')
    editConfigVisible.value = false
    await loadConfig()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    savingConfig.value = false
  }
}

/* ------------------------------ 下发 NetworkPolicy ------------------------------ */

const createPolicyVisible = ref(false)
const savingPolicy = ref(false)
const policyFormRef = ref<FormInstance>()
const policyPortsInput = ref('')

interface PolicyForm {
  name: string
  namespace: string
  type: NetworkPolicyType
  selector: string
}

const policyForm = reactive<PolicyForm>({
  name: '',
  namespace: 'default',
  type: 'ingress',
  selector: ''
})

const policyRules: FormRules = {
  name: [{ required: true, message: '请输入策略名', trigger: 'blur' }],
  namespace: [{ required: true, message: '请输入命名空间', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  selector: [{ required: true, message: '请输入 Pod 选择器', trigger: 'blur' }]
}

/** 打开下发弹窗 */
function openCreatePolicyDialog() {
  resetPolicyForm()
  createPolicyVisible.value = true
}

/** 重置策略表单 */
function resetPolicyForm() {
  policyForm.name = ''
  policyForm.namespace = 'default'
  policyForm.type = 'ingress'
  policyForm.selector = ''
  policyPortsInput.value = ''
  policyFormRef.value?.clearValidate()
}

/** 提交下发 */
async function handleCreatePolicy() {
  if (!selectedCluster.value || !policyFormRef.value) return
  await policyFormRef.value.validate(async (valid) => {
    if (!valid) return
    savingPolicy.value = true
    try {
      const ports = policyPortsInput.value
        .split(',')
        .map((s) => parseInt(s.trim(), 10))
        .filter((n) => !isNaN(n) && n > 0)
      const policy: NetworkPolicy = {
        name: policyForm.name,
        namespace: policyForm.namespace,
        type: policyForm.type,
        ports,
        selector: policyForm.selector
      }
      await infraApi.createNetworkPolicy(
        selectedCluster.value!.environment,
        selectedCluster.value!.clusterId,
        policy
      )
      ElMessage.success('策略已下发')
      createPolicyVisible.value = false
      await loadPolicies()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      savingPolicy.value = false
    }
  })
}

/** 删除策略 */
async function handleDeletePolicy(row: NetworkPolicy) {
  if (!selectedCluster.value) return
  try {
    await ElMessageBox.confirm(`确认删除策略「${row.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await infraApi.deleteNetworkPolicy(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success('策略已删除')
    await loadPolicies()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 环境 → 中文 */
function envLabel(env: ClusterEnv): string {
  const map: Record<ClusterEnv, string> = {
    private: '私有云',
    cloud: '公有云',
    xinchuang: '信创'
  }
  return map[env] ?? env
}

/** 策略类型 → 中文 */
function policyTypeLabel(t: NetworkPolicyType): string {
  const map: Record<NetworkPolicyType, string> = {
    ingress: '入站',
    egress: '出站',
    both: '双向'
  }
  return map[t] ?? t
}

/** 策略类型 → tag 类型 */
function policyTypeTagType(t: NetworkPolicyType): 'primary' | 'success' | 'warning' {
  const map: Record<NetworkPolicyType, 'primary' | 'success' | 'warning'> = {
    ingress: 'primary',
    egress: 'success',
    both: 'warning'
  }
  return map[t] ?? 'primary'
}

/* ------------------------------ 生命周期 ------------------------------ */

/** 重新加载全部 */
async function reloadAll() {
  await loadClusters()
  await Promise.all([loadConfig(), loadPolicies()])
}

/** 监听选中集群变化 */
watch(selectedCluster, () => {
  if (selectedCluster.value) {
    void loadConfig()
    void loadPolicies()
  } else {
    networkConfig.value = null
    policies.value = []
  }
})

onMounted(() => {
  void loadClusters()
})
</script>

<style scoped>
.infra-net-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: #717a80;
  margin: 0 0 8px;
}
.kpi {
  font-size: 24px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.w {
  color: #c08a2e;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.toolbar .label {
  font-size: 13px;
  color: #717a80;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
</style>
