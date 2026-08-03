<template>
  <div>
    <h1>运维中心</h1>
    <div class="sub">客户视角运行态监控；底层自研 SKE 发行版自愈、扩容对客户透明。</div>
    <div class="grid g4">
      <div class="card"><h3>集群健康</h3><div class="kpi s"><span class="pill g">健康</span></div></div>
      <div class="card"><h3>运行作业</h3><div class="kpi">326</div></div>
      <div class="card"><h3>今日失败</h3><div class="kpi s">3</div></div>
      <div class="card"><h3>平均延迟</h3><div class="kpi s">1.8s</div></div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>作业监控</h3>
      <table>
        <tr><th>作业</th><th>类型</th><th>运行时长</th><th>状态</th><th></th></tr>
        <tr><td>实时风控流</td><td>流(Flink)</td><td>持续</td><td><span class="pill a">运行中</span></td><td><button class="btn ghost sm" @click="openLog('实时风控流')">日志</button></td></tr>
        <tr><td>日汇总DAG</td><td>批(Spark)</td><td>12m</td><td><span class="pill g">成功</span></td><td><button class="btn ghost sm" @click="openLog('日汇总DAG')">日志</button></td></tr>
        <tr><td>报表T+1</td><td>批(Spark)</td><td>0m48s</td><td><span class="pill r">失败</span></td><td><button class="btn ghost sm" @click="openLog('报表T+1')">日志</button></td></tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>告警 <span class="pill r">2</span></h3>
      <table>
        <tr><th>告警</th><th>级别</th><th></th></tr>
        <tr><td>内存水位 71% 接近阈值</td><td><span class="pill a">警告</span></td><td><button class="btn sm" @click="store.showToast('已确认并派单')">处理</button></td></tr>
        <tr><td>报表T+1 作业失败</td><td><span class="pill r">严重</span></td><td><button class="btn sm" @click="store.showToast('已触发重跑')">处理</button></td></tr>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>作业日志：{{ currentJob }}</template>
      <div class="runlog" style="height: auto">
        <span class="ok">[INFO] Flink 作业启动 · 并行度 8</span>
        <span class="info">[INFO] 消费 Kafka topic clickstream offset=88214</span>
        <span class="ok">[INFO] checkpoint 12 完成 1.2s</span>
        <span class="warn">[WARN] 反压中等，自动扩容至并行度 12</span>
        <span class="ok">[INFO] 写入 Doris 在线表 user_risk 12,480 行</span>
        <span class="info">[INFO] 作业健康，时延 1.6s</span>
      </div>
      <div class="note">日志由封装层归一化输出，隐藏 Pod/容器细节。</div>
    </Drawer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAppStore } from '@/stores/app'
import Drawer from '@/components/Drawer.vue'

const store = useAppStore()
const drawerVisible = ref(false)
const currentJob = ref('')

function openLog(job: string) {
  currentJob.value = job
  drawerVisible.value = true
}
</script>