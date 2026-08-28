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
      <span class="title">工具调用记录</span>
      <span class="meta">{{ filtered.length }} 次</span>
      <span class="spacer" />
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <div v-if="loading" class="tc-empty">加载中…</div>
    <div v-else-if="filtered.length === 0" class="tc-empty">暂无工具调用记录</div>

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
          <span class="call-node">节点 {{ c.nodeId.slice(0, 8) }}</span>
          <span class="call-status" :class="`st-${c.status.toLowerCase()}`">{{ c.status }}</span>
          <span class="call-dur">{{ c.durationMs }} ms</span>
          <span class="call-expand" :class="{ open: expanded.has(c.id) }">▾</span>
        </div>
        <div v-if="expanded.has(c.id)" class="call-body">
          <div class="call-section">
            <div class="section-label">参数</div>
            <pre class="json">{{ JSON.stringify(c.args, null, 2) }}</pre>
          </div>
          <div v-if="c.result" class="call-section">
            <div class="section-label">结果</div>
            <pre class="json">{{ JSON.stringify(c.result, null, 2) }}</pre>
          </div>
          <div v-if="c.errorMessage" class="call-section err">
            <div class="section-label">错误</div>
            <div class="err-msg">{{ c.errorMessage }}</div>
          </div>
          <div class="call-time">
            <span>开始：{{ c.startedAt }}</span>
            <span v-if="c.finishedAt">结束：{{ c.finishedAt }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getToolCalls, type ToolCallRecord as ToolCall } from '@/api/orchestrator-viz'

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

.call-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.call-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}
.call-card.st-failed {
  border-left: 3px solid var(--red);
}
.call-card.st-success {
  border-left: 3px solid var(--green);
}
.call-card.st-timeout {
  border-left: 3px solid var(--amber);
}
.call-card.st-skipped {
  border-left: 3px solid var(--c-slate-300);
}

.call-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12.5px;
}
.call-head:hover {
  background: var(--c-surface-hover);
}
.call-seq {
  font-size: 11px;
  color: var(--muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.call-tool {
  font-weight: 600;
  color: var(--ink);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.call-node {
  font-size: 11px;
  color: var(--muted);
  background: var(--c-surface-alt);
  padding: 1px 6px;
  border-radius: 8px;
}
.call-status {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 10px;
}
.call-status.st-success {
  background: var(--c-green-50);
  color: var(--green);
}
.call-status.st-failed {
  background: var(--c-red-50);
  color: var(--red);
}
.call-status.st-timeout {
  background: var(--c-amber-50);
  color: var(--amber);
}
.call-status.st-skipped {
  background: var(--c-surface-alt);
  color: var(--muted);
}
.call-dur {
  font-size: 11px;
  color: var(--muted);
  margin-left: auto;
}
.call-expand {
  color: var(--muted);
  transition: transform 0.2s;
  font-size: 12px;
}
.call-expand.open {
  transform: rotate(180deg);
}

.call-body {
  padding: 10px 12px;
  border-top: 1px solid var(--line);
  background: var(--c-surface-hover);
}
.call-section {
  margin-bottom: 10px;
}
.section-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--muted);
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.json {
  background: #fff;
  border-radius: 6px;
  padding: 8px 10px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  color: var(--c-slate-700);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}
.err-msg {
  font-size: 12px;
  color: var(--red);
  background: var(--c-red-50);
  padding: 6px 10px;
  border-radius: 6px;
  word-break: break-all;
}
.call-time {
  display: flex;
  gap: 16px;
  font-size: 11px;
  color: var(--muted);
  margin-top: 4px;
}
</style>
