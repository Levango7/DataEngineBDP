<template>
  <div>
    <h1>{{ title }}</h1>
    <div class="sub">该模块已纳入产品规划，敬请期待。详见产品设计 §3.3 模块状态表。</div>
    <div class="card">
      <h3>规划说明</h3>
      <div class="kv"><span>所属层级</span><span>见 §3.3 模块状态表</span></div>
      <div class="kv">
        <span>交付状态</span>
        <span><span class="pill a">规划中</span></span>
      </div>
      <div class="kv"><span>技术选型</span><span>见 §5.5 选型决策</span></div>
      <div class="note">本页面为控制台原型 v0.3 占位视图，后续迭代将补充完整交互。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '模块规划中')
</script>