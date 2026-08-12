import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：微调过程监控前端
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5175,
    open: true,
    proxy: {
      // 代理到闭环编排服务
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:18088',
        changeOrigin: true
      },
      // WebSocket 代理
      '/api/v1/loop/tasks': {
        target: process.env.VITE_WS_TARGET || 'ws://localhost:18088',
        ws: true,
        changeOrigin: true
      }
    }
  }
})