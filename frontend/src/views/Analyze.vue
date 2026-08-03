<template>
  <div>
    <h1>BI 分析</h1>
    <div class="sub">
      基于 Superset + ECharts 拖拽式构建看板，查询经统一 SQL 网关跨湖仓集联邦，客户无感知底层引擎。
    </div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建看板</button>
      <div class="spacer"></div>
      <span class="pill b">统一 SQL 网关</span>
    </div>
    <div class="grid g3">
      <div class="card">
        <h3>GMV 趋势</h3>
        <div style="height: 110px; display: flex; align-items: flex-end; gap: 6px">
          <i style="flex: 1; background: var(--primary); height: 40%"></i>
          <i style="flex: 1; background: var(--primary); height: 62%"></i>
          <i style="flex: 1; background: var(--primary); height: 55%"></i>
          <i style="flex: 1; background: var(--primary); height: 78%"></i>
          <i style="flex: 1; background: var(--primary); height: 70%"></i>
          <i style="flex: 1; background: var(--primary); height: 90%"></i>
        </div>
        <div class="meta" style="margin-top: 8px">近 6 月 · 亿元</div>
      </div>
      <div class="card">
        <h3>渠道占比</h3>
        <div style="height: 110px; display: flex; align-items: center; justify-content: center">
          <div
            style="
              width: 90px;
              height: 90px;
              border-radius: 50%;
              background: conic-gradient(var(--primary) 0 45%, var(--amber) 45% 70%, #cdd5d8 70% 100%);
            "
          ></div>
        </div>
        <div class="meta" style="margin-top: 8px">App 45% · 小程序 25% · 其他 30%</div>
      </div>
      <div class="card">
        <h3>实时指标</h3>
        <div class="kpi s">1,284 <span class="meta">笔/分</span></div>
        <div class="meta" style="margin-top: 8px">流计算结果 · 延迟 &lt; 2s</div>
        <button class="btn ghost sm" style="margin-top: 10px" @click="store.showToast('已打开组件库（mock）')">编辑组件</button>
      </div>
    </div>

    <Modal :visible="modalVisible" title="新建看板" @close="modalVisible = false">
      <label>看板名</label><input placeholder="如 经营驾驶舱" />
      <label>数据源</label>
      <select><option>统一 SQL 网关</option></select>
      <label>组件</label>
      <div class="chips">
        <span class="chip on">折线</span>
        <span class="chip on">饼图</span>
        <span class="chip">指标卡</span>
        <span class="chip">柱状</span>
        <span class="chip">漏斗</span>
      </div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('看板已创建')">创建</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'

const store = useAppStore()
const modalVisible = ref(false)
function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}
</script>