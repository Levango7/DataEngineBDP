<template>
  <div>
    <h1>运营后台（平台侧）</h1>
    <div class="sub">仅平台运维可见；租户、计量、底座运维与多环境管理。</div>
    <div class="grid g4">
      <div class="card"><h3>租户总数</h3><div class="kpi">27</div><div class="meta">外部 19 · 内部 8</div></div>
      <div class="card"><h3>集群实例</h3><div class="kpi">34</div><div class="meta">信创 12 · 本地 9 · 云VM 13</div></div>
      <div class="card"><h3>本月营收</h3><div class="kpi s">¥ 4.2M</div></div>
      <div class="card"><h3>底座告警</h3><div class="kpi s">2</div><div class="meta">已自动处置 2</div></div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>环境矩阵</h3>
      <table>
        <tr><th>环境</th><th>Namespace</th><th>节点</th><th>控制面</th><th>状态</th></tr>
        <tr><td>信创-华东</td><td>19</td><td>64</td><td>HA 3</td><td><span class="pill g">健康</span></td></tr>
        <tr><td>本地-华北</td><td>11</td><td>40</td><td>HA 3</td><td><span class="pill g">健康</span></td></tr>
        <tr><td>云VM-华南</td><td>14</td><td>52</td><td>HA 3</td><td><span class="pill a">扩容中</span></td></tr>
      </table>
      <div class="note">
        此处为平台运维视图，印证「自研 SKE 发行版封装层」将底层复杂度对客屏蔽；客户控制台仅见工作空间/项目/配额。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 静态展示页
</script>