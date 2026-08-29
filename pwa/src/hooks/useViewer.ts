import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, createSession, deleteSession, fetchSnapshot, getSession } from '../api'
import { clearDeviceData, clearSnapshot, loadSnapshot, saveSnapshot } from '../db'
import { effectiveServerNow } from '../freshness'
import { connectViewerStream } from '../live'
import type { ViewerStreamEvent } from '../live'
import type { AuthState, ConnectionState, LogoutState, SyncState, ViewerAccessMode, ViewerSnapshot } from '../types'

const LOGOUT_PENDING_KEY = 'juggluco-viewer:logout-pending'
const CLOCK_TICK_MS = 15_000
const FALLBACK_POLL_MS = 5_000
const CONNECTED_RECONCILE_MS = 5 * 60_000
const STREAM_WATCHDOG_MS = 5_000
const STREAM_STALE_MS = 45_000
const STREAM_RESTART_MS = 20_000

interface ServerClockAnchor {
  serverTimeMs: number
  observedAtMs: number
}

function message(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) return error.message
  return 'Произошла неизвестная ошибка.'
}

function logoutWasInterrupted(): boolean {
  try {
    return localStorage.getItem(LOGOUT_PENDING_KEY) === '1'
  } catch {
    return false
  }
}

function rememberPendingLogout(): void {
  try {
    localStorage.setItem(LOGOUT_PENDING_KEY, '1')
  } catch {
    // The current page still remains fail-closed when storage is unavailable.
  }
}

function forgetPendingLogout(): void {
  try {
    localStorage.removeItem(LOGOUT_PENDING_KEY)
  } catch {
    // A stale non-sensitive marker is safer than silently restoring a session.
  }
}

export function useViewer() {
  const pendingLogoutOnMount = useRef(logoutWasInterrupted()).current
  const [snapshot, setSnapshot] = useState<ViewerSnapshot | null>(null)
  const [savedAt, setSavedAt] = useState<number | null>(null)
  const [authState, setAuthState] = useState<AuthState>(pendingLogoutOnMount ? 'logout-pending' : 'checking')
  const [accessMode, setAccessMode] = useState<ViewerAccessMode>('session')
  const [syncState, setSyncState] = useState<SyncState>('loading')
  const [sessionExpiresAt, setSessionExpiresAt] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [persistenceError, setPersistenceError] = useState<string | null>(null)
  const [logoutState, setLogoutState] = useState<LogoutState>(pendingLogoutOnMount ? 'failed' : 'idle')
  const [logoutError, setLogoutError] = useState<string | null>(
    pendingLogoutOnMount
      ? 'Предыдущая попытка выхода не была подтверждена сервером. Повторите её при наличии связи.'
      : null,
  )
  const [serverSessionEnded, setServerSessionEnded] = useState(false)
  const [localDataCleared, setLocalDataCleared] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [offlineCopyPaused, setOfflineCopyPaused] = useState(false)
  const [connectionState, setConnectionState] = useState<ConnectionState>(
    typeof navigator !== 'undefined' && navigator.onLine === false ? 'offline' : 'connecting',
  )
  const [clockAnchor, setClockAnchor] = useState<ServerClockAnchor | null>(null)
  const [clientNowMs, setClientNowMs] = useState(() => Date.now())
  const hasData = useRef(false)
  const snapshotRef = useRef<ViewerSnapshot | null>(null)
  const refreshSequence = useRef(0)
  const refreshController = useRef<AbortController | null>(null)
  const persistQueue = useRef<Promise<void>>(Promise.resolve())
  const logoutRunning = useRef(false)
  const accessModeRef = useRef<ViewerAccessMode>('session')
  const offlinePersistencePaused = useRef(false)
  const latestStreamEvent = useRef<{ streamId: string; revision: number } | null>(null)
  const reconcileRequested = useRef(false)
  const reconcileRunning = useRef(false)
  const reconcileRetryTimer = useRef<number | null>(null)
  const requestReconcileRef = useRef<() => void>(() => undefined)

  const rememberAccessMode = (mode: ViewerAccessMode) => {
    accessModeRef.current = mode
    setAccessMode(mode)
  }

  const refresh = useCallback(async (parentSignal?: AbortSignal): Promise<boolean> => {
    const sequence = ++refreshSequence.current
    refreshController.current?.abort()
    const controller = new AbortController()
    refreshController.current = controller
    const abortFromParent = () => controller.abort()
    if (parentSignal?.aborted) controller.abort()
    else parentSignal?.addEventListener('abort', abortFromParent, { once: true })
    setRefreshing(true)
    try {
      const next = await fetchSnapshot(controller.signal)
      if (sequence !== refreshSequence.current) return false
      const now = Date.now()

      // A local storage failure must not delay or invalidate fresh server data.
      hasData.current = true
      snapshotRef.current = next
      setSnapshot(next)
      setSavedAt(now)
      setClockAnchor({ serverTimeMs: next.serverTimeMs, observedAtMs: now })
      setClientNowMs(now)
      setSyncState('fresh')
      setError(null)

      const persist = persistQueue.current.then(async () => {
        if (sequence !== refreshSequence.current) return
        if (offlinePersistencePaused.current) {
          setPersistenceError(null)
          return
        }
        try {
          await saveSnapshot(next, now, accessModeRef.current)
        } catch {
          if (sequence === refreshSequence.current) {
            setPersistenceError('Данные обновлены, но не сохранены для офлайн-доступа. После закрытия приложения без сети может открыться предыдущая копия.')
          }
          return
        }
        if (sequence === refreshSequence.current) setPersistenceError(null)
      })
      persistQueue.current = persist.catch(() => undefined)
      return true
    } catch (reason) {
      if (sequence !== refreshSequence.current || (reason instanceof DOMException && reason.name === 'AbortError')) return false
      if (reason instanceof ApiError && reason.status === 401) {
        const wasPublic = accessModeRef.current === 'public'
        rememberAccessMode('session')
        setAuthState('unauthenticated')
        setError(wasPublic
          ? 'Публичный просмотр отключён на сервере.'
          : 'Сессия закончилась. Введите ключ ещё раз.')
      } else {
        setSyncState(hasData.current ? 'offline' : 'error')
        setError(message(reason))
      }
      return false
    } finally {
      parentSignal?.removeEventListener('abort', abortFromParent)
      if (sequence === refreshSequence.current) {
        refreshController.current = null
        setRefreshing(false)
      }
    }
  }, [])

  const requestReconcile = useCallback(() => {
    reconcileRequested.current = true
    if (reconcileRunning.current) return

    reconcileRunning.current = true
    void (async () => {
      let passes = 0
      while (reconcileRequested.current && passes < 3) {
        reconcileRequested.current = false
        passes += 1
        const succeeded = await refresh()
        if (!succeeded) {
          reconcileRequested.current = false
          break
        }

        const observed = latestStreamEvent.current
        const current = snapshotRef.current
        if (
          observed
          && (!current || current.streamId !== observed.streamId || current.glucoseRevision < observed.revision)
        ) {
          reconcileRequested.current = true
        }
      }

      reconcileRunning.current = false
      if (reconcileRequested.current && reconcileRetryTimer.current === null) {
        reconcileRetryTimer.current = window.setTimeout(() => {
          reconcileRetryTimer.current = null
          requestReconcileRef.current()
        }, 1_000)
      }
    })()
  }, [refresh])
  requestReconcileRef.current = requestReconcile

  useEffect(() => () => {
    refreshController.current?.abort()
    if (reconcileRetryTimer.current !== null) window.clearTimeout(reconcileRetryTimer.current)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const start = async () => {
      if (pendingLogoutOnMount) {
        const snapshotClear = clearSnapshot().then(
          () => null,
          (reason: unknown) => reason,
        )
        let session = null
        try {
          session = await getSession(controller.signal)
        } catch (reason) {
          if (!active || (reason instanceof DOMException && reason.name === 'AbortError')) return
        }

        // A previously interrupted private logout remains fail-closed. The
        // public mode is the sole exception because it has no session to end.
        if (session?.accessMode === 'public' && session.authenticated) {
          const snapshotFailure = await snapshotClear
          if (!active) return
          if (snapshotFailure) {
            setLogoutError('Браузер не подтвердил очистку прежней локальной копии. Повторите попытку.')
            return
          }
          forgetPendingLogout()
          rememberAccessMode('public')
          setSessionExpiresAt(null)
          setLogoutState('idle')
          setLogoutError(null)
          setServerSessionEnded(false)
          setLocalDataCleared(false)
          setAuthState('authenticated')
          await refresh(controller.signal)
          return
        }

        // A private or unavailable server cannot prove that the old session is
        // gone. Clear all local viewer data and keep the explicit retry screen.
        try {
          await clearDeviceData()
          if (!active) return
          setLocalDataCleared(true)
        } catch {
          if (!active) return
          setLogoutError('Серверный выход не подтверждён, и браузер не подтвердил очистку локальной копии. Повторите попытку.')
        }
        return
      }

      let cachedAccessMode: ViewerAccessMode | null = null
      try {
        const cached = await loadSnapshot()
        if (!active) return
        if (cached) {
          cachedAccessMode = cached.accessMode
          rememberAccessMode(cached.accessMode)
          hasData.current = true
          snapshotRef.current = cached.snapshot
          setSnapshot(cached.snapshot)
          setSavedAt(cached.savedAt)
          setClockAnchor({ serverTimeMs: cached.snapshot.serverTimeMs, observedAtMs: cached.savedAt })
          setClientNowMs(Date.now())
        }
      } catch {
        // A broken local cache should never block a network refresh.
      }
      try {
        const session = await getSession(controller.signal)
        if (!active) return
        rememberAccessMode(session.accessMode)
        if (session.accessMode === 'public' && cachedAccessMode === 'session') {
          // An older private cache may contain meals or insulin. Never render it
          // after the server declares this URL public and glucose-only.
          hasData.current = false
          snapshotRef.current = null
          setSnapshot(null)
          setSavedAt(null)
          await clearSnapshot().catch(() => undefined)
          if (!active) return
        }
        if (!session.authenticated) {
          setAuthState('unauthenticated')
          setSyncState(hasData.current ? 'offline' : 'error')
          return
        }
        setAuthState('authenticated')
        setSessionExpiresAt(session.expiresAtMs)
        await refresh(controller.signal)
      } catch (reason) {
        if (!active || (reason instanceof DOMException && reason.name === 'AbortError')) return
        if (hasData.current) {
          setAuthState('offline-cache')
          setSyncState('offline')
          setConnectionState('offline')
        } else {
          setAuthState('unauthenticated')
          setSyncState('error')
        }
        setError('Нет связи с сервером. Сохранённые данные останутся на устройстве.')
      }
    }
    void start()
    return () => {
      active = false
      controller.abort()
    }
  }, [pendingLogoutOnMount, refresh])

  useEffect(() => {
    const tick = () => setClientNowMs(Date.now())
    const timer = window.setInterval(tick, CLOCK_TICK_MS)
    const onVisibility = () => {
      if (document.visibilityState === 'visible') tick()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [])

  useEffect(() => {
    if (authState !== 'authenticated') return

    let stopped = false
    let generation = 0
    let stream: ReturnType<typeof connectViewerStream> | null = null
    let streamHealthy = false
    let lastActivityAt = performance.now()
    let restartTimer: number | null = null

    const clearRestartTimer = () => {
      if (restartTimer !== null) window.clearTimeout(restartTimer)
      restartTimer = null
    }

    const isBrowserOnline = () => navigator.onLine !== false

    const scheduleRestart = () => {
      if (stopped || restartTimer !== null || !isBrowserOnline()) return
      restartTimer = window.setTimeout(() => {
        restartTimer = null
        startStream(true)
      }, STREAM_RESTART_MS)
    }

    const observe = (event: ViewerStreamEvent, eventGeneration: number) => {
      if (stopped || eventGeneration !== generation) return
      streamHealthy = true
      lastActivityAt = performance.now()
      clearRestartTimer()
      setConnectionState('online')
      const observedAtMs = Date.now()
      setClockAnchor({ serverTimeMs: event.serverTimeMs, observedAtMs })
      setClientNowMs(observedAtMs)

      const previous = latestStreamEvent.current
      const streamChanged = previous !== null && previous.streamId !== event.streamId
      const gap = previous !== null
        && previous.streamId === event.streamId
        && event.revision > previous.revision + 1

      if (previous === null || streamChanged || event.revision > previous.revision) {
        latestStreamEvent.current = { streamId: event.streamId, revision: event.revision }
      }

      const current = snapshotRef.current
      const snapshotBehind = !current
        || current.streamId !== event.streamId
        || current.glucoseRevision < event.revision
      if (streamChanged || gap || snapshotBehind) requestReconcileRef.current()
    }

    function startStream(reconnecting: boolean) {
      if (stopped) return
      generation += 1
      const eventGeneration = generation
      streamHealthy = false
      stream?.close()
      stream = null
      clearRestartTimer()
      lastActivityAt = performance.now()

      if (!isBrowserOnline()) {
        setConnectionState('offline')
        return
      }

      setConnectionState(reconnecting ? 'reconnecting' : 'connecting')
      try {
        stream = connectViewerStream({
          onOpen: () => {
            if (stopped || eventGeneration !== generation) return
            lastActivityAt = performance.now()
            clearRestartTimer()
          },
          onEvent: (event) => observe(event, eventGeneration),
          onError: () => {
            if (stopped || eventGeneration !== generation) return
            streamHealthy = false
            setConnectionState(isBrowserOnline() ? 'reconnecting' : 'offline')
            requestReconcileRef.current()
            scheduleRestart()
          },
          onProtocolError: () => {
            if (stopped || eventGeneration !== generation) return
            streamHealthy = false
            setConnectionState('reconnecting')
            requestReconcileRef.current()
            scheduleRestart()
          },
        })
      } catch {
        streamHealthy = false
        setConnectionState(isBrowserOnline() ? 'reconnecting' : 'offline')
        requestReconcileRef.current()
        scheduleRestart()
      }
    }

    const onOffline = () => {
      generation += 1
      streamHealthy = false
      stream?.close()
      stream = null
      clearRestartTimer()
      setConnectionState('offline')
    }
    const onOnline = () => {
      startStream(true)
      requestReconcileRef.current()
    }
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return
      setClientNowMs(Date.now())
      requestReconcileRef.current()
      if (!streamHealthy) startStream(true)
    }

    if (isBrowserOnline()) startStream(false)
    else setConnectionState('offline')

    const watchdog = window.setInterval(() => {
      if (!isBrowserOnline() || performance.now() - lastActivityAt <= STREAM_STALE_MS) return
      streamHealthy = false
      setConnectionState('reconnecting')
      requestReconcileRef.current()
      startStream(true)
    }, STREAM_WATCHDOG_MS)

    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      stopped = true
      generation += 1
      stream?.close()
      clearRestartTimer()
      window.clearInterval(watchdog)
      window.removeEventListener('online', onOnline)
      window.removeEventListener('offline', onOffline)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [authState])

  useEffect(() => {
    if (authState !== 'authenticated') return
    const interval = connectionState === 'online' ? CONNECTED_RECONCILE_MS : FALLBACK_POLL_MS
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') requestReconcile()
    }, interval)
    return () => window.clearInterval(timer)
  }, [authState, connectionState, requestReconcile])

  useEffect(() => {
    if (authState !== 'offline-cache') return
    const controller = new AbortController()
    let recovering = false

    const recover = async () => {
      if (recovering || navigator.onLine === false || document.visibilityState !== 'visible') return
      recovering = true
      try {
        const session = await getSession(controller.signal)
        if (!session.authenticated) {
          setAuthState('unauthenticated')
          setError('Сессия закончилась. Введите ключ ещё раз.')
          return
        }
        if (session.accessMode === 'public' && accessModeRef.current === 'session') {
          hasData.current = false
          snapshotRef.current = null
          setSnapshot(null)
          setSavedAt(null)
          await clearSnapshot().catch(() => undefined)
        }
        rememberAccessMode(session.accessMode)
        setSessionExpiresAt(session.expiresAtMs)
        setConnectionState('connecting')
        setAuthState('authenticated')
        await refresh(controller.signal)
      } catch (reason) {
        if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
          setConnectionState('offline')
        }
      } finally {
        recovering = false
      }
    }

    const timer = window.setInterval(() => void recover(), FALLBACK_POLL_MS)
    const onOnline = () => void recover()
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void recover()
    }
    window.addEventListener('online', onOnline)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      controller.abort()
      window.clearInterval(timer)
      window.removeEventListener('online', onOnline)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [authState, refresh])

  const login = async (token: string) => {
    refreshSequence.current += 1
    refreshController.current?.abort()
    refreshController.current = null
    setError(null)
    setLogoutError(null)
    const session = await createSession(token)
    if (!session.authenticated) throw new ApiError('Сервер не создал сессию.', 401)
    rememberAccessMode(session.accessMode)
    latestStreamEvent.current = null
    setConnectionState(navigator.onLine === false ? 'offline' : 'connecting')
    setAuthState('authenticated')
    setSessionExpiresAt(session.expiresAtMs)
    await refresh()
  }

  const logoutAndClear = async () => {
    if (accessMode === 'public') return
    if (logoutRunning.current) return
    logoutRunning.current = true
    rememberPendingLogout()
    refreshSequence.current += 1
    refreshController.current?.abort()
    refreshController.current = null
    latestStreamEvent.current = null
    reconcileRequested.current = false
    if (reconcileRetryTimer.current !== null) {
      window.clearTimeout(reconcileRetryTimer.current)
      reconcileRetryTimer.current = null
    }
    setAuthState('logout-pending')
    setLogoutState('deleting')
    setLogoutError(null)
    setError(null)
    setPersistenceError(null)
    setServerSessionEnded(false)
    setLocalDataCleared(false)
    setRefreshing(false)
    hasData.current = false
    snapshotRef.current = null
    setSnapshot(null)
    setSavedAt(null)
    setClockAnchor(null)
    setConnectionState(navigator.onLine === false ? 'offline' : 'connecting')

    const serverAttempt = deleteSession().then(
      () => null,
      (reason: unknown) => reason,
    )
    let localFailure: unknown = null
    try {
      await persistQueue.current.catch(() => undefined)
      await clearDeviceData()
      setLocalDataCleared(true)
    } catch (reason) {
      localFailure = reason
    }
    const serverFailure = await serverAttempt
    if (!serverFailure) setServerSessionEnded(true)

    if (!serverFailure && !localFailure) {
      forgetPendingLogout()
      setSessionExpiresAt(null)
      setAuthState('unauthenticated')
      setSyncState('loading')
      setLogoutState('idle')
      logoutRunning.current = false
      return
    }

    setSyncState('error')
    setLogoutState('failed')
    if (serverFailure && localFailure) {
      setLogoutError('Сервер не подтвердил завершение сессии, а браузер не подтвердил очистку локальной копии. Повторите попытку.')
    } else if (serverFailure) {
      setLogoutError(`Локальные данные удалены, но сервер не подтвердил завершение сессии. ${message(serverFailure)}`)
    } else {
      setLogoutError(`Серверная сессия завершена, но локальная очистка не подтверждена. ${message(localFailure)}`)
    }
    logoutRunning.current = false
  }

  const clearOfflineCopy = async () => {
    if (accessModeRef.current !== 'public') return
    offlinePersistencePaused.current = true
    setPersistenceError(null)
    try {
      await persistQueue.current.catch(() => undefined)
      await clearSnapshot()
      setOfflineCopyPaused(true)
    } catch (reason) {
      offlinePersistencePaused.current = false
      setOfflineCopyPaused(false)
      throw reason
    }
  }

  const serverNowMs = useMemo(() => {
    if (!snapshot) return clientNowMs
    if (!clockAnchor) return effectiveServerNow(snapshot, savedAt, clientNowMs)
    return effectiveServerNow(
      { ...snapshot, serverTimeMs: clockAnchor.serverTimeMs },
      clockAnchor.observedAtMs,
      clientNowMs,
    )
  }, [clientNowMs, clockAnchor, savedAt, snapshot])

  return {
    snapshot,
    savedAt,
    authState,
    accessMode,
    syncState,
    sessionExpiresAt,
    error,
    persistenceError,
    logoutState,
    logoutError,
    serverSessionEnded,
    localDataCleared,
    refreshing,
    offlineCopyPaused,
    connectionState,
    serverNowMs,
    login,
    refresh,
    logoutAndClear,
    clearOfflineCopy,
  }
}
