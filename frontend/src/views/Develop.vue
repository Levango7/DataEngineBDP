<template>
  <div>
    <h1>数据开发</h1>
    <div class="sub">
      Web IDE 编写 SQL / 配置 DAG，提交即由 DolphinScheduler 调度运行；底层 Pod 由 Spark/Flink Operator 托管，客户不可见。
    </div>
    <div class="toolbar">
      <span class="chip on" @click="store.showToast('已切换：开发环境')">开发环境</span>
      <span class="chip" @click="store.showToast('已切换：生产环境（独立配额与数据隔离）')">生产环境</span>
      <div class="spacer"></div>
      <span class="pill b">标准模式 · 双环境隔离</span>
    </div>
    <div class="ide">
      <!-- 文件树：从后端 /develop/files 拉取真实工作空间文件 -->
      <div class="tree">
        <div v-if="fileTreeLoading" class="tree-loading">加载文件树…</div>
        <div v-else-if="fileTreeError" class="tree-error">
          加载失败，<a href="javascript:void(0)" @click="loadFileTree">重试</a>
        </div>
        <div v-else-if="fileTree.length === 0" class="tree-empty">工作空间为空</div>
        <template v-else>
          <div
            v-for="node in fileTree"
            :key="node.id"
            class="tree-node"
            :class="{ folder: node.type === 'folder', active: node.path === currentFilePath }"
            :style="{ paddingLeft: '8px' }"
            @click="handleFileClick(node)"
          >
            <span v-if="node.type === 'folder'">📁</span>
            <span v-else>📄</span>
            {{ node.name }}
          </div>
        </template>
      </div>
      <div class="code-wrap">
        <div class="tabs">
          <div class="tab on">
            {{ currentFilePath || '未选择文件' }}
            <span v-if="currentFilePath" class="x" @click="closeCurrentFile">×</span>
          </div>
          <div class="tab">+ 新建</div>
        </div>
        <!-- 代码编辑器：textarea + 等宽字体，加载/错误/空三态 -->
        <div class="code-editor">
          <div v-if="fileContentLoading" class="code-loading">加载文件内容…</div>
          <div v-else-if="fileContentError" class="code-error">
            加载失败：{{ fileContentError.message }}
          </div>
          <textarea
            v-else
            v-model="codeContent"
            class="code-textarea"
            spellcheck="false"
            placeholder="-- 选择左侧文件或直接编写 SQL/Python 代码"
          ></textarea>
        </div>
        <div class="runlog" ref="runlogEl">
          <!-- 三态：loading -->
          <template v-if="runLoading">
            <div class="info">{{ runlog[0]?.text || '[运行中] 封装层接收任务…' }}</div>
          </template>
          <!-- 三态：error -->
          <template v-else-if="runError">
            <div class="info">[错误] {{ runError.message || '运行失败' }}</div>
          </template>
          <!-- 三态：data（成功后渐进式渲染日志） -->
          <template v-else>
            <div v-for="(line, i) in runlog" :key="i" :class="line.cls">{{ line.text }}</div>
          </template>
        </div>
      </div>
      <div class="params">
        <h3 style="font-size: 13px; margin-bottom: 8px">运行参数</h3>
        <label>引擎</label>
        <select v-model="runParams.engine">
          <option value="spark">Spark SQL</option>
          <option value="flink">Flink SQL</option>
          <option value="trino">Trino</option>
          <option value="doris">Doris</option>
        </select>
        <label>CPU / 内存</label>
        <div class="row">
          <input type="number" v-model.number="runParams.cpu" min="1" max="64" style="width: 60px" />
          <span>核</span>
          <input type="number" v-model.number="runParams.memory" min="1" max="256" style="width: 60px" />
          <span>GB</span>
        </div>
        <label>并发度</label>
        <input type="number" v-model.number="runParams.parallelism" min="1" max="100" />
        <label>调度</label>
        <select v-model="runParams.schedule">
          <option value="">手动</option>
          <option value="0 4 * * *">每日 04:00</option>
          <option value="custom">Cron 自定义</option>
        </select>
        <input
          v-if="runParams.schedule === 'custom'"
          v-model="customCron"
          placeholder="如 0 0 * * 1（每周一 0 点）"
          style="margin-top: 4px"
        />
        <button class="btn" style="width: 100%; margin-top: 12px" :disabled="runLoading || !canRun" @click="handleRunJob">
          <svg class="play" viewBox="0 0 24 24"><path d="M7 5l12 7-12 7Z" /></svg>
          {{ runLoading ? '运行中…' : '运行' }}
        </button>
        <button
          class="btn ghost"
          style="width: 100%; margin-top: 8px"
          :disabled="scheduleLoading || !canSchedule"
          @click="handleSubmitSchedule"
        >
          {{ scheduleLoading ? '提交中…' : '提交调度' }}
        </button>
        <div v-if="scheduleError" class="note" style="color: var(--red)">
          调度提交失败：{{ scheduleError.message }}
        </div>
        <div class="note">资源请求受工作空间 Quota 约束，超额自动排队或扩容。</div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>任务 DAG（按数据分层自动生成）</h3>
      <div v-if="dagLoading" class="dag-loading">解析 DAG…</div>
      <div v-else-if="dagError" class="dag-error">DAG 解析失败：{{ dagError.message }}</div>
      <div v-else-if="dagData" class="dag">
        <template v-for="(node, i) in dagData.nodes" :key="node.id">
          <div class="node" :class="{ act: node.highlight }" :title="`层级: ${node.layer || ''}`">
            {{ node.name }}
          </div>
          <span v-if="i < dagData.nodes.length - 1" class="arrow">→</span>
        </template>
        <div v-if="dagData.nodes.length === 0" class="dag-empty">暂无 DAG 节点</div>
      </div>
      <div v-else class="dag-empty">选择文件后自动解析 DAG</div>
    </div>

    <!-- 调度配置确认弹窗 -->
    <Modal :visible="scheduleConfirmVisible" title="确认提交调度" @close="scheduleConfirmVisible = false">
      <div style="line-height: 1.8">
        <p><strong>文件：</strong>{{ currentFilePath || '（未保存的代码）' }}</p>
        <p><strong>引擎：</strong>{{ runParams.engine }}</p>
        <p><strong>Cron 表达式：</strong>{{ effectiveSchedule || '（手动触发）' }}</p>
        <p style="color: var(--muted); font-size: 12px">提交后将按 cron 定时触发，可在调度运维页管理。</p>
      </div>
      <template #footer>
        <button class="btn ghost" @click="scheduleConfirmVisible = false">取消</button>
        <button class="btn" :disabled="scheduleLoading" @click="confirmSubmitSchedule">
          {{ scheduleLoading ? '提交中…' : '确认提交' }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick, onMounted, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import {
  getFileTree,
  readFile as apiReadFile,
  runJob as apiRunJob,
  submitSchedule,
  getTaskDag,
  type FileNode,
  type RunLogLine,
  type RunResult,
  type TaskDag
} from '@/api/develop'

const store = useAppStore()

interface LogLine {
  cls: string
  text: string
}

const runlog = ref<LogLine[]>([{ cls: 'info', text: '[就绪] 点击「运行」提交至封装层调度…' }])
const runlogEl = ref<HTMLElement | null>(null)

/* ------------------------------ 文件树 ------------------------------ */

// 文件树：通过 useApi 包装 /develop/files 调用
const {
  data: fileTreeData,
  loading: fileTreeLoading,
  error: fileTreeError,
  execute: loadFileTree
} = useApi<FileNode[]>(() => getFileTree(), { initialData: [] })
const fileTree = computed<FileNode[]>(() => fileTreeData.value ?? [])

/* ------------------------------ 文件内容 ------------------------------ */

const currentFilePath = ref<string>('')
const codeContent = ref<string>('')

// 文件内容：通过 useApi 包装 /develop/files/content 调用
const {
  loading: fileContentLoading,
  error: fileContentError,
  execute: loadFileContent
} = useApi<string, [string]>((path: string) => apiReadFile(path), { initialData: '' })

/** 点击文件节点：加载文件内容 */
async function handleFileClick(node: FileNode): Promise<void> {
  if (node.type === 'folder') {
    store.showToast(`文件夹：${node.name}`)
    return
  }
  currentFilePath.value = node.path ?? node.id
  const content = await loadFileContent(currentFilePath.value)
  codeContent.value = content ?? ''
  // 加载 DAG
  await loadDag(currentFilePath.value)
}

/** 关闭当前文件 */
function closeCurrentFile(): void {
  currentFilePath.value = ''
  codeContent.value = ''
}

/* ------------------------------ 运行参数 ------------------------------ */

const runParams = reactive({
  engine: 'spark' as 'spark' | 'flink' | 'trino' | 'doris',
  cpu: 4,
  memory: 16,
  parallelism: 8,
  schedule: ''
})
const customCron = ref<string>('')

/** 实际生效的 cron 表达式 */
const effectiveSchedule = computed<string>(() => {
  if (runParams.schedule === 'custom') return customCron.value
  return runParams.schedule
})

/** 是否可运行：必须有文件路径或代码内容 */
const canRun = computed<boolean>(() => !!currentFilePath.value || !!codeContent.value.trim())
/** 是否可提交调度：必须有文件路径 */
const canSchedule = computed<boolean>(() => !!currentFilePath.value)

/* ------------------------------ 运行作业 ------------------------------ */

/** 将 API 返回的日志级别映射为前端样式类 */
function logLevelToClass(level: RunLogLine['level']): string {
  switch (level) {
    case 'ok':
      return 'ok'
    case 'warn':
      return 'warn'
    case 'error':
      return 'info'
    default:
      return 'info'
  }
}

/** 渐进式渲染运行日志（逐行 push，自动滚动到底部） */
function renderLogs(result: RunResult): void {
  const lines: LogLine[] = result.logs.map((l) => ({
    cls: logLevelToClass(l.level),
    text: l.text
  }))
  runlog.value = []
  let i = 0
  function step(): void {
    if (i >= lines.length) return
    runlog.value.push(lines[i++])
    nextTick(() => {
      if (runlogEl.value) runlogEl.value.scrollTop = runlogEl.value.scrollHeight
    })
    setTimeout(step, 500)
  }
  step()
}

// 运行作业：通过 useApi 包装，自动维护 loading / error / data 三态
const {
  loading: runLoading,
  error: runError,
  execute: executeRunJob
} = useApi<RunResult>(
  () =>
    apiRunJob({
      filePath: currentFilePath.value || 'untitled.sql',
      engine: runParams.engine,
      cpu: runParams.cpu,
      memory: runParams.memory,
      parallelism: runParams.parallelism
    }),
  {
    onSuccess: (result) => {
      renderLogs(result)
      store.showToast(`运行完成：${result.status}`)
    }
  }
)

/** 运行作业（触发 useApi execute） */
async function handleRunJob(): Promise<void> {
  runlog.value = [{ cls: 'info', text: '[提交] 封装层接收任务…' }]
  await executeRunJob()
}

/* ------------------------------ 提交调度 ------------------------------ */

const scheduleConfirmVisible = ref(false)

// 提交调度：通过 useApi 包装，自动维护 loading / error / data 三态
const {
  loading: scheduleLoading,
  error: scheduleError,
  execute: executeSubmitSchedule
} = useApi<void>(
  () =>
    submitSchedule({
      filePath: currentFilePath.value,
      schedule: effectiveSchedule.value || '0 4 * * *',
      engine: runParams.engine
    }),
  {
    onSuccess: () => {
      store.showToast('已提交调度')
      scheduleConfirmVisible.value = false
    }
  }
)

/** 提交调度：打开确认弹窗 */
async function handleSubmitSchedule(): Promise<void> {
  if (!currentFilePath.value) {
    store.showToast('请先选择文件')
    return
  }
  scheduleConfirmVisible.value = true
}

/** 确认提交调度 */
async function confirmSubmitSchedule(): Promise<void> {
  await executeSubmitSchedule()
}

/* ------------------------------ DAG 解析 ------------------------------ */

// 任务 DAG：通过 useApi 包装 /develop/dag 调用
const {
  data: dagData,
  loading: dagLoading,
  error: dagError,
  execute: loadDag
} = useApi<TaskDag, [string]>((filePath: string) => getTaskDag(filePath))

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void loadFileTree()
})

// 切换工作空间时重载文件树
watch(
  () => store.workspace,
  () => {
    void loadFileTree()
  }
)
</script>

<style scoped>
.ide {
  display: grid;
  grid-template-columns: 220px 1fr 200px;
  gap: 12px;
  margin-top: 14px;
}
.tree {
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  padding: 8px;
  min-height: 360px;
  max-height: 480px;
  overflow: auto;
  background: #fafbfc;
}
.tree-loading,
.tree-error,
.tree-empty,
.dag-loading,
.dag-error,
.dag-empty,
.code-loading,
.code-error {
  color: #717a80;
  padding: 12px;
  text-align: center;
}
.tree-error,
.code-error,
.dag-error {
  color: #d14343;
}
.tree-node {
  padding: 4px 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tree-node:hover {
  background: #eef2ff;
}
.tree-node.folder {
  font-weight: 500;
}
.tree-node.active {
  background: #eef2ff;
  color: #4f46e5;
}
.code-wrap {
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  min-height: 360px;
}
.tabs {
  display: flex;
  border-bottom: 1px solid #e4e8ea;
  background: #fafbfc;
  border-radius: 8px 8px 0 0;
}
.tab {
  padding: 6px 12px;
  font-size: 12.5px;
  border-right: 1px solid #e4e8ea;
  cursor: pointer;
}
.tab.on {
  background: #fff;
  font-weight: 500;
}
.tab .x {
  margin-left: 6px;
  color: #9aa3ad;
  cursor: pointer;
}
.code-editor {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 240px;
}
.code-textarea {
  flex: 1;
  border: none;
  outline: none;
  padding: 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  resize: none;
  background: #fff;
}
.params {
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  padding: 12px;
  background: #fafbfc;
}
.params label {
  display: block;
  font-size: 12px;
  color: #717a80;
  margin-top: 8px;
  margin-bottom: 4px;
}
.params select,
.params input {
  width: 100%;
  padding: 4px 6px;
  border: 1px solid #e4e8ea;
  border-radius: 4px;
  font-size: 12.5px;
}
.params .row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.dag {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 0;
}
.dag .node {
  padding: 6px 12px;
  background: #f4f5f7;
  border-radius: 6px;
  font-size: 12.5px;
}
.dag .node.act {
  background: #eef2ff;
  color: #4f46e5;
  cursor: pointer;
}
.dag .arrow {
  color: #9aa3ad;
}
</style>
