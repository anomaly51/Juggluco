export type ViewerStreamEventType = 'ready' | 'glucose' | 'heartbeat'

export interface ViewerStreamEvent {
  type: ViewerStreamEventType
  streamId: string
  revision: number
  serverTimeMs: number
  latestReadingAtMs: number | null
}

export interface ViewerStreamHandlers {
  onOpen: () => void
  onEvent: (event: ViewerStreamEvent) => void
  onError: () => void
  onProtocolError: () => void
}

export interface ViewerStreamConnection {
  close: () => void
}

const STREAM_URL = '/v1/viewer/stream'
const MAX_EVENT_CHARS = 16 * 1024

function eventRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('Некорректное событие потока')
  }
  return value as Record<string, unknown>
}

export function parseViewerStreamEvent(type: ViewerStreamEventType, raw: string): ViewerStreamEvent {
  if (raw.length > MAX_EVENT_CHARS) throw new Error('Событие потока слишком большое')

  let parsed: unknown
  try {
    parsed = JSON.parse(raw) as unknown
  } catch {
    throw new Error('Некорректное событие потока')
  }

  const source = eventRecord(parsed)
  const streamId = source.stream_id
  const revision = source.revision
  const serverTimeMs = source.server_time_ms
  const latestReadingAtMs = source.latest_reading_at_ms

  if (typeof streamId !== 'string' || streamId.length === 0 || streamId.length > 128) {
    throw new Error('Некорректный идентификатор потока')
  }
  if (typeof revision !== 'number' || !Number.isSafeInteger(revision) || revision < 0) {
    throw new Error('Некорректная ревизия потока')
  }
  if (typeof serverTimeMs !== 'number' || !Number.isFinite(serverTimeMs) || serverTimeMs <= 0) {
    throw new Error('Некорректное время потока')
  }
  if (
    latestReadingAtMs != null
    && (typeof latestReadingAtMs !== 'number' || !Number.isFinite(latestReadingAtMs) || latestReadingAtMs <= 0)
  ) {
    throw new Error('Некорректное время показания')
  }

  return {
    type,
    streamId,
    revision,
    serverTimeMs,
    latestReadingAtMs: typeof latestReadingAtMs === 'number' ? latestReadingAtMs : null,
  }
}

export function connectViewerStream(handlers: ViewerStreamHandlers): ViewerStreamConnection {
  const source = new EventSource(STREAM_URL, { withCredentials: true })

  source.onopen = handlers.onOpen
  source.onerror = handlers.onError

  const listen = (type: ViewerStreamEventType) => {
    source.addEventListener(type, (rawEvent) => {
      try {
        const event = rawEvent as MessageEvent<string>
        handlers.onEvent(parseViewerStreamEvent(type, event.data))
      } catch {
        handlers.onProtocolError()
      }
    })
  }

  listen('ready')
  listen('glucose')
  listen('heartbeat')

  return { close: () => source.close() }
}
