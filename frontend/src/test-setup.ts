/**
 * 测试辅助工具
 *
 * 提供 Vue 组件测试通用的 mock 和工具函数
 */
import { config } from '@vue/test-utils'
import { vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createTestI18n } from '@/test-utils/test-i18n'

// 全局安装 i18n：组件里普遍使用 useI18n()，未安装会抛
// "Need to install with `app.use` function"（NOT_INSTALLED）。
// 这里装的实例位于 config.global.plugins，VTU 会把 mount 级 plugins 追加在后面，
// 因此测试自带 i18n 时以测试自带者为准，不会互相覆盖。
config.global.plugins = [createTestI18n()]

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
// 注意：组件中存在两种导入方式——`import * as echarts`（命名空间）与
// `import echarts from 'echarts'`（默认导出），两者都必须提供 init
vi.mock('echarts', () => {
  const makeChart = () => ({
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn()
  })
  const init = vi.fn(() => makeChart())
  return {
    default: { init, graphic: { LinearGradient: vi.fn() } },
    init,
    graphic: { LinearGradient: vi.fn() }
  }
})

// Mock @element-plus/icons-vue
//
// 只白名单 6 个图标时，任何用到其它图标的组件（如 Develop.vue 的 VideoPlay）都会在
// 渲染期抛 `[vitest] No "Xxx" export is defined on the mock`。改为对任意 PascalCase
// 图标名按需生成 SVG 占位组件：既能覆盖现有全部图标，也不必维护一份会过期的名单。
vi.mock('@element-plus/icons-vue', () => {
  const knownIcons: Record<string, unknown> = {
    Refresh: { name: 'Refresh', template: '<svg />' },
    Close: { name: 'Close', template: '<svg />' },
    CircleCheckFilled: { name: 'CircleCheckFilled', template: '<svg />' },
    CircleCloseFilled: { name: 'CircleCloseFilled', template: '<svg />' },
    WarningFilled: { name: 'WarningFilled', template: '<svg />' },
    InfoFilled: { name: 'InfoFilled', template: '<svg />' }
  }

  /** Element Plus 图标导出名均为 PascalCase 组件名 */
  const isIconName = (prop: string | symbol): boolean =>
    typeof prop === 'string' && /^[A-Z]/.test(prop)

  return new Proxy(knownIcons, {
    has(target, prop) {
      // vitest 的 mock 代理用 `prop in target` 判断导出是否存在，
      // 图标名一律视为存在，否则会抛 "No ... export is defined on the mock"
      return isIconName(prop) ? true : Reflect.has(target, prop)
    },
    get(target, prop) {
      if (isIconName(prop) && !Reflect.has(target, prop)) {
        target[prop as string] = { name: prop as string, template: '<svg />' }
      }
      return Reflect.get(target, prop)
    }
  })
})

// 透传 slot 的通用 stub 工厂
function slotStub(name: string) {
  return defineComponent({
    name,
    props: [
      'modelValue',
      'placeholder',
      'clearable',
      'type',
      'showPassword',
      'disabled',
      'rows',
      'effect',
      'size',
      'loading',
      'stripe',
      'border',
      'data',
      'width',
      'minWidth',
      'fixed',
      'align',
      'prop',
      'label',
      'gutter',
      'closeOnClickModal',
      'rules',
      'labelWidth',
      'labelPosition',
      'currentPage',
      'pageSize',
      'pageSizes',
      'total',
      'layout',
      'background',
      'min',
      'max',
      'controlsPosition',
      'value',
      'percentage',
      'color',
      'strokeWidth',
      'showText',
      'xs',
      'sm',
      'md',
      'lg',
      'shadow',
      'icon',
      'circle',
      'link',
      'title'
    ],
    emits: [
      'update:modelValue',
      'click',
      'change',
      'keyup.enter',
      'clear',
      'size-change',
      'current-change',
      'closed',
      'opened',
      'tab-change',
      'input'
    ],
    setup(_props, { slots, emit }) {
      // 转发原生 click 到组件 click emit，使 @click 监听能正常工作
      return () =>
        h(
          'div',
          {
            class: name,
            onClick: (e: MouseEvent) => emit('click', e)
          },
          slots
        )
    }
  })
}

// ElTableColumn stub：不渲染 default scoped slot（避免 row 解构错误），但渲染其他 slot
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: ['prop', 'label', 'width', 'minWidth', 'fixed', 'align'],
  setup(_props, { slots }) {
    return () =>
      h(
        'div',
        { class: 'el-table-column' },
        {
          header: slots.header?.()
          // 不渲染 default scoped slot，避免 { row } 解构错误
        }
      )
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
