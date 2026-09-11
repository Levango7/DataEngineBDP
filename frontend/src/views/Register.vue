<template>
  <div class="reg-page" role="main" :aria-label="t('register.title')">
    <div class="reg-bg-grid" aria-hidden="true"></div>
    <div class="reg-bg-glow reg-bg-glow--1" aria-hidden="true"></div>
    <div class="reg-bg-glow reg-bg-glow--2" aria-hidden="true"></div>

    <div class="reg-card" role="region" :aria-label="t('register.card')">
      <div class="brand">
        <span class="dot" aria-hidden="true"></span>
        <span class="brand-text">{{ t('nav.brand') }}</span>
      </div>

      <!-- 步骤条 -->
      <ol class="reg-steps" role="list">
        <li :class="{ on: step >= 1, done: step > 1 }">
          <span class="step-dot">1</span>
          <span class="step-text">{{ t('register.steps.code') }}</span>
        </li>
        <li :class="{ on: step >= 2, done: step > 2 }">
          <span class="step-dot">2</span>
          <span class="step-text">{{ t('register.steps.info') }}</span>
        </li>
        <li :class="{ on: step >= 3 }">
          <span class="step-dot">3</span>
          <span class="step-text">{{ t('register.steps.review') }}</span>
        </li>
      </ol>

      <!-- Step 1: 邀请码 -->
      <section v-if="step === 1" class="reg-section" :aria-label="t('register.step1.aria')">
        <h2>{{ t('register.step1.heading') }}</h2>
        <p class="reg-sub">{{ t('register.step1.sub') }}</p>
        <el-form :model="step1" @submit.prevent="validateCode">
          <el-form-item>
            <el-input
              v-model="step1.code"
              :placeholder="t('register.step1.codePlaceholder')"
              size="large"
              style="text-align: center; letter-spacing: 6px; font-weight: 700"
              maxlength="8"
              @keyup.enter="validateCode"
            />
          </el-form-item>
          <div v-if="step1Err" class="err" role="alert">{{ step1Err }}</div>
          <el-button
            type="primary"
            size="large"
            class="reg-btn"
            :loading="validating"
            @click="validateCode"
          >
            {{ t('register.step1.next') }}
          </el-button>
        </el-form>
        <div class="reg-tip">
          {{ t('register.step1.noCode') }}
          <a href="javascript:void(0)">{{ t('register.step1.contact') }}</a>
        </div>
      </section>

      <!-- Step 2: 资料 -->
      <section v-else-if="step === 2" class="reg-section" :aria-label="t('register.step2.aria')">
        <h2>{{ t('register.step2.heading') }}</h2>
        <div class="reg-banner">
          <div class="reg-banner-row">
            <span>{{ t('register.step2.tenant') }}</span>
            <b>{{ preview.tenant?.displayName }}</b>
            <code>{{ preview.tenant?.name }}</code>
          </div>
          <div class="reg-banner-row">
            <span>{{ t('register.step2.inviteRole') }}</span>
            <b>{{ roleLabel(preview.invite?.role) }}</b>
            <span class="reg-banner-tip">{{ preview.invite?.note }}</span>
          </div>
        </div>

        <el-form ref="step2FormRef" :model="step2" :rules="step2Rules" label-position="top">
          <el-form-item :label="t('register.step2.fieldUsername')" prop="username">
            <el-input
              v-model="step2.username"
              :placeholder="t('register.step2.usernameHint')"
              size="large"
            />
          </el-form-item>
          <el-form-item :label="t('register.step2.fieldFullName')" prop="fullName">
            <el-input
              v-model="step2.fullName"
              :placeholder="t('register.step2.fullNameHint')"
              size="large"
            />
          </el-form-item>
          <el-form-item :label="t('register.step2.fieldEmail')" prop="email">
            <el-input
              v-model="step2.email"
              :placeholder="t('register.step2.emailHint')"
              size="large"
            />
          </el-form-item>
          <el-form-item :label="t('register.step2.fieldDepartment')" prop="department">
            <el-input
              v-model="step2.department"
              :placeholder="t('register.step2.departmentHint')"
              size="large"
            />
          </el-form-item>
          <el-form-item :label="t('register.step2.fieldEmployeeId')" prop="employeeId">
            <el-input
              v-model="step2.employeeId"
              :placeholder="t('register.step2.employeeIdHint')"
              size="large"
            />
          </el-form-item>
        </el-form>

        <div class="reg-actions">
          <el-button @click="step = 1">{{ t('register.step2.prev') }}</el-button>
          <el-button type="primary" size="large" :loading="submitting" @click="submitForm">
            {{ t('register.step2.submit') }}
          </el-button>
        </div>
      </section>

      <!-- Step 3: 完成 -->
      <section v-else class="reg-section" :aria-label="t('register.step3.aria')">
        <div class="reg-success">
          <div class="reg-success-icon" aria-hidden="true">✓</div>
          <h2>{{ t('register.step3.heading') }}</h2>
          <p class="reg-sub">{{ t('register.step3.sub') }}</p>
          <ul class="reg-success-meta">
            <li>
              {{ t('register.step3.labelUsername') }}
              <b>{{ step2.username }}</b>
            </li>
            <li>
              {{ t('register.step3.labelTenant') }}
              <b>{{ preview.tenant?.displayName }}</b>
            </li>
            <li>
              {{ t('register.step3.labelTime') }}
              <b>{{ submittedAt }}</b>
            </li>
          </ul>
          <div class="reg-success-tip">
            {{ t('register.step3.footer') }}
          </div>
        </div>
      </section>

      <div class="reg-bottom">
        <a href="javascript:void(0)" @click="$router.replace('/login')">
          {{ t('register.step3.backToLogin') }}
        </a>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { useTenantAdminStore, type AdminInvite, type AdminTenant } from '@/stores/tenantAdmin'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const admin = useTenantAdminStore()

const step = ref<1 | 2 | 3>(1)
const step1 = reactive({ code: '' })
const step1Err = ref('')
const validating = ref(false)
const preview = ref<{ invite?: AdminInvite; tenant?: AdminTenant }>({})

const step2FormRef = ref<FormInstance>()
const step2 = reactive({
  username: '',
  fullName: '',
  email: '',
  department: '',
  employeeId: ''
})
const step2Rules = {
  username: [
    { required: true, message: t('register.rules.usernameRequired'), trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_-]{2,30}$/,
      message: t('register.rules.usernamePattern'),
      trigger: 'blur'
    }
  ],
  fullName: [{ required: true, message: t('register.rules.fullNameRequired'), trigger: 'blur' }],
  email: [
    { required: true, message: t('register.rules.emailRequired'), trigger: 'blur' },
    { type: 'email' as const, message: t('register.rules.emailInvalid'), trigger: 'blur' }
  ],
  department: [
    { required: true, message: t('register.rules.departmentRequired'), trigger: 'blur' }
  ],
  employeeId: [{ required: true, message: t('register.rules.employeeIdRequired'), trigger: 'blur' }]
}

const submitting = ref(false)
const submittedAt = ref('')

function roleLabel(role: string | undefined): string {
  const m: Record<string, string> = {
    PLATFORM_ADMIN: t('register.labels.rolePlatformAdmin'),
    TENANT_ADMIN: t('register.labels.roleTenantAdmin'),
    USER: t('register.labels.roleUser')
  }
  return m[role ?? ''] || (role ?? '—')
}

/** URL ?code=XXX 自动填入 + 立即预检 */
onMounted(() => {
  const c = route.query.code
  if (typeof c === 'string' && c.trim()) {
    step1.code = c.trim().toUpperCase()
    setTimeout(() => validateCode(), 100)
  }
})

function validateCode() {
  step1Err.value = ''
  if (!step1.code.trim()) {
    step1Err.value = t('register.messages.codeRequired')
    return
  }
  validating.value = true
  setTimeout(() => {
    const result = admin.previewInvite(step1.code.trim())
    if (!result.ok) {
      step1Err.value = result.error || t('register.messages.codeInvalid')
      validating.value = false
      return
    }
    preview.value = { invite: result.invite, tenant: result.tenant }
    validating.value = false
    step.value = 2
  }, 250)
}

async function submitForm() {
  if (!step2FormRef.value) return
  const valid = await step2FormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  const result = admin.submitRegistration({
    code: preview.value.invite!.code,
    ...step2
  })
  submitting.value = false
  if (!result.ok) {
    ElMessage.error(result.error || t('register.messages.submitFailed'))
    return
  }
  submittedAt.value = new Date().toLocaleString('zh-CN')
  step.value = 3
}
</script>

<style scoped>
.reg-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  overflow: hidden;
  background:
    radial-gradient(ellipse 1100px 700px at 0% 0%, #dbeafe 0%, transparent 60%),
    radial-gradient(ellipse 1000px 700px at 100% 100%, #e0e7ff 0%, transparent 55%),
    linear-gradient(135deg, #f0f4ff 0%, #e8eef9 100%);
}
.reg-bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(59, 130, 246, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(59, 130, 246, 0.05) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 80%);
  pointer-events: none;
}
.reg-bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(110px);
  pointer-events: none;
  animation: regFloat 4s ease-in-out infinite alternate;
}
.reg-bg-glow--1 {
  width: 460px;
  height: 460px;
  top: -180px;
  left: -180px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.32) 0%, transparent 70%);
}
.reg-bg-glow--2 {
  width: 400px;
  height: 400px;
  bottom: -160px;
  right: -120px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.3) 0%, transparent 70%);
  animation-delay: 2.4s;
}
@keyframes regFloat {
  0% {
    transform: translate(0, 0) scale(1);
  }
  100% {
    transform: translate(20px, -30px) scale(1.05);
  }
}
.reg-card {
  position: relative;
  z-index: 2;
  width: 100%;
  max-width: 520px;
  padding: 36px 40px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 16px;
  box-shadow:
    0 20px 60px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.7);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ds-text-primary);
  margin-bottom: 18px;
}
.brand .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6 0%, #6366f1 100%);
  box-shadow: 0 0 10px rgba(99, 102, 241, 0.7);
}

.reg-steps {
  display: flex;
  align-items: center;
  gap: 0;
  list-style: none;
  padding: 0;
  margin: 0 0 28px;
  font-size: 12.5px;
}
.reg-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ds-border-strong);
  flex: 1;
}
.reg-steps li + li::before {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--ds-border-subtle);
  margin: 0 4px;
}
.reg-steps li.done + li::before,
.reg-steps li.on + li::before {
  background: var(--ds-color-primary-500);
}
.step-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ds-bg-muted);
  color: var(--ds-border-strong);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  flex: none;
  transition: all 0.2s var(--ease-smooth);
}
.reg-steps li.on .step-dot {
  background: var(--ds-color-primary-500);
  color: var(--ds-text-inverse);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.2);
}
.reg-steps li.done .step-dot {
  background: var(--ds-color-success-500);
  color: var(--ds-text-inverse);
}
.reg-steps li.on .step-text,
.reg-steps li.done .step-text {
  color: var(--ds-text-primary);
  font-weight: 600;
}

.reg-section h2 {
  font-size: 20px;
  font-weight: 700;
  color: var(--ds-text-primary);
  margin: 0 0 6px;
}
.reg-sub {
  font-size: 13px;
  color: var(--ds-text-tertiary);
  margin: 0 0 22px;
  line-height: 1.6;
}
.reg-btn {
  width: 100%;
  height: 44px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 4px;
  background: linear-gradient(135deg, #3b82f6 0%, #6366f1 100%) !important;
  border: none !important;
  margin-top: 6px;
}
.reg-tip {
  margin-top: 18px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ds-text-tertiary);
}
.reg-tip a {
  margin-left: 4px;
  color: var(--ds-color-primary-500);
  text-decoration: none;
  font-weight: 600;
}
.err {
  color: var(--ds-color-error-700);
  background: var(--ds-color-error-50);
  border: 1px solid var(--ds-color-error-200);
  border-left: 3px solid var(--ds-color-error-500);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12.5px;
  margin: 8px 0;
}

.reg-banner {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.07) 0%, rgba(99, 102, 241, 0.04) 100%);
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 18px;
  font-size: 13px;
}
.reg-banner-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
}
.reg-banner-row span:first-child {
  color: var(--ds-text-tertiary);
  font-size: 12px;
  min-width: 60px;
}
.reg-banner-row b {
  color: var(--ds-color-primary-700);
  font-weight: 700;
}
.reg-banner-row code {
  font-family: var(--ds-font-family-mono);
  font-size: 11.5px;
  color: var(--ds-text-secondary);
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  border-radius: 4px;
  padding: 1px 6px;
}
.reg-banner-tip {
  margin-left: auto;
  color: var(--ds-border-strong);
  font-size: 11.5px;
}

.reg-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 18px;
}
.reg-actions .el-button:first-child {
  flex: 1;
  margin-right: 8px;
}
.reg-actions .el-button:last-child {
  flex: 2;
}

.reg-success {
  text-align: center;
  padding: 20px 0 8px;
}
.reg-success-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: var(--ds-text-inverse);
  font-size: 36px;
  font-weight: 700;
  line-height: 64px;
  margin: 0 auto 16px;
  box-shadow: 0 6px 18px rgba(16, 185, 129, 0.3);
  animation: regSuccess 0.45s var(--ease-spring);
}
@keyframes regSuccess {
  0% {
    transform: scale(0.5);
    opacity: 0;
  }
  60% {
    transform: scale(1.1);
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}
.reg-success-meta {
  list-style: none;
  padding: 16px 20px;
  margin: 20px 0 0;
  background: var(--ds-bg-base);
  border: 1px solid var(--ds-border-subtle);
  border-radius: 10px;
  text-align: left;
  font-size: 13px;
}
.reg-success-meta li {
  display: flex;
  padding: 4px 0;
  color: var(--ds-text-secondary);
}
.reg-success-meta li b {
  margin-left: auto;
  color: var(--ds-text-primary);
  font-weight: 600;
}
.reg-success-tip {
  margin-top: 18px;
  font-size: 12.5px;
  color: var(--ds-text-tertiary);
  line-height: 1.6;
}

.reg-bottom {
  text-align: center;
  margin-top: 22px;
  font-size: 12.5px;
}
.reg-bottom a {
  color: var(--ds-color-primary-500);
  text-decoration: none;
  font-weight: 500;
}
</style>
