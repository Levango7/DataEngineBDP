/**
 * Playwright 端到端测试配置
 *
 * 约定：
 * - baseURL: http://127.0.0.1:5173 （Vite dev server，IPv4）
 * - 后端 API 栈：默认指向 tests/integration/docker-compose.yml 的宿主机映射端口
 *   （18080-18096）。nightly-e2e.yml 拉起 compose 后，vite proxy 经下列环境变量
 *   分流到各服务；本地跑单个服务时可用环境变量覆盖为直连端口（如 8080）。
 * - 测试目录: tests/e2e/
 * - 失败时截图 + trace
 * - HTML 报告输出到 tests/e2e-report/
 */
import { defineConfig, devices } from '@playwright/test'

/** 后端各服务的代理目标（宿主机端口 = docker-compose 映射；容器内端口不同）
 *  栈外服务（encaps-data/gateway/vector/ai/stream-batch 等）统一指向 encaps-layer
 *  18080 兜底——其 /api 兜底代理转发一切未细分前缀，stub Controller 提供契约响应 */
const stack = {
  api: process.env.VITE_API_TARGET || 'http://127.0.0.1:18080',
  encapsTenant: process.env.VITE_ENCAPS_TENANT_TARGET || 'http://127.0.0.1:18080',
  encapsData: process.env.VITE_ENCAPS_DATA_TARGET || 'http://127.0.0.1:18080',
  encapsGateway: process.env.VITE_ENCAPS_GATEWAY_TARGET || 'http://127.0.0.1:18080',
  assetExchange: process.env.VITE_ASSET_EXCHANGE_TARGET || 'http://127.0.0.1:18094',
  businessPortal: process.env.VITE_BUSINESS_PORTAL_TARGET || 'http://127.0.0.1:18093',
  apiCatalog: process.env.VITE_API_CATALOG_TARGET || 'http://127.0.0.1:18095',
  templates: process.env.VITE_TEMPLATES_TARGET || 'http://127.0.0.1:18096',
  ruleEngine: process.env.VITE_RULE_ENGINE_TARGET || 'http://127.0.0.1:18083',
  sqlGateway: process.env.VITE_SQL_GATEWAY_TARGET || 'http://127.0.0.1:18081',
  bi: process.env.VITE_BI_TARGET || 'http://127.0.0.1:18087',
  ops: process.env.VITE_OPS_TARGET || 'http://127.0.0.1:18080',
  vector: process.env.VITE_VECTOR_TARGET || 'http://127.0.0.1:18080',
  ai: process.env.VITE_AI_TARGET || 'http://127.0.0.1:18080',
  models: process.env.VITE_MODELS_TARGET || 'http://127.0.0.1:18080',
  registry: process.env.VITE_REGISTRY_TARGET || 'http://127.0.0.1:18089',
  streamBatch: process.env.VITE_STREAM_BATCH_TARGET || 'http://127.0.0.1:18080'
}

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: /.*\.spec\.ts/,

  /* 并发与超时 */
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },

  /* 报告 */
  reporter: [
    ['html', { outputFolder: 'tests/e2e-report', open: 'never' }],
    ['list']
  ],

  /* 全局配置 */
  use: {
    baseURL: 'http://127.0.0.1:5173',
    /* 强制中文 locale：i18n 框架根据 navigator.language 检测语言，
     * CI runner 默认英文环境 → app 显示英文 → E2E 断言期望中文文本失败。
     * 设为 zh-CN 确保 app 始终显示中文，与测试断言一致。 */
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    extraHTTPHeaders: {
      'Accept': 'application/json',
      'Accept-Language': 'zh-CN,zh;q=0.9'
    }
  },

  /* 浏览器项目 */
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ],

  /* 自动启动 Vite dev server（端口 5173）
   * 后端栈由 tests/integration/docker-compose.yml 外部拉起（nightly-e2e.yml 负责）；
   * 此处注入全套 VITE_*_TARGET 环境变量，使 vite proxy 各前缀分流到 compose 宿主机端口
   */
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120_000,
    env: {
      VITE_API_TARGET: stack.api,
      VITE_ENCAPS_TENANT_TARGET: stack.encapsTenant,
      VITE_ENCAPS_DATA_TARGET: stack.encapsData,
      VITE_ENCAPS_GATEWAY_TARGET: stack.encapsGateway,
      VITE_ASSET_EXCHANGE_TARGET: stack.assetExchange,
      VITE_BUSINESS_PORTAL_TARGET: stack.businessPortal,
      VITE_API_CATALOG_TARGET: stack.apiCatalog,
      VITE_TEMPLATES_TARGET: stack.templates,
      VITE_RULE_ENGINE_TARGET: stack.ruleEngine,
      VITE_SQL_GATEWAY_TARGET: stack.sqlGateway,
      VITE_BI_TARGET: stack.bi,
      VITE_OPS_TARGET: stack.ops,
      VITE_VECTOR_TARGET: stack.vector,
      VITE_AI_TARGET: stack.ai,
      VITE_MODELS_TARGET: stack.models,
      VITE_REGISTRY_TARGET: stack.registry,
      VITE_STREAM_BATCH_TARGET: stack.streamBatch
    }
  }
})
