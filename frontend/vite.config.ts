/// <reference types="vitest/config" />
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
    port: 5173,
    open: true,
    proxy: {
      // 后端 API 网关（开发环境默认 encaps-layer :8080，支持本地登录；
      // 生产经 APISIX 网关，用 VITE_API_TARGET 覆盖）
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      },
      // 路径分流：查询类 API 由 observability query-api 提供（组件独立部署）
      '/api/v1/ops': {
        target: process.env.VITE_OPS_TARGET || 'http://localhost:8090',
        changeOrigin: true
      },
      '/api/v1/cluster': {
        target: process.env.VITE_OPS_TARGET || 'http://localhost:8090',
        changeOrigin: true
      },
      // 向量引擎（vector-engine :8086）
      '/api/v1/vector': {
        target: process.env.VITE_VECTOR_TARGET || 'http://localhost:8086',
        changeOrigin: true
      },
      // AI 助手（ai-assistant :18110）
      '/api/v1/ai-assistant': {
        target: process.env.VITE_AI_TARGET || 'http://localhost:18110',
        changeOrigin: true
      },
      // BI 看板（finops-dashboard :8085）
      '/api/v1/dashboards': {
        target: process.env.VITE_BI_TARGET || 'http://localhost:8085',
        changeOrigin: true
      }
    }
  },
  build: {
    // 警告大 chunk（500KB）
    chunkSizeWarningLimit: 500,
    rollupOptions: {
      output: {
        // 手动分包，将大依赖拆分
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'element-vendor': ['element-plus', '@element-plus/icons-vue'],
          'echarts-vendor': ['echarts']
        }
      }
    }
  },
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['src/test-setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html', 'lcov'],
      include: [
        'src/api/client.ts',
        'src/api/tenant.ts',
        'src/api/datasource.ts',
        'src/api/job.ts',
        'src/api/cluster.ts',
        'src/stores/auth.ts',
        'src/stores/tenant.ts',
        'src/views/TenantManagement.vue',
        'src/views/ClusterOverview.vue',
        'src/views/DataSourceManagement.vue',
        'src/views/JobManagement.vue'
      ],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.test.ts',
        'src/**/*.spec.ts',
        'src/**/__tests__/**',
        'src/main.ts',
        'src/env.d.ts'
      ],
      thresholds: {
        lines: 50,
        functions: 50,
        branches: 50,
        statements: 50
      }
    }
  }
})