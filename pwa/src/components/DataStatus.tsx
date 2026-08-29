import { relativeAge } from '../format'
import type { SyncState } from '../types'
import { Icon } from './Icon'

interface DataStatusProps {
  state: SyncState
  stale: boolean
  savedAt: number | null
  refreshing: boolean
  onRefresh: () => void
}

export function DataStatus({ state, stale, savedAt, refreshing, onRefresh }: DataStatusProps) {
  const offline = state === 'offline'
  const label = offline ? 'Офлайн' : stale ? 'Данные устарели' : state === 'fresh' ? 'Актуально' : 'Обновление'
  const icon = offline ? 'cloud-off' : stale ? 'clock' : 'wifi'
  return (
    <div className={`data-status ${offline ? 'offline' : stale ? 'stale' : 'online'}`} role="status">
      <span className="status-copy">
        <Icon name={icon} size={18} />
        <span><strong>{label}</strong>{savedAt && <small> · сохранено {relativeAge(savedAt)}</small>}</span>
      </span>
      <button type="button" className="icon-button" onClick={onRefresh} disabled={refreshing} aria-label="Обновить данные">
        <Icon name="refresh" size={20} className={refreshing ? 'rotating' : ''} />
      </button>
    </div>
  )
}
