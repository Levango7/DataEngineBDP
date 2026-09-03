/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'
// https://vitejs.dev/config/
const pkg = JSON.parse(readFileSync(fileURLToPath(new URL('./package.json', import.meta.url)), 'utf-8'))

// Sprint 1.3：版本号从 package.json 单一来源读取，环境从 VITE_APP_ENV 注入（默认 dev）
export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __APP_ENV__: JSON.stringify(process.env.VITE_APP_ENV ?? 'dev')
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // 强制绑定 IPv4（Windows 上 localhost 默认解析为 ::1，Playwright 用 127.0.0.1 探测会失败）
    host: '127.0.0.1',
    proxy: {
      // 流批作业（stream-batch-scheduler JobController /api/v1/jobs :18086）
      '/api/v1/jobs': {
        target: process.env.VITE_STREAM_BATCH_TARGET || 'http://127.0.0.1:18086',
        changeOrigin: true
      },
      // 元数据采集（metadata-collector :8084）
      '/api/v1/metadata': {
        target: process.env.VITE_METADATA_TARGET || 'http://127.0.0.1:8084',
        changeOrigin: true
      },
      // 标签引擎（tag-engine :8080）：标签/画像/受众——注意与 encaps-layer 同默认端口，
      // 本地同机双跑时需环境变量错开（VITE_TAG_ENGINE_TARGET / VITE_API_TARGET）
      '/api/v1/tags': {
        target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/api/v1/profiles': {
        target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/api/v1/audiences': {
        target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      // 行业模板（industry-templates Python :8091）
      '/api/v1/templates': {
        target: process.env.VITE_TEMPLATES_TARGET || 'http://127.0.0.1:8091',
        changeOrigin: true
      },
      // 业务线门户（business-portal Python :8088）
      '/api/v1/business-lines': {
        target: process.env.VITE_BUSINESS_PORTAL_TARGET || 'http://127.0.0.1:8088',
        changeOrigin: true
      },
      // 开放 API 目录（open-api-catalog Python :8090）
      // 注意：8090 与 observability query-api 默认端口相同——本地同机同时跑两服务时
      // 需通过环境变量错开（VITE_API_CATALOG_TARGET / VITE_OPS_TARGET）；
      // nightly 集成栈中 open-api-catalog 映射 18095，query-api 不入栈
      '/api/v1/apis': {
        target: process.env.VITE_API_CATALOG_TARGET || 'http://127.0.0.1:8090',
        changeOrigin: true
      },
      // 虚拟表（sql-gateway VirtualTableController :8081）
      '/api/v1/virtual-tables': {
        target: process.env.VITE_SQL_GATEWAY_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      // 物化视图（flink-cdc MaterializedViewController）
      // 注意：前端 engine.ts 使用 baseURL:'/api' 调用 /materialized-views，故路径无 /v1
      // 端口待定：flink-cdc 为库形态（无独立进程），Controller 挂载宿主未定——
      // Sprint 2.2 前契约已匹配（stream-batch 编排），暂保留 8098 占位待宿主确认
      '/api/materialized-views': {
        target: process.env.VITE_FLINK_CDC_TARGET || 'http://127.0.0.1:8098',
        changeOrigin: true
      },
      // 基础设施编排（infra-orchestrator :8085——注意与 finops-dashboard 同默认端口）
      '/api/v1/clusters': {
        target: process.env.VITE_INFRA_ORCHESTRATOR_TARGET || 'http://127.0.0.1:8085',
        changeOrigin: true
      },
      // 编排可视化（rule-engine OrchestratorController :8083——rule-engine 实际端口）
      '/api/v1/orchestrator': {
        target: process.env.VITE_RULE_ENGINE_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      // 数据资产治理（encaps-layer AssetController :8080）
      // governance.ts 使用 /governance/assets 路径，避免与 assetMarket.ts 的 /assets 冲突
      '/api/v1/governance': {
        target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      },

      // ============================================================
      // 以下为原有 proxy 条目中的细粒度代理（更具体路径优先匹配）
      // 必须放在通用 /api 条目之前，否则会被 /api 默认转发到 encaps-layer
      // ============================================================

      // 路径分流：查询类 API 由 observability query-api 提供（组件独立部署）
      '/api/v1/ops': {
        target: process.env.VITE_OPS_TARGET || 'http://127.0.0.1:8090',
        changeOrigin: true
      },
      '/api/v1/cluster': {
        target: process.env.VITE_OPS_TARGET || 'http://127.0.0.1:8090',
        changeOrigin: true
      },
      // 向量引擎（vector-engine :8086）
      '/api/v1/vector': {
        target: process.env.VITE_VECTOR_TARGET || 'http://127.0.0.1:8086',
        changeOrigin: true
      },
      // AI 助手（ai-assistant :18110）
      '/api/v1/ai-assistant': {
        target: process.env.VITE_AI_TARGET || 'http://127.0.0.1:18110',
        changeOrigin: true
      },
      // BI 看板（finops-dashboard :8085）
      '/api/v1/dashboards': {
        target: process.env.VITE_BI_TARGET || 'http://127.0.0.1:8085',
        changeOrigin: true
      },
      // stream-batch-scheduler（流批调度服务 :18086）
      '/api/v1/stream-batch': {
        target: process.env.VITE_STREAM_BATCH_TARGET || 'http://127.0.0.1:18086',
        changeOrigin: true
      },
      // sql-gateway（SQL 网关服务 :8081）
      '/api/v1/sql': {
        target: process.env.VITE_SQL_GATEWAY_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      // governance/lineage-analyzer（血缘分析服务 :8086——注意与 vector-engine 同默认端口）
      '/lineage': {
        target: process.env.VITE_LINEAGE_TARGET || 'http://127.0.0.1:8086',
        changeOrigin: true
      },
      // rule-engine（规则引擎服务 :8083——rule-engine 实际端口）
      '/api/v1/quality': {
        target: process.env.VITE_RULE_ENGINE_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      // 资产流通市场（asset-exchange Python :8087）
      // 注意：real-time-pipeline(:8092) 实际用 /api/v1/governance 前缀（GovernanceController），
      // 不占 /api/v1/assets；/api/v1/assets 由 asset-exchange 的 FastAPI assets router 提供
      '/api/v1/assets': {
        target: process.env.VITE_ASSET_EXCHANGE_TARGET || 'http://127.0.0.1:8087',
        changeOrigin: true
      },
      // API 订阅（open-api-catalog subscriptions_router，前缀 /subscriptions）
      // Sprint 4.2：此前被 asset-exchange 抢占导致 APIMarket 页订阅审批错路由，
      // 归还给 open-api-catalog（detail design 契约路径 /subscriptions/{id}/approve）
      '/api/v1/subscriptions': {
        target: process.env.VITE_API_CATALOG_TARGET || 'http://127.0.0.1:8090',
        changeOrigin: true
      },
      // 资产订阅（asset-exchange subscriptions router，Sprint 4.2 独立前缀）
      '/api/v1/asset-subscriptions': {
        target: process.env.VITE_ASSET_EXCHANGE_TARGET || 'http://127.0.0.1:8087',
        changeOrigin: true
      },
      // 机器学习模型管理（dev-ml.ts 的 /models/models，Python ml-platform/llmops 域）
      // 注意：默认端口 8080 与 encaps-layer/tag-engine 同——本地同机双跑需 VITE_MODELS_TARGET 错开；
      // nightly 栈无此服务，playwright 兜底指向 encaps-layer 18080
      '/api/v1/models': {
        target: process.env.VITE_MODELS_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      // 模型仓库（dev-ml.ts 的 /registry/deployments，Python registry 服务 :18089）
      // Sprint 3.2 补：3.1 的契约扫描器 bug 修复后该前缀被正确扫出，代理此前缺失
      '/api/v1/registry': {
        target: process.env.VITE_REGISTRY_TARGET || 'http://127.0.0.1:18089',
        changeOrigin: true
      },

      // 封装层租户域（encaps-tenant Java :8081，独立进程）
      // Sprint 2.2 L3 补全：此前缺失，前端请求落到 /api 兜底（encaps-layer :8080）→ 404
      // 覆盖：project.ts /account 前缀们、workspace.ts、quota.ts、account.ts、admin.ts
      '/api/v1/account': {
        target: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      '/api/v1/admin': {
        target: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      '/api/v1/projects': {
        target: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      '/api/v1/quotas': {
        target: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      '/api/v1/workspaces': {
        target: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true
      },
      // 封装层数据域（encaps-data Java :8083，独立进程）
      // Sprint 2.2 L3 补全：datasource.ts /search 与 search.ts、engine.ts
      // 引擎监控（flink/doris/kafka/iotdb）调用此前全部缺失
      '/api/v1/datasources': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      '/api/v1/search': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      '/api/v1/flink': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      '/api/v1/doris': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      '/api/v1/kafka': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      '/api/v1/iotdb': {
        target: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:8083',
        changeOrigin: true
      },
      // 封装层网关域（encaps-gateway Java :8082，独立进程）
      // Sprint 2.2 L3 补全：gateway.ts 调用
      '/api/v1/gateway': {
        target: process.env.VITE_ENCAPS_GATEWAY_TARGET || 'http://127.0.0.1:8082',
        changeOrigin: true
      },

      // ============================================================
      // 通用 /api 代理（兜底，必须放在所有细粒度代理之后）
      // 后端 API 网关（开发环境默认 encaps-layer :8080，支持本地登录；
      // 生产经 APISIX 网关，用 VITE_API_TARGET 覆盖）
      // ============================================================
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8080',
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
