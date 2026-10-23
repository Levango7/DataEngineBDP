<template>
  <div
    class="oa-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('apiMarket.generate.title')"
  >
    <PageHeader
      :title="t('apiMarket.generate.title')"
      :subtitle="t('apiMarket.generate.subtitle')"
    />

    <p v-if="optionsError" class="oa-error" role="alert">
      {{ optionsError }}
      <el-button link type="primary" @click="loadOptions">
        {{ t('common.retry') }}
      </el-button>
    </p>

    <PageCard :title="t('apiMarket.generate.formTitle')">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="t('apiMarket.generate.tabSql')" name="sql">
          <el-form class="oa-form" label-width="150px" label-position="left">
            <el-form-item :label="t('apiMarket.generate.fieldName')">
              <el-input v-model="sql.name" :placeholder="t('apiMarket.generate.namePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldSql')">
              <el-input
                v-model="sql.sql"
                type="textarea"
                :rows="5"
                :placeholder="t('apiMarket.generate.sqlPlaceholder')"
              />
              <p class="oa-hint">{{ t('apiMarket.generate.sqlHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldDatasource')">
              <el-select v-model="sql.datasource" :placeholder="t('apiMarket.generate.pickHint')">
                <el-option v-for="d in options.datasources" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldProvider')">
              <el-input
                v-model="sql.providerTenantId"
                :placeholder="t('apiMarket.generate.providerPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldDescription')">
              <el-input v-model="sql.description" />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldCost')">
              <el-select v-model="sql.costStrategy" class="oa-cost-select">
                <el-option
                  v-for="s in COST_STRATEGIES"
                  :key="s"
                  :label="t(`apiMarket.status.cost.${s}`)"
                  :value="s"
                />
              </el-select>
              <el-input-number
                v-model="sql.costUnitPrice"
                :min="0"
                :step="0.01"
                :precision="4"
                class="oa-price"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldSla')">
              <el-radio-group v-model="sql.sla">
                <el-radio-button v-for="l in SLA_LEVELS" :key="l" :value="l">
                  {{ t(`apiMarket.status.sla.${l}`) }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane :label="t('apiMarket.generate.tabModel')" name="model">
          <el-form class="oa-form" label-width="150px" label-position="left">
            <el-form-item :label="t('apiMarket.generate.fieldName')">
              <el-input
                v-model="model.name"
                :placeholder="t('apiMarket.generate.namePlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldModelId')">
              <el-input
                v-model="model.modelId"
                :placeholder="t('apiMarket.generate.modelIdPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldModelType')">
              <el-select v-model="model.modelType" :placeholder="t('apiMarket.generate.pickHint')">
                <el-option v-for="m in options.modelTypes" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldProvider')">
              <el-input
                v-model="model.providerTenantId"
                :placeholder="t('apiMarket.generate.providerPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldDescription')">
              <el-input v-model="model.description" />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldCost')">
              <el-select v-model="model.costStrategy" class="oa-cost-select">
                <el-option
                  v-for="s in COST_STRATEGIES"
                  :key="s"
                  :label="t(`apiMarket.status.cost.${s}`)"
                  :value="s"
                />
              </el-select>
              <el-input-number
                v-model="model.costUnitPrice"
                :min="0"
                :step="0.01"
                :precision="4"
                class="oa-price"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldSla')">
              <el-radio-group v-model="model.sla">
                <el-radio-button v-for="l in SLA_LEVELS" :key="l" :value="l">
                  {{ t(`apiMarket.status.sla.${l}`) }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane :label="t('apiMarket.generate.tabFunction')" name="function">
          <el-form class="oa-form" label-width="150px" label-position="left">
            <el-form-item :label="t('apiMarket.generate.fieldName')">
              <el-input v-model="fn.name" :placeholder="t('apiMarket.generate.namePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldFunctionName')">
              <el-input
                v-model="fn.functionName"
                :placeholder="t('apiMarket.generate.functionNamePlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldRuntime')">
              <el-select v-model="fn.runtime" :placeholder="t('apiMarket.generate.pickHint')">
                <el-option v-for="r in options.runtimes" :key="r" :label="r" :value="r" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldProvider')">
              <el-input
                v-model="fn.providerTenantId"
                :placeholder="t('apiMarket.generate.providerPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldTimeout')">
              <el-input-number
                v-model="fn.timeout"
                :min="1000"
                :max="900000"
                :step="1000"
                class="oa-price"
              />
              <span class="oa-hint">{{ t('apiMarket.generate.timeoutHint') }}</span>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldMemory')">
              <el-input-number
                v-model="fn.memoryMB"
                :min="128"
                :max="32768"
                :step="128"
                class="oa-price"
              />
              <span class="oa-hint">{{ t('apiMarket.generate.memoryHint') }}</span>
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldDescription')">
              <el-input v-model="fn.description" />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldCost')">
              <el-select v-model="fn.costStrategy" class="oa-cost-select">
                <el-option
                  v-for="s in COST_STRATEGIES"
                  :key="s"
                  :label="t(`apiMarket.status.cost.${s}`)"
                  :value="s"
                />
              </el-select>
              <el-input-number
                v-model="fn.costUnitPrice"
                :min="0"
                :step="0.001"
                :precision="4"
                class="oa-price"
              />
            </el-form-item>
            <el-form-item :label="t('apiMarket.generate.fieldSla')">
              <el-radio-group v-model="fn.sla">
                <el-radio-button v-for="l in SLA_LEVELS" :key="l" :value="l">
                  {{ t(`apiMarket.status.sla.${l}`) }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-form-item>
          <el-button type="primary" :loading="creating" @click="submit">
            {{ t('apiMarket.generate.actionGenerate') }}
          </el-button>
          <el-button @click="reset">{{ t('apiMarket.generate.actionReset') }}</el-button>
        </el-form-item>
      </el-tabs>
      <p v-if="formError" class="oa-error" role="alert">{{ formError }}</p>
    </PageCard>

    <PageCard v-if="created" :title="t('apiMarket.generate.resultTitle')">
      <dl class="oa-detail">
        <div class="oa-kv">
          <dt>{{ t('apiMarket.generate.resultId') }}</dt>
          <dd class="oa-mono">{{ created.id }}</dd>
        </div>
        <div class="oa-kv">
          <dt>{{ t('apiMarket.generate.resultName') }}</dt>
          <dd>{{ created.name }}</dd>
        </div>
        <div class="oa-kv">
          <dt>{{ t('apiMarket.generate.resultPath') }}</dt>
          <dd class="oa-mono">{{ created.path }}</dd>
        </div>
        <div class="oa-kv">
          <dt>{{ t('apiMarket.generate.resultStatus') }}</dt>
          <dd>
            <StatusTag :status="created.status" :label="statusLabel(created.status)" />
          </dd>
        </div>
      </dl>
      <p class="oa-hint">{{ t('apiMarket.generate.resultHint') }}</p>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  generateFromFunction,
  generateFromModel,
  generateFromSql,
  getGenerateOptions,
  type APIDefinition,
  type APIStatus,
  type CostStrategy,
  type GenerateOptions,
  type SLALevel
} from '@/api/apiCatalog'

const { t } = useI18n()

// 词表取后端枚举，不自己发明档位：`generate.py` 的 costStrategy/sla 落进同一套 APIStatus 口径，
// 而 `apis.py` 的注册路径用的是同一份 CostStrategy 与 SLA 取值。
const COST_STRATEGIES: CostStrategy[] = ['by_call', 'by_bytes', 'monthly_package']
const SLA_LEVELS: SLALevel[] = ['platinum', 'gold', 'silver']

const activeTab = ref<'sql' | 'model' | 'function'>('sql')
const options = reactive<GenerateOptions>({ datasources: [], modelTypes: [], runtimes: [] })
const optionsError = ref('')
const formError = ref('')
const creating = ref(false)
const created = ref<APIDefinition | null>(null)

// 三张表单各自独立：后端三个 Body 模型的默认单价不同（0.01 / 0.10 / 0.001），
// 共用一份默认值会让切标签页时把上一档的单价带过去。
const sql = reactive({
  name: '',
  sql: '',
  datasource: '',
  providerTenantId: '',
  description: '',
  costStrategy: 'by_call' as CostStrategy,
  costUnitPrice: 0.01,
  sla: 'silver' as SLALevel
})
const model = reactive({
  name: '',
  modelId: '',
  modelType: '',
  providerTenantId: '',
  description: '',
  costStrategy: 'by_call' as CostStrategy,
  costUnitPrice: 0.1,
  sla: 'gold' as SLALevel
})
const fn = reactive({
  name: '',
  functionName: '',
  runtime: '',
  providerTenantId: '',
  timeout: 30000,
  memoryMB: 512,
  description: '',
  costStrategy: 'by_call' as CostStrategy,
  costUnitPrice: 0.001,
  sla: 'silver' as SLALevel
})

function statusLabel(status: APIStatus): string {
  return t(`apiMarket.status.api.${status}`)
}

async function loadOptions(): Promise<void> {
  optionsError.value = ''
  try {
    const resp = await getGenerateOptions()
    options.datasources = resp?.datasources ?? []
    options.modelTypes = resp?.modelTypes ?? []
    options.runtimes = resp?.runtimes ?? []
  } catch (e) {
    optionsError.value = `${t('apiMarket.generate.optionsFailed')}${(e as Error)?.message ?? ''}`
  }
}

function reset(): void {
  formError.value = ''
  created.value = null
  if (activeTab.value === 'sql') {
    Object.assign(sql, {
      name: '',
      sql: '',
      datasource: '',
      providerTenantId: '',
      description: '',
      costStrategy: 'by_call',
      costUnitPrice: 0.01,
      sla: 'silver'
    })
  } else if (activeTab.value === 'model') {
    Object.assign(model, {
      name: '',
      modelId: '',
      modelType: '',
      providerTenantId: '',
      description: '',
      costStrategy: 'by_call',
      costUnitPrice: 0.1,
      sla: 'gold'
    })
  } else {
    Object.assign(fn, {
      name: '',
      functionName: '',
      runtime: '',
      providerTenantId: '',
      timeout: 30000,
      memoryMB: 512,
      description: '',
      costStrategy: 'by_call',
      costUnitPrice: 0.001,
      sla: 'silver'
    })
  }
}

/** 本地先拦后端会 422 的必填项，错误文案指名是哪一项 */
function validate(): string {
  if (activeTab.value === 'sql') {
    if (!sql.name.trim()) return t('apiMarket.generate.needName')
    if (!sql.sql.trim()) return t('apiMarket.generate.needSql')
    if (!sql.datasource) return t('apiMarket.generate.needDatasource')
    if (!sql.providerTenantId.trim()) return t('apiMarket.generate.needProvider')
  } else if (activeTab.value === 'model') {
    if (!model.name.trim()) return t('apiMarket.generate.needName')
    if (!model.modelId.trim()) return t('apiMarket.generate.needModelId')
    if (!model.modelType) return t('apiMarket.generate.needModelType')
    if (!model.providerTenantId.trim()) return t('apiMarket.generate.needProvider')
  } else {
    if (!fn.name.trim()) return t('apiMarket.generate.needName')
    if (!fn.functionName.trim()) return t('apiMarket.generate.needFunctionName')
    if (!fn.runtime) return t('apiMarket.generate.needRuntime')
    if (!fn.providerTenantId.trim()) return t('apiMarket.generate.needProvider')
  }
  return ''
}

async function submit(): Promise<void> {
  formError.value = ''
  created.value = null
  const invalid = validate()
  if (invalid) {
    formError.value = invalid
    return
  }
  creating.value = true
  try {
    if (activeTab.value === 'sql') {
      created.value = await generateFromSql({
        name: sql.name.trim(),
        sql: sql.sql.trim(),
        datasource: sql.datasource,
        providerTenantId: sql.providerTenantId.trim(),
        description: sql.description.trim() || undefined,
        costStrategy: sql.costStrategy,
        costUnitPrice: Number(sql.costUnitPrice) || 0,
        sla: sql.sla
      })
    } else if (activeTab.value === 'model') {
      created.value = await generateFromModel({
        name: model.name.trim(),
        modelId: model.modelId.trim(),
        modelType: model.modelType,
        providerTenantId: model.providerTenantId.trim(),
        description: model.description.trim() || undefined,
        costStrategy: model.costStrategy,
        costUnitPrice: Number(model.costUnitPrice) || 0,
        sla: model.sla
      })
    } else {
      created.value = await generateFromFunction({
        name: fn.name.trim(),
        functionName: fn.functionName.trim(),
        runtime: fn.runtime,
        providerTenantId: fn.providerTenantId.trim(),
        description: fn.description.trim() || undefined,
        timeout: Math.trunc(Number(fn.timeout) || 0),
        memoryMB: Math.trunc(Number(fn.memoryMB) || 0),
        costStrategy: fn.costStrategy,
        costUnitPrice: Number(fn.costUnitPrice) || 0,
        sla: fn.sla
      })
    }
  } catch (e) {
    formError.value = `${t('apiMarket.generate.generateFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  void loadOptions()
})
</script>

<style scoped>
.oa-page {
  padding: var(--ds-spacing-4);
}

.oa-form {
  max-width: 760px;
}

.oa-cost-select {
  width: 180px;
  margin-right: var(--ds-spacing-2);
}

.oa-price {
  width: 160px;
}

.oa-hint {
  margin: var(--ds-spacing-1) 0 0;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.oa-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.oa-detail {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}

.oa-kv {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: baseline;
}

.oa-kv dt {
  flex: 0 0 150px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.oa-kv dd {
  margin: 0;
  word-break: break-all;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
}

.oa-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
