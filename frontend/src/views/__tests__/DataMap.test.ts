/**
 * DataMap.vue（数据地图）单元测试
 *
 * 覆盖：挂载加载库/表、按库过滤、分词检索、表结构详情。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import dataMapZh from '@/i18n/locales/modules/dataMap.zh-CN.json'
import dataMapEn from '@/i18n/locales/modules/dataMap.en-US.json'
import DataMap from '../DataMap.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': dataMapZh as never,
    'en-US': dataMapEn as never
  }
})

const mockDatabases = {
  list: [
    { id: 'db1', tenantId: 't1', name: 'default', owner: 'admin', createdAt: '2024-01-01' },
    { id: 'db2', tenantId: 't1', name: 'ods', owner: 'etl', createdAt: '2024-01-02' }
  ],
  total: 2,
  page: 1,
  pageSize: 100
}

const mockTable = {
  id: 'tb1',
  tenantId: 't1',
  databaseName: 'default',
  tableName: 'orders',
  columns: [
    { name: 'id', type: 'bigint', nullable: false },
    { name: 'amount', type: 'decimal', nullable: true }
  ],
  createdAt: '2024-01-01',
  updatedAt: '2024-01-03'
}

const mockTables = {
  list: [mockTable],
  total: 1,
  page: 1,
  pageSize: 100
}

const mockHits = [{ table: mockTable, score: 0.87 }]

vi.mock('@/api/catalog', () => ({
  listDatabases: vi.fn(() => Promise.resolve(mockDatabases)),
  listTables: vi.fn(() => Promise.resolve(mockTables)),
  searchTables: vi.fn(() => Promise.resolve(mockHits))
}))

describe('DataMap.vue（数据地图）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(DataMap, { global: { plugins: [i18n] } })
  }

  it('挂载后应加载库列表与表列表并渲染', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const { listDatabases, listTables } = await import('@/api/catalog')
    expect(listDatabases).toHaveBeenCalled()
    expect(listTables).toHaveBeenCalled()
    expect(wrapper.text()).toContain('数据地图')
    // 库列表为原生列表（非 el-table）⇒ 文本可断言
    expect(wrapper.text()).toContain('default')
    // 表列表为 el-table（jsdom 不渲染行内容）⇒ 数据走组件状态断言
    const vm = wrapper.vm as any
    expect(vm.tables?.length).toBe(1)
    expect(vm.tables?.[0]?.tableName).toBe('orders')
  })

  it('点击库按钮应按库名过滤表列表', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const { listTables } = await import('@/api/catalog')
    // 索引 0=「全部库」，1=default，2=ods
    const buttons = wrapper.findAll('.dm-db')
    expect(buttons.length).toBeGreaterThanOrEqual(3)
    await buttons[2].trigger('click')
    await flushPromises()
    expect(listTables).toHaveBeenLastCalledWith(expect.objectContaining({ database: 'ods' }))
  })

  it('分词检索应调用 searchTables 并展示命中与相关度', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const { searchTables } = await import('@/api/catalog')
    const vm = wrapper.vm as any
    vm.keyword = '订单'
    await vm.runSearch()
    await flushPromises()
    expect(searchTables).toHaveBeenCalledWith('订单')
    expect(vm.searchMode).toBe(true)
    expect(vm.hits?.length).toBe(1)
    expect(vm.hits?.[0]?.table?.tableName).toBe('orders')
    expect(vm.hits?.[0]?.score).toBe(0.87)
  })

  it('点击表行应打开表结构详情（含列清单）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openDetail(mockTable)
    await flushPromises()
    // Modal 使用 Teleport to body
    const body = document.body.textContent ?? ''
    expect(body).toContain('表结构')
    // el-table（列清单）在 jsdom 不渲染行内容 ⇒ 列数据走组件状态断言
    expect(vm.detailTable?.columns?.length).toBe(2)
    expect(vm.detailTable?.columns?.[0]?.type).toBe('bigint')
    expect(vm.detailTable?.columns?.[1]?.type).toBe('decimal')
    vm.closeDetail()
    await flushPromises()
  })

  it('元数据为空时应展示引导空态', async () => {
    const api = await import('@/api/catalog')
    const spy1 = vi.spyOn(api, 'listDatabases').mockResolvedValueOnce({
      list: [],
      total: 0,
      page: 1,
      pageSize: 100
    })
    const spy2 = vi.spyOn(api, 'listTables').mockResolvedValueOnce({
      list: [],
      total: 0,
      page: 1,
      pageSize: 100
    })
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无元数据')
    spy1.mockRestore()
    spy2.mockRestore()
  })
})
