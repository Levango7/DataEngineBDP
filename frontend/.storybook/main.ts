import type { StorybookConfig } from '@storybook/vue3-vite'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL('../package.json', import.meta.url)), 'utf-8')
)

const config: StorybookConfig = {
  stories: ['../src/components/ui/**/*.stories.@(ts|tsx)'],
  addons: [
    '@storybook/addon-essentials',
    '@storybook/addon-interactions',
    '@storybook/blocks'
  ],
  framework: {
    name: '@storybook/vue3-vite',
    options: {}
  },
  viteFinal: async (config) => {
    // 复制主应用的 @ alias
    config.resolve = config.resolve || {}
    config.resolve.alias = {
      ...config.resolve.alias,
      '@': fileURLToPath(new URL('../src', import.meta.url))
    }

    // 复制主应用的 define 常量，避免组件引用 __APP_VERSION__ / __APP_ENV__ 时报错
    config.define = {
      ...config.define,
      __APP_VERSION__: JSON.stringify(pkg.version),
      __APP_ENV__: JSON.stringify(process.env.VITE_APP_ENV ?? 'dev')
    }

    return config
  }
}

export default config
