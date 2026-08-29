import { useState } from 'react'
import type { EventKind, ViewerSnapshot } from '../types'
import { EventList } from './EventList'

type Filter = 'all' | Exclude<EventKind, 'other'>

const filters: { value: Filter; label: string }[] = [
  { value: 'all', label: 'Все' },
  { value: 'meal', label: 'Еда' },
  { value: 'rapid', label: 'Быстрый' },
  { value: 'long', label: 'Длительный' },
]

export function EventsView({ snapshot }: { snapshot: ViewerSnapshot }) {
  const [filter, setFilter] = useState<Filter>('all')
  const [visibleCount, setVisibleCount] = useState(50)
  const events = filter === 'all' ? snapshot.intakeEvents : snapshot.intakeEvents.filter((event) => event.kind === filter)
  return (
    <section className="card events-page" aria-labelledby="events-heading">
      <div className="section-heading">
        <p className="eyebrow">Только просмотр</p>
        <h1 id="events-heading">События</h1>
        <p className="section-intro">Еда и инсулин, записанные в основном Android-приложении.</p>
      </div>
      <div className="filter-chips" aria-label="Фильтр событий">
        {filters.map((item) => (
          <button
            type="button"
            key={item.value}
            className={filter === item.value ? 'selected' : ''}
            aria-pressed={filter === item.value}
            onClick={() => {
              setFilter(item.value)
              setVisibleCount(50)
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
      <EventList events={events} limit={visibleCount} emptyText="В этой категории событий нет." />
      {visibleCount < events.length && (
        <button type="button" className="secondary-button load-more" onClick={() => setVisibleCount((count) => count + 50)}>
          Показать ещё
        </button>
      )}
      {snapshot.intakeEventsTruncated && <p className="list-note">Показаны только последние события из снимка.</p>}
    </section>
  )
}
