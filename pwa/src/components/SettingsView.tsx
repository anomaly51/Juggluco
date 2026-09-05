import { useEffect, useState } from 'react'
import { requestPersistentStorage } from '../db'
import { dateTime } from '../format'
import type { ThemeMode, ViewerAccessMode } from '../types'
import { Icon } from './Icon'

interface SettingsProps {
  theme: ThemeMode
  onThemeChange: (theme: ThemeMode) => void
  accessMode: ViewerAccessMode
  sessionExpiresAt: number | null
  savedAt: number | null
  install: {
    installed: boolean
    canPrompt: boolean
    isIos: boolean
    install: () => Promise<boolean>
  }
  onLogout: () => Promise<void>
  offlineCopyPaused: boolean
  onClearOfflineCopy: () => Promise<void>
}

export function SettingsView({
  theme,
  onThemeChange,
  accessMode,
  sessionExpiresAt,
  savedAt,
  install,
  onLogout,
  offlineCopyPaused,
  onClearOfflineCopy,
}: SettingsProps) {
  const [confirmLogout, setConfirmLogout] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [persistent, setPersistent] = useState<boolean | null>(null)
  const [storageUsage, setStorageUsage] = useState<string | null>(null)
  const [clearingOfflineCopy, setClearingOfflineCopy] = useState(false)
  const [offlineClearError, setOfflineClearError] = useState<string | null>(null)

  useEffect(() => {
    if (!navigator.storage?.estimate) return
    void navigator.storage.estimate().then(({ usage }) => {
      if (typeof usage === 'number') {
        setStorageUsage(`${Math.max(0.1, usage / 1024 / 1024).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} МБ`)
      }
    })
  }, [])

  const protectStorage = async () => {
    setPersistent(await requestPersistentStorage())
  }

  const logout = async () => {
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
    }
  }

  const clearOfflineCopy = async () => {
    setClearingOfflineCopy(true)
    setOfflineClearError(null)
    try {
      await onClearOfflineCopy()
    } catch {
      setOfflineClearError('Не удалось удалить offline-копию.')
    } finally {
      setClearingOfflineCopy(false)
    }
  }

  return (
    <div className="settings-panel-body">
      <section className="settings-section" aria-labelledby="appearance-heading">
        <div className="settings-section-heading">
          <Icon name="sun" size={20} />
          <h3 id="appearance-heading">Тема</h3>
        </div>
        <div className="segmented theme-picker" aria-label="Тема приложения">
          {([
            ['system', 'Система'],
            ['dark', 'Тёмная'],
            ['light', 'Светлая'],
          ] as [ThemeMode, string][]).map(([value, label]) => (
            <button
              type="button"
              key={value}
              className={theme === value ? 'selected' : ''}
              aria-pressed={theme === value}
              onClick={() => onThemeChange(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </section>

      <section className="settings-section" aria-labelledby="install-heading">
        <div className="settings-section-heading">
          <Icon name="smartphone" size={20} />
          <h3 id="install-heading">На телефоне</h3>
        </div>
        {install.installed ? (
          <p className="success-copy compact-status"><Icon name="check" size={17} />Установлено</p>
        ) : install.canPrompt ? (
          <button type="button" className="secondary-button" onClick={() => void install.install()}>
            <Icon name="download" size={19} />Установить
          </button>
        ) : install.isIos ? (
          <p className="settings-hint">Safari → Поделиться → На экран «Домой»</p>
        ) : (
          <p className="settings-hint">Меню браузера → Установить</p>
        )}
      </section>

      <section className="settings-section" aria-labelledby="offline-heading">
        <div className="settings-section-heading">
          <Icon name="cloud-off" size={20} />
          <h3 id="offline-heading">Офлайн</h3>
        </div>
        <p className="settings-hint">
          {offlineCopyPaused
            ? 'Копия очищена'
            : savedAt
              ? `Снимок: ${dateTime(savedAt)}`
              : 'Код приложения сохранён'}
          {storageUsage && ` · ${storageUsage}`}
        </p>
        {persistent === true ? (
          <p className="success-copy compact-status"><Icon name="check" size={17} />Хранение защищено</p>
        ) : (
          <button type="button" className="secondary-button" onClick={() => void protectStorage()}>
            <Icon name="shield" size={19} />Защитить копию
          </button>
        )}
        {persistent === false && <p className="field-help">Хранением управляет iOS.</p>}
      </section>

      <section className="settings-section" aria-labelledby="access-heading">
        <div className="settings-section-heading">
          <Icon name="shield" size={20} />
          <h3 id="access-heading">Доступ</h3>
        </div>
        {accessMode === 'public' ? (
          <div className="public-access-copy compact-access">
            <strong>Публичный просмотр</strong>
            <p>Сахар, прогноз и отметки инсулина. Личные записи скрыты.</p>
            {offlineCopyPaused ? (
              <p className="success-copy compact-status" role="status"><Icon name="check" size={17} />Копия удалена</p>
            ) : (
              <button type="button" className="secondary-button" onClick={() => void clearOfflineCopy()} disabled={clearingOfflineCopy}>
                <Icon name="trash" size={19} />{clearingOfflineCopy ? 'Удаляем…' : 'Удалить сохранённые показания'}
              </button>
            )}
            {offlineClearError && <p className="form-error" role="alert">{offlineClearError}</p>}
          </div>
        ) : (
          <div className="session-access compact-access">
            <p>{sessionExpiresAt ? `Сессия до ${dateTime(sessionExpiresAt)}` : 'Защищённая сессия'}</p>
            {!confirmLogout ? (
              <button type="button" className="danger-button subtle" onClick={() => setConfirmLogout(true)}>
                <Icon name="trash" size={19} />Выйти и удалить данные
              </button>
            ) : (
              <div className="confirm-panel compact-confirm" role="alert">
                <strong>Удалить данные?</strong>
                <div className="confirm-actions">
                  <button type="button" className="secondary-button" onClick={() => setConfirmLogout(false)}>Отмена</button>
                  <button type="button" className="danger-button" onClick={() => void logout()} disabled={loggingOut}>
                    {loggingOut ? 'Удаляем…' : 'Удалить'}
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
