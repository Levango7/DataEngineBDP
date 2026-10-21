<template>
  <div class="km-page" role="main" data-design-contract="v2" :aria-label="t('karmada.policies.title')">
    <PageHeader :title="t('karmada.policies.title')" :subtitle="t('karmada.policies.subtitle')" />

    <p v-if="error" class="km-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('karmada.actionRetry') }}</el-button>
    </p>

    <PageCard :title="t('karmada.policies.formTitle')">
      <el-form class="km-form" label-width="160px" label-position="left">
        <el-form-item :label="t('karmada.policies.fieldName')">
          <el-input v-model="form.name" :placeholder="t('karmada.policies.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.colNamespace')">
          <el-input v-model="form.namespace" :placeholder="t('karmada.namespacePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldPrimary')">
          <el-input v-model="form.primaryCluster" :placeholder="t('karmada.policies.primaryPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldBackups')">
          <el-input v-model="form.backupClusters" :placeholder="t('karmada.policies.backupsPlaceholder')" />
          <p class="km-hint">{{ t('karmada.policies.backupsHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldDetectionWindow')">
          <el-input v-model.number="form.detectionWindowSeconds" type="number" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldMigrationTimeout')">
          <el-input v-model.number="form.migrationTimeoutSeconds" type="number" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldCheckInterval')">
          <el-input v-model.number="form.healthCheckIntervalSeconds" type="number" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldEnabled')">
          <!-- el-switch 不在测试 stub 表里；这里用 el-select 承载三态选择，
               真值仍以布尔发给后端（Go 的 enabled 是 bool，不是字符串） -->
          <el-select v-model="enabledChoice" style="width: 160px">
            <el-option :label="t('karmada.yes')" value="true" />
            <el-option :label="t('karmada.no')" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="creating" @click="create">
            {{ t('karmada.policies.actionCreate') }}
          </el-button>
          <el-button @click="reset">{{ t('karmada.actionReset') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="formError" class="km-error" role="alert">{{ formError }}</p>
      <p v-if="formOk" class="km-ok" role="status">{{ formOk }}</p>
    </PageCard>

    <PageCard :title="t('karmada.policies.listTitle')">
      <el-table v-if="rows.length" v-loading="loading" :data="rows" stripe>
        <el-table-column :label="t('karmada.policies.colName')" min-width="160" prop="name" />
        <el-table-column :label="t('karmada.colNamespace')" width="120" prop="namespace" />
        <el-table-column :label="t('karmada.policies.colPrimary')" width="160" prop="primaryCluster" />
        <el-table-column :label="t('karmada.policies.colBackups')" min-width="180">
          <template #default="{ row }">{{ backupsLabel(row.backupClusters) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.policies.colDetectionWindow')" width="120">
          <template #default="{ row }">{{ seconds(row.detectionWindowSeconds) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.policies.colMigrationTimeout')" width="120">
          <template #default="{ row }">{{ seconds(row.migrationTimeoutSeconds) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.policies.colCheckInterval')" width="120">
          <template #default="{ row }">{{ seconds(row.healthCheckIntervalSeconds) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.policies.colEnabled')" width="90">
          <template #default="{ row }">
            <StatusTag
              :status="row.enabled ? 'enabled' : 'disabled'"
              :label="row.enabled ? yesText : noText"
              :status-map="KARMADA_ENABLED_MAP"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.colActions')" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">
              {{ t('karmada.actionEdit') }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">
              {{ t('karmada.actionDelete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="t('karmada.policies.emptyPolicies')" />
    </PageCard>

    <PageCard v-if="editing" :title="t('karmada.policies.editTitle')">
      <el-form class="km-form" label-width="160px" label-position="left">
        <el-form-item :label="t('karmada.policies.colName')">
          <span class="km-mono">{{ editing.name }}</span>
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldPrimary')">
          <el-input v-model="editForm.primaryCluster" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldBackups')">
          <el-input v-model="editForm.backupClusters" />
        </el-form-item>
        <el-form-item :label="t('karmada.policies.fieldEnabled')">
          <el-select v-model="editEnabledChoice" style="width: 160px">
            <el-option :label="t('karmada.yes')" value="true" />
            <el-option :label="t('karmada.no')" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">
            {{ t('karmada.actionSave') }}
          </el-button>
          <el-button @click="closeEdit">{{ t('karmada.actionCancel') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="editError" class="km-error" role="alert">{{ editError }}</p>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { parseJsonList, seconds } from './format'
import type { TagType } from '../exchange/format'
import {
  createFailoverPolicy,
  deleteFailoverPolicy,
  listFailoverPolicies,
  updateFailoverPolicy,
  type FailoverPolicy
} from '@/api/karmada'

const { t } = useI18n()

/** Go 侧 enabled 是 bool，序列化出来是 true/false 而不是字符串，所以标签键自己造 */
const KARMADA_ENABLED_MAP: Record<string, TagType> = {
  enabled: 'success',
  disabled: 'info'
}

const rows = ref<FailoverPolicy[]>([])
const editing = ref<FailoverPolicy | null>(null)
const loading = ref(false)
const creating = ref(false)
const saving = ref(false)
const error = ref('')
const formError = ref('')
const formOk = ref('')
const editError = ref('')

const emptyForm = () => ({
  name: '',
  namespace: 'default',
  primaryCluster: '',
  backupClusters: '',
  detectionWindowSeconds: 30,
  migrationTimeoutSeconds: 60,
  healthCheckIntervalSeconds: 10
})
const form = reactive(emptyForm())
// 后端默认值取证：failover.go:611-619 —— 检测窗口 30s / 迁移超时 60s / 检查间隔 10s
const enabledChoice = ref('true')
const editEnabledChoice = ref('true')

const editForm = reactive({ primaryCluster: '', backupClusters: '' })

const yesText = computed(() => t('karmada.yes'))
const noText = computed(() => t('karmada.no'))

function splitList(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** backupClusters 在响应里是 text 列的 JSON 数组串（model/failover.go:94），不是数组 */
function backupsLabel(json: string): string {
  const list = parseJsonList(json)
  return list.length ? list.join(', ') : '—'
}

function reset(): void {
  Object.assign(form, emptyForm())
  enabledChoice.value = 'true'
  formError.value = ''
  formOk.value = ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const resp = await listFailoverPolicies({ limit: 100 })
    rows.value = resp?.items ?? []
  } catch (e) {
    error.value = `${t('karmada.loadFailed')}${(e as Error)?.message ?? ''}`
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  formError.value = ''
  formOk.value = ''
  if (!form.name.trim()) {
    formError.value = t('karmada.policies.needName')
    return
  }
  if (!form.primaryCluster.trim()) {
    formError.value = t('karmada.policies.needPrimary')
    return
  }
  const backups = splitList(form.backupClusters)
  if (backups.length === 0) {
    // failover.go:605-608 对空 backupClusters 明确 400
    formError.value = t('karmada.policies.needBackups')
    return
  }

  creating.value = true
  try {
    await createFailoverPolicy({
      name: form.name.trim(),
      namespace: form.namespace.trim() || 'default',
      primaryCluster: form.primaryCluster.trim(),
      backupClusters: backups,
      detectionWindowSeconds: Math.trunc(Number(form.detectionWindowSeconds) || 0),
      migrationTimeoutSeconds: Math.trunc(Number(form.migrationTimeoutSeconds) || 0),
      healthCheckIntervalSeconds: Math.trunc(Number(form.healthCheckIntervalSeconds) || 0),
      enabled: enabledChoice.value === 'true'
    })
    formOk.value = t('karmada.policies.createOk')
    reset()
    await load()
  } catch (e) {
    formError.value = `${t('karmada.createFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    creating.value = false
  }
}

function edit(policy: FailoverPolicy): void {
  editing.value = policy
  editError.value = ''
  editForm.primaryCluster = policy.primaryCluster
  try {
    editForm.backupClusters = (JSON.parse(policy.backupClusters) as string[]).join(', ')
  } catch {
    editForm.backupClusters = ''
  }
  editEnabledChoice.value = policy.enabled ? 'true' : 'false'
}

function closeEdit(): void {
  editing.value = null
  editError.value = ''
}

async function save(): Promise<void> {
  if (!editing.value) return
  editError.value = ''
  const backups = splitList(editForm.backupClusters)
  if (backups.length === 0) {
    editError.value = t('karmada.policies.needBackups')
    return
  }
  saving.value = true
  try {
    await updateFailoverPolicy(
      editing.value.name,
      {
        primaryCluster: editForm.primaryCluster.trim(),
        backupClusters: backups,
        enabled: editEnabledChoice.value === 'true'
      },
      editing.value.namespace
    )
    closeEdit()
    await load()
  } catch (e) {
    editError.value = `${t('karmada.saveFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    saving.value = false
  }
}

async function remove(policy: FailoverPolicy): Promise<void> {
  error.value = ''
  try {
    await ElMessageBox.confirm(t('karmada.policies.deleteConfirm', { name: policy.name }), {
      type: 'warning'
    })
    await deleteFailoverPolicy(policy.name, policy.namespace)
    await load()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    error.value = `${t('karmada.deleteFailed')}${(e as Error)?.message ?? ''}`
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.km-page {
  padding: var(--ds-spacing-4);
}

.km-form {
  max-width: 760px;
}

.km-hint {
  margin: var(--ds-spacing-1) 0 0;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.km-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.km-ok {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-success);
  font-size: var(--ds-font-size-sm);
}

.km-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
