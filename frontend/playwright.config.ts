/**
 * Playwright 端到端测试配置
 *
 * 约定：
 * - baseURL: http://127.0.0.1:5173 （Vite dev server，IPv4）
 * - 后端 API: http://127.0.0.1:18080 （encaps-layer，由 tests/integration/docker-compose.yml 提供）。
 *   历史注释误写 18086——该端口现为 evaluation 模型评测服务，无 /auth/login
 *   端点，曾导致全部 UI 登录用例超时（nightly E2E 失败根因）。
 * - 测试目录: tests/e2e/
 * - 失败时截图 + trace
 * - HTML 报告输出到 tests/e2e-report/
 */
import { defineConfig, devices } from '@playwright/test'

/** 后端 API 基址（默认 encaps-layer 集成栈 18080，可用 VITE_API_TARGET 覆盖） */
const API_TARGET = process.env.VITE_API_TARGET || 'http://127.0.0.1:18080'

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
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    extraHTTPHeaders: {
      'Accept': 'application/json'
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
   * 后端栈由 tests/integration/docker-compose.yml 提供（encaps-layer → 18080）。
   * 通过环境变量将 Vite proxy 所有 target 指向 encaps-layer（18080），
   * 使 stub Controller（ProjectController/SearchController 等）统一处理请求，
   * 避免 proxy 默认指向 8081/8083 等容器内端口（宿主机未映射 → 连接拒绝）。
   */
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120_000,
    env: {
      VITE_API_TARGET: API_TARGET,
      VITE_ENCAPS_TENANT_TARGET: API_TARGET,
      VITE_ENCAPS_DATA_TARGET: API_TARGET,
      VITE_ENCAPS_GATEWAY_TARGET: API_TARGET
    }
  }
})