<template>
  <div>
    <h1>工作空间</h1>
    <div class="sub">
      顶层隔离边界，底层基于自研 SKE 发行版自动映射为 Namespace + 配额 + 网络策略，客户无需关心容器与编排。
    </div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建工作空间</button>
      <input style="width: 220px" placeholder="搜索…" />
      <div class="spacer"></div>
      <span class="pill b">配额独立</span>
      <span class="pill p">网络隔离</span>
    </div>
    <div class="grid g3">
      <div class="card" v-for="ws in workspaces" :key="ws.name">
        <div class="row">
          <b>{{ ws.name }}</b>
          <span class="pill" :class="ws.pillClass">{{ ws.pillText }}</span>
        </div>
        <div class="meta">{{ ws.meta }}</div>
        <div class="row" style="margin-top: 8px">
          <span>CPU {{ ws.cpu }}%</span>
          <span>内存 {{ ws.mem }}%</span>
        </div>
        <div class="bar"><i :style="{ width: ws.cpu + '%' }"></i></div>
        <button class="btn ghost sm" style="margin-top: 10px" @click="openDrawer(ws)">查看详情</button>
      </div>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        工作空间：{{ current?.name }}
        <span class="pill g">运行中</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">概览</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">成员</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">配额</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">项目</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>租户</span><span>外部客户A</span></div>
        <div class="kv"><span>套餐</span><span>企业版</span></div>
        <div class="kv"><span>环境</span><span>信创</span></div>
        <div class="kv"><span>创建时间</span><span>2025-11-02</span></div>
        <div class="note">底层自动映射为 Namespace + ResourceQuota + NetworkPolicy(deny-all)。</div>
      </div>
      <div v-if="tab === 1">
        <table>
          <tr><th>成员</th><th>角色</th></tr>
          <tr><td>张工</td><td>空间管理员</td></tr>
          <tr><td>李工</td><td>开发</td></tr>
          <tr><td>王工</td><td>开发</td></tr>
        </table>
        <button class="btn ghost sm" style="margin-top: 8px" @click="store.showToast('已邀请成员（mock）')">+ 邀请</button>
      </div>
      <div v-if="tab === 2">
        <div class="row"><span>CPU</span><span>{{ current?.cpu }}%</span></div>
        <div class="bar"><i :style="{ width: (current?.cpu || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px"><span>内存</span><span>{{ current?.mem }}%</span></div>
        <div class="bar"><i class="a" :style="{ width: (current?.mem || 0) + '%' }"></i></div>
        <div class="row" style="margin-top: 8px"><span>存储</span><span>43%</span></div>
        <div class="bar"><i style="width: 43%"></i></div>
      </div>
      <div v-if="tab === 3">
        <table>
          <tr><th>项目</th><th>状态</th></tr>
          <tr><td>交易域</td><td><span class="pill g">运行中</span></td></tr>
          <tr><td>营销域</td><td><span class="pill g">运行中</span></td></tr>
          <tr><td>风控域</td><td><span class="pill a">运行中</span></td></tr>
        </table>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="新建工作空间" @close="modalVisible = false">
      <label>名称</label><input placeholder="如 华南生产集群" />
      <label>租户</label>
      <select><option>外部客户A</option><option>内部业务线</option></select>
      <label>套餐</label>
      <select><option>标准版</option><option>企业版</option><option>旗舰版</option></select>
      <label>环境</label>
      <select><option>信创</option><option>本地数据中心</option><option>公有云 VM</option><option>私有云</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('工作空间已创建')">创建</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'

const store = useAppStore()

interface Ws {
  name: string
  pillClass: string
  pillText: string
  meta: string
  cpu: number
  mem: number
}

const workspaces: Ws[] = [
  { name: '华东生产集群', pillClass: 'g', pillText: '运行中', meta: '企业版 · 外部客户A', cpu: 58, mem: 71 },
  { name: '华北测试集群', pillClass: 'a', pillText: '受限', meta: '标准版 · 外部客户A', cpu: 22, mem: 30 },
  { name: '内部数据中枢', pillClass: 'g', pillText: '运行中', meta: '内部无限 · 内部业务线', cpu: 64, mem: 55 }
]

const drawerVisible = ref(false)
const modalVisible = ref(false)
const tab = ref(0)
const current = ref<Ws | null>(null)

function openDrawer(ws: Ws) {
  current.value = ws
  tab.value = 0
  drawerVisible.value = true
}
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>