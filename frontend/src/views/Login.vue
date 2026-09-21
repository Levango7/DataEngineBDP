<template>
  <div class="login-page" role="main" :aria-label="t('login.title')">
    <!-- 左侧品牌区（飞书风：品牌 + Slogan + 装饰） -->
    <section class="login-left" :aria-label="t('login.brandAria')">
      <!-- 装饰层：极淡蓝光晕 + 网格（数据平台质感） -->
      <div class="left-bg-grid" aria-hidden="true"></div>
      <div class="left-bg-glow left-bg-glow--1" aria-hidden="true"></div>
      <div class="left-bg-glow left-bg-glow--2" aria-hidden="true"></div>

      <div class="left-content">
        <!-- 品牌行 -->
        <div class="brand" :aria-label="t('nav.brand')">
          <span class="dot" aria-hidden="true"></span>
          <span class="brand-text">{{ t('nav.brand') }}</span>
        </div>

        <!-- 核心价值主张 -->
        <h1 class="slogan">
          {{ t('login.sloganLine1') }}
          <br />
          <span class="slogan-accent">{{ t('login.sloganLine2') }}</span>
        </h1>
        <p class="sub-slogan">
          {{ t('login.subSlogan1') }}
          <br />
          {{ t('login.subSlogan2') }}
        </p>

        <!-- 特性指标行 -->
        <div class="metrics" role="list">
          <div class="metric" role="listitem">
            <div class="metric-num">
              99.99
              <span>%</span>
            </div>
            <div class="metric-lbl">{{ t('login.metricAvailability') }}</div>
          </div>
          <div class="metric" role="listitem">
            <div class="metric-num">
              30
              <span>+</span>
            </div>
            <div class="metric-lbl">{{ t('login.metricServices') }}</div>
          </div>
          <div class="metric" role="listitem">
            <div class="metric-num">
              <span class="metric-letter">{{ t('login.metricStack') }}</span>
            </div>
            <div class="metric-lbl">{{ t('login.metricStackLbl') }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 右侧登录表单区 -->
    <section class="login-right" :aria-label="t('login.formAria')">
      <!-- 右侧工具条：主题切换 + 语言切换 -->
      <div class="right-topbar">
        <!-- 二合一工具胶囊：i18n + theme 一体（与整体布局协调，避免双按钮零散） -->
        <div class="tb-capsule" role="group" :aria-label="t('login.quickSwitchAria')">
          <button
            class="tb-pill"
            :aria-label="t('app.localeLabel')"
            :title="t('app.localeLabel')"
            @click="toggleLocale"
          >
            {{ locale === 'zh-CN' ? 'EN' : '中' }}
          </button>
          <span class="tb-divider" aria-hidden="true"></span>
          <button
            class="tb-pill tb-theme"
            :aria-label="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
            :title="theme.isDark ? t('app.themeToggleToLight') : t('app.themeToggleToDark')"
            @click="theme.toggle"
          >
            <span class="tb-pill-ic" aria-hidden="true">
              <!-- 暗色时显示太阳（点击切回亮色），亮色时显示月亮（点击切到暗色） -->
              <el-icon v-if="theme.isDark"><Sunny /></el-icon>
              <el-icon v-else><Moon /></el-icon>
            </span>
          </button>
        </div>
      </div>

      <div class="login-card" role="region" :aria-label="t('login.cardAria')">
        <h2 class="card-title">{{ t('login.title') }}</h2>
        <p class="card-sub">{{ t('login.cardSub') }}</p>

        <el-form :model="form" label-position="top" @submit.prevent="handleLogin">
          <el-form-item :label="t('login.username')">
            <el-input
              v-model="form.username"
              :placeholder="t('login.usernamePlaceholder')"
              autocomplete="username"
              :aria-label="t('login.username')"
              size="large"
            />
          </el-form-item>
          <el-form-item :label="t('login.password')">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="t('login.passwordPlaceholder')"
              autocomplete="current-password"
              :aria-label="t('login.password')"
              size="large"
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <div class="form-row">
            <el-checkbox v-model="remember">{{ t('login.rememberMe') }}</el-checkbox>
            <a class="forgot" href="javascript:void(0)" :aria-label="t('login.forgotPassword')">
              {{ t('login.forgotPassword') }}？
            </a>
          </div>
          <el-button
            type="primary"
            :loading="loading"
            class="login-btn"
            size="large"
            :aria-label="t('login.submit')"
            @click="handleLogin"
          >
            {{ loading ? t('login.loggingIn') : t('login.submit') }}
          </el-button>
          <div v-if="error" class="error" role="alert" aria-live="assertive">{{ error }}</div>
        </el-form>

        <!-- 企业版 / 联系销售 -->
        <div class="card-bottom">
          <span class="bottom-text">{{ t('login.noAccount') }}</span>
          <router-link class="bottom-link" to="/register">
            {{ t('login.useInviteCode') }}
          </router-link>
          <span class="bottom-sep" aria-hidden="true">·</span>
          <a class="bottom-link" href="javascript:void(0)">{{ t('login.applyTrial') }}</a>
        </div>

        <!-- 本地开发提示（仅本地模式可见） -->
        <div class="dev-tip" :aria-label="t('login.devTipAria')">
          <span class="dev-tip-label">{{ t('login.devDemo') }}</span>
          <code>admin / admin</code>
          <span class="dev-tip-sep">·</span>
          <code>user / user</code>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { persistLocale, type SupportedLocale } from '@/i18n'
import { Sunny, Moon } from '@element-plus/icons-vue'

const { t, locale } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()

const form = ref({ username: '', password: '' })
const remember = ref(true)
const loading = ref(false)
const error = ref('')

/** 登录页语言切换：与顶栏同一行为，切换 + 持久化 */
function toggleLocale() {
  const next: SupportedLocale = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  locale.value = next
  persistLocale(next)
}

async function handleLogin() {
  if (!form.value.username || !form.value.password) {
    error.value = t('login.required')
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.value.username, form.value.password)
    ElMessage.success(t('login.loginSuccess'))
    const redirect = Array.isArray(route.query.redirect)
      ? route.query.redirect[0] || '/dashboard'
      : route.query.redirect || '/dashboard'
    router.replace(redirect)
  } catch (e) {
    error.value = `${t('login.loginFailed')}: ${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ============================================================
 * 飞书风登录页：左品牌区 + 右表单区，浅色主调
 * 配色：极淡蓝白渐变 + 蓝色品牌色 + 干净白底
 * ============================================================ */
.login-page {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(380px, 1fr) minmax(440px, 1.1fr);
  overflow: hidden;
  background:
    radial-gradient(
      ellipse 1200px 800px at 0% 50%,
      var(--ds-color-primary-100) 0%,
      transparent 60%
    ),
    radial-gradient(
      ellipse 1000px 700px at 100% 100%,
      var(--ds-color-info-100) 0%,
      transparent 55%
    ),
    linear-gradient(135deg, var(--ds-color-primary-50) 0%, var(--ds-color-gray-100) 100%);
}

/* === 左侧品牌区 === */
.login-left {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--ds-spacing-15) var(--ds-spacing-12);
  overflow: hidden;
}
.left-bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(59, 130, 246, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(59, 130, 246, 0.06) 1px, transparent 1px);
  background-size: 32px 32px;
  /* #000 为 mask luminance 功能值（非视觉颜色），不随主题变化，保留硬编码 */
  mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 80%);
  pointer-events: none;
}
.left-bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
  animation: orbFloat var(--ds-animation-orb-float) ease-out forwards;
}
.left-bg-glow--1 {
  width: 480px;
  height: 480px;
  top: -180px;
  left: -180px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.35) 0%, transparent 70%);
}
.left-bg-glow--2 {
  width: 420px;
  height: 420px;
  bottom: -160px;
  right: -120px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.3) 0%, transparent 70%);
  animation-delay: 0.15s;
}
@keyframes orbFloat {
  0% {
    opacity: var(--ds-opacity-0);
    transform: translateY(12px);
  }
  100% {
    opacity: var(--ds-opacity-10);
    transform: translateY(0);
  }
}
/* 尊重用户减少动画偏好：光晕即时显示，不播放入场动画 */
@media (prefers-reduced-motion: reduce) {
  .left-bg-glow {
    animation: none;
    opacity: var(--ds-opacity-10);
    transform: none;
  }
}
.left-content {
  position: relative;
  max-width: 460px;
  z-index: 2;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--ds-font-size-xl);
  font-weight: var(--ds-font-weight-extrabold);
  color: var(--ds-text-primary);
  margin-bottom: var(--ds-spacing-16);
  letter-spacing: 0.3px;
}
.brand .dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 100%
  );
  box-shadow:
    0 0 12px rgba(99, 102, 241, 0.7),
    0 0 0 4px rgba(99, 102, 241, 0.15);
  animation: dotBreath var(--ds-animation-breath) var(--ease-smooth) infinite;
}

.slogan {
  font-size: var(--ds-font-size-5xl);
  font-weight: var(--ds-font-weight-extrabold);
  line-height: var(--ds-line-height-snug);
  color: var(--ds-text-primary);
  margin: 0 0 18px;
  letter-spacing: 0.5px;
}
.slogan-accent {
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 50%,
    var(--ds-color-accent-500) 100%
  );
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.sub-slogan {
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-tertiary);
  line-height: var(--ds-line-height-extra-loose);
  margin: 0 0 56px;
}
.metrics {
  display: flex;
  gap: var(--ds-spacing-9);
  border-top: 1px solid rgba(100, 116, 139, 0.18);
  padding-top: var(--ds-spacing-6);
}
.metric-num {
  font-size: var(--ds-font-size-4xl);
  font-weight: var(--ds-font-weight-extrabold);
  line-height: var(--ds-line-height-heading);
  color: var(--ds-color-gray-800);
}
.metric-num span {
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-medium);
  color: var(--ds-text-tertiary);
  margin-left: 2px;
}
.metric-letter {
  font-size: var(--ds-font-size-2xl);
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 100%
  );
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.metric-lbl {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-top: var(--ds-spacing-1);
}

/* === 右侧表单区 === */
.login-right {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--ds-spacing-8) var(--ds-spacing-12);
}
.right-topbar {
  position: absolute;
  top: 24px;
  right: 32px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.tb-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--ds-spacing-1);
  height: 30px;
  padding: 0 10px;
  border-radius: var(--ds-radius-lg);
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-semibold);
  cursor: pointer;
  box-shadow: var(--ds-shadow-sm);
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast),
    border-color var(--ds-transition-fast),
    transform var(--ds-transition-fast);
}
.tb-tool:hover {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-700);
  border-color: var(--ds-color-primary-300);
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(59, 130, 246, 0.18);
}
.tb-tool:active {
  transform: translateY(0);
}
.tb-lang {
  min-width: 64px;
  letter-spacing: 0.5px;
}
.login-card {
  width: 100%;
  max-width: 420px;
  padding: 0;
  background: transparent;
  border: none;
  box-shadow: none;
}
.card-title {
  margin: 0 0 8px;
  font-size: var(--ds-font-size-3xl);
  font-weight: var(--ds-font-weight-extrabold);
  color: var(--ds-text-primary);
  letter-spacing: 0.3px;
}
.card-sub {
  margin: 0 0 32px;
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-tertiary);
  line-height: var(--ds-line-height-loose);
}
.form-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: -4px 0 18px;
  font-size: var(--ds-font-size-xs);
}
.forgot {
  color: var(--ds-color-primary-500);
  text-decoration: none;
  font-weight: var(--ds-font-weight-medium);
  transition: color var(--ds-transition-fast);
}
.forgot:hover {
  color: var(--ds-color-primary-700);
  text-decoration: underline;
}
.login-btn {
  width: 100%;
  height: 44px;
  font-size: var(--ds-font-size-lg);
  font-weight: var(--ds-font-weight-semibold);
  letter-spacing: 2px;
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 100%
  ) !important;
  border: none !important;
  box-shadow:
    0 4px 14px rgba(99, 102, 241, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.15);
  transition:
    transform var(--ds-transition-quick),
    box-shadow var(--ds-transition-quick),
    filter var(--ds-transition-quick);
}
.login-btn:hover,
.login-btn:focus {
  transform: translateY(-2px);
  box-shadow:
    0 8px 22px rgba(99, 102, 241, 0.5),
    0 0 0 4px rgba(99, 102, 241, 0.12) !important;
  filter: brightness(1.06);
}
.login-btn:active {
  transform: translateY(0);
}
.error {
  color: var(--ds-color-error-700);
  margin-top: var(--ds-spacing-3);
  font-size: var(--ds-font-size-xs);
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  background: var(--ds-color-error-50);
  border: 1px solid var(--ds-color-error-200);
  border-radius: var(--ds-radius-md);
  border-left: 3px solid var(--ds-color-error-500);
}
.card-bottom {
  margin-top: 22px;
  text-align: center;
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
}
.bottom-sep {
  margin: 0 6px;
  color: var(--ds-border-default);
}
.bottom-link {
  margin-left: var(--ds-spacing-1);
  color: var(--ds-color-primary-500);
  text-decoration: none;
  font-weight: var(--ds-font-weight-semibold);
  transition: color var(--ds-transition-fast);
}
.bottom-link:hover {
  color: var(--ds-color-primary-700);
  text-decoration: underline;
}
.dev-tip {
  margin-top: 18px;
  padding: 10px 14px;
  background: var(--ds-bg-subtle);
  border: 1px dashed var(--ds-border-default);
  border-radius: var(--ds-radius-md);
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-secondary);
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
  flex-wrap: wrap;
}
.dev-tip-label {
  background: var(--ds-color-primary-500);
  color: var(--ds-text-inverse);
  font-weight: var(--ds-font-weight-semibold);
  padding: 1px 7px;
  border-radius: var(--ds-radius-sm);
  font-size: var(--ds-font-size-xs);
}
.dev-tip code {
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  color: var(--ds-color-gray-800);
  background: var(--ds-bg-surface);
  padding: 1px 6px;
  border-radius: var(--ds-radius-sm);
  border: 1px solid var(--ds-border-subtle);
}
.dev-tip-sep {
  color: var(--ds-border-default);
}

/* === Element Plus 输入框适配（白底浅蓝调） === */
:deep(.el-form-item__label) {
  color: var(--ds-color-gray-700);
  font-weight: var(--ds-font-weight-medium);
  font-size: var(--ds-font-size-base);
  padding-bottom: 6px;
}
:deep(.el-input__inner::placeholder) {
  color: var(--ds-border-strong);
}
:deep(.el-checkbox__label) {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-xs);
}
:deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: var(--ds-color-primary-500);
  border-color: var(--ds-color-primary-500);
}
:deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: var(--ds-color-primary-700);
}

/* === 响应式：小屏（≤960px）退化为单列 === */
@media (max-width: 1024px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .login-left {
    display: none;
  }
  .login-right {
    padding: var(--ds-spacing-10) var(--ds-spacing-6);
  }
}
/* === 右上角二合一胶囊：i18n + theme（2026-09-07 整合） === */
.right-topbar {
  position: absolute;
  top: 24px;
  right: 32px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.tb-capsule {
  display: inline-flex;
  align-items: stretch;
  background: rgba(255, 255, 255, 0.9);
  border: 1.5px solid var(--ds-border-default);
  border-radius: var(--ds-radius-2xl);
  box-shadow: var(--ds-shadow-md);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  overflow: hidden;
  transition:
    background var(--ds-transition-quick),
    border-color var(--ds-transition-quick),
    box-shadow var(--ds-transition-quick);
}
.tb-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--ds-spacing-1);
  height: 32px;
  padding: 0 14px;
  background: transparent;
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-semibold);
  letter-spacing: 0.3px;
  cursor: pointer;
  border: none;
  transition:
    background var(--ds-transition-fast),
    color var(--ds-transition-fast);
}
.tb-pill:hover {
  background: rgba(59, 130, 246, 0.1);
  color: var(--ds-color-primary-700);
}
.tb-pill-ic {
  font-size: var(--ds-font-size-base);
  line-height: var(--ds-line-height-none);
}
.tb-divider {
  width: 1px;
  align-self: stretch;
  background: var(--ds-border-default);
  margin: 4px 0;
}
.tb-theme {
  padding: 0 12px;
}
</style>

<!-- 暗色模式覆写（2026-09-07 修复）
    必须放在 <style scoped> 之外（全局），否则 Vue scoped 编译
    会把 :root[] + 复合选择器的后半段误处理为另一个 :root[]
    导致选择器整体失效、文字不变色。
    同一文件内可有多个 <style> 块。 -->
<style>
/* ============================================================
 * 暗色模式（data-theme=dark 时全登录页深色，2026-09-07）
 * ============================================================ */
:root[data-theme='dark'] .login-page {
  background:
    radial-gradient(ellipse 1100px 700px at 0% 0%, var(--ds-bg-surface) 0%, transparent 60%),
    radial-gradient(
      ellipse 1000px 700px at 100% 100%,
      var(--ds-color-info-950) 0%,
      transparent 55%
    ),
    /* indigo-950 深紫装饰色，引用 design-tokens.css token (2026-09-14 P3-2) */
    linear-gradient(135deg, var(--ds-color-dark-bg-1) 0%, var(--ds-bg-base) 100%) !important;
}
/* 左侧品牌区整块深色 */
:root[data-theme='dark'] .login-left {
  background: linear-gradient(
    180deg,
    var(--ds-color-dark-bg-1) 0%,
    var(--ds-color-dark-bg-2) 100%
  ) !important;
}
/* 右侧表单区 */
:root[data-theme='dark'] .login-right {
  background: linear-gradient(
    180deg,
    var(--ds-color-dark-bg-1) 0%,
    var(--ds-bg-base) 100%
  ) !important;
}
:root[data-theme='dark'] .left-bg-grid,
:root[data-theme='dark'] .reg-bg-grid {
  background-image:
    linear-gradient(rgba(99, 102, 241, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99, 102, 241, 0.08) 1px, transparent 1px) !important;
}
/* 暗色版光晕（蓝紫转蓝青，更符合深色） */
:root[data-theme='dark'] .left-bg-glow--1 {
  background: radial-gradient(circle, rgba(59, 130, 246, 0.45) 0%, transparent 70%) !important;
}
:root[data-theme='dark'] .left-bg-glow--2 {
  background: radial-gradient(circle, rgba(99, 102, 241, 0.35) 0%, transparent 70%) !important;
}
:root[data-theme='dark'] .login-card,
:root[data-theme='dark'] .reg-card {
  background: transparent !important;
  border-color: transparent !important;
  box-shadow: none !important;
}
/* 品牌行去独立背景与深色背景融合 */
:root[data-theme='dark'] .brand {
  background: transparent !important;
}
:root[data-theme='dark'] .el-form-item__label {
  color: var(--ds-color-primary-300) !important;
  font-weight: var(--ds-font-weight-semibold) !important;
}
:root[data-theme='dark'] .el-input__wrapper,
:root[data-theme='dark'] .reg-banner-row code {
  background: rgba(15, 23, 42, 0.7) !important;
  border: 1.5px solid rgba(99, 102, 241, 0.45) !important;
  box-shadow: none !important;
}
:root[data-theme='dark'] .el-input__wrapper:hover {
  border-color: var(--ds-color-primary-400) !important;
}
:root[data-theme='dark'] .el-input__wrapper.is-focus {
  border-color: var(--ds-color-primary-400) !important;
  box-shadow:
    0 0 0 1px var(--ds-color-primary-400) inset,
    0 0 0 3px rgba(96, 165, 250, 0.18) !important;
}
:root[data-theme='dark'] .el-input__inner,
:root[data-theme='dark'] .el-textarea__inner {
  color: var(--ds-text-primary) !important;
  padding-left: 0 !important;
  padding-right: 0 !important;
}
:root[data-theme='dark'] .el-input__inner::placeholder,
:root[data-theme='dark'] .el-input__prefix,
:root[data-theme='dark'] .el-input__count .el-input__count-inner {
  color: var(--ds-text-tertiary) !important;
}
:root[data-theme='dark'] .el-input__prefix {
  margin-left: 0 !important;
  margin-right: 8px !important;
}
:root[data-theme='dark'] .el-input__suffix {
  margin-left: 8px !important;
  margin-right: 0 !important;
}
:root[data-theme='dark'] .el-checkbox__label,
:root[data-theme='dark'] .el-checkbox__input.is-checked + .el-checkbox__label {
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .login-btn {
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-500) 0%,
    var(--ds-color-info-500) 100%
  ) !important;
  color: var(--ds-text-inverse) !important;
  box-shadow:
    0 4px 14px rgba(99, 102, 241, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.15) !important;
}
:root[data-theme='dark'] .brand,
:root[data-theme='dark'] .card-title,
:root[data-theme='dark'] .slogan {
  color: var(--ds-text-primary) !important;
}
:root[data-theme='dark'] .slogan-accent {
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-400) 0%,
    var(--ds-color-info-400) 50%,
    var(--ds-color-accent-400) 100%
  ) !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  -webkit-text-fill-color: transparent !important;
}
:root[data-theme='dark'] .card-sub,
:root[data-theme='dark'] .metric-lbl,
:root[data-theme='dark'] .reg-sub,
:root[data-theme='dark'] .invite-hint,
:root[data-theme='dark'] .invite-hint-sm,
:root[data-theme='dark'] .reg-tip,
:root[data-theme='dark'] .reg-banner-tip,
:root[data-theme='dark'] .invite-meta,
:root[data-theme='dark'] .reg-success-meta li,
:root[data-theme='dark'] .reg-success-tip,
:root[data-theme='dark'] .reg-bottom a,
:root[data-theme='dark'] .sub-slogan {
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .metric-num,
:root[data-theme='dark'] .metric-letter {
  background: linear-gradient(
    135deg,
    var(--ds-color-primary-400) 0%,
    var(--ds-color-accent-400) 100%
  ) !important;
  -webkit-background-clip: text !important;
  background-clip: text !important;
  -webkit-text-fill-color: transparent !important;
}
:root[data-theme='dark'] .reg-banner {
  background: linear-gradient(
    135deg,
    rgba(59, 130, 246, 0.18) 0%,
    rgba(99, 102, 241, 0.12) 100%
  ) !important;
  border-color: rgba(59, 130, 246, 0.35) !important;
}
:root[data-theme='dark'] .reg-banner-row span:first-child {
  color: var(--ds-text-tertiary) !important;
}
:root[data-theme='dark'] .reg-banner-row b {
  color: var(--ds-color-primary-300) !important;
}
:root[data-theme='dark'] .reg-banner-row code {
  background: rgba(15, 23, 42, 0.6) !important;
  border-color: var(--ds-border-default) !important;
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .reg-steps li.on .step-text,
:root[data-theme='dark'] .reg-steps li.done .step-text,
:root[data-theme='dark'] .reg-steps li.on .step-dot {
  color: var(--ds-color-primary-300) !important;
}
:root[data-theme='dark'] .reg-steps li.done .step-dot {
  background: var(--ds-color-success-500) !important;
  color: var(--ds-text-inverse) !important;
}
:root[data-theme='dark'] .step-text {
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .step-dot {
  background: var(--ds-bg-surface) !important;
  color: var(--ds-text-tertiary) !important;
}
:root[data-theme='dark'] .reg-success {
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .reg-success-meta {
  background: rgba(15, 23, 42, 0.6) !important;
  border-color: var(--ds-border-default) !important;
}
:root[data-theme='dark'] .reg-success-meta li b {
  color: var(--ds-text-primary) !important;
}
:root[data-theme='dark'] .dev-tip {
  background: rgba(15, 23, 42, 0.6) !important;
  border-color: var(--ds-border-default) !important;
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .dev-tip code {
  background: rgba(15, 23, 42, 0.8) !important;
  border-color: var(--ds-border-default) !important;
  color: var(--ds-border-subtle) !important;
}
:root[data-theme='dark'] .tb-tool {
  background: rgba(15, 23, 42, 0.85) !important;
  border-color: rgba(148, 163, 184, 0.22) !important;
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .tb-tool:hover {
  background: rgba(99, 102, 241, 0.2) !important;
  color: var(--ds-text-primary) !important;
  border-color: rgba(99, 102, 241, 0.5) !important;
}
:root[data-theme='dark'] .err {
  background: rgba(127, 29, 29, 0.3) !important;
  border-color: rgba(239, 68, 68, 0.5) !important;
  color: var(--ds-color-error-300) !important;
}
/* === 暗色下二合一胶囊 === */
:root[data-theme='dark'] .tb-capsule {
  background: rgba(15, 23, 42, 0.85) !important;
  border-color: rgba(99, 102, 241, 0.5) !important;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4) !important;
}
:root[data-theme='dark'] .tb-pill {
  color: var(--ds-text-secondary) !important;
}
:root[data-theme='dark'] .tb-pill:hover {
  background: rgba(99, 102, 241, 0.2) !important;
  color: var(--ds-text-primary) !important;
}
:root[data-theme='dark'] .tb-divider {
  background: rgba(99, 102, 241, 0.4) !important;
}
</style>
