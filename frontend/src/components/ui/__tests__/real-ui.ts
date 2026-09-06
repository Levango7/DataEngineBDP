/**
 * 组件库 spec 专用：解除 test-setup.ts 的全局 Element Plus stub，
 * 让 el-* 组件真实渲染——否则 stub 声明了 emits 却不转发 DOM 事件，
 * click/emit 断言全部失效。
 */
import ElementPlus from 'element-plus'

export const realElGlobal = {
  plugins: [ElementPlus],
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
