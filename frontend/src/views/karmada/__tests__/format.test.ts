/**
 * Karmada 页面取值渲染 helper 的直测。
 *
 * 后端把 spec/status/allocation/weights/backupClusters/affectedWorkloads 存成
 * text 列的 JSON 字符串（model/failover.go、model/override_policy.go 的 gorm tag），
 * 任何"当对象取键"的写法都会静默拿到 undefined。Element Plus 的测试 stub 又不渲染
 * 表格列的 scoped slot（src/test-setup.ts 的 ElTableColumnStub），所以这类逻辑
 * 只能在纯函数上断言——见 views/exchange/__tests__ 的同族说明。
 */
import { describe, it, expect } from 'vitest'
import {
  KARMADA_CLUSTER_STATUS_MAP,
  KARMADA_EVENT_STATUS_MAP,
  boolLabel,
  durationMs,
  formatJsonPairs,
  formatPairs,
  loadPercent,
  parseJsonList,
  parseJsonNumberRecord,
  parseJsonRecord,
  replicaPair,
  seconds
} from '@/views/karmada/format'

describe('karmada/format.ts — JSON 字符串列解析', () => {
  it('对象：合法 JSON 解析；数组/标量/坏 JSON/空值一律给空对象', () => {
    expect(parseJsonRecord('{"a":1}')).toEqual({ a: 1 })
    expect(parseJsonRecord('[]')).toEqual({})
    expect(parseJsonRecord('"x"')).toEqual({})
    expect(parseJsonRecord('{oops')).toEqual({})
    expect(parseJsonRecord(null)).toEqual({})
    expect(parseJsonRecord(undefined)).toEqual({})
  })

  it('字符串数组：数字也转成字符串；非数组给空数组', () => {
    expect(parseJsonList('["c1","c2"]')).toEqual(['c1', 'c2'])
    expect(parseJsonList('[1,2]')).toEqual(['1', '2'])
    expect(parseJsonList('{"a":1}')).toEqual([])
    expect(parseJsonList('not-json')).toEqual([])
  })

  it('数值映射：只留有限数值，脏值不画成 NaN 柱', () => {
    expect(parseJsonNumberRecord('{"a":1,"b":"2","c":null,"d":3.5}')).toEqual({ a: 1, d: 3.5 })
  })

  it('成对渲染：空对象给破折号，不显示空白单元格', () => {
    expect(formatPairs({})).toBe('—')
    expect(formatPairs({ a: 1, b: 'x' })).toBe('a=1, b=x')
    expect(formatJsonPairs('{"c1":3,"c2":3}')).toBe('c1=3, c2=3')
    expect(formatJsonPairs('bad')).toBe('—')
  })
})

describe('karmada/format.ts — 数值列缺值不得变 NaN', () => {
  it('负载百分比：0 是"真的空闲"，缺值/非法值是破折号', () => {
    expect(loadPercent(0)).toBe('0.0%')
    expect(loadPercent(12.34)).toBe('12.3%')
    expect(loadPercent(null)).toBe('—')
    expect(loadPercent(Number.NaN)).toBe('—')
    expect(loadPercent(Number.POSITIVE_INFINITY)).toBe('—')
  })

  it('迁移耗时：0ms 是合法测量值，不吞成破折号', () => {
    expect(durationMs(0)).toBe('0ms')
    expect(durationMs(1500)).toBe('1500ms')
    expect(durationMs(undefined)).toBe('—')
  })

  it('副本对：任一缺值都给破折号，不出现 `3 / NaN`', () => {
    expect(replicaPair(3, 5)).toBe('3 / 5')
    expect(replicaPair(3, null)).toBe('—')
    expect(replicaPair(Number.NaN, 5)).toBe('—')
  })

  it('秒数列：0s 合法', () => {
    expect(seconds(0)).toBe('0s')
    expect(seconds(30)).toBe('30s')
    expect(seconds(null)).toBe('—')
  })

  it('布尔标签由调用方给译文，helper 不碰 i18n', () => {
    expect(boolLabel(true, '是', '否')).toBe('是')
    expect(boolLabel(false, '是', '否')).toBe('否')
  })
})

describe('karmada/format.ts — 状态映射键必须是后端实际序列化值', () => {
  it('集群健康值全小写，与 model/failover.go 的 ClusterStatus* 常量一致', () => {
    // StatusTag.vue:26 是精确匹配不转大小写：写大写键等于整张表作废。
    expect(KARMADA_CLUSTER_STATUS_MAP.healthy).toBe('success')
    expect(KARMADA_CLUSTER_STATUS_MAP.degraded).toBe('warning')
    expect(KARMADA_CLUSTER_STATUS_MAP.down).toBe('danger')
    expect(KARMADA_CLUSTER_STATUS_MAP.Healthy).toBeUndefined()
  })

  it('迁移事件状态覆盖 EventStatus* 的五个取值', () => {
    expect(Object.keys(KARMADA_EVENT_STATUS_MAP).sort()).toEqual(
      ['failed', 'pending', 'rolled_back', 'running', 'succeeded'].sort()
    )
    expect(KARMADA_EVENT_STATUS_MAP.succeeded).toBe('success')
    expect(KARMADA_EVENT_STATUS_MAP.failed).toBe('danger')
  })
})
