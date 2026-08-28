<!--
  ThoughtChain.vue — Agent 思考链展示（T007 viz）

  功能：
  - 拉取并展示 Agent 推理过程（OBSERVE/PLAN/ACT/REFLECT/DECIDE）
  - 按节点过滤（可选 nodeId）
  - 时间线纵向布局，每步展示类型徽标 + 内容 + 耗时
  - 关联工具调用 ID 可点击跳转

  Props：
  - dagId: DAG ID
  - nodeId: 可选，按节点过滤
-->
<template>
  <div class="thought-chain">
    <div class="tc-head">
      <span class="title">Agent 思考链</span>
      <span class="meta">{{ filtered.length }} 步</span>
      <span class="spacer" />
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <div v-if="loading" class="tc-empty">加载中…</div>
    <div v-else-if="filtered.length === 0" class="tc-empty">暂无思考链数据</div>

    <ol v-else class="timeline">
      <li
        v-for="step in filtered"
        :key="step.index"
        class="step"
        :class="`k-${step.kind.toLowerCase()}`"
      >
        <div class="step-marker">
          <span class="kind-badge">{{ step.kind }}</span>
          <span class="step-idx">#{{ step.index }}</span>
        </div>
        <div class="step-body">
          <div class="step-meta">
            <span v-if="step.nodeId" class="meta-node">节点 {{ step.nodeId.slice(0, 8) }}</span>
            <span class="meta-time">{{ formatTime(step.timestamp) }}</span>
            <span v-if="step.durationMs" class="meta-dur">{{ step.durationMs }} ms</span>
          </div>
          <div class="step-content">{{ step.content }}</div>
          <div v-if="step.observation" class="step-obs">
            <span class="obs-label">观察：</span>
            {{ step.observation }}
          </div>
          <div v-if="step.toolCallId" class="step-tool">
            <span class="tool-label">工具调用：</span>
            <a
              href="javascript:void(0)"
              class="tool-link"
              @click="$emit('jump-tool', step.toolCallId)"
            >
              {{ step.toolCallId.slice(0, 12) }}
            </a>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getThoughtChain, type ThoughtStep } from '@/api/orchestrator-viz'

const props = defineProps<{
  dagId: string
  nodeId?: string
}>()

defineEmits<{
  (e: 'jump-tool', toolCallId: string): void
}>()

const steps = ref<ThoughtStep[]>([])
const loading = ref(false)

const filtered = computed<ThoughtStep[]>(() => {
  if (!props.nodeId) return steps.value
  return steps.value.filter((s) => s.nodeId === props.nodeId)
})

async function load() {
  if (!props.dagId) return
  loading.value = true
  try {
    steps.value = await getThoughtChain(props.dagId)
  } catch {
    steps.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(ts: string): string {
  // 简化时间显示，只保留时分秒
  const idx = ts.indexOf('T')
  return idx >= 0 ? ts.slice(idx + 1, idx + 9) : ts
}

watch(() => props.dagId, load)

onMounted(load)
</script>

<style scoped>
.thought-chain {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tc-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.tc-head .title {
  font-size: 13px;
  font-weight: 700;
}
.tc-head .meta {
  font-size: 12px;
  color: var(--muted);
}
.tc-head .spacer {
  flex: 1;
}
.tc-empty {
  color: var(--muted);
  text-align: center;
  padding: 30px 0;
  font-size: 13px;
}

.timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  position: relative;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 52px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--line);
}
.step {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 12px;
  padding: 8px 0;
  position: relative;
}
.step-marker {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  z-index: 1;
}
.kind-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 12px;
  background: var(--c-surface-alt);
  color: var(--muted);
  letter-spacing: 0.4px;
}
.step-idx {
  font-size: 10px;
  color: var(--muted);
}

.step.k-observe .kind-badge {
  background: var(--c-green-50);
  color: var(--green);
}
.step.k-plan .kind-badge {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.step.k-act .kind-badge {
  background: var(--c-amber-50);
  color: var(--amber);
}
.step.k-reflect .kind-badge {
  background: var(--primary-soft);
  color: var(--primary);
}
.step.k-decide .kind-badge {
  background: var(--c-red-50);
  color: var(--red);
}

.step-body {
  background: var(--c-surface-hover);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12.5px;
}
.step-meta {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}
.meta-node {
  background: var(--primary-soft);
  color: var(--primary);
  padding: 1px 6px;
  border-radius: 8px;
}
.step-content {
  color: var(--ink);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.step-obs {
  margin-top: 6px;
  font-size: 12px;
  color: var(--c-slate-700);
  background: #fff;
  border-radius: 6px;
  padding: 5px 8px;
  border-left: 3px solid var(--green);
}
.obs-label {
  color: var(--muted);
  font-weight: 600;
}
.step-tool {
  margin-top: 6px;
  font-size: 11.5px;
}
.tool-label {
  color: var(--muted);
}
.tool-link {
  color: var(--primary);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
</style>
