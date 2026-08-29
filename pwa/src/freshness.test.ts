import { describe, expect, it } from 'vitest'
import { effectiveServerNow, forecastIsCurrent, readingIsStaleAt, STALE_MS } from './freshness'
import { normalizeSnapshot } from './normalize'
import { rawSnapshot } from './test/fixtures'

describe('server-clock freshness', () => {
  const snapshot = normalizeSnapshot(rawSnapshot)

  it('ages cached data from the server clock, not the device epoch', () => {
    expect(effectiveServerNow(snapshot, 10_000, 70_000)).toBe(snapshot.serverTimeMs + 60_000)
  })

  it('fails closed after a backwards wall-clock jump', () => {
    const now = effectiveServerNow(snapshot, 70_000, 10_000)
    expect(now).toBeGreaterThan(snapshot.currentGlucose!.measuredAtMs + 15 * 60_000)
    expect(forecastIsCurrent(snapshot, 70_000, 10_000)).toBe(false)
  })

  it('ages the current reading locally across the exact stale boundary', () => {
    const measuredAt = snapshot.currentGlucose!.measuredAtMs
    expect(readingIsStaleAt(snapshot, measuredAt + STALE_MS)).toBe(false)
    expect(readingIsStaleAt(snapshot, measuredAt + STALE_MS + 1)).toBe(true)
  })
})
