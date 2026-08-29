import { describe, expect, it } from 'vitest'
import { normalizeSnapshot } from './normalize'
import { rawSnapshot } from './test/fixtures'

describe('normalizeSnapshot', () => {
  it('normalizes ordering and locks the target to 4.2–9 mmol/L', () => {
    const result = normalizeSnapshot(rawSnapshot)

    expect(result.targetRange).toEqual({ lowMgDl: 75.6, highMgDl: 162, lowMmolL: 4.2, highMmolL: 9 })
    expect(result.glucoseHistory.map((item) => item.readingId)).toEqual(['old', 'new'])
    expect(result.forecast.points.map((item) => item.atMs)).toEqual([2_100_000, 2_400_000])
    expect(result.intakeEvents.map((item) => item.kind)).toEqual(['meal', 'rapid'])
    expect(result.insulinEvents).toEqual([
      { occurredAtMs: 750_000, insulinUnits: 12, insulinType: 'long', insulinName: 'Tresiba' },
      { occurredAtMs: 1_200_000, insulinUnits: 5, insulinType: 'rapid', insulinName: 'NovoRapid' },
    ])
  })

  it('treats an explicit empty insulin list as authoritative', () => {
    const result = normalizeSnapshot({ ...rawSnapshot, insulin_events: [] })

    expect(result.insulinEvents).toEqual([])
  })

  it('derives sanitized insulin events from legacy intake rows when the new field is absent', () => {
    const legacyInput = Object.fromEntries(
      Object.entries(rawSnapshot).filter(([key]) => key !== 'insulin_events'),
    )
    const result = normalizeSnapshot(legacyInput)

    expect(result.insulinEvents).toEqual([
      { occurredAtMs: 1_200_000, insulinUnits: 5, insulinType: 'rapid', insulinName: 'NovoRapid' },
    ])
  })

  it('accepts camelCase insulin fields and ignores unsupported insulin categories', () => {
    const result = normalizeSnapshot({
      ...rawSnapshot,
      insulin_events: undefined,
      insulinEvents: [
        { occurredAtMs: 1_300_000, insulinUnits: 4, insulinType: 'rapid', insulinName: 'NovoRapid' },
        { occurredAtMs: 1_400_000, insulinUnits: 2, insulinType: 'unsupported', insulinName: 'Unknown' },
      ],
    })

    expect(result.insulinEvents).toEqual([
      { occurredAtMs: 1_300_000, insulinUnits: 4, insulinType: 'rapid', insulinName: 'NovoRapid' },
    ])
  })

  it('rejects non-finite insulin times and doses', () => {
    expect(() => normalizeSnapshot({
      ...rawSnapshot,
      insulin_events: [{
        occurred_at_ms: Number.NaN,
        insulin_units: 5,
        insulin_type: 'rapid',
        insulin_name: 'NovoRapid',
      }],
    })).toThrow('Некорректные данные: insulin event time')

    expect(() => normalizeSnapshot({
      ...rawSnapshot,
      insulin_events: [{
        occurred_at_ms: 1_200_000,
        insulin_units: Number.POSITIVE_INFINITY,
        insulin_type: 'rapid',
        insulin_name: 'NovoRapid',
      }],
    })).toThrow('Некорректные данные: insulin event units')
  })

  it('rejects insulin events outside the backend medical bounds', () => {
    for (const insulinUnits of [0, -1, 501]) {
      expect(() => normalizeSnapshot({
        ...rawSnapshot,
        insulin_events: [{
          occurred_at_ms: 1_200_000,
          insulin_units: insulinUnits,
          insulin_type: 'rapid',
          insulin_name: 'NovoRapid',
        }],
      })).toThrow('Некорректные данные: insulin event units')
    }
  })

  it('drops tombstones and unknown response fields from the local shape', () => {
    const input = {
      ...rawSnapshot,
      secret: 'must-not-survive',
      intake_events: [{ ...rawSnapshot.intake_events[0], deleted: true }],
    }
    const result = normalizeSnapshot(input) as unknown as Record<string, unknown>

    expect(result.secret).toBeUndefined()
    expect(result.intakeEvents).toEqual([])
  })

  it('rejects a malformed snapshot', () => {
    expect(() => normalizeSnapshot({ forecast: { points: [] } })).toThrow('Некорректный формат снимка')
  })
})
