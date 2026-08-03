<template>
  <div>
    <h1>工作台</h1>
    <div class="sub">
      租户：华东生产集群 ｜ 套餐：企业版 ｜ 本月资源消耗 62% ｜
      <span class="pill b">待办 {{ store.todoCount }}</span>
    </div>
    <div class="grid g4">
      <div class="card">
        <h3>数据项目</h3>
        <div class="kpi">18</div>
        <div class="meta">运行中 14 · 暂停 4</div>
      </div>
      <div class="card">
        <h3>调度作业</h3>
        <div class="kpi">326</div>
        <div class="meta">今日成功 312 · 失败 3</div>
      </div>
      <div class="card">
        <h3>存储用量</h3>
        <div class="kpi s">486 TB</div>
        <div class="meta">湖仓集一体</div>
      </div>
      <div class="card">
        <h3>数据资产</h3>
        <div class="kpi s">2,140</div>
        <div class="meta">表/主题/标签</div>
      </div>
    </div>
    <div class="grid g2" style="margin-top: 14px">
      <div class="card">
        <h3>资源趋势（近 7 日）</h3>
        <div class="mini">
          <i style="height: 40%"></i><i style="height: 52%"></i><i style="height: 48%"></i>
          <i style="height: 63%"></i><i style="height: 58%"></i><i style="height: 71%"></i>
          <i style="height: 66%"></i>
        </div>
        <div class="row" style="margin-top: 10px"><span>CPU</span><span>58%</span></div>
        <div class="bar"><i style="width: 58%"></i></div>
        <div class="row" style="margin-top: 8px"><span>内存</span><span>71%</span></div>
        <div class="bar"><i class="a" style="width: 71%"></i></div>
        <div class="note">超 80% 自动扩容，客户无感知。</div>
      </div>
      <div class="card">
        <h3>待办审批 <span class="pill r">{{ store.todoCount }}</span></h3>
        <table>
          <tr><th>申请</th><th>申请人</th><th></th></tr>
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
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const store = useAppStore()
</script>