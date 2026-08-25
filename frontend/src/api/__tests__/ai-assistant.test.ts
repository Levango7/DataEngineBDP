import { describe, it, expect, vi, beforeEach } from 'vitest'

const { fetchMock } = vi.hoisted(() => ({
  fetchMock: vi.fn()
}))

vi.stubGlobal('fetch', fetchMock)

function makeStorageMock() {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      store = {}
    })
  }
}

const localStorageMock = makeStorageMock()
const sessionStorageMock = makeStorageMock()

Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock })
Object.defineProperty(globalThis, 'sessionStorage', { value: sessionStorageMock })

import { chatStream } from '../ai-assistant'

function sseBody(events: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const event of events) {
        controller.enqueue(encoder.encode(event))
      }
      controller.close()
    }
  })
}

function okResponse(body: ReadableStream<Uint8Array>): Response {
  return { ok: true, status: 200, body } as unknown as Response
}

describe('api/ai-assistant chatStream', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    localStorageMock.clear()
    sessionStorageMock.clear()
  })

  it('流式请求 URL 与 Bearer token 来自 sessionStorage', async () => {
    sessionStorageMock.setItem('sq_token', 'session-token-123')
    localStorageMock.setItem('sq_token', 'stale-local-token')
    fetchMock.mockResolvedValue(
      okResponse(
        sseBody([
          'data: {"type":"message","message":{"delta":"你"}}\n\n',
          'data: {"type":"final","final":{"sessionId":"s1","message":{}}}\n\n'
        ])
      )
    )
    const chunks: unknown[] = []
    const final = await chatStream({ message: '你好' }, (chunk) => chunks.push(chunk))
    expect(final.sessionId).toBe('s1')
    expect(chunks).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/ai-assistant/chat/stream')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer session-token-123')
  })

  it('sessionStorage 无 token 时请求不携带 Authorization 头', async () => {
    localStorageMock.setItem('sq_token', 'stale-local-token')
    fetchMock.mockResolvedValue(
      okResponse(sseBody(['data: {"type":"final","final":{"sessionId":"s2","message":{}}}\n\n']))
    )
    await chatStream({ message: 'hi' }, () => {})
    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
  })
})
