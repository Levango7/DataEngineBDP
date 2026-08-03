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
          <div v-for="(line, i) in runlog" :key="i" :class="line.cls">{{ line.text }}</div>
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
        <button class="btn" style="width: 100%; margin-top: 12px" @click="runJob">
          <svg class="play" viewBox="0 0 24 24"><path d="M7 5l12 7-12 7Z" /></svg> 运行
        </button>
        <button class="btn ghost" style="width: 100%; margin-top: 8px" @click="store.showToast('已提交调度')">提交调度</button>
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

const store = useAppStore()

interface LogLine {
  cls: string
  text: string
}

const runlog = ref<LogLine[]>([{ cls: 'info', text: '[就绪] 点击「运行」提交至封装层调度…' }])
const runlogEl = ref<HTMLElement | null>(null)

function runJob() {
  const lines: LogLine[] = [
    { cls: 'info', text: '[提交] 封装层接收任务 → 翻译为 SparkApplication CR' },
    { cls: 'info', text: '[调度] Operator 拉起 Pod（客户不可见）' },
    { cls: 'ok', text: '[运行] Stage 1/3 扫描 12M 行' },
    { cls: 'ok', text: '[运行] Stage 2/3 JOIN dim.user' },
    { cls: 'warn', text: '[资源] 内存峰值 14.2G < 上限' },
    { cls: 'ok', text: '[完成] 写入 dwd.order_wide，耗时 4m12s' }
  ]
  runlog.value = []
  let i = 0
  function step() {
    if (i >= lines.length) return
    runlog.value.push(lines[i++])
    nextTick(() => {
      if (runlogEl.value) runlogEl.value.scrollTop = runlogEl.value.scrollHeight
    })
    setTimeout(step, 500)
  }
  step()
}
</script>