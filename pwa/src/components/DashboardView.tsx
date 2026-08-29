import { mmol, relativeAge, trend } from '../format'
import { effectiveServerNow, forecastIsCurrent } from '../freshness'
import type { RangeHours, ViewerSnapshot } from '../types'
import { GlucoseChart } from './GlucoseChart'
import { Icon } from './Icon'

interface DashboardProps {
  snapshot: ViewerSnapshot
  savedAt: number | null
  range: RangeHours
  onRangeChange: (range: RangeHours) => void
}

function glucoseState(value: number, low: number, high: number) {
  if (value < low) return { label: 'Низкий', tone: 'low' }
  if (value > high) return { label: 'Высокий', tone: 'high' }
  return { label: 'В цели', tone: 'target' }
}

export function DashboardView({ snapshot, savedAt, range, onRangeChange }: DashboardProps) {
  const current = snapshot.currentGlucose ?? snapshot.glucoseHistory.at(-1) ?? null
  const effectiveNow = effectiveServerNow(snapshot, savedAt)
  const readingTrend = current ? trend(current) : null
  const state = current
    ? glucoseState(current.glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl)
    : null
  const forecastPoint = snapshot.forecast.points.at(-1)
  const currentForecast = forecastIsCurrent(snapshot, savedAt)
  const forecastStart = snapshot.forecast.points.at(0)?.medianMgDl ?? current?.glucoseMgDl ?? null
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
          {current ? (
            <>
              <div className="glucose-glance-value">
                <strong>{mmol(current.glucoseMgDl)}</strong>
                <span>ммоль/л</span>
                <span className="trend-arrow" aria-label={readingTrend!.label}>{readingTrend!.arrow}</span>
              </div>
              <div className="glucose-glance-meta">
                <span className={`state-pill ${state!.tone}`}>{state!.label}</span>
                <span>{relativeAge(current.measuredAtMs, effectiveNow)}</span>
              </div>
            </>
          ) : (
            <div className="empty-current compact-empty">
              <Icon name="droplet" size={24} />
              <span>Нет данных</span>
            </div>
          )}
        </section>

        {forecastPoint && currentForecast && (
          <div
            className="forecast-glance"
            aria-label={`Прогноз ${mmol(forecastPoint.medianMgDl)} миллимоль на литр, ${forecastDirection.label}, уверенность ${Math.round(snapshot.forecast.confidence * 100)} процентов`}
          >
            <span>Прогноз</span>
            <strong>{mmol(forecastPoint.medianMgDl)}</strong>
            <small><span aria-hidden="true">{forecastDirection.arrow}</span> {Math.round(snapshot.forecast.confidence * 100)}%</small>
          </div>
        )}
      </div>

      <div className="dashboard-chart-stage">
        <GlucoseChart snapshot={snapshot} savedAt={savedAt} range={range} onRangeChange={onRangeChange} />
      </div>
    </div>
  )
}
