<template>
  <aside class="side">
    <div class="brand"><span class="dot"></span>数擎 · 大数据平台</div>
    <nav class="nav">
      <template v-for="group in groups" :key="group.title">
        <div class="grp">{{ group.title }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          active-class="active"
        >
          <svg class="ic"><use :href="`#i-${item.icon}`" /></svg>
          <span>{{ item.label }}</span>
          <span v-if="item.badge" class="badge">{{ item.badge }}</span>
        </router-link>
      </template>
    </nav>
    <div class="side-foot">
      原型 v0.3 · 客户无感知底座<br />自研 SKE 发行版 · 环境: 信创
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'

const store = useAppStore()

interface NavItem {
  path: string
  label: string
  icon: string
  badge?: number
}
interface NavGroup {
  title: string
  items: NavItem[]
}

// 静态导航配置（35 项，6 分组）
const groups = computed<NavGroup[]>(() => [
  {
    title: '基础设施',
    items: [
      { path: '/infra-machine', label: '机器供应', icon: 'ws' },
      { path: '/infra-k8s', label: 'K8s 集群', icon: 'ops' },
      { path: '/infra-net', label: '容器网络', icon: 'integrate' },
      { path: '/infra-store', label: '容器存储', icon: 'folder' },
      { path: '/infra-sched', label: '弹性调度', icon: 'develop' }
    ]
  },
  {
    title: '数据引擎',
    items: [
      { path: '/eng-storage', label: '统一存储', icon: 'folder' },
      { path: '/eng-spark', label: '批计算（Spark）', icon: 'develop' },
      { path: '/eng-flink', label: '流计算（Flink）', icon: 'develop' },
      { path: '/sql', label: '交互查询（Trino）', icon: 'sql' },
      { path: '/eng-doris', label: 'OLAP（Doris）', icon: 'analyze' },
      { path: '/eng-kafka', label: '消息流接入（Kafka）', icon: 'integrate' },
      { path: '/eng-iotdb', label: '时序引擎（IoTDB）', icon: 'ops' },
      { path: '/eng-mmg', label: '多模型引擎', icon: 'vector' }
    ]
  },
  {
    title: '数据治理',
    items: [
      { path: '/govern-meta', label: '元数据管理', icon: 'standard' },
      { path: '/quality', label: '数据质量', icon: 'quality' },
      { path: '/lineage', label: '数据血缘', icon: 'lineage' },
      { path: '/govern', label: '资产目录', icon: 'govern' },
      { path: '/standard', label: '主数据管理', icon: 'standard' },
      { path: '/sec', label: '数据安全', icon: 'sec', badge: store.todoCount }
    ]
  },
  {
    title: '开发工具',
    items: [
      { path: '/integrate', label: '数据集成（SeaTunnel）', icon: 'integrate' },
      { path: '/dev-sched', label: '调度编排（DolphinScheduler）', icon: 'develop' },
      { path: '/scheduler-ops', label: '任务运维中心', icon: 'ops' },
      { path: '/develop', label: '数据开发 IDE', icon: 'develop' },
      { path: '/analyze', label: 'BI 可视化', icon: 'analyze' },
      { path: '/dev-tag', label: '标签画像', icon: 'vector' },
      { path: '/dev-ml', label: '机器学习', icon: 'llmops' }
    ]
  },
  {
    title: '智能数据',
    items: [
      { path: '/ai-assistant', label: 'AI 数据助手', icon: 'llmops' },
      { path: '/vector', label: '向量数据库', icon: 'vector' },
      { path: '/kb', label: '知识工程', icon: 'kb' },
      { path: '/llmops', label: 'LLMOps', icon: 'llmops' },
      { path: '/orchestrator/dag', label: '编排 DAG 可视化', icon: 'lineage' },
      { path: '/gateway', label: '大模型接口', icon: 'gateway' }
    ]
  },
  {
    title: '产品运营',
    items: [
      { path: '/dashboard', label: '统一控制台', icon: 'dash' },
      { path: '/search', label: '检索门户', icon: 'kb' },
      { path: '/admin', label: '运营后台', icon: 'admin' },
      { path: '/ops-tpl', label: '行业应用模板', icon: 'proj' },
      { path: '/ops-portal', label: '业务线门户', icon: 'ws' },
      { path: '/ops-api', label: '开放 API', icon: 'gateway' },
      { path: '/ops-flow', label: '数据资产流通', icon: 'govern' }
    ]
  }
])
</script>