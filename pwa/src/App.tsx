import { useEffect, useRef, useState } from 'react'
import { relativeAge } from './format'
import { readingIsStaleAt } from './freshness'
import { useInstall } from './hooks/useInstall'
import { usePwaUpdate } from './hooks/usePwaUpdate'
import { useTheme } from './hooks/useTheme'
import { useViewer } from './hooks/useViewer'
import type { RangeHours } from './types'
import { DashboardView } from './components/DashboardView'
import { DataStatus } from './components/DataStatus'
import { EventsView } from './components/EventsView'
import { Icon } from './components/Icon'
import { LoginView } from './components/LoginView'
import { SessionExitView } from './components/SessionExitView'
import { SettingsView } from './components/SettingsView'

type AppPanel = 'events' | 'settings'

const FOCUSABLE = [
  'button:not([disabled])',
  '[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export default function App() {
  const viewer = useViewer()
  const { theme, setTheme } = useTheme()
  const install = useInstall()
  const update = usePwaUpdate()
  const [panel, setPanel] = useState<AppPanel | null>(null)
  const [range, setRange] = useState<RangeHours>(6)
  const panelRef = useRef<HTMLElement | null>(null)
  const panelWasOpenRef = useRef(false)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const publicViewer = viewer.accessMode === 'public'

  const stale = viewer.snapshot ? readingIsStaleAt(viewer.snapshot, viewer.serverNowMs) : false

  const openPanel = (nextPanel: AppPanel, trigger: HTMLElement) => {
    returnFocusRef.current = trigger
    setPanel(nextPanel)
  }

  const closePanel = () => {
    setPanel(null)
  }

  useEffect(() => {
    if (!panel) {
      if (panelWasOpenRef.current) returnFocusRef.current?.focus()
      panelWasOpenRef.current = false
      return
    }
    panelWasOpenRef.current = true
    const dialog = panelRef.current
    if (!dialog) return

    const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
    ;(focusable[0] ?? dialog).focus()

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closePanel()
        return
      }
      if (event.key !== 'Tab') return

      const controls = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (controls.length === 0) {
        event.preventDefault()
        dialog.focus()
        return
      }
      const first = controls[0]
      const last = controls.at(-1)!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [panel])

  if (viewer.accessMode === 'session' && viewer.logoutState !== 'idle') {
    return (
      <SessionExitView
        state={viewer.logoutState}
        serverSessionEnded={viewer.serverSessionEnded}
        localDataCleared={viewer.localDataCleared}
        error={viewer.logoutError}
        onRetry={viewer.logoutAndClear}
      />
    )
  }

  if (viewer.authState === 'checking') {
    return (
      <main className="splash-screen" aria-label="Загрузка приложения">
        <div className="brand-mark"><Icon name="droplet" size={30} /></div>
        <span className="spinner large" aria-hidden="true" />
        <p>Загрузка…</p>
      </main>
    )
  }

  if (viewer.authState === 'unauthenticated' || (!viewer.snapshot && viewer.authState !== 'authenticated')) {
    return <LoginView error={viewer.error} onLogin={viewer.login} />
  }

  if (!viewer.snapshot) {
    return (
      <main className="splash-screen" aria-live="polite">
        <span className="spinner large" aria-hidden="true" />
        <p>Синхронизация…</p>
        {viewer.error && <p className="form-error">{viewer.error}</p>}
      </main>
    )
  }

  const snapshot = viewer.snapshot

  return (
    <div className="app-shell graph-app-shell">
      <a className="skip-link" href="#main-content" inert={Boolean(panel)}>Перейти к графику</a>
      <header className="app-header compact-header" inert={Boolean(panel)}>
        <div className="header-inner compact-header-inner">
          <div className="brand-lockup compact-brand" aria-label="Juggluco">
            <span className="brand-mark small"><Icon name="activity" size={20} /></span>
            <strong>Juggluco</strong>
          </div>
          <div className="header-controls">
            <DataStatus
              state={viewer.connectionState}
              stale={stale}
            />
            {!publicViewer && (
              <button
                type="button"
                className="icon-button header-action"
                aria-label="События"
                aria-haspopup="dialog"
                aria-expanded={panel === 'events'}
                onClick={(event) => openPanel('events', event.currentTarget)}
              >
                <Icon name="history" size={20} />
              </button>
            )}
            <button
              type="button"
              className="icon-button header-action"
              aria-label="Настройки"
              aria-haspopup="dialog"
              aria-expanded={panel === 'settings'}
              onClick={(event) => openPanel('settings', event.currentTarget)}
            >
              <Icon name="gear" size={20} />
            </button>
          </div>
        </div>
      </header>

      <div className="app-notices" aria-live="polite" inert={Boolean(panel)}>
        {update.updateReady && (
          <aside className="update-banner" aria-label="Доступно обновление">
            <div><Icon name="download" size={20} /><span><strong>Доступно обновление</strong></span></div>
            <div className="update-actions">
              <button type="button" className="text-button" onClick={update.dismissUpdate}>Позже</button>
              <button type="button" className="primary-button compact-button" onClick={() => void update.applyUpdate()}>Обновить</button>
            </div>
          </aside>
        )}

        {viewer.error && viewer.authState !== 'offline-cache' && (
          <div className="inline-alert" role="alert"><Icon name="warning" size={18} />{viewer.error}</div>
        )}
        {viewer.persistenceError && (
          <div className="storage-alert" role="status"><Icon name="warning" size={18} /><span>{viewer.persistenceError}</span></div>
        )}
        {viewer.authState === 'offline-cache' && (
          <div className="offline-banner" role="status">
            <Icon name="cloud-off" size={19} />
            <span>Офлайн-копия · {viewer.savedAt ? relativeAge(viewer.savedAt) : 'сохранена ранее'}</span>
          </div>
        )}
      </div>

      <main id="main-content" className="main-content graph-main" tabIndex={-1} inert={Boolean(panel)}>
        <DashboardView
          snapshot={snapshot}
          savedAt={viewer.savedAt}
          serverNowMs={viewer.serverNowMs}
          range={range}
          onRangeChange={setRange}
        />
      </main>

      {panel && (
        <div className="app-overlay-backdrop" onPointerDown={(event) => {
          if (event.target === event.currentTarget) closePanel()
        }}>
          <section
            ref={panelRef}
            className={`app-overlay ${panel}-overlay`}
            role="dialog"
            aria-modal="true"
            aria-labelledby={`${panel}-dialog-title`}
            tabIndex={-1}
          >
            <header className="overlay-header">
              <h2 id={`${panel}-dialog-title`}>{panel === 'settings' ? 'Настройки' : 'События'}</h2>
              <button type="button" className="icon-button overlay-close" onClick={closePanel} aria-label="Закрыть">
                <Icon name="x" size={22} />
              </button>
            </header>
            <div className="overlay-content">
              {panel === 'events' && !publicViewer && <EventsView snapshot={snapshot} />}
              {panel === 'settings' && (
                <SettingsView
                  theme={theme}
                  onThemeChange={setTheme}
                  accessMode={viewer.accessMode}
                  sessionExpiresAt={viewer.sessionExpiresAt}
                  savedAt={viewer.savedAt}
                  install={install}
                  onLogout={viewer.logoutAndClear}
                  offlineCopyPaused={viewer.offlineCopyPaused}
                  onClearOfflineCopy={viewer.clearOfflineCopy}
                />
              )}
            </div>
          </section>
        </div>
      )}
    </div>
  )
}
