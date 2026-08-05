<template>
  <div>
    <h1>资产目录</h1>
    <div class="sub">统一检索、申请、订阅数据资产；血缘、质量、脱敏策略随资产沉淀。</div>
    <div class="toolbar">
      <input style="width: 280px" placeholder="搜索表 / 主题 / 标签…" />
      <select><option>全部分层</option></select>
      <div class="spacer"></div>
      <button class="btn sm" @click="modalVisible = true">+ 登记资产</button>
    </div>
    <div class="card">
      <table>
        <thead>
          <tr><th>资产名</th><th>分层</th><th>负责人</th><th>质量分</th><th>敏感</th><th></th></tr>
        </thead>
        <tbody>
          <tr class="click" v-for="a in assets" :key="a.name" @click="openDrawer(a)">
            <td>{{ a.name }}</td>
            <td>{{ a.layer }}</td>
            <td>{{ a.owner }}</td>
            <td>{{ a.score }}</td>
            <td><span class="pill" :class="a.pillClass">{{ a.pillText }}</span></td>
            <td><span class="pill b">详情</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        资产：{{ current?.name }}
        <span class="pill r">{{ current?.pillText }}</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">元数据</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">Schema</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">质量</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">权限</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>分层</span><span>{{ current?.layer }}</span></div>
        <div class="kv"><span>负责人</span><span>{{ current?.owner }}</span></div>
        <div class="kv"><span>质量分</span><span>{{ current?.score }}</span></div>
        <div class="kv"><span>更新频率</span><span>日</span></div>
      </div>
      <div v-if="tab === 1">
        <table>
          <thead>
            <tr><th>字段</th><th>类型</th><th>敏感</th></tr>
          </thead>
          <tbody>
            <tr><td>order_id</td><td>bigint</td><td>—</td></tr>
            <tr><td>user_id</td><td>bigint</td><td>—</td></tr>
            <tr><td>id_card</td><td>string</td><td><span class="pill r">PII</span></td></tr>
            <tr><td>amount</td><td>decimal</td><td>—</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="tab === 2">
        <div class="kv"><span>订单ID非空</span><span><span class="pill g">通过</span></span></div>
        <div class="kv"><span>金额非负</span><span><span class="pill g">通过</span></span></div>
        <div class="kv"><span>行数波动</span><span><span class="pill g">通过</span></span></div>
      </div>
      <div v-if="tab === 3">
        <div class="kv"><span>当前权限</span><span>张工(读写) · 李工(读)</span></div>
        <button class="btn sm" style="margin-top: 10px" @click="store.showToast('权限申请已提交，等待审批')">申请读权限</button>
        <div class="note">申请经审批流，不直连底层存储。</div>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="登记数据资产" @close="modalVisible = false">
      <label>资产名</label><input placeholder="如 dws.xxx" />
      <label>分层</label>
      <select><option>ODS</option><option>DWD</option><option>DWS</option><option>ADS</option></select>
      <label>负责人</label><input />
      <label>敏感级别</label>
      <select><option>无</option><option>受限</option><option>PII</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('资产已登记')">登记</button>
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

interface Asset {
  name: string
  layer: string
  owner: string
  score: number
  pillClass: string
  pillText: string
}

const assets: Asset[] = [
  { name: 'dwd.order_wide', layer: 'DWD', owner: '张工', score: 96, pillClass: 'r', pillText: 'PII' },
  { name: 'ads.user_profile', layer: 'ADS', owner: '李工', score: 91, pillClass: 'a', pillText: '受限' },
  { name: 'dws.pay_summary', layer: 'DWS', owner: '赵工', score: 88, pillClass: 'g', pillText: '无' }
]

const drawerVisible = ref(false)
const modalVisible = ref(false)
const tab = ref(0)
const current = ref<Asset | null>(null)

function openDrawer(a: Asset) {
  current.value = a
  tab.value = 0
  drawerVisible.value = true
}
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>