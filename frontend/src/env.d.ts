/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// Sprint 1.3：vite.config.ts 通过 define 注入的全局常量（版本号从 package.json 单一来源）
declare const __APP_VERSION__: string
declare const __APP_ENV__: string
