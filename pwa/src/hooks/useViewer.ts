import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, createSession, deleteSession, fetchSnapshot, getSession } from '../api'
import { clearDeviceData, clearSnapshot, loadSnapshot, saveSnapshot } from '../db'
import type { AuthState, LogoutState, SyncState, ViewerAccessMode, ViewerSnapshot } from '../types'

const LOGOUT_PENDING_KEY = 'juggluco-viewer:logout-pending'

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
  const hasData = useRef(false)
  const refreshSequence = useRef(0)
  const refreshController = useRef<AbortController | null>(null)
  const persistQueue = useRef<Promise<void>>(Promise.resolve())
  const logoutRunning = useRef(false)
  const accessModeRef = useRef<ViewerAccessMode>('session')
  const offlinePersistencePaused = useRef(false)

  const rememberAccessMode = (mode: ViewerAccessMode) => {
    accessModeRef.current = mode
    setAccessMode(mode)
  }

  const refresh = useCallback(async (parentSignal?: AbortSignal) => {
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
      if (sequence !== refreshSequence.current) return
      const now = Date.now()

      // A local storage failure must not delay or invalidate fresh server data.
      hasData.current = true
      setSnapshot(next)
      setSavedAt(now)
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
    } catch (reason) {
      if (sequence !== refreshSequence.current || (reason instanceof DOMException && reason.name === 'AbortError')) return
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
    } finally {
      parentSignal?.removeEventListener('abort', abortFromParent)
      if (sequence === refreshSequence.current) {
        refreshController.current = null
        setRefreshing(false)
      }
    }
  }, [])

  useEffect(() => () => refreshController.current?.abort(), [])

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
          setSnapshot(cached.snapshot)
          setSavedAt(cached.savedAt)
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
    if (authState !== 'authenticated') return
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, 60_000)
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void refresh()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(timer)
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
    setAuthState('logout-pending')
    setLogoutState('deleting')
    setLogoutError(null)
    setError(null)
    setPersistenceError(null)
    setServerSessionEnded(false)
    setLocalDataCleared(false)
    setRefreshing(false)
    hasData.current = false
    setSnapshot(null)
    setSavedAt(null)

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
    login,
    refresh,
    logoutAndClear,
    clearOfflineCopy,
  }
}
