/**
 * dev-ml 推理服务 API 落点防回归（#133：llmops 承接推理域权威）。
 *
 * 判据：list/deploy/stop/scale 四个方法必须打到 llmops 的
 * /llmops/inference-services*（而非 Harbor 镜像仓库的 /registry/deployments*）。
 * 若 BASE_ML_INFER 被改回 /registry 或其它错址，本测试转红。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as devMl from '@/api/dev-ml'
import * as client from '@/api/client'

vi.mock('@/api/client', () => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn()
}))

describe('dev-ml 推理服务 API（#133：llmops 落点）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listInferenceServices 指向 llmops（带 status 过滤）', () => {
    devMl.listInferenceServices({ status: 'RUNNING' })
    expect(client.get).toHaveBeenCalledWith('/llmops/inference-services', { status: 'RUNNING' })
  })

  it('deployInference 指向 llmops（deploy 请求体含 version）', () => {
    devMl.deployInference({ modelName: 'shuqing-7b', version: 'v3', replicas: 2 })
    expect(client.post).toHaveBeenCalledWith('/llmops/inference-services', {
      modelName: 'shuqing-7b',
      version: 'v3',
      replicas: 2
    })
  })

  it('stopInference 指向 llmops（DELETE /{id}）', () => {
    devMl.stopInference('id-1')
    expect(client.del).toHaveBeenCalledWith('/llmops/inference-services/id-1')
  })

  it('scaleInference 指向 llmops（POST /{id}/scale）', () => {
    devMl.scaleInference('id-1', { replicas: 3 })
    expect(client.post).toHaveBeenCalledWith('/llmops/inference-services/id-1/scale', {
      replicas: 3
    })
  })
})
