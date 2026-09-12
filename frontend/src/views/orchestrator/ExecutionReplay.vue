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
        v-for="tab in subTabs"
        :key="tab.key"
        class="sub-t"
        :class="{ on: subTab === tab.key }"
        @click="subTab = tab.key"
      >
        {{ tab.label }}
        <span v-if="tab.badge" class="sub-badge">{{ tab.badge }}</span>
      </div>
    </div>

    <!-- 执行历史 + 回放 -->
    <div v-if="subTab === 'exec'" class="exec-panel">
      <div class="panel-head">
        <span class="title">{{ t('orchestrator.replay.execHistory') }}</span>
        <span class="spacer" />
        <el-button size="small" :icon="Refresh" @click="loadExecutions">
          {{ t('orchestrator.common.refresh') }}
        </el-button>
      </div>

      <div v-if="executions.length === 0" class="empty">
        {{ t('orchestrator.replay.noExecutions') }}
      </div>
      <el-table
        v-else
        :data="executions"
        size="small"
        border
        highlight-current-row
        :row-class-name="execRowClass"
        @row-click="(row: ExecutionRecord) => onSelectExec(row.execId)"
      >
        <el-table-column :label="t('orchestrator.replay.colExecId')">
          <template #default="{ row }">
            <span class="mono">{{ row.execId.slice(0, 12) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('orchestrator.replay.colTrigger')">
          <template #default="{ row }">
            <span class="trigger-tag" :class="`tg-${row.trigger.toLowerCase()}`">
              {{ row.trigger }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('orchestrator.replay.colStatus')">
          <template #default="{ row }">
            <span class="status-tag" :class="`st-${row.status.toLowerCase()}`">{{ row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('orchestrator.replay.colProgress')">
          <template #default="{ row }">{{ row.completedCount }}/{{ row.totalNodes }}</template>
        </el-table-column>
        <el-table-column :label="t('orchestrator.replay.colStartTime')" prop="startedAt" />
        <el-table-column :label="t('orchestrator.replay.colActions')">
          <template #default="{ row }">
            <el-button size="small" link @click.stop="onSelectExec(row.execId)">
              {{ t('orchestrator.replay.replayBtn') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 回放轨迹 -->
      <div v-if="trace" class="replay-trace">
        <div class="trace-head">
          <span class="title">
            {{ t('orchestrator.replay.traceTitle', { id: trace.execId.slice(0, 12) }) }}
          </span>
          <span class="spacer" />
          <el-button size="small" :icon="VideoPlay" :disabled="playing" @click="play">
            {{ t('orchestrator.replay.play') }}
          </el-button>
          <el-button size="small" :icon="VideoPause" :disabled="!playing" @click="pause">
            {{ t('orchestrator.replay.pause') }}
          </el-button>
          <el-button size="small" :icon="DArrowRight" @click="stepForward">
            {{ t('orchestrator.replay.stepForward') }}
          </el-button>
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
            <span v-if="ev.nodeId" class="ev-node">
              {{ t('orchestrator.replay.evNode', { id: ev.nodeId.slice(0, 8) }) }}
            </span>
            <span class="ev-time">{{ ev.timestamp }}</span>
          </li>
        </ol>
      </div>
    </div>

    <!-- 检查点 + 断点续跑 -->
    <div v-else-if="subTab === 'checkpoint'" class="ckpt-panel">
      <div class="panel-head">
        <span class="title">{{ t('orchestrator.replay.checkpoints') }}</span>
        <span class="spacer" />
        <el-button size="small" :icon="Plus" @click="onCreateCheckpoint">
          {{ t('orchestrator.replay.manualCkpt') }}
        </el-button>
        <el-button size="small" :icon="Refresh" @click="loadCheckpoints">
          {{ t('orchestrator.common.refresh') }}
        </el-button>
      </div>

      <div v-if="checkpoints.length === 0" class="empty">
        {{ t('orchestrator.replay.noCheckpoints') }}
      </div>
      <div v-else class="ckpt-list">
        <div v-for="c in checkpoints" :key="c.id" class="ckpt-card">
          <div class="ckpt-head">
            <span class="ckpt-id mono">{{ c.id.slice(0, 12) }}</span>
            <span class="ckpt-kind" :class="`k-${c.kind.toLowerCase()}`">{{ c.kind }}</span>
            <span class="ckpt-time">{{ c.createdAt }}</span>
            <span class="spacer" />
            <el-button size="small" type="primary" @click="onResume(c.id)">
              {{ t('orchestrator.replay.resumeFrom') }}
            </el-button>
          </div>
          <div class="ckpt-body">
            <div class="ckpt-meta">
              {{ t('orchestrator.replay.completedNodes', { n: c.completedNodes.length }) }}
              <span v-for="nid in c.completedNodes" :key="nid" class="ckpt-node-tag">
                {{ nid.slice(0, 8) }}
              </span>
            </div>
            <div v-if="c.note" class="ckpt-note">
              {{ t('orchestrator.replay.ckptNote', { note: c.note }) }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 人工介入 -->
    <div v-else-if="subTab === 'intervene'" class="iv-panel">
      <div class="panel-head">
        <span class="title">{{ t('orchestrator.replay.intervention') }}</span>
        <span class="spacer" />
        <el-button size="small" :icon="Refresh" @click="loadInterventions">
          {{ t('orchestrator.common.refresh') }}
        </el-button>
      </div>

      <div v-if="interventions.length === 0" class="empty">
        {{ t('orchestrator.replay.noInterventions') }}
      </div>
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
          <div class="iv-reason">
            {{ t('orchestrator.replay.ivReason', { reason: iv.reason }) }}
          </div>
          <div v-if="iv.context" class="iv-context">
            <pre class="json">{{ JSON.stringify(iv.context, null, 2) }}</pre>
          </div>
          <div v-if="iv.status === 'PENDING'" class="iv-form">
            <el-input
              v-model="ivForm.approver"
              :placeholder="t('orchestrator.replay.approverPlaceholder')"
              size="small"
              style="width: 120px"
            />
            <el-input
              v-model="ivForm.comment"
              :placeholder="t('orchestrator.replay.commentPlaceholder')"
              size="small"
              style="width: 200px"
            />
            <el-button size="small" type="success" @click="onIntervene(iv.id, 'APPROVED')">
              {{ t('orchestrator.replay.approve') }}
            </el-button>
            <el-button size="small" type="danger" @click="onIntervene(iv.id, 'REJECTED')">
              {{ t('orchestrator.replay.reject') }}
            </el-button>
          </div>
          <div v-else class="iv-resolved">
            <span>{{ t('orchestrator.replay.approver', { who: iv.approver || '--' }) }}</span>
            <span v-if="iv.comment">
              {{ t('orchestrator.replay.comment', { text: iv.comment }) }}
            </span>
            <span>{{ t('orchestrator.replay.resolvedAt', { at: iv.resolvedAt || '--' }) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()

const props = defineProps<{
  dagId: string
}>()

/* ------------------------------ 子 Tab ------------------------------ */

const subTab = ref<'exec' | 'checkpoint' | 'intervene'>('exec')

const executions = ref<ExecutionRecord[]>([])
const interventions = ref<InterventionRequest[]>([])
const checkpoints = ref<Checkpoint[]>([])

const subTabs = computed(() => [
  {
    key: 'exec' as const,
    label: t('orchestrator.replay.tabs.exec'),
    badge: executions.value.length
  },
  {
    key: 'checkpoint' as const,
    label: t('orchestrator.replay.tabs.checkpoint'),
    badge: checkpoints.value.length
  },
  {
    key: 'intervene' as const,
    label: t('orchestrator.replay.tabs.intervention'),
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

/** el-table 行样式类（选中行高亮） */
function execRowClass({ row }: { row: ExecutionRecord }): string {
  return row.execId === selectedExecId.value ? 'active' : ''
}

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
    const note = t('orchestrator.replay.manualNote', { time: new Date().toLocaleTimeString() })
    await createCheckpoint(props.dagId, note)
    ElMessage.success(t('orchestrator.replay.messages.ckptCreated'))
    await loadCheckpoints()
  } catch {
    // ignore
  }
}

async function onResume(checkpointId: string) {
  if (!props.dagId) return
  try {
    await resumeFromCheckpoint(props.dagId, checkpointId)
    ElMessage.success(t('orchestrator.replay.messages.resumed'))
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
    ElMessage.warning(t('orchestrator.replay.messages.approverRequired'))
    return
  }
  try {
    await submitIntervention(props.dagId, {
      interventionId,
      decision,
      approver: ivForm.value.approver,
      comment: ivForm.value.comment
    })
    ElMessage.success(
      decision === 'APPROVED'
        ? t('orchestrator.replay.messages.approved')
        : t('orchestrator.replay.messages.rejected')
    )
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
  border-bottom: 1px solid var(--ds-border-subtle);
  margin-bottom: 10px;
}
.sub-t {
  padding: 7px 14px;
  cursor: pointer;
  font-size: 12px;
  color: var(--ds-text-tertiary);
  border-bottom: 2px solid transparent;
}
.sub-t.on {
  color: var(--ds-color-primary-500);
  border-bottom-color: var(--ds-color-primary-500);
  font-weight: 600;
}
.sub-badge {
  display: inline-block;
  margin-left: 4px;
  font-size: 12px;
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
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
  font-size: 14px;
  font-weight: 700;
}
.panel-head .spacer {
  flex: 1;
}

.empty {
  color: var(--ds-text-tertiary);
  text-align: center;
  padding: 30px 0;
  font-size: 14px;
}

.mono {
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
}

/* 执行历史表 */
.exec-table {
  font-size: 12px;
}
.exec-table tr.active td {
  background: var(--ds-color-primary-50);
}
.trigger-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
}
.tg-run {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.tg-resume {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.tg-replay {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.status-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
}
.st-success {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.st-failed {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.st-running {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.st-stopped {
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
}
.st-paused {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.st-draft {
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
}

/* 回放轨迹 */
.replay-trace {
  margin-top: 14px;
  border-top: 1px solid var(--ds-border-subtle);
  padding-top: 12px;
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.trace-head .title {
  font-size: 12px;
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
  background: var(--ds-color-primary-500);
  transition: width 0.2s;
}
.progress-text {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  font-family: var(--ds-font-family-mono);
}

.event-timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 280px;
  overflow-y: auto;
  font-size: 12px;
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
  color: var(--ds-text-tertiary);
  font-family: var(--ds-font-family-mono);
  width: 28px;
}
.ev-kind {
  font-size: 12px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
  width: 88px;
  text-align: center;
}
.ev-node {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  background: var(--c-surface-hover);
  padding: 1px 5px;
  border-radius: 6px;
}
.ev-time {
  margin-left: auto;
  color: var(--ds-text-tertiary);
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
}

.event.ev-node_start .ev-kind {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.event.ev-node_success .ev-kind {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.event.ev-node_failed .ev-kind {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.event.ev-node_skip .ev-kind {
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
}
.event.ev-checkpoint .ev-kind {
  background: var(--c-indigo-50);
  color: var(--c-violet);
}
.event.ev-intervene .ev-kind {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.event.ev-tool_call .ev-kind {
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
}

/* 检查点 */
.ckpt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ckpt-card {
  border: 1px solid var(--ds-border-subtle);
  border-radius: 8px;
  background: var(--ds-bg-surface);
  padding: 10px 12px;
}
.ckpt-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.ckpt-id {
  font-size: 12px;
  color: var(--ds-text-primary);
}
.ckpt-kind {
  font-size: 12px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--c-surface-alt);
  color: var(--ds-text-tertiary);
}
.ckpt-kind.k-auto {
  background: var(--c-green-50);
  color: var(--ds-color-success-500);
}
.ckpt-kind.k-manual {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.ckpt-kind.k-intervention {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.ckpt-time {
  font-size: 12px;
  color: var(--ds-text-tertiary);
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
  font-size: 12px;
  background: var(--c-surface-hover);
  padding: 1px 5px;
  border-radius: 6px;
  font-family: var(--ds-font-family-mono);
}
.ckpt-note {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ds-text-tertiary);
}

/* 人工介入 */
.iv-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.iv-card {
  border: 1px solid var(--ds-border-subtle);
  border-radius: 8px;
  background: var(--ds-bg-surface);
  padding: 10px 12px;
}
.iv-card.iv-st-pending {
  border-left: 3px solid var(--c-violet);
}
.iv-card.iv-st-approved {
  border-left: 3px solid var(--ds-color-success-500);
}
.iv-card.iv-st-rejected {
  border-left: 3px solid var(--ds-color-error-500);
}
.iv-card.iv-st-timeout {
  border-left: 3px solid var(--ds-color-warning-500);
}
.iv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.iv-node {
  font-weight: 600;
  font-size: 12px;
}
.iv-status {
  font-size: 12px;
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
  color: var(--ds-color-success-500);
}
.iv-status.iv-st-rejected {
  background: var(--c-red-50);
  color: var(--ds-color-error-500);
}
.iv-status.iv-st-timeout {
  background: var(--c-amber-50);
  color: var(--ds-color-warning-500);
}
.iv-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--ds-text-tertiary);
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
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
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
  font-size: 12px;
  color: var(--ds-text-tertiary);
  margin-top: 6px;
}
</style>
