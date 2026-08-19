<template>
  <div role="main" aria-label="工作台首页">
    <h1>工作台</h1>
    <div class="sub" aria-label="当前租户与套餐信息">
      租户：华东生产集群 ｜ 套餐：企业版 ｜ 本月资源消耗 62% ｜
      <span class="pill b" aria-label="待办数量">{{ store.todoCount }} 待办</span>
    </div>

    <!-- 集群概览 KPI 卡片：三态 loading / error / data -->
    <div class="grid g4" role="region" aria-label="集群概览 KPI 卡片">
      <template v-if="overviewLoading">
        <div class="card" v-for="i in 4" :key="i" role="status" aria-live="polite">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="overviewError">
        <div class="card" style="grid-column: span 4" role="alert" aria-live="assertive">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ overviewError.message }}，<a href="javascript:void(0)" @click="reloadOverview" aria-label="重新加载集群概览">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="overview">
        <div class="card" role="region" aria-label="数据项目数">
          <h3>数据项目</h3>
          <div class="kpi">{{ overview.projectCount }}</div>
          <div class="meta">运行中 {{ Math.round(overview.projectCount * 0.78) }} · 暂停 {{ overview.projectCount - Math.round(overview.projectCount * 0.78) }}</div>
        </div>
        <div class="card" role="region" aria-label="调度作业数">
          <h3>调度作业</h3>
          <div class="kpi">{{ overview.jobCount }}</div>
          <div class="meta">今日成功 {{ overview.jobSuccessToday }} · 失败 {{ overview.jobFailToday }}</div>
        </div>
        <div class="card" role="region" aria-label="存储用量">
          <h3>存储用量</h3>
          <div class="kpi s">{{ overview.storageUsed }} TB</div>
          <div class="meta">湖仓集一体</div>
        </div>
        <div class="card" role="region" aria-label="数据资产数">
          <h3>数据资产</h3>
          <div class="kpi s">{{ overview.assetCount.toLocaleString() }}</div>
          <div class="meta">表/主题/标签</div>
        </div>
      </template>
    </div>

    <div class="grid g2" style="margin-top: 14px" role="region" aria-label="资源趋势与待办审批">
      <!-- 资源趋势：三态 -->
      <div class="card" role="region" aria-label="资源趋势">
        <h3>资源趋势（近 7 日）</h3>
        <template v-if="overviewLoading">
          <div class="meta" style="color: var(--muted)" role="status" aria-live="polite">加载中…</div>
        </template>
        <template v-else-if="overviewError">
          <div class="meta" style="color: var(--muted)" role="alert">资源趋势加载失败</div>
        </template>
        <template v-else-if="overview">
          <div class="mini" role="img" aria-label="CPU 趋势图">
            <i v-for="(h, idx) in overview.trendCpu" :key="`cpu-${idx}`" :style="{ height: h + '%' }"></i>
          </div>
          <div class="row" style="margin-top: 10px"><span>CPU</span><span>{{ cpuPercent }}%</span></div>
          <div class="bar" role="progressbar" :aria-valuenow="cpuPercent" aria-valuemin="0" aria-valuemax="100" aria-label="CPU 使用率"><i :style="{ width: cpuPercent + '%' }"></i></div>
          <div class="row" style="margin-top: 8px"><span>内存</span><span>{{ memPercent }}%</span></div>
          <div class="bar" role="progressbar" :aria-valuenow="memPercent" aria-valuemin="0" aria-valuemax="100" aria-label="内存使用率"><i class="a" :style="{ width: memPercent + '%' }"></i></div>
          <div class="note">超 80% 自动扩容，客户无感知。</div>
        </template>
      </div>
      <div class="card" role="region" aria-label="待办审批">
        <h3>待办审批 <span class="pill r" aria-label="待办总数">{{ store.todoCount }}</span></h3>
        <table role="table" aria-label="待办审批列表">
          <thead>
            <tr role="row"><th role="columnheader">申请</th><th role="columnheader">申请人</th><th role="columnheader">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in store.todos" :key="t.id" role="row">
              <td role="cell">{{ t.text }}</td>
              <td role="cell">{{ t.applicant }}</td>
              <td role="cell">
                <button class="btn sm" aria-label="批准申请" @click="store.approve(t.id)">批准</button>
                <button class="btn ghost sm" aria-label="驳回申请" @click="store.reject(t.id)">驳回</button>
              </td>
            </tr>
            <tr v-if="store.todos.length === 0" role="row">
              <td colspan="3" style="text-align: center; color: var(--muted)" role="cell">暂无待办</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="card" style="margin-top: 14px" role="region" aria-label="快捷入口">
      <h3>快捷入口</h3>
      <div class="chips" role="navigation" aria-label="快捷功能入口">
        <span class="chip on" role="link" tabindex="0" aria-label="新建作业" @click="router.push('/develop')">新建作业</span>
        <span class="chip" role="link" tabindex="0" aria-label="配置同步" @click="router.push('/integrate')">配置同步</span>
        <span class="chip" role="link" tabindex="0" aria-label="登记资产" @click="router.push('/govern')">登记资产</span>
        <span class="chip" role="link" tabindex="0" aria-label="训练模型" @click="router.push('/llmops')">训练模型</span>
        <span class="chip" role="link" tabindex="0" aria-label="建看板" @click="router.push('/analyze')">建看板</span>
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
