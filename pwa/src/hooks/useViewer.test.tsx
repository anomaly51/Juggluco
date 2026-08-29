import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteSession, fetchSnapshot, getSession } from '../api'
import { clearDeviceData, clearSnapshot, loadSnapshot, saveSnapshot } from '../db'
import { connectViewerStream } from '../live'
import type { ViewerStreamHandlers } from '../live'
import { normalizeSnapshot } from '../normalize'
import { rawSnapshot } from '../test/fixtures'
import { useViewer } from './useViewer'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    createSession: vi.fn(),
    deleteSession: vi.fn(async () => undefined),
    fetchSnapshot: vi.fn(),
    getSession: vi.fn(),
  }
})

vi.mock('../db', () => ({
  clearDeviceData: vi.fn(async () => undefined),
  clearSnapshot: vi.fn(async () => undefined),
  loadSnapshot: vi.fn(async () => null),
  saveSnapshot: vi.fn(async () => undefined),
}))

vi.mock('../live', () => ({
  connectViewerStream: vi.fn(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

describe('useViewer request ordering', () => {
  const firstSnapshot = normalizeSnapshot(rawSnapshot)
  const secondSnapshot = normalizeSnapshot({
    ...rawSnapshot,
    glucose_revision: 2,
    current_glucose: { ...rawSnapshot.current_glucose, glucose_mg_dl: 144 },
  })
  let streamHandlers: ViewerStreamHandlers | null = null
  let closeStream: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    vi.mocked(getSession).mockResolvedValue({ authenticated: false, accessMode: 'session', expiresAtMs: null })
    vi.mocked(deleteSession).mockReset()
    vi.mocked(deleteSession).mockResolvedValue(undefined)
    vi.mocked(loadSnapshot).mockResolvedValue(null)
    vi.mocked(saveSnapshot).mockClear()
    vi.mocked(clearDeviceData).mockClear()
    vi.mocked(clearSnapshot).mockClear()
    vi.mocked(fetchSnapshot).mockReset()
    streamHandlers = null
    closeStream = vi.fn()
    vi.mocked(connectViewerStream).mockReset()
    vi.mocked(connectViewerStream).mockImplementation((handlers) => {
      streamHandlers = handlers
      return { close: closeStream }
    })
  })

  const emitStreamEvent = (event: Parameters<ViewerStreamHandlers['onEvent']>[0]) => {
    if (!streamHandlers) throw new Error('stream is not connected')
    streamHandlers.onEvent(event)
  }

  it('does not let an older response replace a newer refresh', async () => {
    const first = deferred<typeof firstSnapshot>()
    const second = deferred<typeof secondSnapshot>()
    vi.mocked(fetchSnapshot).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.authState).toBe('unauthenticated'))

    let firstRefresh!: Promise<boolean>
    let secondRefresh!: Promise<boolean>
    act(() => {
      firstRefresh = result.current.refresh()
      secondRefresh = result.current.refresh()
    })
    await act(async () => {
      second.resolve(secondSnapshot)
      await secondRefresh
    })
    await act(async () => {
      first.resolve(firstSnapshot)
      await firstRefresh
    })

    expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(144)
    expect(saveSnapshot).toHaveBeenCalledTimes(1)
    expect(vi.mocked(saveSnapshot).mock.calls[0][0].currentGlucose?.glucoseMgDl).toBe(144)
  })

  it('shows fresh network data immediately when IndexedDB persistence fails', async () => {
    const pendingSave = deferred<void>()
    vi.mocked(fetchSnapshot).mockResolvedValueOnce(secondSnapshot)
    vi.mocked(saveSnapshot).mockReturnValueOnce(pendingSave.promise)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.authState).toBe('unauthenticated'))

    await act(async () => { await result.current.refresh() })

    expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(144)
    expect(result.current.syncState).toBe('fresh')
    expect(result.current.error).toBeNull()
    expect(result.current.persistenceError).toBeNull()

    pendingSave.reject(new DOMException('Quota exceeded', 'QuotaExceededError'))

    await waitFor(() => expect(result.current.persistenceError).toContain('не сохранены для офлайн-доступа'))
    expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(144)
    expect(result.current.syncState).toBe('fresh')
    expect(result.current.error).toBeNull()
  })

  it('opens the public viewer and loads data without creating a key session', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot).mockResolvedValueOnce(firstSnapshot)

    const { result } = renderHook(() => useViewer())

    await waitFor(() => expect(result.current.snapshot).not.toBeNull())
    expect(result.current.authState).toBe('authenticated')
    expect(result.current.accessMode).toBe('public')
    expect(result.current.sessionExpiresAt).toBeNull()
  })

  it('reconciles immediately when the stream advertises a newer glucose revision', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot)
      .mockResolvedValueOnce(firstSnapshot)
      .mockResolvedValueOnce(secondSnapshot)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())
    await waitFor(() => expect(connectViewerStream).toHaveBeenCalledOnce())

    act(() => emitStreamEvent({
      type: 'glucose',
      streamId: 'test-stream',
      revision: 2,
      serverTimeMs: rawSnapshot.server_time_ms + 1_000,
      latestReadingAtMs: rawSnapshot.current_glucose.measured_at_ms,
    }))

    await waitFor(() => expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(144))
    expect(result.current.snapshot?.glucoseRevision).toBe(2)
    expect(result.current.connectionState).toBe('online')
    expect(fetchSnapshot).toHaveBeenCalledTimes(2)
  })

  it('coalesces events during a fetch and catches up without letting an older watermark win', async () => {
    const revisionTwo = deferred<typeof secondSnapshot>()
    const revisionThree = normalizeSnapshot({
      ...rawSnapshot,
      glucose_revision: 3,
      current_glucose: { ...rawSnapshot.current_glucose, glucose_mg_dl: 155 },
    })
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot)
      .mockResolvedValueOnce(firstSnapshot)
      .mockReturnValueOnce(revisionTwo.promise)
      .mockResolvedValueOnce(revisionThree)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot?.glucoseRevision).toBe(1))

    act(() => emitStreamEvent({
      type: 'glucose', streamId: 'test-stream', revision: 2,
      serverTimeMs: 1_801_000, latestReadingAtMs: 1_500_000,
    }))
    await waitFor(() => expect(fetchSnapshot).toHaveBeenCalledTimes(2))
    act(() => emitStreamEvent({
      type: 'glucose', streamId: 'test-stream', revision: 3,
      serverTimeMs: 1_802_000, latestReadingAtMs: 1_501_000,
    }))

    await act(async () => revisionTwo.resolve(secondSnapshot))
    await waitFor(() => expect(result.current.snapshot?.glucoseRevision).toBe(3))
    expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(155)
    expect(fetchSnapshot).toHaveBeenCalledTimes(3)
  })

  it('uses heartbeats for liveness and server time without refetching an unchanged revision', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot).mockResolvedValue(firstSnapshot)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())

    act(() => emitStreamEvent({
      type: 'heartbeat',
      streamId: 'test-stream',
      revision: 1,
      serverTimeMs: 2_000_000,
      latestReadingAtMs: 1_500_000,
    }))

    expect(result.current.connectionState).toBe('online')
    expect(result.current.serverNowMs).toBeGreaterThanOrEqual(2_000_000)
    expect(fetchSnapshot).toHaveBeenCalledOnce()
  })

  it('marks a broken stream as reconnecting, reconciles over HTTP, and closes it on unmount', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot).mockResolvedValue(firstSnapshot)
    const { result, unmount } = renderHook(() => useViewer())
    await waitFor(() => expect(connectViewerStream).toHaveBeenCalledOnce())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())

    await act(async () => {
      if (!streamHandlers) throw new Error('stream is not connected')
      streamHandlers.onError()
    })

    expect(result.current.connectionState).toBe('reconnecting')
    await waitFor(() => expect(fetchSnapshot).toHaveBeenCalledTimes(2))
    unmount()
    expect(closeStream).toHaveBeenCalledOnce()
  })

  it('does not expose an older private cache while public access is being resolved', async () => {
    const session = deferred<{ authenticated: boolean; accessMode: 'public'; expiresAtMs: null }>()
    vi.mocked(loadSnapshot).mockResolvedValueOnce({ snapshot: firstSnapshot, savedAt: 123, accessMode: 'session' })
    vi.mocked(getSession).mockReturnValueOnce(session.promise)
    vi.mocked(fetchSnapshot).mockResolvedValueOnce(secondSnapshot)

    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())
    expect(result.current.authState).toBe('checking')

    await act(async () => {
      session.resolve({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    })
    await waitFor(() => expect(result.current.snapshot?.currentGlucose?.glucoseMgDl).toBe(144))

    expect(result.current.accessMode).toBe('public')
    expect(clearSnapshot).toHaveBeenCalledOnce()
  })

  it('removes only the persisted medical snapshot in public mode and pauses re-saving', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot).mockResolvedValue(firstSnapshot)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())
    await waitFor(() => expect(saveSnapshot).toHaveBeenCalledOnce())

    await act(async () => { await result.current.clearOfflineCopy() })

    expect(clearSnapshot).toHaveBeenCalledOnce()
    expect(clearDeviceData).not.toHaveBeenCalled()
    expect(result.current.offlineCopyPaused).toBe(true)

    await act(async () => { await result.current.refresh() })
    expect(saveSnapshot).toHaveBeenCalledOnce()
  })

  it('clears the persistence warning after a later snapshot is saved', async () => {
    vi.mocked(fetchSnapshot).mockResolvedValue(secondSnapshot)
    vi.mocked(saveSnapshot)
      .mockRejectedValueOnce(new DOMException('Quota exceeded', 'QuotaExceededError'))
      .mockResolvedValueOnce(undefined)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.authState).toBe('unauthenticated'))

    await act(async () => { await result.current.refresh() })
    await waitFor(() => expect(result.current.persistenceError).not.toBeNull())

    await act(async () => { await result.current.refresh() })
    await waitFor(() => expect(result.current.persistenceError).toBeNull())
    expect(result.current.syncState).toBe('fresh')
  })

  it('cannot restore a snapshot after logout has cleared the device', async () => {
    const pending = deferred<typeof firstSnapshot>()
    vi.mocked(fetchSnapshot).mockReturnValueOnce(pending.promise)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.authState).toBe('unauthenticated'))

    let refresh!: Promise<boolean>
    act(() => { refresh = result.current.refresh() })
    await act(async () => { await result.current.logoutAndClear() })
    await act(async () => {
      pending.resolve(firstSnapshot)
      await refresh
    })

    expect(clearDeviceData).toHaveBeenCalledOnce()
    expect(saveSnapshot).not.toHaveBeenCalled()
    expect(result.current.snapshot).toBeNull()
  })

  it('keeps a failed server logout explicit, clears local data, and succeeds on retry', async () => {
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'session', expiresAtMs: Date.now() + 60_000 })
    vi.mocked(fetchSnapshot).mockResolvedValueOnce(firstSnapshot)
    vi.mocked(deleteSession)
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(undefined)
    const { result } = renderHook(() => useViewer())
    await waitFor(() => expect(result.current.snapshot).not.toBeNull())

    await act(async () => { await result.current.logoutAndClear() })

    expect(result.current.authState).toBe('logout-pending')
    expect(result.current.logoutState).toBe('failed')
    expect(result.current.serverSessionEnded).toBe(false)
    expect(result.current.localDataCleared).toBe(true)
    expect(result.current.logoutError).toContain('сервер не подтвердил завершение сессии')
    expect(result.current.snapshot).toBeNull()
    expect(clearDeviceData).toHaveBeenCalledOnce()
    expect(localStorage.getItem('juggluco-viewer:logout-pending')).toBe('1')

    await act(async () => { await result.current.logoutAndClear() })

    expect(result.current.authState).toBe('unauthenticated')
    expect(result.current.logoutState).toBe('idle')
    expect(result.current.serverSessionEnded).toBe(true)
    expect(localStorage.getItem('juggluco-viewer:logout-pending')).toBeNull()
    expect(deleteSession).toHaveBeenCalledTimes(2)
  })

  it('keeps a private session fail-closed when a previous logout is still pending', async () => {
    localStorage.setItem('juggluco-viewer:logout-pending', '1')
    const { result } = renderHook(() => useViewer())

    await waitFor(() => expect(result.current.localDataCleared).toBe(true))

    expect(result.current.authState).toBe('logout-pending')
    expect(result.current.logoutState).toBe('failed')
    expect(result.current.snapshot).toBeNull()
    expect(loadSnapshot).not.toHaveBeenCalled()
    expect(getSession).toHaveBeenCalledOnce()
    expect(clearDeviceData).toHaveBeenCalledOnce()
  })

  it('does not let an old private logout marker block a newly public viewer', async () => {
    localStorage.setItem('juggluco-viewer:logout-pending', '1')
    vi.mocked(getSession).mockResolvedValueOnce({ authenticated: true, accessMode: 'public', expiresAtMs: null })
    vi.mocked(fetchSnapshot).mockResolvedValueOnce(firstSnapshot)

    const { result } = renderHook(() => useViewer())

    await waitFor(() => expect(result.current.snapshot).not.toBeNull())
    expect(result.current.accessMode).toBe('public')
    expect(result.current.authState).toBe('authenticated')
    expect(result.current.logoutState).toBe('idle')
    expect(localStorage.getItem('juggluco-viewer:logout-pending')).toBeNull()
    expect(clearSnapshot).toHaveBeenCalledOnce()
    expect(clearDeviceData).not.toHaveBeenCalled()
  })
})
