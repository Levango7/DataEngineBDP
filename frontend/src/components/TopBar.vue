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
    <div class="avatar">租</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const wsMenuOpen = ref(false)
const wsList = ['华东生产集群', '华北测试集群', '内部数据中枢']

function toggleWsMenu() {
  wsMenuOpen.value = !wsMenuOpen.value
}
function chooseWs(ws: string) {
  store.setWorkspace(ws)
  wsMenuOpen.value = false
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
</style>