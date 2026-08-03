<template>
  <div>
    <h1>数据集成</h1>
    <div class="sub">
      基于 SeaTunnel 可视化配置异构数据源同步至湖仓集一体存储，支持批流一体，无需搬运代码。
    </div>
    <div class="section-title">数据源连接器</div>
    <div class="conn-grid">
      <div class="conn" v-for="c in connectors" :key="c.name" @click="c.onClick()">
        <div class="logo">{{ c.logo }}</div>
        {{ c.name }}
        <span class="pill" :class="c.pillClass" style="display: block; margin-top: 6px">{{ c.pillText }}</span>
      </div>
    </div>
    <div class="toolbar" style="margin-top: 16px">
      <button class="btn sm" @click="syncModal = true">+ 新建同步任务</button>
      <div class="spacer"></div>
      <span class="pill b">批流一体</span>
    </div>
    <div class="card">
      <table>
        <tr><th>任务</th><th>源→目标</th><th>模式</th><th>状态</th><th>最近运行</th></tr>
        <tr><td>订单全量</td><td>MySQL → Iceberg</td><td>批</td><td><span class="pill g">成功</span></td><td>04:00 · 12m</td></tr>
        <tr><td>点击流CDC</td><td>Kafka → Doris</td><td>流</td><td><span class="pill a">运行中</span></td><td>持续</td></tr>
        <tr><td>台账增量</td><td>Oracle → Iceberg</td><td>批</td><td><span class="pill g">成功</span></td><td>05:10 · 8m</td></tr>
      </table>
    </div>

    <Modal :visible="syncModal" title="新建同步任务" @close="syncModal = false">
      <label>任务名</label><input placeholder="如 订单全量" />
      <label>源</label>
      <select><option>MySQL</option><option>Oracle</option><option>Kafka</option></select>
      <label>目标</label>
      <select><option>Iceberg(湖)</option><option>Doris(仓/集)</option></select>
      <label>模式</label>
      <select><option>批</option><option>流(CDC)</option></select>
      <label>调度频率</label><input value="每日 04:00" />
      <template #footer>
        <button class="btn ghost" @click="syncModal = false">取消</button>
        <button class="btn" @click="ok('同步任务已创建')">创建</button>
      </template>
    </Modal>

    <Modal :visible="srcModal" title="新增数据源" @close="srcModal = false">
      <label>类型</label>
      <select><option>MySQL</option><option>Oracle</option><option>PostgreSQL</option><option>API</option></select>
      <label>连接串</label><input placeholder="jdbc:mysql://…" />
      <label>账号</label><input />
      <label>密码</label><input type="password" />
      <template #footer>
        <button class="btn ghost" @click="srcModal = false">取消</button>
        <button class="btn" @click="ok('数据源已添加')">测试并保存</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'

const store = useAppStore()
const syncModal = ref(false)
const srcModal = ref(false)

const connectors = [
  { name: 'MySQL', logo: 'My', pillClass: 'g', pillText: '已连通', onClick: () => store.showToast('已连通 MySQL') },
  { name: 'Oracle', logo: 'Or', pillClass: 'g', pillText: '已连通', onClick: () => store.showToast('已连通 Oracle') },
  { name: 'Kafka', logo: 'Ka', pillClass: 'g', pillText: '已连通', onClick: () => store.showToast('已连通 Kafka') },
  { name: '新增源', logo: '+', pillClass: 'a', pillText: '待配置', onClick: () => (srcModal.value = true) },
  { name: 'HDFS', logo: 'Hd', pillClass: 'g', pillText: '已连通', onClick: () => store.showToast('HDFS 已注册') },
  { name: 'REST API', logo: 'API', pillClass: 'a', pillText: '待授权', onClick: () => store.showToast('API 待授权') }
]

function ok(msg: string) {
  syncModal.value = false
  srcModal.value = false
  store.showToast(msg)
}
</script>