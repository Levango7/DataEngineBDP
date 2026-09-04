<template>
  <div class="infra-net-page">
    <h1>{{ t('infraNet.title') }}</h1>
    <div class="sub">{{ t('infraNet.subtitle') }}</div>

    <!-- 集群选择器 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <span class="label">{{ t('infraNet.selectCluster.label') }}</span>
        <el-select
          v-model="selectedClusterKey"
          :placeholder="t('infraNet.selectCluster.placeholder')"
          style="width: 320px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusterOptions"
            :key="`${c.environment}/${c.clusterId}`"
            :label="
              t('infraSched.selectCluster.optionFmt', {
                name: c.clusterName,
                env: envLabel(c.environment)
              })
            "
            :value="`${c.environment}/${c.clusterId}`"
          />
        </el-select>
        <div class="spacer"></div>
        <el-button
          :icon="Refresh"
          circle
          :aria-label="t('infraNet.selectCluster.refreshAria')"
          @click="reloadAll"
        />
      </div>
    </el-card>

    <!-- KPI 卡片区：三态 -->
    <div class="grid g4" style="margin-top: 16px">
      <template v-if="!selectedCluster">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('infraNet.selectClusterHint') }}</h3>
          <div class="meta">{{ t('infraNet.selectClusterHintMeta') }}</div>
        </div>
      </template>
      <template v-else-if="configLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="configError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ configError.message }}，
            <a href="javascript:void(0)" @click="loadConfig">
              {{ t('engines.kpi.loadFailedRetry') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else-if="networkConfig">
        <div class="card">
          <h3>{{ t('infraNet.kpi.cni') }}</h3>
          <div class="kpi">{{ networkConfig.cni }}</div>
          <div class="meta">{{ t('infraNet.kpi.cniMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraNet.kpi.ipFamily') }}</h3>
          <div class="kpi">{{ networkConfig.ipFamily }}</div>
          <div class="meta">{{ t('infraNet.kpi.mtu', { mtu: networkConfig.mtu }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraNet.kpi.policyCount') }}</h3>
          <div class="kpi s">{{ policies?.length ?? 0 }}</div>
          <div class="meta">{{ t('infraNet.kpi.policyCountMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraNet.kpi.abnormalPolicy') }}</h3>
          <div class="kpi d">{{ abnormalPolicyCount }}</div>
          <div class="meta">{{ t('infraNet.kpi.abnormalPolicyMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 网络配置卡片 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>{{ t('infraNet.config.title') }}</span>
          <el-button type="primary" size="small" @click="openEditConfig">
            {{ t('infraNet.config.edit') }}
          </el-button>
        </div>
      </template>
      <template v-if="configLoading">
        <div class="meta">{{ t('engines.kpi.loading') }}</div>
      </template>
      <template v-else-if="configError">
        <div class="meta" style="color: var(--muted)">{{ t('infraNet.config.loadFailed') }}</div>
      </template>
      <template v-else-if="networkConfig">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('infraNet.config.fields.cni')">
            <el-tag effect="light">{{ networkConfig.cni }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraNet.config.fields.ipFamily')">
            {{ networkConfig.ipFamily }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraNet.config.fields.podCidr')">
            {{ networkConfig.podCidr }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraNet.config.fields.serviceCidr')">
            {{ networkConfig.serviceCidr }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraNet.config.fields.mtu')">
            {{ networkConfig.mtu }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- NetworkPolicy 列表 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>{{ t('infraNet.policy.title') }}</span>
          <el-button type="primary" size="small" @click="openCreatePolicyDialog">
            {{ t('infraNet.policy.create') }}
          </el-button>
        </div>
      </template>
      <el-table
        v-loading="policiesLoading"
        :data="policies ?? []"
        stripe
        border
        style="width: 100%"
        :empty-text="policiesError ? t('infraNet.policy.loadFailed') : t('infraNet.policy.empty')"
      >
        <el-table-column prop="name" :label="t('infraNet.policy.columns.name')" min-width="180" />
        <el-table-column
          prop="namespace"
          :label="t('infraNet.policy.columns.namespace')"
          width="160"
        />
        <el-table-column :label="t('infraNet.policy.columns.type')" width="160">
          <template #default="{ row }">
            <el-tag :type="policyTypeTagType(row.type)" effect="light">
              {{ policyTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('infraNet.policy.columns.ports')" width="200">
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
        <el-table-column
          prop="selector"
          :label="t('infraNet.policy.columns.selector')"
          min-width="200"
        />
        <el-table-column :label="t('infraNet.policy.columns.actions')" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeletePolicy(row)">
              {{ t('infraNet.policy.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑网络配置弹窗 -->
    <el-dialog
      v-model="editConfigVisible"
      :title="t('infraNet.policy.editConfig.title')"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item :label="t('infraNet.policy.editConfig.fields.cni')">
          <el-select v-model="editConfig.cni" style="width: 100%">
            <el-option :label="t('infraNet.policy.editConfig.cniOptions.calico')" value="calico" />
            <el-option
              :label="t('infraNet.policy.editConfig.cniOptions.flannel')"
              value="flannel"
            />
            <el-option :label="t('infraNet.policy.editConfig.cniOptions.cilium')" value="cilium" />
            <el-option
              :label="t('infraNet.policy.editConfig.cniOptions.kubeOvn')"
              value="kube-ovn"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.editConfig.fields.podCidr')">
          <el-input v-model="editConfig.podCidr" />
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.editConfig.fields.serviceCidr')">
          <el-input v-model="editConfig.serviceCidr" />
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.editConfig.fields.ipFamily')">
          <el-select v-model="editConfig.ipFamily" style="width: 100%">
            <el-option :label="t('infraNet.policy.editConfig.ipFamilyOptions.IPv4')" value="IPv4" />
            <el-option :label="t('infraNet.policy.editConfig.ipFamilyOptions.IPv6')" value="IPv6" />
            <el-option
              :label="t('infraNet.policy.editConfig.ipFamilyOptions.DualStack')"
              value="DualStack"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.editConfig.fields.mtu')">
          <el-input-number v-model="editConfig.mtu" :min="1200" :max="9000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editConfigVisible = false">
          {{ t('infraNet.policy.editConfig.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="savingConfig" @click="handleSaveConfig">
          {{ t('infraNet.policy.editConfig.actions.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 下发 NetworkPolicy 弹窗 -->
    <el-dialog
      v-model="createPolicyVisible"
      :title="t('infraNet.policy.createForm.title')"
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
        <el-form-item :label="t('infraNet.policy.createForm.fields.name')" prop="name">
          <el-input
            v-model="policyForm.name"
            :placeholder="t('infraNet.policy.createForm.fields.namePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.createForm.fields.namespace')" prop="namespace">
          <el-input
            v-model="policyForm.namespace"
            :placeholder="t('infraNet.policy.createForm.fields.namespacePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.createForm.fields.type')" prop="type">
          <el-select v-model="policyForm.type" style="width: 100%">
            <el-option :label="t('infraNet.policy.createForm.types.ingress')" value="ingress" />
            <el-option :label="t('infraNet.policy.createForm.types.egress')" value="egress" />
            <el-option :label="t('infraNet.policy.createForm.types.both')" value="both" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.createForm.fields.ports')">
          <el-input
            v-model="policyPortsInput"
            :placeholder="t('infraNet.policy.createForm.fields.portsPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('infraNet.policy.createForm.fields.selector')" prop="selector">
          <el-input
            v-model="policyForm.selector"
            :placeholder="t('infraNet.policy.createForm.fields.selectorPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createPolicyVisible = false">
          {{ t('infraNet.policy.createForm.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="savingPolicy" @click="handleCreatePolicy">
          {{ t('infraNet.policy.createForm.actions.submit') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t, te } = useI18n()

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
    ElMessage.success(t('infraNet.messages.configUpdated'))
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

const policyRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('infraNet.rules.nameRequired'), trigger: 'blur' }],
  namespace: [{ required: true, message: t('infraNet.rules.namespaceRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('infraNet.rules.typeRequired'), trigger: 'change' }],
  selector: [{ required: true, message: t('infraNet.rules.selectorRequired'), trigger: 'blur' }]
}))

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
      ElMessage.success(t('infraNet.messages.policyCreated'))
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
    await ElMessageBox.confirm(
      t('infraNet.messages.deleteConfirm', { name: row.name }),
      t('infraNet.messages.deleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('infraNet.messages.deleteConfirmOk'),
        cancelButtonText: t('infraNet.messages.deleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await infraApi.deleteNetworkPolicy(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success(t('infraNet.messages.policyDeleted'))
    await loadPolicies()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 环境 → 词条（复用 infraK8s.env） */
function envLabel(env: ClusterEnv): string {
  return t(`infraK8s.env.${env}`)
}

/** 策略类型 → 词条 */
function policyTypeLabel(npType: NetworkPolicyType): string {
  return t(`infraNet.policy.createForm.types.${npType}`)
}

/** 策略类型 → tag 类型 */
const POLICY_TYPE_TAG_MAP: Record<NetworkPolicyType, 'primary' | 'success' | 'warning'> = {
  ingress: 'primary',
  egress: 'success',
  both: 'warning'
}

function policyTypeTagType(npType: NetworkPolicyType): 'primary' | 'success' | 'warning' {
  return POLICY_TYPE_TAG_MAP[npType] ?? 'primary'
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
  color: var(--ds-text-secondary);
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
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: 24px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.s {
  color: var(--ds-color-success-600);
}
.kpi.w {
  color: var(--ds-color-warning-600);
}
.kpi.d {
  color: var(--ds-color-error-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
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
  color: var(--ds-text-secondary);
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
</style>
