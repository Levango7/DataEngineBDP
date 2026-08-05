/**
 * 测试辅助工具
 *
 * 提供 Vue 组件测试通用的 mock 和工具函数
 */
import { config } from '@vue/test-utils'
import { vi } from 'vitest'
import { defineComponent, h } from 'vue'

// Mock Element Plus 的 ElMessage / ElMessageBox
vi.mock('element-plus', async (importOriginal) => {
  const original = await importOriginal<typeof import('element-plus')>()
  return {
    ...original,
    ElMessage: {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    },
    ElMessageBox: {
      confirm: vi.fn(() => Promise.resolve('confirm'))
    }
  }
})

// Mock echarts（避免在测试环境初始化真实 DOM 图表）
vi.mock('echarts', () => ({
  default: {
    init: vi.fn(() => ({
      setOption: vi.fn(),
      resize: vi.fn(),
      dispose: vi.fn()
    })),
    graphic: {
      LinearGradient: vi.fn()
    }
  }
}))

// Mock @element-plus/icons-vue
vi.mock('@element-plus/icons-vue', () => ({
  Refresh: { name: 'Refresh', template: '<svg />' }
}))

// 透传 slot 的通用 stub 工厂
function slotStub(name: string) {
  return defineComponent({
    name,
    props: [
      'modelValue', 'placeholder', 'clearable', 'type', 'showPassword', 'disabled', 'rows',
      'effect', 'size', 'loading', 'stripe', 'border', 'data', 'width', 'minWidth', 'fixed',
      'align', 'prop', 'label', 'gutter', 'closeOnClickModal', 'rules', 'labelWidth',
      'labelPosition', 'currentPage', 'pageSize', 'pageSizes', 'total', 'layout', 'background',
      'min', 'max', 'controlsPosition', 'value', 'percentage', 'color', 'strokeWidth',
      'showText', 'xs', 'sm', 'md', 'lg', 'shadow', 'icon', 'circle', 'link', 'title'
    ],
    emits: [
      'update:modelValue', 'click', 'change', 'keyup.enter', 'clear', 'size-change',
      'current-change', 'closed', 'opened', 'tab-change', 'input'
    ],
    setup(_props, { slots }) {
      return () => h('div', { class: name }, slots)
    }
  })
}

// ElTableColumn stub：不渲染 default scoped slot（避免 row 解构错误），但渲染其他 slot
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: ['prop', 'label', 'width', 'minWidth', 'fixed', 'align'],
  setup(_props, { slots }) {
    return () =>
      h('div', { class: 'el-table-column' }, {
        header: slots.header?.(),
        // 不渲染 default scoped slot，避免 { row } 解构错误
      })
  }
})

// ElForm stub：需要 clearValidate 和 validate 方法
const ElFormStub = defineComponent({
  name: 'ElForm',
  props: ['model', 'rules', 'labelWidth', 'labelPosition'],
  emits: [],
  setup(_props, { slots }) {
    // 暴露 clearValidate 和 validate 方法供 ref 调用
    const clearValidate = vi.fn()
    const validate = vi.fn((cb?: Function) => {
      cb?.(true)
      return Promise.resolve(true)
    })
    return { clearValidate, validate }
  },
  render() {
    return h('div', { class: 'el-form' }, this.$slots)
  }
})

// 全局 stub Element Plus 组件
config.global.stubs = {
  ElCard: slotStub('el-card'),
  ElButton: slotStub('el-button'),
  ElInput: slotStub('el-input'),
  ElSelect: slotStub('el-select'),
  ElOption: slotStub('el-option'),
  ElTable: slotStub('el-table'),
  ElTableColumn: ElTableColumnStub,
  ElPagination: slotStub('el-pagination'),
  ElDialog: slotStub('el-dialog'),
  ElForm: ElFormStub,
  ElFormItem: slotStub('el-form-item'),
  ElTag: slotStub('el-tag'),
  ElProgress: slotStub('el-progress'),
  ElRow: slotStub('el-row'),
  ElCol: slotStub('el-col'),
  ElTabs: slotStub('el-tabs'),
  ElTabPane: slotStub('el-tab-pane'),
  ElInputNumber: slotStub('el-input-number'),
  ElIcon: slotStub('el-icon')
}
