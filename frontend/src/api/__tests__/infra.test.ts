/**
 * infra.ts 单元测试
 *
 * 目前只覆盖创建集群的环境映射：UI 的 (环境, Provider) 二元选择必须映射成编排层
 * `EnvironmentType` 的枚举名，Jackson 按 name 反序列化（大小写敏感），错一个值就是 400。
 */
import { describe, it, expect } from 'vitest'

import { toClusterEnvironment } from '@/api/infra'

describe('toClusterEnvironment', () => {
  it('把 UI 组合映射为 EnvironmentType 枚举名', () => {
    expect(toClusterEnvironment('xinchuang', 'xinchang')).toBe('XINCHANG')
    expect(toClusterEnvironment('private', 'vsphere')).toBe('PRIVATE_VSPHERE')
    expect(toClusterEnvironment('private', 'openstack')).toBe('PRIVATE_OPENSTACK')
    expect(toClusterEnvironment('cloud', 'huawei')).toBe('CLOUD_HUAWEI')
    expect(toClusterEnvironment('cloud', 'ali')).toBe('CLOUD_ALI')
    expect(toClusterEnvironment('cloud', 'tencent')).toBe('CLOUD_TENCENT')
  })

  it('环境与支持 Provider 不匹配时显式报错，不静默挑一个', () => {
    expect(() => toClusterEnvironment('cloud', 'vsphere')).toThrow(/公有云环境不支持/)
    expect(() => toClusterEnvironment('private', 'huawei')).toThrow(/私有云环境不支持/)
    expect(() => toClusterEnvironment('xinchuang', 'ali')).toThrow(/信创环境不支持/)
  })
})
