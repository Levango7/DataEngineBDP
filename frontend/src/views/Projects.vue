<template>
  <div>
    <h1>数据项目</h1>
    <div class="sub">工作空间：华东生产集群 ｜ 项目是数据加工与消费的组织单元。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建项目</button>
      <select><option>全部状态</option></select>
      <div class="spacer"></div>
      <input style="width: 200px" placeholder="搜索项目…" />
    </div>
    <div class="card">
      <table>
        <tr><th>项目</th><th>域</th><th>数据集</th><th>作业</th><th>负责人</th><th>状态</th></tr>
        <tr class="click" v-for="p in projects" :key="p.name" @click="openDrawer(p)">
          <td>{{ p.name }}</td>
          <td>{{ p.domain }}</td>
          <td>{{ p.datasets }}</td>
          <td>{{ p.jobs }}</td>
          <td>{{ p.owner }}</td>
          <td><span class="pill" :class="p.pillClass">{{ p.pillText }}</span></td>
        </tr>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        数据项目：{{ current?.name }}
        <span class="pill g">运行中</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">概览</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">数据集</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">作业</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">成员</div>
        <div class="t" :class="{ on: tab === 4 }" @click="tab = 4">设置</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>业务域</span><span>{{ current?.domain }}</span></div>
        <div class="kv"><span>数据集</span><span>{{ current?.datasets }}</span></div>
        <div class="kv"><span>作业</span><span>{{ current?.jobs }}</span></div>
        <div class="kv"><span>负责人</span><span>{{ current?.owner }}</span></div>
      </div>
      <div v-if="tab === 1">
        <table>
          <tr><th>数据集</th><th>类型</th><th>字段</th></tr>
          <tr><td>ods.orders</td><td>Iceberg</td><td>23</td></tr>
          <tr><td>dwd.order_wide</td><td>Iceberg</td><td>31</td></tr>
          <tr><td>ads.order_kpi</td><td>Doris</td><td>12</td></tr>
        </table>
      </div>
      <div v-if="tab === 2">
        <table>
          <tr><th>作业</th><th>引擎</th><th>状态</th></tr>
          <tr><td>ods_订单宽表ETL</td><td>Spark</td><td><span class="pill g">成功</span></td></tr>
          <tr><td>画像日更新</td><td>Spark</td><td><span class="pill g">成功</span></td></tr>
          <tr><td>实时风控</td><td>Flink</td><td><span class="pill a">运行中</span></td></tr>
        </table>
      </div>
      <div v-if="tab === 3">
        <table>
          <tr><th>成员</th><th>角色</th></tr>
          <tr><td>张工</td><td>owner</td></tr>
          <tr><td>李工</td><td>dev</td></tr>
          <tr><td>赵工</td><td>reader</td></tr>
        </table>
      </div>
      <div v-if="tab === 4">
        <label>项目名</label><input :value="current?.name" />
        <label>描述</label><textarea rows="3">核心交易域数据加工</textarea>
        <button class="btn sm" style="margin-top: 10px" @click="store.showToast('已保存')">保存</button>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="新建数据项目" @close="modalVisible = false">
      <label>项目名</label><input placeholder="如 供应链域" />
      <label>业务域</label><input placeholder="运营" />
      <label>描述</label><textarea rows="3"></textarea>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('数据项目已创建')">创建</button>
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

interface Proj {
  name: string
  domain: string
  datasets: number
  jobs: number
  owner: string
  pillClass: string
  pillText: string
}

const projects: Proj[] = [
  { name: '交易域', domain: '核心', datasets: 42, jobs: 88, owner: '张工', pillClass: 'g', pillText: '运行中' },
  { name: '营销域', domain: '增长', datasets: 31, jobs: 54, owner: '李工', pillClass: 'g', pillText: '运行中' },
  { name: '风控域', domain: '安全', datasets: 18, jobs: 37, owner: '王工', pillClass: 'a', pillText: '运行中' },
  { name: '财务域', domain: '财经', datasets: 12, jobs: 21, owner: '赵工', pillClass: 'r', pillText: '异常' }
]

const drawerVisible = ref(false)
const modalVisible = ref(false)
const tab = ref(0)
const current = ref<Proj | null>(null)

function openDrawer(p: Proj) {
  current.value = p
  tab.value = 0
  drawerVisible.value = true
}
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>