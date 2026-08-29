import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { useInstall } from './hooks/useInstall'
import { usePwaUpdate } from './hooks/usePwaUpdate'
import { useTheme } from './hooks/useTheme'
import { useViewer } from './hooks/useViewer'
import { normalizeSnapshot } from './normalize'
import { rawSnapshot } from './test/fixtures'

vi.mock('./hooks/useInstall', () => ({ useInstall: vi.fn() }))
vi.mock('./hooks/usePwaUpdate', () => ({ usePwaUpdate: vi.fn() }))
vi.mock('./hooks/useTheme', () => ({ useTheme: vi.fn() }))
vi.mock('./hooks/useViewer', () => ({ useViewer: vi.fn() }))

function publicViewerState(): ReturnType<typeof useViewer> {
  return {
    snapshot: normalizeSnapshot(rawSnapshot),
    savedAt: Date.now(),
    authState: 'authenticated',
    accessMode: 'public',
    syncState: 'fresh',
    sessionExpiresAt: null,
    error: null,
    persistenceError: null,
    logoutState: 'idle',
    logoutError: null,
    serverSessionEnded: false,
    localDataCleared: false,
    refreshing: false,
    offlineCopyPaused: false,
    connectionState: 'online',
    serverNowMs: rawSnapshot.server_time_ms,
    login: vi.fn(async () => undefined),
    refresh: vi.fn(async () => true),
    logoutAndClear: vi.fn(async () => undefined),
    clearOfflineCopy: vi.fn(async () => undefined),
  }
}

describe('App public viewer', () => {
  beforeEach(() => {
    vi.mocked(useTheme).mockReturnValue({ theme: 'system', setTheme: vi.fn() })
    vi.mocked(useInstall).mockReturnValue({
      installed: true,
      canPrompt: false,
      isIos: false,
      install: vi.fn(async () => false),
    })
    vi.mocked(usePwaUpdate).mockReturnValue({
      updateReady: false,
      applyUpdate: vi.fn(async () => undefined),
      dismissUpdate: vi.fn(),
    })
    vi.mocked(useViewer).mockReturnValue(publicViewerState())
  })

  it('opens a graph-first screen and keeps public settings in a dialog', async () => {
    const user = userEvent.setup()
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Текущий сахар' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Ключ просмотра')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Последние события' })).not.toBeInTheDocument()
    expect(screen.queryByText('Рис')).not.toBeInTheDocument()
    expect(screen.getByTestId('insulin-marker-rapid')).toHaveAttribute('data-insulin-name', 'NovoRapid')
    expect(screen.getByTestId('insulin-marker-long')).toHaveAttribute('data-insulin-name', 'Tresiba')
    expect(screen.queryByRole('navigation', { name: 'Основная навигация' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'События' })).not.toBeInTheDocument()
    expect(screen.getByText('Онлайн')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Обновить данные' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Показать данные за 6 часов' })).toHaveAttribute('aria-pressed', 'true')

    const settingsButton = screen.getByRole('button', { name: 'Настройки' })
    await user.click(settingsButton)
    expect(screen.getByRole('dialog', { name: 'Настройки' })).toBeInTheDocument()
    expect(screen.getByText('Публичный просмотр')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Выйти и удалить данные' })).not.toBeInTheDocument()

    expect(screen.getByRole('button', { name: 'Закрыть' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Настройки' })).not.toBeInTheDocument()
    expect(settingsButton).toHaveFocus()
  })

  it('keeps private events available from a compact dialog', async () => {
    const user = userEvent.setup()
    vi.mocked(useViewer).mockReturnValue({ ...publicViewerState(), accessMode: 'session' })
    render(<App />)

    expect(screen.queryByText('NovoRapid')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'События' }))

    expect(screen.getByRole('dialog', { name: 'События' })).toBeInTheDocument()
    expect(screen.getByText('NovoRapid')).toBeInTheDocument()
  })

  it('keeps cached private content behind the splash until access mode is known', () => {
    vi.mocked(useViewer).mockReturnValue({
      ...publicViewerState(),
      snapshot: normalizeSnapshot(rawSnapshot),
      authState: 'checking',
      accessMode: 'session',
    })

    render(<App />)

    expect(screen.getByLabelText('Загрузка приложения')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Последние события' })).not.toBeInTheDocument()
    expect(screen.queryByText('Рис')).not.toBeInTheDocument()
    expect(screen.queryByText('NovoRapid')).not.toBeInTheDocument()
  })
})
