import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 路由标题的 i18n key（用于 document.title 设置） */
    titleKey?: string
    /** 路由图标名称 */
    icon?: string
    /** 是否为公开页面（无需鉴权） */
    public?: boolean
    /** 旧版标题（已废弃，使用 titleKey 替代） */
    title?: string
  }
}

export {}