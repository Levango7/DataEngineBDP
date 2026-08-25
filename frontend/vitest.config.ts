/**
 * Vitest 独立配置文件
 *
 * 设计说明：
 * - 本文件优先级高于 vite.config.ts 中的 test 字段，专门用于单元测试
 * - environment 使用 jsdom（比 happy-dom 更完整的 DOM 实现，兼容已有测试）
 * - coverage 使用 istanbul（与 v8 互补，支持更精细的分支覆盖）
 * - alias '@' -> src，与 vite.config.ts 保持一致
 * - @vitest/ui 通过 `vitest --ui` 启用（见 package.json 的 test:ui 脚本）
 * - 保留与 vite.config.ts 一致的 setupFiles / include / coverage 范围，避免破坏已有测试
 */
/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import path from 'node:path'
import { existsSync, statSync } from 'node:fs'

const mainSrcDir = fileURLToPath(new URL('./src', import.meta.url))
const finopsSrcDir = fileURLToPath(new URL('./finops-dashboard/src', import.meta.url))

function resolveModuleFile(absWithoutExt: string): string | null {
  const candidates = [
    absWithoutExt,
    `${absWithoutExt}.ts`,
    `${absWithoutExt}.tsx`,
    `${absWithoutExt}.vue`,
    `${absWithoutExt}.js`,
    path.join(absWithoutExt, 'index.ts'),
    path.join(absWithoutExt, 'index.js')
  ]
  for (const candidate of candidates) {
    if (existsSync(candidate) && statSync(candidate).isFile()) {
      return candidate
    }
  }
  return null
}

// finops-dashboard 子应用的 '@/' 在测试中解析到其自身 src（与根应用 '@/' 互不干扰）
const workspaceAwareAlias = [
  {
    find: '@',
    replacement: mainSrcDir,
    async customResolver(
      this: { resolve: (id: string, importer?: string, opts?: object) => Promise<{ id: string } | null> },
      updatedId: string,
      importer: string | undefined
    ) {
      const normalizedImporter = importer ? path.normalize(importer) : ''
      if (normalizedImporter.includes('finops-dashboard')) {
        const rel = path.normalize(updatedId).slice(path.normalize(mainSrcDir).length)
        const resolved = resolveModuleFile(path.join(finopsSrcDir, rel))
        if (resolved) {
          return { id: resolved }
        }
      }
      return this.resolve(updatedId, importer, { skipSelf: true })
    }
  }
]

// https://vitest.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: workspaceAwareAlias
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['src/test-setup.ts'],
    include: [
      'src/**/*.{test,spec}.{ts,tsx}',
      'finops-dashboard/src/**/*.{test,spec}.{ts,tsx}',
      'open-api-dashboard/src/**/*.{test,spec}.{ts,tsx}'
    ],
    coverage: {
      provider: 'istanbul',
      reporter: ['text', 'json', 'html', 'lcov'],
      include: [
        'src/api/client.ts',
        'src/api/tenant.ts',
        'src/api/datasource.ts',
        'src/api/job.ts',
        'src/api/cluster.ts',
        'src/stores/auth.ts',
        'src/stores/tenant.ts',
        'src/composables/useApi.ts',
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