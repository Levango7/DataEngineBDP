<template>
  <div>
    <h1>血缘分析</h1>
    <div class="sub">端到端自动采集字段级血缘；影响分析一键追溯。当前高亮：dwd.order_wide</div>
    <div class="legend">
      <span style="color: #8a9ba0">■ 上游</span>
      <span style="color: var(--primary)">■ 当前</span>
      <span style="color: var(--green)">■ 下游</span>
      <span style="color: #94a3b8">□ 弱化</span>
    </div>
    <div class="card">
      <div class="lineage">
        <div class="lvl">
          <div class="ln">ods.orders</div>
          <div class="ln">ods.pay</div>
          <div class="ln">dim.user</div>
        </div>
        <div class="lvl">
          <div class="ln hot" @click="store.showToast('当前节点 dwd.order_wide')">dwd.order_wide</div>
        </div>
        <div class="lvl">
          <div class="ln">ads.订单看板</div>
          <div class="ln">dws.用户复购</div>
        </div>
        <div class="lvl">
          <div class="ln">Doris 在线</div>
          <div class="ln">BI 报表</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app'
const store = useAppStore()
</script>