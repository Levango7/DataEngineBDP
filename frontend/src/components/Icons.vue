<template>
  <!-- 全局 SVG symbol 定义，挂在 body 上一次即可 -->
  <svg width="0" height="0" style="position: absolute" aria-hidden="true">
    <symbol id="i-dash" viewBox="0 0 24 24">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </symbol>
    <symbol id="i-ws" viewBox="0 0 24 24">
      <path d="M12 3 21 8 12 13 3 8Z" />
      <path d="M3 13 12 18 21 13" />
    </symbol>
    <symbol id="i-proj" viewBox="0 0 24 24">
      <path d="M3 7 12 3 21 7 12 11Z" />
      <path d="M3 7V17L12 21V11" />
      <path d="M21 7V17L12 21" />
    </symbol>
    <symbol id="i-integrate" viewBox="0 0 24 24">
      <path d="M4 8h12l-3-3" />
      <path d="M20 16H8l3 3" />
    </symbol>
    <symbol id="i-develop" viewBox="0 0 24 24">
      <path d="M9 8 5 12l4 4" />
      <path d="M15 8l4 4-4 4" />
    </symbol>
    <symbol id="i-sql" viewBox="0 0 24 24">
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M7 9l3 3-3 3" />
      <path d="M13 15h4" />
    </symbol>
    <symbol id="i-govern" viewBox="0 0 24 24">
      <rect x="4" y="4" width="16" height="16" rx="2" />
      <path d="M8 9h8M8 13h8M8 17h5" />
    </symbol>
    <symbol id="i-standard" viewBox="0 0 24 24">
      <path d="M4 8h16" />
      <circle cx="9" cy="8" r="2" />
      <path d="M4 16h16" />
      <circle cx="15" cy="16" r="2" />
    </symbol>
    <symbol id="i-quality" viewBox="0 0 24 24">
      <path d="M12 3 20 6V11C20 16 16 19 12 21 8 19 4 16 4 11V6Z" />
      <path d="M9 12l2 2 4-4" />
    </symbol>
    <symbol id="i-lineage" viewBox="0 0 24 24">
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="18" cy="6" r="2.5" />
      <circle cx="12" cy="18" r="2.5" />
      <path d="M6 8.5 12 15.5M18 8.5 12 15.5" />
    </symbol>
    <symbol id="i-sec" viewBox="0 0 24 24">
      <rect x="5" y="11" width="14" height="9" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </symbol>
    <symbol id="i-vector" viewBox="0 0 24 24">
      <circle cx="6" cy="6" r="1.4" />
      <circle cx="12" cy="6" r="1.4" />
      <circle cx="18" cy="6" r="1.4" />
      <circle cx="6" cy="12" r="1.4" />
      <circle cx="12" cy="12" r="1.4" />
      <circle cx="18" cy="12" r="1.4" />
      <circle cx="6" cy="18" r="1.4" />
      <circle cx="12" cy="18" r="1.4" />
      <circle cx="18" cy="18" r="1.4" />
    </symbol>
    <symbol id="i-kb" viewBox="0 0 24 24">
      <rect x="4" y="4" width="16" height="16" rx="2" />
      <path d="M12 4v16" />
    </symbol>
    <symbol id="i-llmops" viewBox="0 0 24 24">
      <rect x="6" y="6" width="12" height="12" rx="2" />
      <path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3" />
    </symbol>
    <symbol id="i-gateway" viewBox="0 0 24 24">
      <circle cx="6" cy="18" r="2.5" />
      <circle cx="18" cy="6" r="2.5" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M6 15.5 10.5 13M18 8.5 13.5 10.5" />
    </symbol>
    <symbol id="i-analyze" viewBox="0 0 24 24">
      <path d="M4 20h16" />
      <rect x="6" y="11" width="3" height="9" />
      <rect x="11" y="7" width="3" height="13" />
      <rect x="16" y="13" width="3" height="7" />
    </symbol>
    <symbol id="i-ops" viewBox="0 0 24 24">
      <path d="M3 12h4l2-6 4 12 2-6h6" />
    </symbol>
    <symbol id="i-account" viewBox="0 0 24 24">
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <circle cx="8" cy="11" r="2" />
      <path d="M13 9h5M13 14h5" />
    </symbol>
    <symbol id="i-admin" viewBox="0 0 24 24">
      <rect x="4" y="4" width="16" height="7" rx="1.5" />
      <rect x="4" y="13" width="16" height="7" rx="1.5" />
      <circle cx="8" cy="7.5" r="1" />
      <circle cx="8" cy="16.5" r="1" />
    </symbol>
    <symbol id="i-folder" viewBox="0 0 24 24">
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
    </symbol>
  </svg>
</template>

<script setup lang="ts">
// 仅承载 SVG symbol 定义，无逻辑
</script>
