import type { ViewerSnapshot } from './types'

const STALE_MS = 15 * 60_000

export function effectiveServerNow(
  snapshot: ViewerSnapshot,
  savedAt: number | null,
  clientNow = Date.now(),
): number {
  if (savedAt == null) return snapshot.serverTimeMs
  const elapsed = clientNow - savedAt
  if (elapsed < -5_000) {
    const newestReading = snapshot.currentGlucose ?? snapshot.glucoseHistory.at(-1)
    const readingExpiredAt = (newestReading?.measuredAtMs ?? snapshot.serverTimeMs) + STALE_MS + 1
    const forecastExpiredAt = (snapshot.forecast.points.at(-1)?.atMs ?? snapshot.serverTimeMs) + 1
    return Math.max(snapshot.serverTimeMs, readingExpiredAt, forecastExpiredAt)
  }
  return snapshot.serverTimeMs + Math.max(0, elapsed)
}

export function forecastIsCurrent(snapshot: ViewerSnapshot, savedAt: number | null, clientNow = Date.now()): boolean {
  if (snapshot.forecast.status === 'no_data' || snapshot.forecast.status === 'stale' || !snapshot.forecast.points.length) {
    return false
  }
  const anchor = snapshot.forecast.basedOnReadingAtMs
  const finalPoint = snapshot.forecast.points.at(-1)?.atMs
  if (anchor == null || finalPoint == null) return false
  const now = effectiveServerNow(snapshot, savedAt, clientNow)
  const anchorAge = now - anchor
  return anchorAge >= 0 && anchorAge <= STALE_MS && finalPoint >= anchor && now <= finalPoint
}
