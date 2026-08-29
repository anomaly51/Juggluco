import { describe, expect, it } from 'vitest'
import { normalizeSnapshot } from './normalize'
import { forecastHorizonLabel, forecastPresentationAt } from './forecastPresentation'
import { rawSnapshot } from './test/fixtures'

describe('forecast presentation policy', () => {
  const snapshot = normalizeSnapshot(rawSnapshot)
  const now = rawSnapshot.server_time_ms

  it('presents only a fresh ready trajectory and keeps a stable ready forecast', () => {
    const stable = {
      ...snapshot,
      forecast: {
        ...snapshot.forecast,
        status: 'ready',
        points: snapshot.forecast.points.map((point) => ({
          ...point,
          medianMgDl: 108,
          lowMgDl: 88,
          highMgDl: 128,
        })),
      },
    }

    const presentation = forecastPresentationAt(stable, now)
    expect(presentation.kind).toBe('ready')
    if (presentation.kind === 'ready') {
      expect(presentation.points).toHaveLength(2)
      expect(new Set(presentation.points.map((point) => point.medianMgDl))).toEqual(new Set([108]))
      expect(presentation.endpoint.atMs).toBe(2_400_000)
      expect(presentation.horizonMinutes).toBe(120)
    }
  })

  it.each(['cold_start', 'learning', 'low_confidence'] as const)(
    'keeps fresh %s data out of the chart while exposing a compact pending state',
    (status) => {
      const presentation = forecastPresentationAt({
        ...snapshot,
        forecast: { ...snapshot.forecast, status },
      }, now)

      expect(presentation).toEqual({ kind: 'pending', label: 'Прогноз ещё не готов', status })
    },
  )

  it.each(['no_data', 'stale'] as const)('hides %s instead of presenting it as pending', (status) => {
    expect(forecastPresentationAt({
      ...snapshot,
      forecast: { ...snapshot.forecast, status },
    }, now)).toEqual({ kind: 'hidden' })
  })

  it('fails closed for expired, single-point, or malformed ready trajectories', () => {
    expect(forecastPresentationAt(snapshot, snapshot.forecast.basedOnReadingAtMs! + 15 * 60_000 + 1))
      .toEqual({ kind: 'hidden' })
    expect(forecastPresentationAt({
      ...snapshot,
      forecast: { ...snapshot.forecast, points: [snapshot.forecast.points[0]] },
    }, now)).toEqual({ kind: 'hidden' })
    expect(forecastPresentationAt({
      ...snapshot,
      forecast: {
        ...snapshot.forecast,
        points: snapshot.forecast.points.map((point) => ({ ...point, lowMgDl: point.medianMgDl + 1 })),
      },
    }, now)).toEqual({ kind: 'hidden' })
  })

  it('formats the backend horizon for the compact card', () => {
    expect(forecastHorizonLabel(120)).toBe('2 ч')
    expect(forecastHorizonLabel(90)).toBe('1 ч 30 мин')
    expect(forecastHorizonLabel(45)).toBe('45 мин')
  })
})
