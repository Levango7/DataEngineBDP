import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 路由标题的 i18n key（用于 document.title 设置） */
    titleKey?: string
    /** 路由图标名称 */
    icon?: string
    /** 是否为公开页面（无需鉴权） */
    public?: boolean
    /**
     * 允许访问的角色（与后端 @PreAuthorize 声明的 realm 角色一致）。
     * 不设则只要求已登录 —— 注意不要凭感觉加：后端若未限制角色，
     * 前端擅自加限会比后端更严、直接改变产品行为。
     */
    requiresRole?: string[]
    /** 旧版标题（已废弃，使用 titleKey 替代） */
    title?: string
  }
}

export {}
