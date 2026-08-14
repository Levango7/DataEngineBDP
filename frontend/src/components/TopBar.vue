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
.ws-menu {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 6px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  min-width: 180px;
  z-index: 30;
  overflow: hidden;
}
.ws-item {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  cursor: pointer;
}
.ws-item:hover {
  background: var(--primary-soft);
}
.ws-item.on {
  color: var(--primary);
  background: var(--primary-soft);
}
/* 用户菜单 */
.user-menu {
  position: relative;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--primary, #2f6f6a);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  user-select: none;
}
.user-pop {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 6px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  min-width: 200px;
  z-index: 30;
  overflow: hidden;
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
  color: #909399;
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
}
.user-action:hover {
  background: var(--primary-soft);
}
</style>