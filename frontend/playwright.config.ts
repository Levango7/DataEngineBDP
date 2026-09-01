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
   * 后端 18086 由外部脚本启动（避免 Playwright 重复拉起 JVM）
   * 通过环境变量 VITE_API_TARGET 指向 18086，使 Vite proxy 转发正确
   */
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120_000,
    env: {
      VITE_API_TARGET: API_TARGET
    }
  }
})