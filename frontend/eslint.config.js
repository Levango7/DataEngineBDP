import pluginVue from 'eslint-plugin-vue'
import vueTsEslint from '@vue/eslint-config-typescript'
import prettierPlugin from 'eslint-plugin-prettier'
import prettierConfig from 'eslint-config-prettier'

export default [
  // 全局忽略
  {
    ignores: [
      'dist',
      'node_modules',
      '*.d.ts',
      '.build-verify',
      '.verify-build',
      'src/test-setup.ts'
    ]
  },

  // Vue 基础规则 + Vue parser
  ...pluginVue.configs['flat/recommended'],

  // Vue + TypeScript 规则（自带 vue-eslint-parser 和 ts parser 配置）
  ...vueTsEslint(),

  // Prettier 关闭与 ESLint 冲突的规则
  prettierConfig,

  // Prettier 插件
  {
    plugins: {
      prettier: prettierPlugin
    },
    rules: {
      'prettier/prettier': 'warn'
    }
  },

  // 自定义规则覆盖
  {
    files: ['**/*.{ts,vue}'],
    rules: {
      // Vue 规则
      'vue/multi-word-component-names': 'off',
      'vue/no-v-html': 'warn',
      'vue/require-default-prop': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/require-prop-types': 'off',
      'vue/one-component-per-file': 'off',

      // TypeScript 规则
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/ban-ts-comment': 'off',
      '@typescript-eslint/no-unsafe-function-type': 'off',
      '@typescript-eslint/no-empty-object-type': 'off',

      // 通用规则
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'no-debugger': 'error'
    }
  },

  // 测试文件放宽规则
  {
    files: ['**/__tests__/**', '**/*.test.ts', '**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-unused-vars': 'off',
      'no-console': 'off'
    }
  }
]
