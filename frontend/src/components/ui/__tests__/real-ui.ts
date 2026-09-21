/**
 * 组件库 spec 专用：解除 test-setup.ts 的全局 Element Plus stub，
 * 让 el-* 组件真实渲染——否则 stub 声明了 emits 却不转发 DOM 事件，
 * click/emit 断言全部失效。
 */
import ElementPlus from 'element-plus'
import { createTestI18n } from '@/test-utils/test-i18n'

// 提供 i18n 实例（部分 UI 组件如 Toolbar 使用 useI18n 做 fallback 文案）。
// 复用测试统一工厂：使用生产 locale 全量框架级词条，避免这里只塞几个 key 导致
// 组件取不到译文（如 EmptyState 的 common.empty 曾退回原始 key）。
const i18n = createTestI18n()

export const realElGlobal = {
  plugins: [ElementPlus, i18n],
  stubs: {
    ElCard: false,
    ElButton: false,
    ElInput: false,
    ElSelect: false,
    ElOption: false,
    ElDialog: false,
    ElTag: false,
    ElIcon: false
  }
}
