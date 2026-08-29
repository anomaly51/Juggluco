import type { LogoutState } from '../types'
import { Icon } from './Icon'

interface SessionExitViewProps {
  state: Exclude<LogoutState, 'idle'>
  serverSessionEnded: boolean
  localDataCleared: boolean
  error: string | null
  onRetry: () => Promise<void>
}

export function SessionExitView({
  state,
  serverSessionEnded,
  localDataCleared,
  error,
  onRetry,
}: SessionExitViewProps) {
  const deleting = state === 'deleting'
  return (
    <main className="logout-screen">
      <section className="logout-card" aria-labelledby="logout-heading" aria-live="polite">
        <div className={`logout-symbol ${deleting ? '' : 'warning'}`}>
          {deleting ? <span className="spinner large" aria-hidden="true" /> : <Icon name="warning" size={30} />}
        </div>
        <p className="eyebrow">Защита данных</p>
        <h1 id="logout-heading">{deleting ? 'Завершаем доступ…' : 'Выход не завершён'}</h1>
        <p className="logout-intro">
          Данные скрыты, а новые показания не загружаются. Полный выход подтверждается отдельно на телефоне и сервере.
        </p>
        <ul className="logout-status-list" aria-label="Состояние выхода">
          <li className={localDataCleared ? 'done' : ''}>
            <Icon name={localDataCleared ? 'check' : deleting ? 'clock' : 'warning'} size={20} />
            <span>
              <strong>Данные на телефоне</strong>
              {localDataCleared ? 'Локальный снимок и offline-кэш удалены.' : deleting ? 'Очищаем локальную копию…' : 'Удаление локальной копии не подтверждено.'}
            </span>
          </li>
          <li className={serverSessionEnded ? 'done' : ''}>
            <Icon name={serverSessionEnded ? 'check' : deleting ? 'clock' : 'warning'} size={20} />
            <span>
              <strong>Сессия на сервере</strong>
              {serverSessionEnded ? 'Сервер подтвердил завершение сессии.' : deleting ? 'Ждём подтверждение сервера…' : 'Cookie-сессия может оставаться активной.'}
            </span>
          </li>
        </ul>
        {!deleting && error && <p className="logout-error" role="alert">{error}</p>}
        {!deleting && (
          <button type="button" className="danger-button logout-retry" onClick={() => void onRetry()}>
            <Icon name="refresh" size={20} />Повторить выход
          </button>
        )}
      </section>
    </main>
  )
}
