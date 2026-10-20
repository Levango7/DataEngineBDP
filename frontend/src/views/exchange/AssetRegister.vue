<template>
  <div
    class="ex-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('exchange.register.title')"
  >
    <PageHeader :title="t('exchange.register.title')" :subtitle="t('exchange.register.subtitle')" />

    <PageCard :title="t('exchange.register.formTitle')">
      <el-form class="reg-form" label-width="140px" label-position="left">
        <el-form-item :label="t('exchange.register.fieldName')">
          <el-input v-model="form.name" :placeholder="t('exchange.register.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldType')">
          <el-select v-model="form.type" style="width: 220px">
            <el-option v-for="opt in ASSET_TYPE_OPTIONS" :key="opt" :label="opt" :value="opt" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldTenant')">
          <el-input
            v-model="form.tenantId"
            :placeholder="t('exchange.register.tenantPlaceholder')"
            clearable
          />
          <p class="reg-hint">{{ t('exchange.register.tenantHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldDesc')">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldSecurity')">
          <el-select v-model="form.securityLevel" style="width: 220px">
            <el-option v-for="s in SECURITY_OPTIONS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldQuality')">
          <el-input-number v-model="form.qualityScore" :min="0" :max="100" />
          <span class="reg-hint">{{ t('exchange.register.qualityHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldFrequency')">
          <el-select v-model="form.updateFrequency" style="width: 220px">
            <el-option
              v-for="f in FREQUENCY_OPTIONS"
              :key="f"
              :label="t(`exchange.register.freq.${f}`)"
              :value="f"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('exchange.register.fieldPricing')">
          <el-select v-model="form.pricing.mode" style="width: 160px">
            <el-option v-for="m in PRICING_MODES" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input-number v-model="form.pricing.price" :min="0" :precision="2" :step="0.1" />
          <el-input
            v-model="form.pricing.unit"
            :placeholder="t('exchange.register.unitPlaceholder')"
            style="width: 140px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="registering" @click="register">
            {{ t('exchange.register.actionRegister') }}
          </el-button>
          <el-button @click="reset">
            {{ t('exchange.register.actionReset') }}
          </el-button>
        </el-form-item>
      </el-form>
      <p v-if="registerError" class="ex-error" role="alert">{{ registerError }}</p>
    </PageCard>

    <PageCard v-if="created" :title="t('exchange.register.resultTitle')">
      <dl class="reg-result">
        <div class="reg-kv">
          <dt>{{ t('exchange.register.colId') }}</dt>
          <dd class="ex-mono">{{ created.id }}</dd>
        </div>
        <div class="reg-kv">
          <dt>{{ t('exchange.register.colName') }}</dt>
          <dd>{{ created.name }}</dd>
        </div>
        <div class="reg-kv">
          <dt>{{ t('exchange.register.colType') }}</dt>
          <dd>{{ created.type }}</dd>
        </div>
        <div class="reg-kv">
          <dt>{{ t('exchange.register.colStatus') }}</dt>
          <dd>
            <StatusTag
              :status="created.status"
              :label="created.status"
              :status-map="EXCHANGE_STATUS_MAP"
            />
          </dd>
        </div>
      </dl>

      <div class="reg-actions">
        <el-button
          type="success"
          :loading="auditing"
          :disabled="!canAudit"
          @click="audit('approved')"
        >
          {{ t('exchange.register.actionApprove') }}
        </el-button>
        <el-button
          type="danger"
          :loading="auditing"
          :disabled="!canAudit"
          @click="audit('rejected')"
        >
          {{ t('exchange.register.actionReject') }}
        </el-button>
        <el-button :loading="publishing" :disabled="!canPublish" @click="publish">
          {{ t('exchange.register.actionPublish') }}
        </el-button>
      </div>
      <el-input
        v-model="reason"
        class="reg-reason"
        :placeholder="t('exchange.register.reasonPlaceholder')"
      />
      <p class="reg-hint">{{ t('exchange.register.roleHint') }}</p>
      <p v-if="actionError" class="ex-error" role="alert">{{ actionError }}</p>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  auditAsset,
  publishAsset,
  registerAsset,
  type Asset,
  type AssetAuditResult,
  type AssetType,
  type SecurityLevel
} from '@/api/assetMarket'
import { EXCHANGE_STATUS_MAP } from './format'

const { t } = useI18n()

/**
 * 选项集合以服务端枚举为准（models/base.py 全小写）。
 * 交付方式（deliveryMethod）刻意不采集：后端 ListAssetRequest/RegisterAssetRequest
 * 都没有这个字段，收了也会被静默丢弃。
 */
const ASSET_TYPE_OPTIONS: AssetType[] = ['table', 'api', 'model', 'dashboard', 'stream']
const SECURITY_OPTIONS: SecurityLevel[] = ['public', 'internal', 'sensitive']
const FREQUENCY_OPTIONS = ['realtime', 'hourly', 'daily', 'weekly', 'monthly', 'static'] as const
const PRICING_MODES = ['by_call', 'by_data', 'subscription', 'by_time', 'one_time'] as const

const form = reactive({
  name: '',
  type: 'table' as AssetType,
  tenantId: '',
  description: '',
  securityLevel: 'internal' as SecurityLevel,
  qualityScore: 80,
  updateFrequency: 'daily' as (typeof FREQUENCY_OPTIONS)[number],
  pricing: { mode: 'by_call' as (typeof PRICING_MODES)[number], price: 1, unit: '次' }
})

const created = ref<Asset | null>(null)
const reason = ref('')
const registering = ref(false)
const auditing = ref(false)
const publishing = ref(false)
const registerError = ref('')
const actionError = ref('')

/** 后端只在 draft / pending_audit 两种状态下接受审核 */
const canAudit = computed(
  () => !!created.value && ['draft', 'pending_audit'].includes(created.value.status)
)
/** 上架允许 draft / pending_audit / offline；已是 listed 时按钮自然置灰 */
const canPublish = computed(
  () => !!created.value && ['draft', 'pending_audit', 'offline'].includes(created.value.status)
)

async function register(): Promise<void> {
  if (!form.name.trim()) {
    registerError.value = t('exchange.register.nameRequired')
    return
  }
  registering.value = true
  registerError.value = ''
  actionError.value = ''
  try {
    created.value = await registerAsset({
      name: form.name.trim(),
      type: form.type,
      tenantId: form.tenantId.trim() || undefined,
      description: form.description.trim() || undefined,
      securityLevel: form.securityLevel,
      qualityScore: Number(form.qualityScore) || 0,
      updateFrequency: form.updateFrequency,
      pricing: {
        mode: form.pricing.mode,
        price: Number(form.pricing.price) || 0,
        unit: form.pricing.unit.trim() || form.pricing.mode
      }
    })
    ElMessage.success(t('exchange.register.registered'))
  } catch (e) {
    registerError.value = `${t('exchange.register.registerFailed')}${(e as Error)?.message ?? ''}`
    created.value = null
  } finally {
    registering.value = false
  }
}

async function audit(result: AssetAuditResult): Promise<void> {
  if (!created.value) return
  if (result === 'rejected' && !reason.value.trim()) {
    actionError.value = t('exchange.register.reasonRequired')
    return
  }
  auditing.value = true
  actionError.value = ''
  try {
    created.value = await auditAsset(created.value.id, {
      result,
      reason: reason.value.trim() || undefined
    })
    ElMessage.success(
      result === 'approved' ? t('exchange.register.approved') : t('exchange.register.rejected')
    )
  } catch (e) {
    actionError.value = `${t('exchange.register.auditFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    auditing.value = false
  }
}

async function publish(): Promise<void> {
  if (!created.value) return
  publishing.value = true
  actionError.value = ''
  try {
    created.value = await publishAsset(created.value.id)
    ElMessage.success(t('exchange.register.published'))
  } catch (e) {
    actionError.value = `${t('exchange.register.publishFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    publishing.value = false
  }
}

function reset(): void {
  form.name = ''
  form.tenantId = ''
  form.description = ''
  reason.value = ''
  created.value = null
  registerError.value = ''
  actionError.value = ''
}
</script>

<style scoped>
.ex-page {
  padding: var(--ds-spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-4);
}

.reg-form {
  max-width: 640px;
}

.reg-hint {
  margin: var(--ds-spacing-1) 0 0;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.reg-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-3);
}

.reg-reason {
  max-width: 480px;
  margin-bottom: var(--ds-spacing-2);
}

.reg-result {
  margin: 0 0 var(--ds-spacing-3);
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}

.reg-kv {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: baseline;
}

.reg-kv dt {
  flex: 0 0 120px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.reg-kv dd {
  margin: 0;
  word-break: break-all;
  color: var(--ds-text-primary);
}

.ex-error {
  margin: var(--ds-spacing-2) 0 0;
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.ex-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
