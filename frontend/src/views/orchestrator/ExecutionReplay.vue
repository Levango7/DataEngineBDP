<!--
  ExecutionReplay.vue — 回放控制 + 断点续跑 + 人工介入（T007 viz）

  功能：
  - 执行历史列表（execId / trigger / status / 进度）
  - 检查点列表 + 手动打点 + 从检查点恢复执行
  - 人工介入请求列表 + 提交审批（批准/驳回）
  - 回放轨迹：事件流时间线 + 播放/暂停/单步/速度控制

  Props：
  - dagId: DAG ID
-->
<template>
  <div class="exec-replay">
    <!-- 子 Tab：执行历史 / 检查点 / 人工介入 -->
    <div class="sub-tabbar">
      <div
        v-for="t in subTabs"
        :key="t.key"
        class="sub-t"
        :class="{ on: subTab === t.key }"
        @click="subTab = t.key"
      >
        {{ t.label }}
        <span v-if="t.badge" class="sub-badge">{{ t.badge }}</span>
      </div>
    </div>

    <!-- 执行历史 + 回放 -->
    <div v-if="subTab === 'exec'" class="exec-panel">
      <div class="panel-head">
        <span class="title">执行历史</span>
        <span class="spacer" />
        <el-button size="small" :icon="Refresh" @click="loadExecutions">刷新</el-button>
      </div>

      <div v-if="executions.length === 0" class="empty">暂无执行记录</div>
      <table v-else class="exec-table">
        <thead>
          <tr>
            <th>执行 ID</th>
            <th>触发</th>
            <th>状态</th>
            <th>进度</th>
            <th>开始时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="e in executions"
            :key="e.execId"
            class="click"
            :class="{ active: e.execId === selectedExecId }"
            @click="onSelectExec(e.execId)"
          >
            <td class="mono">{{ e.execId.slice(0, 12) }}</td>
            <td>
              <span class="trigger-tag" :class="`tg-${e.trigger.toLowerCase()}`">
                {{ e.trigger }}
              </span>
            </td>
            <td>
              <span class="status-tag" :class="`st-${e.status.toLowerCase()}`">{{ e.status }}</span>
            </td>
            <td>{{ e.completedCount }}/{{ e.totalNodes }}</td>
            <td>{{ e.startedAt }}</td>
            <td>
              <el-button size="small" link @click.stop="onSelectExec(e.execId)">回放</el-button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 回放轨迹 -->
      <div v-if="trace" class="replay-trace">
        <div class="trace-head">
          <span class="title">回放轨迹 · {{ trace.execId.slice(0, 12) }}</span>
          <span class="spacer" />
          <el-button size="small" :icon="VideoPlay" :disabled="playing" @click="play">
            播放
          </el-button>
          <el-button size="small" :icon="VideoPause" :disabled="!playing" @click="pause">
            暂停
          </el-button>
          <el-button size="small" :icon="DArrowRight" @click="stepForward">单步</el-button>
          <el-select v-model="speed" size="small" style="width: 90px; margin-left: 8px">
            <el-option label="0.5x" :value="0.5" />
            <el-option label="1x" :value="1" />
            <el-option label="2x" :value="2" />
            <el-option label="4x" :value="4" />
          </el-select>
        </div>

        <!-- 进度条 -->
        <div class="progress">
          <div class="progress-bar">
            <i :style="{ width: progressPct + '%' }" />
          </div>
          <span class="progress-text">{{ currentStep }} / {{ trace.events.length }}</span>
        </div>

        <!-- 事件时间线 -->
        <ol class="event-timeline">
          <li
            v-for="(ev, idx) in trace.events"
            :key="ev.seq"
            class="event"
            :class="[
              `ev-${ev.kind.toLowerCase()}`,
              { done: idx < currentStep, current: idx === currentStep - 1 }
            ]"
          >
            <span class="ev-seq">{{ ev.seq }}</span>
            <span class="ev-kind">{{ ev.kind }}</span>
            <span v-if="ev.nodeId" class="ev-node">节点 {{ ev.nodeId.slice(0, 8) }}</span>
            <span class="ev-time">{{ ev.timestamp }}</span>
          </li>
        </ol>
      </div>
    </div>

    <!-- 检查点 + 断点续跑 -->
    <div v-else-if="subTab === 'checkpoint'" class="ckpt-panel">
      <div class="panel-head">
        <span class="title">检查点 · 断点续跑</span>
        <span class="spacer" />
        <el-button size="small" :icon="Plus" @click="onCreateCheckpoint">手动打点</el-button>
        <el-button size="small" :icon="Refresh" @click="loadCheckpoints">刷新</el-button>
      </div>

      <div v-if="checkpoints.length === 0" class="empty">暂无检查点</div>
      <div v-else class="ckpt-list">
        <div v-for="c in checkpoints" :key="c.id" class="ckpt-card">
          <div class="ckpt-head">
            <span class="ckpt-id mono">{{ c.id.slice(0, 12) }}</span>
            <span class="ckpt-kind" :class="`k-${c.kind.toLowerCase()}`">{{ c.kind }}</span>
            <span class="ckpt-time">{{ c.createdAt }}</span>
            <span class="spacer" />
            <el-button size="small" type="primary" @click="onResume(c.id)">从此处恢复</el-button>
          </div>
          <div class="ckpt-body">
            <div class="ckpt-meta">
              已完成 {{ c.completedNodes.length }} 节点：
              <span v-for="nid in c.completedNodes" :key="nid" class="ckpt-node-tag">
                {{ nid.slice(0, 8) }}
              </span>
            </div>
            <div v-if="c.note" class="ckpt-note">备注：{{ c.note }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 人工介入 -->
    <div v-else-if="subTab === 'intervene'" class="iv-panel">
      <div class="panel-head">
        <span class="title">人工介入</span>
        <span class="spacer" />
        <el-button size="small" :icon="Refresh" @click="loadInterventions">刷新</el-button>
      </div>

      <div v-if="interventions.length === 0" class="empty">暂无待处理介入请求</div>
      <div v-else class="iv-list">
        <div
          v-for="iv in interventions"
          :key="iv.id"
          class="iv-card"
          :class="`iv-st-${iv.status.toLowerCase()}`"
        >
          <div class="iv-head">
            <span class="iv-node">{{ iv.nodeName }}</span>
            <span class="iv-status" :class="`iv-st-${iv.status.toLowerCase()}`">
              {{ iv.status }}
            </span>
            <span class="iv-time">{{ iv.createdAt }}</span>
          </div>
          <div class="iv-reason">原因：{{ iv.reason }}</div>
          <div v-if="iv.context" class="iv-context">
            <pre class="json">{{ JSON.stringify(iv.context, null, 2) }}</pre>
          </div>
          <div v-if="iv.status === 'PENDING'" class="iv-form">
            <el-input
              v-model="ivForm.approver"
              placeholder="审批人"
              size="small"
              style="width: 120px"
            />
            <el-input
              v-model="ivForm.comment"
              placeholder="审批意见"
              size="small"
              style="width: 200px"
            />
            <el-button size="small" type="success" @click="onIntervene(iv.id, 'APPROVED')">
              批准
            </el-button>
            <el-button size="small" type="danger" @click="onIntervene(iv.id, 'REJECTED')">
              驳回
            </el-button>
          </div>
          <div v-else class="iv-resolved">
            <span>审批人：{{ iv.approver || '--' }}</span>
            <span v-if="iv.comment">意见：{{ iv.comment }}</span>
            <span>处理时间：{{ iv.resolvedAt || '--' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { VideoPlay, VideoPause, DArrowRight, Refresh, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getExecutions,
  getReplayTrace,
  getCheckpoints,
  createCheckpoint,
  resumeFromCheckpoint,
  getInterventions,
  submitIntervention,
  type ExecutionRecord,
  type ReplayTrace,
  type Checkpoint,
  type InterventionRequest
} from '@/api/orchestrator-viz'

const props = defineProps<{
  dagId: string
}>()

/* ------------------------------ 子 Tab ------------------------------ */

const subTab = ref<'exec' | 'checkpoint' | 'intervene'>('exec')

const executions = ref<ExecutionRecord[]>([])
const interventions = ref<InterventionRequest[]>([])
const checkpoints = ref<Checkpoint[]>([])

const subTabs = computed(() => [
  { key: 'exec' as const, label: '执行历史', badge: executions.value.length },
  { key: 'checkpoint' as const, label: '检查点', badge: checkpoints.value.length },
  {
    key: 'intervene' as const,
    label: '人工介入',
    badge: interventions.value.filter((i) => i.status === 'PENDING').length
  }
])

/* ------------------------------ 回放 ------------------------------ */

const selectedExecId = ref<string>('')
const trace = ref<ReplayTrace | null>(null)
const playing = ref(false)
const speed = ref(1)
const currentStep = ref(0)
let playTimer: ReturnType<typeof setInterval> | null = null

const progressPct = computed(() => {
  if (!trace.value || trace.value.events.length === 0) return 0
  return Math.round((currentStep.value / trace.value.events.length) * 100)
})

async function loadExecutions() {
  if (!props.dagId) return
  try {
    executions.value = await getExecutions(props.dagId)
  } catch {
    executions.value = []
  }
}

async function onSelectExec(execId: string) {
  selectedExecId.value = execId
  playing.value = false
  currentStep.value = 0
  try {
    trace.value = await getReplayTrace(props.dagId, execId)
  } catch {
    trace.value = null
  }
}

function play() {
  if (!trace.value || trace.value.events.length === 0) return
  playing.value = true
  const interval = 1000 / speed.value
  playTimer = setInterval(() => {
    if (currentStep.value >= trace.value!.events.length) {
      pause()
      return
    }
    currentStep.value++
  }, interval)
}

function pause() {
  playing.value = false
  if (playTimer) {
    clearInterval(playTimer)
    playTimer = null
  }
}

function stepForward() {
  if (!trace.value) return
  if (currentStep.value < trace.value.events.length) {
    currentStep.value++
  }
}

onBeforeUnmount(() => {
  if (playTimer) clearInterval(playTimer)
})

/* ------------------------------ 检查点 ------------------------------ */

async function loadCheckpoints() {
  if (!props.dagId) return
  try {
    checkpoints.value = await getCheckpoints(props.dagId)
  } catch {
    checkpoints.value = []
  }
}

async function onCreateCheckpoint() {
  if (!props.dagId) return
  try {
    const note = `手动打点 ${new Date().toLocaleTimeString()}`
    await createCheckpoint(props.dagId, note)
    ElMessage.success('检查点已创建')
    await loadCheckpoints()
  } catch {
    // ignore
  }
}

async function onResume(checkpointId: string) {
  if (!props.dagId) return
  try {
    await resumeFromCheckpoint(props.dagId, checkpointId)
    ElMessage.success('已从检查点恢复执行')
    await loadExecutions()
  } catch {
    // ignore
  }
}

/* ------------------------------ 人工介入 ------------------------------ */

const ivForm = ref({ approver: '', comment: '' })

async function loadInterventions() {
  if (!props.dagId) return
  try {
    interventions.value = await getInterventions(props.dagId)
  } catch {
    interventions.value = []
  }
}

async function onIntervene(interventionId: string, decision: 'APPROVED' | 'REJECTED') {
  if (!props.dagId) return
  if (!ivForm.value.approver) {
    ElMessage.warning('请填写审批人')
    return
  }
  try {
    await submitIntervention(props.dagId, {
      interventionId,
      decision,
      approver: ivForm.value.approver,
      comment: ivForm.value.comment
    })
    ElMessage.success(decision === 'APPROVED' ? '已批准' : '已驳回')
    ivForm.value = { approver: '', comment: '' }
    await loadInterventions()
  } catch {
    // ignore
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

watch(
  () => props.dagId,
  () => {
    loadExecutions()
    loadCheckpoints()
    loadInterventions()
  }
)

watch(subTab, (v) => {
  if (v === 'exec' && executions.value.length === 0) loadExecutions()
  if (v === 'checkpoint' && checkpoints.value.length === 0) loadCheckpoints()
  if (v === 'intervene' && interventions.value.length === 0) loadInterventions()
})

onMounted(() => {
  loadExecutions()
  loadCheckpoints()
  loadInterventions()
})
</script>

<style scoped>
.exec-replay {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sub-tabbar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--line);
  margin-bottom: 10px;
}
.sub-t {
  padding: 7px 14px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--muted);
  border-bottom: 2px solid transparent;
}
.sub-t.on {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}
.sub-badge {
  display: inline-block;
  margin-left: 4px;
  font-size: 10px;
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: 10px;
  padding: 0 6px;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.panel-head .title {
  font-size: 13px;
  font-weight: 700;
}
.panel-head .spacer {
  flex: 1;
}

.empty {
  color: var(--muted);
  text-align: center;
  padding: 30px 0;
  font-size: 13px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11.5px;
}

/* 执行历史表 */
.exec-table {
  font-size: 12px;
}
.exec-table tr.active td {
  background: var(--primary-soft);
}
.trigger-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
}
.tg-run {
  background: var(--c-green-50);
  color: var(--green);
}
.tg-resume {
  background: var(--c-amber-50);
  color: var(--amber);
}
.tg-replay {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.status-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
}
.st-success {
  background: var(--c-green-50);
  color: var(--green);
}
.st-failed {
  background: var(--c-red-50);
  color: var(--red);
}
.st-running {
  background: var(--c-amber-50);
  color: var(--amber);
}
.st-stopped {
  background: var(--c-surface-alt);
  color: var(--muted);
}
.st-paused {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.st-draft {
  background: var(--c-surface-alt);
  color: var(--muted);
}

/* 回放轨迹 */
.replay-trace {
  margin-top: 14px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.trace-head .title {
  font-size: 12.5px;
  font-weight: 700;
}
.trace-head .spacer {
  flex: 1;
}

.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.progress-bar {
  flex: 1;
  height: 6px;
  background: var(--c-track);
  border-radius: 4px;
  overflow: hidden;
}
.progress-bar i {
  display: block;
  height: 100%;
  background: var(--primary);
  transition: width 0.2s;
}
.progress-text {
  font-size: 11px;
  color: var(--muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.event-timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 280px;
  overflow-y: auto;
  font-size: 11.5px;
}
.event {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  opacity: 0.5;
}
.event.done {
  opacity: 1;
}
.event.current {
  background: var(--c-amber-50);
  font-weight: 600;
}
.ev-seq {
  color: var(--muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
  width: 28px;
}
.ev-kind {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--c-surface-alt);
  color: var(--muted);
  width: 88px;
  text-align: center;
}
.ev-node {
  font-size: 10px;
  color: var(--muted);
  background: var(--c-surface-hover);
  padding: 1px 5px;
  border-radius: 6px;
}
.ev-time {
  margin-left: auto;
  color: var(--muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10.5px;
}

.event.ev-node_start .ev-kind {
  background: var(--c-amber-50);
  color: var(--amber);
}
.event.ev-node_success .ev-kind {
  background: var(--c-green-50);
  color: var(--green);
}
.event.ev-node_failed .ev-kind {
  background: var(--c-red-50);
  color: var(--red);
}
.event.ev-node_skip .ev-kind {
  background: var(--c-surface-alt);
  color: var(--muted);
}
.event.ev-checkpoint .ev-kind {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.event.ev-intervene .ev-kind {
  background: var(--c-red-50);
  color: var(--red);
}
.event.ev-tool_call .ev-kind {
  background: var(--primary-soft);
  color: var(--primary);
}

/* 检查点 */
.ckpt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ckpt-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  padding: 10px 12px;
}
.ckpt-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.ckpt-id {
  font-size: 11.5px;
  color: var(--ink);
}
.ckpt-kind {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--c-surface-alt);
  color: var(--muted);
}
.ckpt-kind.k-auto {
  background: var(--c-green-50);
  color: var(--green);
}
.ckpt-kind.k-manual {
  background: var(--c-amber-50);
  color: var(--amber);
}
.ckpt-kind.k-intervention {
  background: var(--c-red-50);
  color: var(--red);
}
.ckpt-time {
  font-size: 11px;
  color: var(--muted);
}
.ckpt-head .spacer {
  flex: 1;
}
.ckpt-meta {
  font-size: 12px;
  color: var(--c-slate-700);
}
.ckpt-node-tag {
  display: inline-block;
  margin: 2px 4px 0 0;
  font-size: 10px;
  background: var(--c-surface-hover);
  padding: 1px 5px;
  border-radius: 6px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.ckpt-note {
  margin-top: 6px;
  font-size: 11px;
  color: var(--muted);
}

/* 人工介入 */
.iv-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.iv-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  padding: 10px 12px;
}
.iv-card.iv-st-pending {
  border-left: 3px solid var(--c-violet);
}
.iv-card.iv-st-approved {
  border-left: 3px solid var(--green);
}
.iv-card.iv-st-rejected {
  border-left: 3px solid var(--red);
}
.iv-card.iv-st-timeout {
  border-left: 3px solid var(--amber);
}
.iv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.iv-node {
  font-weight: 600;
  font-size: 12.5px;
}
.iv-status {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 8px;
}
.iv-status.iv-st-pending {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.iv-status.iv-st-approved {
  background: var(--c-green-50);
  color: var(--green);
}
.iv-status.iv-st-rejected {
  background: var(--c-red-50);
  color: var(--red);
}
.iv-status.iv-st-timeout {
  background: var(--c-amber-50);
  color: var(--amber);
}
.iv-time {
  margin-left: auto;
  font-size: 11px;
  color: var(--muted);
}
.iv-reason {
  font-size: 12px;
  color: var(--c-slate-700);
  margin-bottom: 6px;
}
.iv-context {
  margin-bottom: 8px;
}
.json {
  background: var(--c-surface-hover);
  border-radius: 6px;
  padding: 8px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  color: var(--c-slate-700);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
  margin: 0;
}
.iv-form {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
  flex-wrap: wrap;
}
.iv-resolved {
  display: flex;
  gap: 14px;
  font-size: 11px;
  color: var(--muted);
  margin-top: 6px;
}
</style>
