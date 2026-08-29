import { forecastIsCurrentAt, STALE_MS } from './freshness'
import type { ForecastPoint, ViewerSnapshot } from './types'

type ForecastPendingStatus = 'cold_start' | 'learning' | 'low_confidence'

export type ForecastPresentation =
  | {
      kind: 'ready'
      points: ForecastPoint[]
      endpoint: ForecastPoint
      confidence: number
      horizonMinutes: number
    }
  | {
      kind: 'pending'
      label: 'Прогноз ещё не готов'
      status: ForecastPendingStatus
    }
  | {
      kind: 'hidden'
    }

const PENDING_STATUSES = new Set<ForecastPendingStatus>([
  'cold_start',
  'learning',
  'low_confidence',
])

function pendingStatus(value: string): value is ForecastPendingStatus {
  return PENDING_STATUSES.has(value as ForecastPendingStatus)
}

function hasRecentAnchor(snapshot: ViewerSnapshot, serverNowMs: number): boolean {
  const anchor = snapshot.forecast.basedOnReadingAtMs
  if (anchor == null) return false
  const age = serverNowMs - anchor
  return age >= 0 && age <= STALE_MS
}

function validPoint(point: ForecastPoint): boolean {
  return Number.isFinite(point.atMs)
    && Number.isFinite(point.medianMgDl)
    && Number.isFinite(point.lowMgDl)
    && Number.isFinite(point.highMgDl)
    && point.lowMgDl <= point.medianMgDl
    && point.medianMgDl <= point.highMgDl
}

/**
 * Converts backend forecast state into an explicit UI contract.
 *
 * Baseline/learning states can contain mathematically valid points, but those
 * points are not presented as a finished prediction. Only a fresh `ready`
 * trajectory reaches the chart; the UI never manufactures fallback points.
 */
export function forecastPresentationAt(
  snapshot: ViewerSnapshot,
  serverNowMs: number,
): ForecastPresentation {
  const status = snapshot.forecast.status

  if (pendingStatus(status)) {
    return hasRecentAnchor(snapshot, serverNowMs)
      ? { kind: 'pending', label: 'Прогноз ещё не готов', status }
      : { kind: 'hidden' }
  }
  if (status !== 'ready' || !forecastIsCurrentAt(snapshot, serverNowMs)) {
    return { kind: 'hidden' }
  }

  const points = snapshot.forecast.points.filter((point) => point.atMs >= serverNowMs)
  if (points.length < 2 || points.some((point) => !validPoint(point))) {
    return { kind: 'hidden' }
  }
  for (let index = 1; index < points.length; index += 1) {
    if (points[index].atMs <= points[index - 1].atMs) return { kind: 'hidden' }
  }

  return {
    kind: 'ready',
    points,
    endpoint: points.at(-1)!,
    confidence: snapshot.forecast.confidence,
    horizonMinutes: snapshot.forecast.horizonMinutes,
  }
}

export function forecastHorizonLabel(minutes: number): string {
  const rounded = Math.max(1, Math.round(minutes))
  const hours = Math.floor(rounded / 60)
  const remainingMinutes = rounded % 60
  if (hours === 0) return `${remainingMinutes} мин`
  if (remainingMinutes === 0) return `${hours} ч`
  return `${hours} ч ${remainingMinutes} мин`
}
