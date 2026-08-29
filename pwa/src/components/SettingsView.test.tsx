import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SettingsView } from './SettingsView'

const install = {
  installed: true,
  canPrompt: false,
  isIos: false,
  install: vi.fn(async () => false),
}

function renderSettings(accessMode: 'public' | 'session') {
  const onClearOfflineCopy = vi.fn(async () => undefined)
  const view = render(
    <SettingsView
      theme="system"
      onThemeChange={vi.fn()}
      accessMode={accessMode}
      sessionExpiresAt={accessMode === 'session' ? Date.now() + 60_000 : null}
      savedAt={null}
      install={install}
      onLogout={vi.fn(async () => undefined)}
      offlineCopyPaused={false}
      onClearOfflineCopy={onClearOfflineCopy}
    />,
  )
  return { ...view, onClearOfflineCopy }
}

describe('SettingsView access mode', () => {
  it('describes public glucose-only access without session controls', async () => {
    const user = userEvent.setup()
    const { onClearOfflineCopy } = renderSettings('public')
    await screen.findByText(/0,1 МБ/)

    expect(screen.getByText('Публичный просмотр')).toBeInTheDocument()
    expect(screen.getByText(/Только сахар и прогноз/)).toBeInTheDocument()
    expect(screen.getByText(/Еда и инсулин скрыты/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Удалить сохранённые показания' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Выйти и удалить данные' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Сессия действует/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Удалить сохранённые показания' }))
    expect(onClearOfflineCopy).toHaveBeenCalledOnce()
  })

  it('preserves session expiry and logout controls for private access', async () => {
    renderSettings('session')
    await screen.findByText(/0,1 МБ/)

    expect(screen.getByText(/Сессия до/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Выйти и удалить данные' })).toBeInTheDocument()
    expect(screen.queryByText(/Публичная ссылка/)).not.toBeInTheDocument()
  })
})
