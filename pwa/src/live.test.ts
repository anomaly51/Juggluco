import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectViewerStream, parseViewerStreamEvent } from './live'

class FakeEventSource {
  static latest: FakeEventSource | null = null

  readonly listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>()
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false

  constructor(readonly url: string, readonly init?: EventSourceInit) {
    FakeEventSource.latest = this
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const callback = listener as (event: MessageEvent<string>) => void
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback])
  }

  emit(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data) })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  close() {
    this.closed = true
  }
}

describe('viewer live stream', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    FakeEventSource.latest = null
  })

  it('strictly parses the bounded stream watermark', () => {
    expect(parseViewerStreamEvent('glucose', JSON.stringify({
      stream_id: 'process-1',
      revision: 42,
      server_time_ms: 1_000,
      latest_reading_at_ms: 900,
    }))).toEqual({
      type: 'glucose',
      streamId: 'process-1',
      revision: 42,
      serverTimeMs: 1_000,
      latestReadingAtMs: 900,
    })

    expect(() => parseViewerStreamEvent('heartbeat', '{broken')).toThrow('Некорректное событие')
    expect(() => parseViewerStreamEvent('heartbeat', JSON.stringify({
      stream_id: 'process-1', revision: -1, server_time_ms: 1_000,
    }))).toThrow('Некорректная ревизия')
    expect(() => parseViewerStreamEvent('ready', 'x'.repeat(16 * 1024 + 1))).toThrow('слишком большое')
  })

  it('opens a credentialed same-origin EventSource and routes named events', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const onEvent = vi.fn()
    const onProtocolError = vi.fn()
    const connection = connectViewerStream({
      onOpen: vi.fn(),
      onEvent,
      onError: vi.fn(),
      onProtocolError,
    })
    const source = FakeEventSource.latest!

    expect(source.url).toBe('/v1/viewer/stream')
    expect(source.init).toEqual({ withCredentials: true })

    source.emit('ready', { stream_id: 'process-1', revision: 7, server_time_ms: 1_000 })
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'ready', revision: 7 }))

    source.emit('heartbeat', { stream_id: '', revision: 7, server_time_ms: 1_000 })
    expect(onProtocolError).toHaveBeenCalledOnce()

    connection.close()
    expect(source.closed).toBe(true)
  })
})
