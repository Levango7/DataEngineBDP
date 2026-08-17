<template>
  <div class="topbar">
    <div class="ws-switch" @click="toggleWsMenu">
      ▾ 工作空间：{{ store.workspace }}
      <div v-if="wsMenuOpen" class="ws-menu" @click.stop>
        <div
          v-for="ws in wsList"
          :key="ws"
          class="ws-item"
          :class="{ on: ws === store.workspace }"
          @click="chooseWs(ws)"
        >
          {{ ws }}
        </div>
      </div>
    </div>
    <div class="spacer"></div>
    <span class="env-tag">● {{ store.envTag }}</span>
    <!-- 用户菜单（登录用户 + 退出登录） -->
    <div class="user-menu" @click.stop>
      <div class="avatar" @click="toggleUserMenu">{{ avatarText }}</div>
      <div v-if="userMenuOpen" class="user-pop">
        <div class="user-info">
          <div class="user-name">{{ auth.user?.username || '未登录' }}</div>
          <div class="user-email">{{ auth.user?.email || '—' }}</div>
        </div>
        <div class="user-actions">
          <router-link to="/account" class="user-action" @click="userMenuOpen = false">
            账户与配额
          </router-link>
          <button class="user-action" @click="handleLogout">退出登录</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const store = useAppStore()
const auth = useAuthStore()
const router = useRouter()
const wsMenuOpen = ref(false)
const userMenuOpen = ref(false)
const wsList = ['华东生产集群', '华北测试集群', '内部数据中枢']

/** 头像首字（优先用户名首字母） */
const avatarText = computed(() => {
  const name = auth.user?.username
  return name ? name.charAt(0).toUpperCase() : '租'
})

function toggleWsMenu() {
  wsMenuOpen.value = !wsMenuOpen.value
}
function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
}
function chooseWs(ws: string) {
  store.setWorkspace(ws)
  wsMenuOpen.value = false
}
function handleLogout() {
  auth.logout()
  userMenuOpen.value = false
  router.replace('/login')
}
</script>

<style scoped>
.ws-switch {
  position: relative;
  user-select: none;
}
/* 工作空间下拉菜单：毛玻璃 + 弹簧入场 */
.ws-menu {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 6px;
  background: var(--glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18), var(--shadow-glow);
  min-width: 180px;
  z-index: 30;
  overflow: hidden;
  animation: springIn 0.32s var(--ease-spring);
  transform-origin: top left;
}
.ws-item {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  cursor: pointer;
  transition: background 0.18s var(--ease-smooth), color 0.18s var(--ease-smooth);
}
.ws-item:hover {
  background: var(--primary-soft);
  color: var(--primary);
}
.ws-item.on {
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 600;
}
/* 用户菜单 */
.user-menu {
  position: relative;
}
/* 头像：渐变背景 + 发光 */
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--gradient-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  user-select: none;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.4);
  transition: transform 0.2s var(--ease-spring), box-shadow 0.2s var(--ease-smooth);
}
.avatar:hover {
  transform: scale(1.06);
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.55), var(--shadow-glow);
}
/* 用户弹出层：毛玻璃 + 弹簧入场 */
.user-pop {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 6px;
  background: var(--glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18), var(--shadow-glow);
  min-width: 200px;
  z-index: 30;
  overflow: hidden;
  animation: springIn 0.32s var(--ease-spring);
  transform-origin: top right;
}
.user-info {
  padding: 12px;
  border-bottom: 1px solid var(--line);
}
.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.user-email {
  font-size: 12px;
  color: var(--muted);
  margin-top: 2px;
}
.user-actions {
  padding: 4px 0;
}
.user-action {
  display: block;
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--ink);
  text-decoration: none;
  background: none;
  border: none;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s var(--ease-smooth), color 0.18s var(--ease-smooth);
}
.user-action:hover {
  background: var(--primary-soft);
  color: var(--primary);
}
</style>