<template>
  <div>
    <h1>知识工程</h1>
    <div class="sub">L4.5 · 文档入库 → 切片 → 向量化 → RAG 策略配置，构建企业级知识底座。</div>
    <div class="toolbar">
      <button class="btn sm" @click="store.showToast('已触发文档解析（mock）')">⬆ 上传文档</button>
      <div class="spacer"></div>
      <span class="pill c">RAG 已启用</span>
    </div>
    <div class="card">
      <table>
        <tr><th>知识库</th><th>文档数</th><th>切片策略</th><th>检索</th><th>状态</th></tr>
        <tr><td>商品知识库</td><td>1,204</td><td>按段落 512</td><td>混合(向量+关键词)</td><td><span class="pill g">就绪</span></td></tr>
        <tr><td>制度文档库</td><td>356</td><td>按标题 256</td><td>向量</td><td><span class="pill g">就绪</span></td></tr>
        <tr><td>客服话术库</td><td>89</td><td>按轮次</td><td>向量</td><td><span class="pill a">构建中</span></td></tr>
      </table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>RAG 策略</h3>
      <div class="kv"><span>检索 TopK</span><span>5</span></div>
      <div class="kv"><span>分数阈值</span><span>0.78</span></div>
      <div class="kv"><span>重排模型</span><span>bge-reranker（自托管）</span></div>
      <div class="kv"><span>引用溯源</span><span>开启</span></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app'
const store = useAppStore()
</script>