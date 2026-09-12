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
      <span class="title">{{ t('orchestrator.thought.title') }}</span>
      <span class="meta">{{ t('orchestrator.thought.stepsMeta', { n: filtered.length }) }}</span>
      <span class="spacer" />
      <el-button size="small" :icon="Refresh" @click="load">
        {{ t('orchestrator.common.refresh') }}
      </el-button>
    </div>

    <div v-if="loading" class="tc-empty">{{ t('orchestrator.common.loading') }}</div>
    <div v-else-if="filtered.length === 0" class="tc-empty">
      {{ t('orchestrator.thought.noData') }}
    </div>

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
            <span v-if="step.nodeId" class="meta-node">
              {{ t('orchestrator.replay.evNode', { id: step.nodeId.slice(0, 8) }) }}
            </span>
            <span class="meta-time">{{ formatTime(step.timestamp) }}</span>
            <span v-if="step.durationMs" class="meta-dur">{{ step.durationMs }} ms</span>
          </div>
          <div class="step-content">{{ step.content }}</div>
          <div v-if="step.observation" class="step-obs">
            <span class="obs-label">{{ t('orchestrator.thought.obsLabel') }}</span>
            {{ step.observation }}
          </div>
          <div v-if="step.toolCallId" class="step-tool">
            <span class="tool-label">{{ t('orchestrator.thought.toolLabel') }}</span>
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
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import { getThoughtChain, type ThoughtStep } from '@/api/orchestrator-viz'

const { t } = useI18n()

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
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-bold);
}
.tc-head .meta {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
}
.tc-head .spacer {
  flex: 1;
}
.tc-empty {
  color: var(--ds-text-tertiary);
  text-align: center;
  padding: 30px 0;
  font-size: var(--ds-font-size-base);
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
  background: var(--ds-border-subtle);
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
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-bold);
  padding: 3px 8px;
  border-radius: var(--ds-radius-lg);
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
  letter-spacing: 0.4px;
}
.step-idx {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
}

.step.k-observe .kind-badge {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.step.k-plan .kind-badge {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.step.k-act .kind-badge {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.step.k-reflect .kind-badge {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
}
.step.k-decide .kind-badge {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}

.step-body {
  background: var(--c-surface-hover);
  border-radius: var(--ds-radius-md);
  padding: 8px 12px;
  font-size: var(--ds-font-size-xs);
}
.step-meta {
  display: flex;
  gap: 10px;
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-bottom: 4px;
}
.meta-node {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
  padding: 1px 6px;
  border-radius: var(--ds-radius-md);
}
.step-content {
  color: var(--ds-text-primary);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.step-obs {
  margin-top: 6px;
  font-size: var(--ds-font-size-xs);
  color: var(--c-slate-700);
  background: var(--ds-bg-surface);
  border-radius: var(--ds-radius-md);
  padding: 5px 8px;
  border-left: 3px solid var(--ds-color-success-500);
}
.obs-label {
  color: var(--ds-text-tertiary);
  font-weight: var(--ds-font-weight-semibold);
}
.step-tool {
  margin-top: 6px;
  font-size: var(--ds-font-size-xs);
}
.tool-label {
  color: var(--ds-text-tertiary);
}
.tool-link {
  color: var(--ds-color-primary-500);
  font-family: var(--ds-font-family-mono);
}
</style>
