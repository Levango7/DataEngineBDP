<template>
  <div class="login-page" role="main" :aria-label="t('login.title')">
    <!-- 网格动效背景层 -->
    <div class="grid-bg-layer" aria-hidden="true"></div>
    <!-- 星点闪烁背景层 -->
    <div class="stars-layer" aria-hidden="true"></div>
    <!-- 顶部光晕装饰 -->
    <div class="glow-orb orb-1" aria-hidden="true"></div>
    <div class="glow-orb orb-2" aria-hidden="true"></div>

    <!-- 登录卡片 (毛玻璃 + 弹簧入场) -->
    <div class="login-card glass animate-springIn" role="region" aria-label="登录卡片">
      <div class="brand gradient-text" :aria-label="t('nav.brand')">
        <span class="dot" aria-hidden="true"></span>
        {{ t('nav.brand') }}
      </div>
      <h2>{{ t('login.title') }}</h2>
      <el-form
        :model="form"
        label-position="top"
        :aria-label="t('login.title')"
        @submit.prevent="handleLogin"
      >
        <el-form-item :label="t('login.username')">
          <el-input
            v-model="form.username"
            :placeholder="t('login.usernamePlaceholder')"
            autocomplete="username"
            :aria-label="t('login.username')"
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
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          :loading="loading"
          class="login-btn"
          style="width: 100%"
          :aria-label="t('login.submit')"
          @click="handleLogin"
        >
          {{ loading ? t('login.loggingIn') : t('login.submit') }}
        </el-button>
        <div v-if="error" class="error" role="alert" aria-live="assertive">{{ error }}</div>
      </el-form>
      <div class="tip" aria-label="本地开发账号提示">
        本地开发账号：admin / admin（管理员）或 user / user（普通用户）
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = ref({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

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
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch (e) {
    error.value = `${t('login.loginFailed')}: ${(e as Error)?.message ?? e}`
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* === 深色科技渐变背景 === */
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #312e81 100%);
}

/* === 网格动效背景层 (gridPulse 动画) === */
.grid-bg-layer {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(99, 102, 241, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99, 102, 241, 0.08) 1px, transparent 1px);
  background-size: 36px 36px;
  animation: gridPulse 5s ease-in-out infinite;
  pointer-events: none;
  z-index: 1;
}

/* === 星点闪烁背景层 (纯 CSS 多 box-shadow 星点) === */
.stars-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 1;
}
.stars-layer::before,
.stars-layer::after {
  content: '';
  position: absolute;
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: transparent;
  /* 多个 box-shadow 模拟星点 (覆盖视口各处) */
  box-shadow:
    120px 80px 0 0 rgba(255, 255, 255, 0.7),
    260px 160px 0 0 rgba(255, 255, 255, 0.5),
    420px 60px 0 0 rgba(255, 255, 255, 0.8),
    580px 220px 0 0 rgba(255, 255, 255, 0.4),
    720px 100px 0 0 rgba(255, 255, 255, 0.6),
    880px 280px 0 0 rgba(255, 255, 255, 0.5),
    1080px 140px 0 0 rgba(255, 255, 255, 0.7),
    1280px 60px 0 0 rgba(255, 255, 255, 0.5),
    200px 360px 0 0 rgba(255, 255, 255, 0.6),
    380px 480px 0 0 rgba(255, 255, 255, 0.4),
    560px 420px 0 0 rgba(255, 255, 255, 0.7),
    760px 540px 0 0 rgba(255, 255, 255, 0.5),
    960px 380px 0 0 rgba(255, 255, 255, 0.6),
    1180px 480px 0 0 rgba(255, 255, 255, 0.4),
    320px 620px 0 0 rgba(255, 255, 255, 0.5),
    520px 700px 0 0 rgba(255, 255, 255, 0.7),
    820px 660px 0 0 rgba(255, 255, 255, 0.4),
    1100px 720px 0 0 rgba(255, 255, 255, 0.6);
  animation: starsTwinkle 3.5s ease-in-out infinite alternate;
}
.stars-layer::after {
  /* 第二层星点 (偏移 + 不同闪烁节奏) */
  animation-delay: 1.7s;
  animation-duration: 4.2s;
  box-shadow:
    180px 120px 0 0 rgba(139, 92, 246, 0.6),
    340px 240px 0 0 rgba(99, 102, 241, 0.5),
    500px 100px 0 0 rgba(139, 92, 246, 0.7),
    660px 320px 0 0 rgba(99, 102, 241, 0.4),
    820px 180px 0 0 rgba(139, 92, 246, 0.5),
    1000px 360px 0 0 rgba(99, 102, 241, 0.6),
    1200px 200px 0 0 rgba(139, 92, 246, 0.4),
    260px 460px 0 0 rgba(99, 102, 241, 0.5),
    460px 540px 0 0 rgba(139, 92, 246, 0.7),
    680px 600px 0 0 rgba(99, 102, 241, 0.4),
    900px 500px 0 0 rgba(139, 92, 246, 0.6),
    1140px 620px 0 0 rgba(99, 102, 241, 0.5);
}

@keyframes starsTwinkle {
  0% {
    opacity: 0.3;
  }
  100% {
    opacity: 1;
  }
}

/* === 顶部光晕装饰球 (科技感氛围光) === */
.glow-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  z-index: 1;
  animation: orbFloat 8s ease-in-out infinite alternate;
}
.orb-1 {
  width: 320px;
  height: 320px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.45) 0%, transparent 70%);
  top: -120px;
  left: -100px;
}
.orb-2 {
  width: 360px;
  height: 360px;
  background: radial-gradient(circle, rgba(139, 92, 246, 0.4) 0%, transparent 70%);
  bottom: -140px;
  right: -120px;
  animation-delay: 2s;
}
@keyframes orbFloat {
  0% {
    transform: translate(0, 0) scale(1);
  }
  100% {
    transform: translate(30px, -20px) scale(1.08);
  }
}

/* === 登录卡片 (毛玻璃 + 发光边框) === */
.login-card {
  position: relative;
  z-index: 10;
  width: 400px;
  max-width: 92vw;
  padding: 42px 38px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.5);
  box-shadow:
    0 20px 60px rgba(15, 23, 42, 0.5),
    0 0 30px rgba(99, 102, 241, 0.25),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

/* === 标题渐变文字 === */
.brand {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 10px;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--gradient-primary);
  box-shadow: 0 0 10px rgba(99, 102, 241, 0.7);
  animation: glowPulse 2.4s ease-in-out infinite;
}

h2 {
  margin: 8px 0 22px;
  color: var(--ink);
  font-weight: 600;
}

/* === 登录按钮渐变 + hover 发光 === */
.login-btn {
  background: var(--gradient-primary) !important;
  border: none !important;
  height: 42px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.35);
  transition:
    transform 0.2s var(--ease-smooth),
    box-shadow 0.2s var(--ease-smooth),
    filter 0.2s var(--ease-smooth);
}
.login-btn:hover,
.login-btn:focus {
  transform: translateY(-2px);
  box-shadow:
    0 8px 20px rgba(99, 102, 241, 0.5),
    var(--shadow-glow) !important;
  filter: brightness(1.08);
}
.login-btn:active {
  transform: translateY(0);
}

.error {
  color: var(--red);
  margin-top: 12px;
  font-size: 13px;
  padding: 8px 12px;
  background: var(--c-red-50);
  border-radius: 6px;
  border-left: 3px solid var(--red);
}

.tip {
  margin-top: 18px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

/* === Element Plus 表单深色背景下的输入框适配 === */
:deep(.el-form-item__label) {
  color: var(--ink);
  font-weight: 500;
}
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 1px var(--line) inset;
  transition: box-shadow 0.2s var(--ease-smooth);
}
:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary) inset;
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px var(--primary) inset,
    0 0 0 3px rgba(99, 102, 241, 0.15) !important;
}
:deep(.el-input__inner) {
  color: var(--ink);
}
:deep(.el-input__inner::placeholder) {
  color: var(--muted);
}
</style>
