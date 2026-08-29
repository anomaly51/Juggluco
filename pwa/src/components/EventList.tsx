import { dateTime, eventAmount, eventTitle } from '../format'
import type { EventKind, IntakeEvent } from '../types'
import { Icon } from './Icon'

interface EventListProps {
  events: IntakeEvent[]
  limit?: number
  emptyText?: string
}

function eventIcon(kind: EventKind) {
  return kind === 'meal' ? 'food' : kind === 'rapid' || kind === 'long' ? 'insulin' : 'history'
}

export function EventList({ events, limit, emptyText = 'Событий пока нет.' }: EventListProps) {
  const visible = [...events].sort((a, b) => b.occurredAtMs - a.occurredAtMs).slice(0, limit)
  if (!visible.length) return <p className="empty-state">{emptyText}</p>
  return (
    <ol className="event-list">
      {visible.map((event) => (
        <li key={event.id} className="event-row">
          <span className={`event-icon ${event.kind}`}><Icon name={eventIcon(event.kind)} size={20} /></span>
          <span className="event-copy">
            <strong>{eventTitle(event)}</strong>
            <span>{eventAmount(event)}</span>
          </span>
          <time dateTime={new Date(event.occurredAtMs).toISOString()}>{dateTime(event.occurredAtMs)}</time>
        </li>
      ))}
    </ol>
  )
}
