<template>
  <div>
    <h1>工作台</h1>
    <div class="sub">
      租户：华东生产集群 ｜ 套餐：企业版 ｜ 本月资源消耗 62% ｜
      <span class="pill b">待办 {{ store.todoCount }}</span>
    </div>

    <!-- 集群概览 KPI 卡片：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="overviewLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="overviewError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ overviewError.message }}，<a href="javascript:void(0)" @click="reloadOverview">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="overview">
        <div class="card">
          <h3>数据项目</h3>
          <div class="kpi">{{ overview.projectCount }}</div>
          <div class="meta">运行中 {{ Math.round(overview.projectCount * 0.78) }} · 暂停 {{ overview.projectCount - Math.round(overview.projectCount * 0.78) }}</div>
        </div>
        <div class="card">
          <h3>调度作业</h3>
          <div class="kpi">{{ overview.jobCount }}</div>
          <div class="meta">今日成功 {{ overview.jobSuccessToday }} · 失败 {{ overview.jobFailToday }}</div>
        </div>
        <div class="card">
          <h3>存储用量</h3>
          <div class="kpi s">{{ overview.storageUsed }} TB</div>
          <div class="meta">湖仓集一体</div>
        </div>
        <div class="card">
          <h3>数据资产</h3>
          <div class="kpi s">{{ overview.assetCount.toLocaleString() }}</div>
          <div class="meta">表/主题/标签</div>
        </div>
      </template>
    </div>

    <div class="grid g2" style="margin-top: 14px">
      <!-- 资源趋势：三态 -->
      <div class="card">
        <h3>资源趋势（近 7 日）</h3>
        <template v-if="overviewLoading">
          <div class="meta" style="color: var(--muted)">加载中…</div>
        </template>
        <template v-else-if="overviewError">
          <div class="meta" style="color: var(--muted)">资源趋势加载失败</div>
        </template>
        <template v-else-if="overview">
          <div class="mini">
            <i v-for="(h, idx) in overview.trendCpu" :key="`cpu-${idx}`" :style="{ height: h + '%' }"></i>
          </div>
          <div class="row" style="margin-top: 10px"><span>CPU</span><span>{{ cpuPercent }}%</span></div>
          <div class="bar"><i :style="{ width: cpuPercent + '%' }"></i></div>
          <div class="row" style="margin-top: 8px"><span>内存</span><span>{{ memPercent }}%</span></div>
          <div class="bar"><i class="a" :style="{ width: memPercent + '%' }"></i></div>
          <div class="note">超 80% 自动扩容，客户无感知。</div>
        </template>
      </div>
      <div class="card">
        <h3>待办审批 <span class="pill r">{{ store.todoCount }}</span></h3>
        <table>
          <thead>
            <tr><th>申请</th><th>申请人</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="t in store.todos" :key="t.id">
              <td>{{ t.text }}</td>
              <td>{{ t.applicant }}</td>
              <td>
                <button class="btn sm" @click="store.approve(t.id)">批准</button>
                <button class="btn ghost sm" @click="store.reject(t.id)">驳回</button>
              </td>
            </tr>
            <tr v-if="store.todos.length === 0">
              <td colspan="3" style="text-align: center; color: var(--muted)">暂无待办</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>快捷入口</h3>
      <div class="chips">
        <span class="chip on" @click="router.push('/develop')">新建作业</span>
        <span class="chip" @click="router.push('/integrate')">配置同步</span>
        <span class="chip" @click="router.push('/govern')">登记资产</span>
        <span class="chip" @click="router.push('/llmops')">训练模型</span>
        <span class="chip" @click="router.push('/analyze')">建看板</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import * as clusterApi from '@/api/cluster'
import type { ClusterOverview } from '@/api/types'

const router = useRouter()
const store = useAppStore()

// 集群概览：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: overview,
  loading: overviewLoading,
  error: overviewError,
  execute: loadOverview
} = useApi<ClusterOverview>(() => clusterApi.getClusterOverview())

// CPU 使用率（百分比，保留整数）
const cpuPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.cpuCapacity || 1
  return Math.round((overview.value.cpuUsed / cap) * 100)
})

// 内存使用率（百分比，保留整数）
const memPercent = computed(() => {
  if (!overview.value) return 0
  const cap = overview.value.memCapacity || 1
  return Math.round((overview.value.memUsed / cap) * 100)
})

// 重新拉取集群概览
function reloadOverview() {
  void loadOverview()
}

// 挂载时拉取集群概览
onMounted(() => {
  void loadOverview()
})
</script>
