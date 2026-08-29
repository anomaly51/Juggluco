import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSession, fetchSnapshot, getSession } from './api'
import { rawSnapshot } from './test/fixtures'

describe('viewer API', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('creates a same-origin cookie session without persisting the token', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ authenticated: true, expires_at_ms: 9_999 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(createSession('top-secret')).resolves.toEqual({ authenticated: true, accessMode: 'session', expiresAtMs: 9_999 })
    expect(fetchMock).toHaveBeenCalledWith('/v1/viewer/session', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      redirect: 'error',
      body: JSON.stringify({ token: 'top-secret' }),
    }))
    expect(Object.values(localStorage)).not.toContain('top-secret')
  })

  it('reads the cookie session with credentials included', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ authenticated: true, expires_at_ms: 9_999 }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await getSession()

    expect(fetchMock).toHaveBeenCalledWith('/v1/viewer/session', expect.objectContaining({ credentials: 'include', redirect: 'error' }))
  })

  it('recognizes public access without inventing a session expiry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      authenticated: true,
      access_mode: 'public',
      expires_at_ms: null,
    }), { status: 200 })))

    await expect(getSession()).resolves.toEqual({
      authenticated: true,
      accessMode: 'public',
      expiresAtMs: null,
    })
  })

  it('fails closed to session mode for an unknown access mode', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      authenticated: true,
      access_mode: 'unexpected',
    }), { status: 200 })))

    await expect(getSession()).resolves.toEqual({
      authenticated: true,
      accessMode: 'session',
      expiresAtMs: null,
    })
  })

  it('requests the bounded viewer snapshot and normalizes it', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(rawSnapshot), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchSnapshot()

    expect(result.targetRange.lowMmolL).toBe(4.2)
    expect(result.insulinEvents.map((event) => event.insulinName)).toEqual(['Tresiba', 'NovoRapid'])
    expect(fetchMock).toHaveBeenCalledWith(
      '/v1/viewer/snapshot?glucose_limit=1500&event_limit=500',
      expect.objectContaining({ credentials: 'include', cache: 'no-store', redirect: 'error' }),
    )
  })

  it('refuses a snapshot declared above 12 MiB', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', {
      status: 200,
      headers: { 'Content-Length': String(12 * 1024 * 1024 + 1) },
    })))

    await expect(fetchSnapshot()).rejects.toThrow('Снимок превышает безопасный размер')
  })

  it('cancels a chunked snapshot as soon as it crosses 12 MiB', async () => {
    const cancel = vi.fn()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(12 * 1024 * 1024))
        controller.enqueue(new Uint8Array(1))
      },
      cancel,
    })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status: 200 })))

    await expect(fetchSnapshot()).rejects.toThrow('Снимок превышает безопасный размер')
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('never reflects a backend detail that could contain a secret', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      detail: 'token top-secret leaked by proxy',
    }), { status: 400, headers: { 'Content-Type': 'application/json' } })))

    await expect(createSession('top-secret')).rejects.toThrow('Не удалось выполнить запрос')
    await expect(createSession('top-secret')).rejects.not.toThrow('top-secret')
  })
})
