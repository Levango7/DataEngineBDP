/**
 * 组件库 spec 专用：解除 test-setup.ts 的全局 Element Plus stub，
 * 让 el-* 组件真实渲染——否则 stub 声明了 emits 却不转发 DOM 事件，
 * click/emit 断言全部失效。
 */
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'

// 提供 i18n 实例（部分 UI 组件如 Toolbar 使用 useI18n 做 fallback 文案）
const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': {
      common: { create: '新建', cancel: '取消', confirm: '确定', save: '保存' }
    },
    'en-US': {
      common: { create: 'Create', cancel: 'Cancel', confirm: 'Confirm', save: 'Save' }
    }
  }
})

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
