import type { ConnectionState } from '../types'
import { Icon } from './Icon'

interface DataStatusProps {
  state: ConnectionState
  stale: boolean
}

export function DataStatus({ state, stale }: DataStatusProps) {
  const offline = state === 'offline'
  const connecting = state === 'connecting' || state === 'reconnecting'
  const label = offline ? 'Офлайн' : connecting ? 'Подключение' : stale ? 'Нет новых данных' : 'Онлайн'
  const icon = offline ? 'cloud-off' : connecting || stale ? 'clock' : 'wifi'
  const tone = offline ? 'offline' : connecting ? 'connecting' : stale ? 'stale' : 'online'

  return (
    <div className={`data-status ${tone}`} aria-label={`Состояние данных: ${label}`}>
      <span className="status-copy">
        <Icon name={icon} size={18} />
        <span><strong>{label}</strong></span>
      </span>
    </div>
  )
}
