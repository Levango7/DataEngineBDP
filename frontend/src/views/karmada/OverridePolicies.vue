<template>
  <div class="km-page" role="main" data-design-contract="v2" :aria-label="t('karmada.override.title')">
    <PageHeader :title="t('karmada.override.title')" :subtitle="t('karmada.override.subtitle')" />

    <p v-if="error" class="km-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('karmada.actionRetry') }}</el-button>
    </p>

    <PageCard :title="t('karmada.override.formTitle')">
      <el-form class="km-form" label-width="140px" label-position="left">
        <el-form-item :label="t('karmada.override.fieldName')">
          <el-input v-model="form.name" :placeholder="t('karmada.override.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.colNamespace')">
          <el-input v-model="form.namespace" :placeholder="t('karmada.namespacePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.override.fieldTargetClusters')">
          <el-input v-model="form.targetClusters" :placeholder="t('karmada.override.targetPlaceholder')" />
          <p class="km-hint">{{ t('karmada.override.targetHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('karmada.override.fieldRegistry')">
          <el-input v-model="form.registry" :placeholder="t('karmada.override.registryPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.override.fieldTag')">
          <el-input v-model="form.tag" :placeholder="t('karmada.override.tagPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('karmada.override.fieldEnvs')">
          <div v-for="(env, i) in form.envs" :key="i" class="km-row">
            <el-input v-model="env.name" :placeholder="t('karmada.override.envNamePlaceholder')" />
            <el-input v-model="env.value" :placeholder="t('karmada.override.envValuePlaceholder')" />
            <el-button link type="danger" @click="removeEnv(i)">
              {{ t('karmada.override.actionRemoveEnv') }}
            </el-button>
          </div>
          <el-button @click="addEnv">{{ t('karmada.override.actionAddEnv') }}</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="creating" @click="create">
            {{ t('karmada.override.actionCreate') }}
          </el-button>
          <el-button @click="reset">{{ t('karmada.actionReset') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="formError" class="km-error" role="alert">{{ formError }}</p>
      <p v-if="formOk" class="km-ok" role="status">{{ formOk }}</p>
    </PageCard>

    <PageCard :title="t('karmada.override.listTitle')">
      <el-table v-if="rows.length" v-loading="loading" :data="rows" stripe>
        <el-table-column :label="t('karmada.override.colName')" min-width="180" prop="name" />
        <el-table-column :label="t('karmada.colNamespace')" width="120" prop="namespace" />
        <el-table-column :label="t('karmada.override.colRules')" width="110">
          <template #default="{ row }">{{ ruleCount(row.spec) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.override.colTargetClusters')" min-width="200">
          <template #default="{ row }">{{ targetClusters(row.spec) }}</template>
        </el-table-column>
        <el-table-column :label="t('karmada.override.colUpdatedAt')" width="190">
          <template #default="{ row }">
            <span class="km-mono">{{ formatTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.colCreatedAt')" width="190">
          <template #default="{ row }">
            <span class="km-mono">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('karmada.colActions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.name, row.namespace)">
              {{ t('karmada.actionDetail') }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">
              {{ t('karmada.actionDelete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="t('karmada.override.emptyPolicies')" />
    </PageCard>

    <PageCard v-if="detail" :title="t('karmada.override.detailTitle')">
      <dl class="km-detail">
        <div class="km-kv">
          <dt>{{ t('karmada.override.colName') }}</dt>
          <dd class="km-mono">{{ detail.name }}</dd>
        </div>
        <div class="km-kv">
          <dt>{{ t('karmada.colNamespace') }}</dt>
          <dd class="km-mono">{{ detail.namespace }}</dd>
        </div>
        <div class="km-kv">
          <dt>{{ t('karmada.override.specJson') }}</dt>
          <dd class="km-mono km-pre">{{ prettyJson(detail.spec) }}</dd>
        </div>
      </dl>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { formatTime as fmtTime } from '../exchange/format'
import { parseJsonRecord } from './format'
import {
  createOverridePolicy,
  deleteOverridePolicy,
  getOverridePolicy,
  listOverridePolicies,
  type OverridePolicy,
  type OverridePolicySpec
} from '@/api/karmada'

const { t, locale } = useI18n()

const rows = ref<OverridePolicy[]>([])
const detail = ref<OverridePolicy | null>(null)
const loading = ref(false)
const creating = ref(false)
const error = ref('')
const formError = ref('')
const formOk = ref('')

// Go 的 spec/status 是 text 列里的 JSON 字符串，表单只收集"镜像 + 环境变量"两类覆盖器，
// 组装成 OverridePolicySpec 后由后端序列化回 spec 字符串（override_policy.go:73-78）。
const emptyForm = () => ({
  name: '',
  namespace: 'default',
  targetClusters: '',
  registry: '',
  tag: '',
  envs: [] as Array<{ name: string; value: string }>
})
const form = reactive(emptyForm())

const formatTime = (iso: string | null | undefined): string => fmtTime(iso, locale.value)

function splitList(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function addEnv(): void {
  form.envs.push({ name: '', value: '' })
}

function removeEnv(index: number): void {
  form.envs.splice(index, 1)
}

function reset(): void {
  Object.assign(form, emptyForm())
  formError.value = ''
  formOk.value = ''
}

/** overrideRules 是 spec 里唯一必填的数组（override_policy.go:50 无 omitempty） */
function ruleCount(specJson: string): number {
  const spec = parseJsonRecord(specJson)
  return Array.isArray(spec.overrideRules) ? spec.overrideRules.length : 0
}

function targetClusters(specJson: string): string {
  const spec = parseJsonRecord(specJson)
  const rules = Array.isArray(spec.overrideRules) ? spec.overrideRules : []
  const names = new Set<string>()
  for (const rule of rules as Array<{ targetCluster?: { clusterNames?: string[] } }>) {
    for (const c of rule.targetCluster?.clusterNames ?? []) names.add(c)
  }
  return names.size ? [...names].join(', ') : '—'
}

function prettyJson(json: string): string {
  const rec = parseJsonRecord(json)
  return Object.keys(rec).length ? JSON.stringify(rec, null, 2) : json || '—'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const resp = await listOverridePolicies({ limit: 100 })
    rows.value = resp?.items ?? []
  } catch (e) {
    error.value = `${t('karmada.loadFailed')}${(e as Error)?.message ?? ''}`
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(name: string, namespace: string): Promise<void> {
  error.value = ''
  try {
    detail.value = await getOverridePolicy(name, namespace)
  } catch (e) {
    error.value = `${t('karmada.detailFailed')}${(e as Error)?.message ?? ''}`
  }
}

async function create(): Promise<void> {
  formError.value = ''
  formOk.value = ''
  if (!form.name.trim()) {
    formError.value = t('karmada.override.needName')
    return
  }
  const clusters = splitList(form.targetClusters)
  const imageOverrider: Array<{ component?: string; operator: string; value: string }> = []
  if (form.registry.trim()) {
    imageOverrider.push({ component: 'Registry', operator: 'replace', value: form.registry.trim() })
  }
  if (form.tag.trim()) {
    imageOverrider.push({ component: 'Tag', operator: 'replace', value: form.tag.trim() })
  }
  const envs = form.envs.filter((e) => e.name.trim()).map((e) => ({ name: e.name.trim(), value: e.value }))

  const overrideRules: OverridePolicySpec['overrideRules'] = []
  if (imageOverrider.length) {
    overrideRules.push({
      targetCluster: { clusterNames: clusters },
      overriders: { imageOverrider }
    })
  }
  if (envs.length) {
    overrideRules.push({
      targetCluster: { clusterNames: clusters },
      overriders: { envOverrider: [{ containerName: 'app', operator: 'add', value: envs }] }
    })
  }
  // 后端 override_policy.go:67 对空 overrideRules 直接 400，先在本地拦住并给原因
  if (!overrideRules.length) {
    formError.value = t('karmada.override.needRule')
    return
  }

  creating.value = true
  try {
    await createOverridePolicy({
      name: form.name.trim(),
      namespace: form.namespace.trim() || 'default',
      spec: { overrideRules }
    })
    formOk.value = t('karmada.override.createOk')
    reset()
    await load()
  } catch (e) {
    formError.value = `${t('karmada.createFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    creating.value = false
  }
}

async function remove(policy: OverridePolicy): Promise<void> {
  error.value = ''
  try {
    await deleteOverridePolicy(policy.name, policy.namespace)
    await load()
  } catch (e) {
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
  max-width: 720px;
}

.km-row {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: center;
  margin-bottom: var(--ds-spacing-2);
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

.km-detail {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}

.km-kv {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: baseline;
}

.km-kv dt {
  flex: 0 0 140px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.km-kv dd {
  margin: 0;
  word-break: break-all;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
}

.km-pre {
  white-space: pre-wrap;
}

.km-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
