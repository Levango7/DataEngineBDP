<template>
  <div>
    <h1>统一 SQL 查询</h1>
    <div class="sub">
      单一入口跨 Doris / Trino / Iceberg 联邦查询，Trino 作为统一 SQL 网关，底层引擎对客户透明。
    </div>
    <div class="ide" style="grid-template-columns: 1fr 240px">
      <div class="code-wrap">
        <div class="tabs">
          <div class="tab on">联邦查询</div>
          <div class="tab">+ 新查询</div>
        </div>
        <div class="code"><span class="c">-- 跨引擎联邦：Iceberg 明细 + Doris 维表</span>
<span class="k">SELECT</span> u.city, <span class="k">SUM</span>(p.amount) gmv
<span class="k">FROM</span> iceberg.ods.orders o
<span class="k">JOIN</span> doris.dim.user u <span class="k">ON</span> o.user_id = u.user_id
<span class="k">GROUP BY</span> u.city <span class="k">ORDER BY</span> gmv <span class="k">DESC</span>;</div>
        <div class="runlog" ref="sqllogEl">
          <div v-for="(line, i) in sqllog" :key="i" :class="line.cls">{{ line.text }}</div>
        </div>
      </div>
      <div class="params">
        <h3 style="font-size: 13px">查询配置</h3>
        <label>路由引擎</label>
        <select><option>网关自动</option><option>Trino</option><option>Doris</option></select>
        <label>超时(s)</label><input value="120" />
        <div class="chips" style="margin-top: 10px">
          <span class="chip on">iceberg</span>
          <span class="chip on">doris</span>
          <span class="chip">kafka</span>
        </div>
        <label>AI 辅助（自然语言 → SQL）</label>
        <div style="display: flex; gap: 6px">
          <input placeholder="如：各城市 GMV 排名" style="flex: 1" />
          <button class="btn ghost sm" @click="store.showToast('已生成 SQL（mock）')">生成</button>
        </div>
        <button class="btn" style="width: 100%; margin-top: 12px" @click="runSql">
          <svg class="play" viewBox="0 0 24 24"><path d="M7 5l12 7-12 7Z" /></svg> 执行
        </button>
        <div class="note">网关自动选择最优引擎并下推，客户只见结果。</div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>结果预览</h3>
      <table>
        <tr><th>city</th><th>gmv</th></tr>
        <tr><td>上海</td><td>4.82亿</td></tr>
        <tr><td>北京</td><td>4.31亿</td></tr>
        <tr><td>深圳</td><td>3.77亿</td></tr>
        <tr><td>成都</td><td>2.95亿</td></tr>
      </table>
      <div class="note">实际执行后由网关回填结果集与耗时/扫描量指标。</div>
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

const sqllog = ref<LogLine[]>([{ cls: 'info', text: '[就绪] 点击「执行」经统一 SQL 网关路由…' }])
const sqllogEl = ref<HTMLElement | null>(null)

function runSql() {
  const lines: LogLine[] = [
    { cls: 'info', text: '[网关] 解析联邦查询 → 路由 Trino + Doris' },
    { cls: 'info', text: '[下推] iceberg.ods.orders 扫描 12M 行' },
    { cls: 'ok', text: '[关联] doris.dim.user 维表 JOIN' },
    { cls: 'ok', text: '[返回] 4 行 · 扫描 14M · 耗时 1.8s' }
  ]
  sqllog.value = []
  let i = 0
  function step() {
    if (i >= lines.length) return
    sqllog.value.push(lines[i++])
    nextTick(() => {
      if (sqllogEl.value) sqllogEl.value.scrollTop = sqllogEl.value.scrollHeight
    })
    setTimeout(step, 500)
  }
  step()
}
</script>