import { dateTime, mmol, relativeAge, trend } from '../format'
import { forecastHorizonLabel, forecastPresentationAt } from '../forecastPresentation'
import type { RangeHours, ViewerSnapshot } from '../types'
import { GlucoseChart } from './GlucoseChart'
import { Icon } from './Icon'

interface DashboardProps {
  snapshot: ViewerSnapshot
  savedAt: number | null
  serverNowMs: number
  range: RangeHours
  onRangeChange: (range: RangeHours) => void
}

function glucoseState(value: number, low: number, high: number) {
  if (value < low) return { label: 'Низкий', tone: 'low' }
  if (value > high) return { label: 'Высокий', tone: 'high' }
  return { label: 'В цели', tone: 'target' }
}

export function DashboardView({ snapshot, savedAt, serverNowMs, range, onRangeChange }: DashboardProps) {
  const current = snapshot.currentGlucose ?? snapshot.glucoseHistory.at(-1) ?? null
  const readingTrend = current ? trend(current) : null
  const state = current
    ? glucoseState(current.glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl)
    : null
  const forecastPresentation = forecastPresentationAt(snapshot, serverNowMs)
  const forecastPoint = forecastPresentation.kind === 'ready' ? forecastPresentation.endpoint : null
  const forecastStart = forecastPresentation.kind === 'ready'
    ? forecastPresentation.points.at(0)?.medianMgDl ?? current?.glucoseMgDl ?? null
    : null
  const forecastDelta = forecastPoint && forecastStart != null
    ? forecastPoint.medianMgDl - forecastStart
    : 0
  const forecastDirection = forecastDelta > 9
    ? { arrow: '↗', label: 'рост' }
    : forecastDelta < -9
      ? { arrow: '↘', label: 'снижение' }
      : { arrow: '→', label: 'стабильно' }

  return (
    <div className="dashboard-screen">
      <h1 className="sr-only">Сахар</h1>
      <div className="dashboard-glance-row">
        <section className={`glucose-glance ${state?.tone ?? 'empty'}`} aria-labelledby="current-heading">
          <h2 id="current-heading" className="sr-only">Текущий сахар</h2>
          <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
            {current
              ? `Новое показание сахара: ${mmol(current.glucoseMgDl)} миллимоль на литр, ${readingTrend!.label}, измерено ${dateTime(current.measuredAtMs)}`
              : 'Новых показаний сахара нет'}
          </span>
          {current ? (
            <>
              <div className="glucose-glance-value">
                <strong>{mmol(current.glucoseMgDl)}</strong>
                <span>ммоль/л</span>
                <span className="trend-arrow" aria-label={readingTrend!.label}>{readingTrend!.arrow}</span>
              </div>
              <div className="glucose-glance-meta">
                <span className={`state-pill ${state!.tone}`}>{state!.label}</span>
                <time dateTime={new Date(current.measuredAtMs).toISOString()} title={dateTime(current.measuredAtMs)}>
                  {relativeAge(current.measuredAtMs, serverNowMs)}
                </time>
              </div>
            </>
          ) : (
            <div className="empty-current compact-empty">
              <Icon name="droplet" size={24} />
              <span>Нет данных</span>
            </div>
          )}
        </section>

        {forecastPoint && forecastPresentation.kind === 'ready' && (
          <div
            className="forecast-glance"
            aria-label={`Экспериментальный прогноз, не для расчёта дозы. Через ${forecastHorizonLabel(forecastPresentation.horizonMinutes)}: ${mmol(forecastPoint.medianMgDl)} миллимоль на литр, ${forecastDirection.label}, уверенность ${Math.round(forecastPresentation.confidence * 100)} процентов`}
          >
            <span>Прогноз</span>
            <strong>{mmol(forecastPoint.medianMgDl)}</strong>
            <small><span aria-hidden="true">{forecastDirection.arrow}</span> {Math.round(forecastPresentation.confidence * 100)}%</small>
            <small className="forecast-horizon">через {forecastHorizonLabel(forecastPresentation.horizonMinutes)}</small>
            <small className="forecast-safety">не для дозы</small>
          </div>
        )}
        {forecastPresentation.kind === 'pending' && (
          <div className="forecast-glance pending" aria-label={forecastPresentation.label}>
            <span>{forecastPresentation.label}</span>
          </div>
        )}
      </div>

      <div className="dashboard-chart-stage">
        <GlucoseChart snapshot={snapshot} savedAt={savedAt} serverNowMs={serverNowMs} range={range} onRangeChange={onRangeChange} />
      </div>
    </div>
  )
}
