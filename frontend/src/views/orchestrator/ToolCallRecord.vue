<!--
  ToolCallRecord.vue — 工具调用记录可视化（T007 viz）

  功能：
  - 拉取并展示工具调用参数与结果
  - 按节点过滤（可选 nodeId）
  - 卡片式布局：工具名 + 状态 + 耗时；展开查看参数与结果 JSON
  - 状态着色：SUCCESS/FAILED/TIMEOUT/SKIPPED

  Props：
  - dagId: DAG ID
  - nodeId: 可选，按节点过滤
-->
<template>
  <div class="tool-calls">
    <div class="tc-head">
      <span class="title">{{ t('orchestrator.toolCall.title') }}</span>
      <span class="meta">{{ t('orchestrator.toolCall.timesMeta', { n: filtered.length }) }}</span>
      <span class="spacer" />
      <el-button size="small" :icon="Refresh" @click="load">
        {{ t('orchestrator.common.refresh') }}
      </el-button>
    </div>

    <div v-if="loading" class="tc-empty">{{ t('orchestrator.common.loading') }}</div>
    <div v-else-if="filtered.length === 0" class="tc-empty">
      {{ t('orchestrator.toolCall.noData') }}
    </div>

    <div v-else class="call-list">
      <div
        v-for="c in filtered"
        :key="c.id"
        class="call-card"
        :class="`st-${c.status.toLowerCase()}`"
      >
        <div class="call-head" @click="toggle(c.id)">
          <span class="call-seq">#{{ c.seq }}</span>
          <span class="call-tool">{{ c.toolName }}</span>
          <span class="call-node">
            {{ t('orchestrator.replay.evNode', { id: c.nodeId.slice(0, 8) }) }}
          </span>
          <span class="call-status" :class="`st-${c.status.toLowerCase()}`">{{ c.status }}</span>
          <span class="call-dur">{{ c.durationMs }} ms</span>
          <span class="call-expand" :class="{ open: expanded.has(c.id) }">▾</span>
        </div>
        <div v-if="expanded.has(c.id)" class="call-body">
          <div class="call-section">
            <div class="section-label">{{ t('orchestrator.common.params') }}</div>
            <pre class="json">{{ JSON.stringify(c.args, null, 2) }}</pre>
          </div>
          <div v-if="c.result" class="call-section">
            <div class="section-label">{{ t('orchestrator.toolCall.result') }}</div>
            <pre class="json">{{ JSON.stringify(c.result, null, 2) }}</pre>
          </div>
          <div v-if="c.errorMessage" class="call-section err">
            <div class="section-label">{{ t('orchestrator.common.error') }}</div>
            <div class="err-msg">{{ c.errorMessage }}</div>
          </div>
          <div class="call-time">
            <span>{{ t('orchestrator.toolCall.started', { at: c.startedAt }) }}</span>
            <span v-if="c.finishedAt">
              {{ t('orchestrator.toolCall.finished', { at: c.finishedAt }) }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import { getToolCalls, type ToolCallRecord as ToolCall } from '@/api/orchestrator-viz'

const { t } = useI18n()

const props = defineProps<{
  dagId: string
  nodeId?: string
}>()

const calls = ref<ToolCall[]>([])
const loading = ref(false)
const expanded = ref<Set<string>>(new Set())

const filtered = computed<ToolCall[]>(() => {
  if (!props.nodeId) return calls.value
  return calls.value.filter((c) => c.nodeId === props.nodeId)
})

async function load() {
  if (!props.dagId) return
  loading.value = true
  try {
    calls.value = await getToolCalls(props.dagId)
  } catch {
    calls.value = []
  } finally {
    loading.value = false
  }
}

function toggle(id: string) {
  if (expanded.value.has(id)) {
    expanded.value.delete(id)
  } else {
    expanded.value.add(id)
  }
  // 触发响应式更新
  expanded.value = new Set(expanded.value)
}

watch(() => props.dagId, load)
onMounted(load)
</script>

<style scoped>
.tool-calls {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tc-head {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-1);
}
.tc-head .title {
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-extrabold);
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

.call-list {
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}
.call-card {
  border: 1px solid var(--ds-border-subtle);
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-surface);
  overflow: hidden;
}
.call-card.st-failed {
  border-left: 3px solid var(--ds-color-error-500);
}
.call-card.st-success {
  border-left: 3px solid var(--ds-color-success-500);
}
.call-card.st-timeout {
  border-left: 3px solid var(--ds-color-warning-500);
}
.call-card.st-skipped {
  border-left: 3px solid var(--c-slate-300);
}

.call-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  cursor: pointer;
  font-size: var(--ds-font-size-xs);
}
.call-head:hover {
  background: var(--c-surface-hover);
}
.call-seq {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  font-family: var(--ds-font-family-mono);
}
.call-tool {
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-primary);
  font-family: var(--ds-font-family-mono);
}
.call-node {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  background: var(--c-surface-alt);
  padding: 1px 6px;
  border-radius: var(--ds-radius-md);
}
.call-status {
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-extrabold);
  padding: 2px 7px;
  border-radius: var(--ds-radius-md-plus);
}
.call-status.st-success {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.call-status.st-failed {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.call-status.st-timeout {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.call-status.st-skipped {
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
}
.call-dur {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-left: auto;
}
.call-expand {
  color: var(--ds-text-tertiary);
  transition: transform var(--ds-transition-quick);
  font-size: var(--ds-font-size-xs);
}
.call-expand.open {
  transform: rotate(180deg);
}

.call-body {
  padding: 10px 12px;
  border-top: 1px solid var(--ds-border-subtle);
  background: var(--c-surface-hover);
}
.call-section {
  margin-bottom: 10px;
}
.section-label {
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-tertiary);
  margin-bottom: var(--ds-spacing-1);
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.json {
  background: var(--ds-bg-surface);
  border-radius: var(--ds-radius-md);
  padding: 8px 10px;
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  color: var(--c-slate-700);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}
.err-msg {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-color-error-500);
  background: var(--c-red-50);
  padding: 6px 10px;
  border-radius: var(--ds-radius-md);
  word-break: break-all;
}
.call-time {
  display: flex;
  gap: var(--ds-spacing-4);
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-top: var(--ds-spacing-1);
}
</style>
