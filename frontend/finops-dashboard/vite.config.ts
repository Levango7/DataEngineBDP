import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5174,
    open: true,
    proxy: {
      // 将 /api 请求代理到 FinOps 看板后端
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8085',
        changeOrigin: true
      }
    }
  }
})