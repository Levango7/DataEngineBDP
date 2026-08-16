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
      <div class="tree">
        <div><svg class="fic"><use href="#i-folder" /></svg> 交易域</div>
        <div style="padding-left: 12px">▾ ods</div>
        <div style="padding-left: 24px">orders.sql</div>
        <div style="padding-left: 24px">pay.sql</div>
        <div style="padding-left: 12px">▾ dwd</div>
        <div style="padding-left: 24px; background: #eef2ff; border-radius: 5px">order_wide.sql</div>
        <div style="padding-left: 24px">user_city.sql</div>
        <div><svg class="fic"><use href="#i-folder" /></svg> 营销域</div>
        <div style="padding-left: 12px">tag_calc.py</div>
        <div><svg class="fic"><use href="#i-folder" /></svg> 调度</div>
        <div style="padding-left: 12px">daily_dag.dag</div>
      </div>
      <div class="code-wrap">
        <div class="tabs">
          <div class="tab on">order_wide.sql <span class="x">×</span></div>
          <div class="tab">user_city.sql <span class="x">×</span></div>
          <div class="tab">+ 新建</div>
        </div>
        <div class="code"><span class="c">-- 订单宽表（湖仓集一体：写 Iceberg，物化至 Doris 在线服务）</span>
<span class="k">INSERT</span> <span class="k">OVERWRITE</span> dwd.order_wide
<span class="k">SELECT</span> o.order_id, o.user_id, u.city, p.amount
<span class="k">FROM</span> ods.orders o
<span class="k">JOIN</span> dim.user u <span class="k">ON</span> o.user_id = u.user_id
<span class="k">JOIN</span> dws.pay p <span class="k">ON</span> o.order_id = p.order_id
<span class="k">WHERE</span> o.dt = <span class="s">'${bizdate}'</span>;</div>
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
        <select><option>Spark SQL</option><option>Flink SQL</option><option>Trino</option></select>
        <label>CPU / 内存</label>
        <div class="row"><span>4 核</span><span>16 GB</span></div>
        <label>并发度</label><input value="8" />
        <label>调度</label>
        <select><option>手动</option><option>每日 04:00</option><option>Cron</option></select>
        <button class="btn" style="width: 100%; margin-top: 12px" :disabled="runLoading" @click="handleRunJob">
          <svg class="play" viewBox="0 0 24 24"><path d="M7 5l12 7-12 7Z" /></svg>
          {{ runLoading ? '运行中…' : '运行' }}
        </button>
        <button class="btn ghost" style="width: 100%; margin-top: 8px" :disabled="scheduleLoading" @click="handleSubmitSchedule">
          {{ scheduleLoading ? '提交中…' : '提交调度' }}
        </button>
        <div v-if="scheduleError" class="note" style="color: var(--red)">
          调度提交失败：{{ scheduleError.message }}
        </div>
        <div class="note">资源请求受工作空间 Quota 约束，超额自动排队或扩容。</div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>任务 DAG（自动生成）</h3>
      <div class="dag">
        <div class="node">ods.orders</div><span class="arrow">→</span>
        <div class="node">ods.pay</div><span class="arrow">→</span>
        <div class="node act" @click="store.showToast('高亮节点：dwd.order_wide')">dwd.order_wide</div>
        <span class="arrow">→</span>
        <div class="node">ADS 报表</div><span class="arrow">→</span>
        <div class="node">Doris 在线</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { runJob as apiRunJob, submitSchedule, type RunLogLine, type RunResult } from '@/api/develop'

const store = useAppStore()

interface LogLine {
  cls: string
  text: string
}

const runlog = ref<LogLine[]>([{ cls: 'info', text: '[就绪] 点击「运行」提交至封装层调度…' }])
const runlogEl = ref<HTMLElement | null>(null)

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
      filePath: 'dwd/order_wide.sql',
      engine: 'spark',
      cpu: 4,
      memory: 16,
      parallelism: 8
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

// 提交调度：通过 useApi 包装，自动维护 loading / error / data 三态
const {
  loading: scheduleLoading,
  error: scheduleError,
  execute: executeSubmitSchedule
} = useApi<void>(
  () =>
    submitSchedule({
      filePath: 'dwd/order_wide.sql',
      schedule: '0 4 * * *',
      engine: 'spark'
    }),
  {
    onSuccess: () => {
      store.showToast('已提交调度')
    }
  }
)

/** 提交调度（触发 useApi execute） */
async function handleSubmitSchedule(): Promise<void> {
  await executeSubmitSchedule()
  // 错误提示已由拦截器统一处理，scheduleError 用于模板展示
}
</script>
