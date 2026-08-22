<template>
  <aside class="side" role="complementary" aria-label="平台侧边栏">
    <div class="brand" aria-label="数擎大数据平台品牌标识"><span class="dot" aria-hidden="true"></span>数擎 · 大数据平台</div>
    <nav class="nav" role="navigation" aria-label="主导航菜单">
      <template v-for="(group, gi) in groups" :key="group.title">
        <div
          class="grp"
          role="button"
          :aria-expanded="isOpen(gi)"
          :aria-controls="`nav-group-${gi}`"
          :aria-label="`${group.title} 分组，共 ${group.items.length} 项`"
          tabindex="0"
          @click="toggleGroup(gi)"
          @keyup.enter="toggleGroup(gi)"
        >
          <span class="grp-arrow" :class="{ open: isOpen(gi) }" aria-hidden="true">▸</span>
          <span class="grp-label">{{ group.title }}</span>
          <span class="grp-count" aria-hidden="true">{{ group.items.length }}</span>
        </div>
        <div
          class="grp-items"
          :class="{ collapsed: !isOpen(gi) }"
          :id="`nav-group-${gi}`"
          role="group"
          :aria-label="`${group.title} 导航项`"
        >
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            active-class="active"
            :aria-label="item.label"
          >
            <svg class="ic" aria-hidden="true"><use :href="`#i-${item.icon}`" /></svg>
            <span class="nav-label">{{ item.label }}</span>
            <span v-if="item.badge" class="badge" aria-label="待办数量">{{ item.badge }}</span>
          </router-link>
        </div>
      </template>
    </nav>
    <div class="side-foot" aria-label="平台版本信息">
      v2.1 GA · 客户无感知底座<br />自研 SKE 发行版 · 环境: 信创
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
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

// 静态导航配置（35 项，7 分组）—— 所有路由均指向真实功能组件
const groups = computed<NavGroup[]>(() => [
  {
    title: '基础设施',
    items: [
      { path: '/infra-machine', label: '机器供应', icon: 'ws' },
      { path: '/infra-k8s', label: 'K8s 集群', icon: 'ops' },
      { path: '/cluster', label: '集群总览', icon: 'ops' },
      { path: '/datasources', label: '数据源管理', icon: 'integrate' },
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
      { path: '/data-lineage', label: '血缘可视化', icon: 'lineage' },
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
      { path: '/jobs', label: '作业管理', icon: 'develop' },
      { path: '/develop', label: '数据开发 IDE', icon: 'develop' },
      { path: '/sql-workbench', label: 'SQL 工作台', icon: 'sql' },
      { path: '/analyze', label: 'BI 可视化', icon: 'analyze' },
      { path: '/dev-tag', label: '标签画像', icon: 'vector' },
      { path: '/dev-ml', label: '机器学习', icon: 'llmops' }
    ]
  },
  {
    title: '租户与配额',
    items: [
      { path: '/tenants', label: '租户管理', icon: 'ws' },
      { path: '/workspaces', label: '工作空间', icon: 'ws' },
      { path: '/workspace-management', label: 'Workspace 管理', icon: 'ws' },
      { path: '/quota-management', label: '配额管理', icon: 'ops' },
      { path: '/projects', label: '项目管理', icon: 'proj' },
      { path: '/account', label: '账户与配额', icon: 'admin' }
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

// 分组展开/折叠状态：默认全部展开（未在 collapsed 中记录即展开）
const collapsed = ref<number[]>([])

function isOpen(idx: number): boolean {
  return !collapsed.value.includes(idx)
}

function toggleGroup(idx: number): void {
  if (collapsed.value.includes(idx)) {
    collapsed.value = collapsed.value.filter(i => i !== idx)
  } else {
    collapsed.value = [...collapsed.value, idx]
  }
}
</script>

<style scoped>
/* === 分组标题：可点击 + 折叠箭头 === */
.nav .grp {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 12px 18px 4px;
  transition: color 0.2s var(--ease-smooth);
}
.nav .grp:hover {
  color: var(--sidebar-ink);
}
.nav .grp:hover .grp-arrow,
.nav .grp:hover .grp-count {
  color: var(--primary);
}
.grp-arrow {
  display: inline-block;
  font-size: 10px;
  line-height: 1;
  color: var(--sidebar-muted);
  transition: transform 0.25s var(--ease-smooth), color 0.2s var(--ease-smooth);
  transform: rotate(0deg);
}
.grp-arrow.open {
  transform: rotate(90deg);
}
.grp-label {
  flex: 1;
}
.grp-count {
  font-size: 10px;
  color: var(--sidebar-muted);
  background: var(--sidebar-hover-bg);
  border-radius: 8px;
  padding: 1px 6px;
  min-width: 16px;
  text-align: center;
  transition: color 0.2s var(--ease-smooth), background 0.2s var(--ease-smooth);
}

/* === 分组容器：平滑高度过渡 === */
.grp-items {
  overflow: hidden;
  max-height: 1200px;
  opacity: 1;
  transition: max-height 0.32s var(--ease-drawer), opacity 0.24s var(--ease-smooth);
}
.grp-items.collapsed {
  max-height: 0;
  opacity: 0;
}

/* === 菜单项流光 hover 效果（::after 横向流光，不遮文字） === */
.nav-item {
  position: relative;
  overflow: hidden;
}
.nav-item::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(99, 102, 241, 0.22) 50%,
    transparent 100%
  );
  background-size: 200% 100%;
  background-position: -100% 0;
  opacity: 0;
  transition: opacity 0.3s var(--ease-smooth);
  pointer-events: none;
  z-index: 0;
}
.nav-item:hover::after {
  opacity: 1;
  animation: flowLight 0.9s var(--ease-smooth);
}
/* 文字与图标置于流光之上 */
.nav-item > * {
  position: relative;
  z-index: 1;
}

/* === 激活态额外发光 === */
.nav-item.active {
  box-shadow: inset 0 0 12px rgba(99, 102, 241, 0.08);
}
</style>
