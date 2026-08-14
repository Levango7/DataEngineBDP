<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand"><span class="dot"></span>数擎 · 大数据平台</div>
      <h2>登录</h2>
      <el-form :model="form" @submit.prevent="handleLogin" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="handleLogin">
          登 录
        </el-button>
        <div v-if="error" class="error">{{ error }}</div>
      </el-form>
      <div class="tip">默认开发环境账号：admin / admin123（生产接入 Keycloak 后由此处跳转 OIDC）</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = ref({ username: 'admin', password: 'admin123' })
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  if (!form.value.username || !form.value.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.value.username, form.value.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch (e) {
    error.value = `登录失败: ${(e as Error)?.message ?? e}`
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2f6f6a 0%, #1d4a46 100%);
}
.login-card {
  width: 380px;
  padding: 40px 36px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
}
.brand {
  font-size: 20px;
  font-weight: 600;
  color: #2f6f6a;
  margin-bottom: 8px;
}
.brand .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #2f6f6a;
  margin-right: 8px;
}
h2 {
  margin: 8px 0 20px;
  color: #303133;
}
.error {
  color: #f56c6c;
  margin-top: 12px;
  font-size: 13px;
}
.tip {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>