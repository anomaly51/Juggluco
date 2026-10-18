import { afterEach, describe, expect, it, vi } from 'vitest'
import { configurePersonalManifest, profileContext, profileStorageKey, viewerEndpoint } from './profile'
import { clearSnapshot, loadSnapshot, saveSnapshot } from './db'
import { normalizeSnapshot } from './normalize'
import { rawSnapshot } from './test/fixtures'
import { fetchSnapshot, getSession } from './api'
import { connectViewerStream } from './live'

const a = 'a'.repeat(22)
const b = 'b'.repeat(22)
function open(handle: string) { history.replaceState(null, '', `/viewer/?profile=${handle}`) }
afterEach(() => {
  history.replaceState(null, '', '/viewer/')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('personal viewer isolation', () => {
  it.each(['gluco-', 'g-', 'user-', ''])('binds a %s DNS viewer to its origin, ignores profile overrides and launches at root', async (prefix) => {
    const length = prefix === 'g-' ? 12 : 26
    const hostA = `${prefix}${'a'.repeat(length)}.api-api-api.com`
    const hostB = `${prefix}${'b'.repeat(length)}.api-api-api.com`
    expect(profileContext(`?profile=${b}`, hostA)).toEqual({ key: `host:${hostA}`, prefix: '/v1/viewer', handle: null })
    expect(profileContext('', 'gluco-invalid.api-api-api.com').key).not.toBe('legacy')
    expect(profileContext('', 'g-invalid.api-api-api.com').key).not.toBe('legacy')
    expect(profileContext(`?profile=${b}`, 'vlad.api-api-api.com').key).toBe('host:vlad.api-api-api.com')
    expect(profileContext('', 'juggluco-general1.api-api-api.com').key).toBe('legacy')
    expect(profileContext(`?profile=${a}`, 'juggluco-general1.api-api-api.com').handle).toBe(a)
    vi.stubGlobal('window', { location: { hostname: hostA, search: `?profile=${b}` } })
    await clearSnapshot()
    await saveSnapshot(normalizeSnapshot(rawSnapshot), 321, 'public')
    const markerA = profileStorageKey('logout')
    configurePersonalManifest()
    expect(document.querySelector<HTMLLinkElement>('link[rel="manifest"]')?.getAttribute('href'))
      .toBe('/viewer/personal.webmanifest')
    expect(viewerEndpoint('snapshot')).toBe('/v1/viewer/snapshot')
    vi.stubGlobal('window', { location: { hostname: hostB, search: '' } })
    expect(profileStorageKey('logout')).not.toBe(markerA)
    expect(await loadSnapshot()).toBeNull()
    vi.stubGlobal('window', { location: { hostname: hostA, search: '' } })
    expect((await loadSnapshot())?.savedAt).toBe(321)
    await clearSnapshot()
  })
  it('never falls back to legacy for malformed or duplicate personal links', () => {
    expect(profileContext('').prefix).toBe('/v1/viewer')
    for (const search of ['?profile=', '?profile=../../secret', `?profile=${a}&profile=${b}`]) {
      expect(profileContext(search).prefix).toBe('/v1/shares/invalid')
    }
    expect(profileContext(`?profile=my-sugar-${a}`).handle).toBe(`my-sugar-${a}`)
  })

  it('partitions offline snapshots and logout markers by explicit link', async () => {
    open(a)
    await clearSnapshot()
    const markerA = profileStorageKey('logout')
    await saveSnapshot(normalizeSnapshot(rawSnapshot), 123, 'public')
    open(b)
    await clearSnapshot()
    expect(profileStorageKey('logout')).not.toBe(markerA)
    expect(await loadSnapshot()).toBeNull()
    open(a)
    expect((await loadSnapshot())?.savedAt).toBe(123)
    await clearSnapshot()
    history.replaceState(null, '', '/viewer/')
    expect(profileStorageKey('logout')).toBe('logout')
  })

  it('uses the same profile for requests, live stream and installed PWA launch', async () => {
    open(a)
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ authenticated: true, access_mode: 'public' })))
      .mockResolvedValueOnce(new Response(JSON.stringify(rawSnapshot)))
    vi.stubGlobal('fetch', fetchMock)
    await getSession()
    await fetchSnapshot()
    expect(fetchMock.mock.calls[0][0]).toBe(`/v1/shares/${a}/session`)
    expect(fetchMock.mock.calls[1][0]).toContain(`/v1/shares/${a}/snapshot?`)
    const Source = vi.fn(function (_url: string, _options: EventSourceInit) { return { addEventListener: vi.fn(), close: vi.fn() } })
    vi.stubGlobal('EventSource', Source)
    connectViewerStream({ onOpen: vi.fn(), onEvent: vi.fn(), onError: vi.fn(), onProtocolError: vi.fn() })
    expect(Source.mock.calls[0][0]).toBe(viewerEndpoint('stream'))
    configurePersonalManifest()
    expect(document.querySelector<HTMLLinkElement>('link[rel="manifest"]')?.getAttribute('href'))
      .toBe(`/viewer/personal.webmanifest?profile=${a}`)
  })
})
