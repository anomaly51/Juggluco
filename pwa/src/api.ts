import { normalizeSnapshot } from './normalize'
import type { ViewerSession, ViewerSnapshot } from './types'

const SESSION_URL = '/v1/viewer/session'
const SNAPSHOT_URL = '/v1/viewer/snapshot'
const MAX_SNAPSHOT_BYTES = 12 * 1024 * 1024

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function safeError(response: Response): Promise<ApiError> {
  const fallback =
    response.status === 401 || response.status === 403
      ? 'Ключ не подошёл. Проверьте его и попробуйте снова.'
      : response.status === 413
        ? 'Ответ сервера слишком большой.'
        : response.status === 429
          ? 'Слишком много попыток. Подождите и попробуйте снова.'
      : response.status >= 500
        ? 'Сервер временно недоступен.'
        : 'Не удалось выполнить запрос.'
  return new ApiError(fallback, response.status)
}

function sessionFrom(value: unknown): ViewerSession {
  if (typeof value !== 'object' || value === null) {
    return { authenticated: false, accessMode: 'session', expiresAtMs: null }
  }
  const body = value as Record<string, unknown>
  const expiry = body.expires_at_ms ?? body.expiresAtMs
  const rawAccessMode = body.access_mode ?? body.accessMode
  return {
    authenticated: body.authenticated === true,
    // Missing or unknown values stay on the safer key-protected flow. This also
    // keeps the PWA compatible with older private viewer backends.
    accessMode: rawAccessMode === 'public' ? 'public' : 'session',
    expiresAtMs: typeof expiry === 'number' && Number.isFinite(expiry) ? expiry : null,
  }
}

async function boundedResponseText(response: Response): Promise<string> {
  if (!response.body) {
    const text = await response.text()
    if (new TextEncoder().encode(text).byteLength > MAX_SNAPSHOT_BYTES) {
      throw new ApiError('Снимок превышает безопасный размер.', 413)
    }
    return text
  }

  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let received = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    received += value.byteLength
    if (received > MAX_SNAPSHOT_BYTES) {
      await reader.cancel().catch(() => undefined)
      throw new ApiError('Снимок превышает безопасный размер.', 413)
    }
    chunks.push(value)
  }
  const body = new Uint8Array(received)
  let offset = 0
  for (const chunk of chunks) {
    body.set(chunk, offset)
    offset += chunk.byteLength
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(body)
  } catch {
    throw new ApiError('Сервер вернул некорректный снимок.', 502)
  }
}

export async function getSession(signal?: AbortSignal): Promise<ViewerSession> {
  const response = await fetch(SESSION_URL, {
    method: 'GET',
    credentials: 'include',
    cache: 'no-store',
    redirect: 'error',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    if (response.status === 401) return { authenticated: false, accessMode: 'session', expiresAtMs: null }
    throw await safeError(response)
  }
  return sessionFrom(await response.json())
}

export async function createSession(token: string): Promise<ViewerSession> {
  const response = await fetch(SESSION_URL, {
    method: 'POST',
    credentials: 'include',
    cache: 'no-store',
    redirect: 'error',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ token }),
  })
  if (!response.ok) throw await safeError(response)
  return sessionFrom(await response.json())
}

export async function deleteSession(): Promise<void> {
  const response = await fetch(SESSION_URL, {
    method: 'DELETE',
    credentials: 'include',
    cache: 'no-store',
    redirect: 'error',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok && response.status !== 401) throw await safeError(response)
}

export async function fetchSnapshot(signal?: AbortSignal): Promise<ViewerSnapshot> {
  const query = new URLSearchParams({ glucose_limit: '1500', event_limit: '500' })
  const response = await fetch(`${SNAPSHOT_URL}?${query}`, {
    method: 'GET',
    credentials: 'include',
    cache: 'no-store',
    redirect: 'error',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw await safeError(response)
  const declaredLength = Number(response.headers.get('Content-Length'))
  if (Number.isFinite(declaredLength) && declaredLength > MAX_SNAPSHOT_BYTES) {
    throw new ApiError('Снимок превышает безопасный размер.', 413)
  }
  let payload: string
  try {
    payload = await boundedResponseText(response)
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('Не удалось безопасно прочитать снимок.', 502)
  }
  try {
    return normalizeSnapshot(JSON.parse(payload) as unknown)
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('Сервер вернул некорректный снимок.', 502)
  }
}
