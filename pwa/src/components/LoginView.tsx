import { type FormEvent, useState } from 'react'
import { Icon } from './Icon'

interface LoginViewProps {
  error: string | null
  onLogin: (token: string) => Promise<void>
}

export function LoginView({ error: initialError, onLogin }: LoginViewProps) {
  const [token, setToken] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(initialError)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const submittedToken = token.trim()
    if (!submittedToken) {
      setError('Введите ключ просмотра.')
      return
    }
    setToken('')
    setSubmitting(true)
    setError(null)
    try {
      await onLogin(submittedToken)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Не удалось войти.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-screen">
      <section className="login-card" aria-labelledby="login-title">
        <div className="brand-mark"><Icon name="droplet" size={30} /></div>
        <p className="eyebrow">Личный просмотр</p>
        <h1 id="login-title">Ваш сахар — без лишнего</h1>
        <p className="login-intro">
          Введите ключ просмотра один раз. Приложение создаст защищённую сессию и не сохранит сам ключ на телефоне.
        </p>
        <form onSubmit={submit} className="login-form">
          <label htmlFor="viewer-token">Ключ просмотра</label>
          <input
            id="viewer-token"
            type="password"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            disabled={submitting}
            aria-describedby="token-help"
          />
          <p id="token-help" className="field-help">Ключ отправляется только вашему серверу и не попадает в offline-копию.</p>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button type="submit" className="primary-button" disabled={submitting}>
            {submitting ? <span className="spinner" aria-hidden="true" /> : <Icon name="shield" size={20} />}
            {submitting ? 'Подключаем…' : 'Открыть приложение'}
          </button>
        </form>
        <div className="privacy-line">
          <Icon name="shield" size={18} />
          <span>Только чтение. Изменить записи из PWA невозможно.</span>
        </div>
      </section>
    </main>
  )
}
