/**
 * AllocationConfig.vue 单元测试
 *
 * 测试分账配置页面：执行分账按钮应绑定本地处理链路（executeAlloc），
 * 以正确参数调用 API，并在成功后弹出结果弹窗
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

const { executeAllocationMock, listAllocationConfigsMock } = vi.hoisted(() => ({
  executeAllocationMock: vi.fn(),
  listAllocationConfigsMock: vi.fn()
}))

vi.mock('@/api/finops', () => ({
  listAllocationConfigs: listAllocationConfigsMock,
  saveAllocationConfig: vi.fn(() => Promise.resolve({})),
  deleteAllocationConfig: vi.fn(() => Promise.resolve()),
  executeAllocation: executeAllocationMock
}))

import AllocationConfig from '../AllocationConfig.vue'

const mockConfigs = [
  {
    id: 'cfg-1',
    parentWorkspace: 'ns-team1',
    dimension: 'namespace',
    ratios: { default: 1 },
    enabled: true,
    remark: ''
  }
]

const mockResult = {
  items: [
    {
      parentWorkspace: 'ns-team1',
      subWorkspace: 'default',
      ratio: 1,
      originalCost: 100,
      allocatedCost: 100,
      dimensionAllocatedCosts: { CPU: 60 },
      dimension: 'namespace'
    }
  ],
  total: 1,
  start: '2026-08-24T00:00:00.000Z',
  end: '2026-08-25T00:00:00.000Z',
  tenant: 't1',
  summary: {}
}

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () => {
      const columns = slots.default ? (slots.default() as any[]) : []
      return h(
        'div',
        { class: 'el-table-stub' },
        (props.data as any[]).map((row) =>
          h(
            'div',
            { class: 'el-table-stub-row' },
            columns.map((col) => {
              const slot = (col.children as any)?.default
              return h('div', typeof slot === 'function' ? slot({ row }) : null)
            })
          )
        )
      )
    }
  }
})

const ElButtonStub = defineComponent({
  name: 'ElButton',
  setup(_, { slots }) {
    return () => h('button', { class: 'el-button-stub' }, slots.default?.())
  }
})

describe('finops AllocationConfig.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listAllocationConfigsMock.mockResolvedValue(mockConfigs)
  })

  function mountComponent(): VueWrapper {
    return mount(AllocationConfig, {
      global: {
        stubs: {
          ElTable: ElTableStub,
          ElButton: ElButtonStub
        }
      }
    })
  }

  async function findExecuteButton(wrapper: VueWrapper) {
    await flushPromises()
    const button = wrapper.findAll('button').find((b) => b.text().includes('执行分账'))
    expect(button).toBeTruthy()
    return button!
  }

  it('挂载后应加载分账配置列表', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(listAllocationConfigsMock).toHaveBeenCalled()
    const vm = wrapper.vm as any
    expect(vm.configs).toHaveLength(1)
    wrapper.unmount()
  })

  it('点击执行分账按钮应以正确参数调用 API 并弹出结果弹窗', async () => {
    executeAllocationMock.mockResolvedValue(mockResult)
    const wrapper = mountComponent()
    const button = await findExecuteButton(wrapper)

    await button.trigger('click')
    expect(executeAllocationMock).toHaveBeenCalledTimes(1)
    const arg = executeAllocationMock.mock.calls[0][0]
    expect(arg.configId).toBe('cfg-1')
    expect(typeof arg.start).toBe('string')
    expect(typeof arg.end).toBe('string')
    expect(new Date(arg.start).getTime()).toBeLessThanOrEqual(new Date(arg.end).getTime())

    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.resultVisible).toBe(true)
    expect(vm.allocationResults).toEqual(mockResult.items)
    wrapper.unmount()
  })

  it('每行配置的执行按钮应传入各自的 configId', async () => {
    listAllocationConfigsMock.mockResolvedValue([
      ...mockConfigs,
      { ...mockConfigs[0], id: 'cfg-2' }
    ])
    executeAllocationMock.mockResolvedValue(mockResult)
    const wrapper = mountComponent()
    await flushPromises()

    const buttons = wrapper.findAll('button').filter((b) => b.text().includes('执行分账'))
    expect(buttons).toHaveLength(2)

    await buttons[1].trigger('click')
    await flushPromises()
    expect(executeAllocationMock.mock.calls[0][0].configId).toBe('cfg-2')
    wrapper.unmount()
  })

  it('执行失败时应提示且不弹出结果弹窗', async () => {
    executeAllocationMock.mockRejectedValue(new Error('boom'))
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {})
    const wrapper = mountComponent()
    const button = await findExecuteButton(wrapper)

    await button.trigger('click')
    await flushPromises()

    const vm = wrapper.vm as any
    expect(vm.resultVisible).toBe(false)
    expect(alertSpy).toHaveBeenCalled()
    alertSpy.mockRestore()
    wrapper.unmount()
  })
})
