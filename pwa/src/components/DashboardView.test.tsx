import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { normalizeSnapshot } from '../normalize'
import { rawSnapshot } from '../test/fixtures'
import { DashboardView } from './DashboardView'

describe('DashboardView freshness', () => {
  it('ticks the visible reading age locally without changing the live announcement', () => {
    const snapshot = normalizeSnapshot(rawSnapshot)
    const measuredAtMs = snapshot.currentGlucose!.measuredAtMs
    const props = {
      snapshot,
      savedAt: 123,
      range: 6 as const,
      onRangeChange: vi.fn(),
    }
    const { rerender } = render(<DashboardView {...props} serverNowMs={measuredAtMs + 5_000} />)
    const announcement = screen.getByRole('status').textContent

    expect(screen.getByText('только что')).toBeInTheDocument()

    rerender(<DashboardView {...props} serverNowMs={measuredAtMs + 35_000} />)

    expect(screen.getByText('35 сек назад')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(announcement!)
  })

  it('shows the ready endpoint with its explicit backend horizon and confidence', () => {
    const snapshot = normalizeSnapshot(rawSnapshot)
    render(
      <DashboardView
        snapshot={snapshot}
        savedAt={Date.now()}
        serverNowMs={rawSnapshot.server_time_ms}
        range={6}
        onRangeChange={vi.fn()}
      />,
    )

    expect(screen.getByText('через 2 ч')).toBeInTheDocument()
    expect(screen.getByText('не для дозы')).toBeInTheDocument()
    expect(screen.getByLabelText(/Экспериментальный прогноз, не для расчёта дозы.*Через 2 ч: 5,6 миллимоль на литр.*уверенность 76 процентов/))
      .toBeInTheDocument()
  })

  it('uses a compact pending card for a fresh cold start but hides stale forecast state', () => {
    const base = normalizeSnapshot(rawSnapshot)
    const coldStart = {
      ...base,
      forecast: { ...base.forecast, status: 'cold_start' },
    }
    const props = {
      savedAt: Date.now(),
      serverNowMs: rawSnapshot.server_time_ms,
      range: 6 as const,
      onRangeChange: vi.fn(),
    }
    const { rerender } = render(<DashboardView {...props} snapshot={coldStart} />)

    expect(screen.getByText('Прогноз ещё не готов')).toBeInTheDocument()
    expect(screen.queryByText('через 2 ч')).not.toBeInTheDocument()

    rerender(<DashboardView {...props} snapshot={{
      ...base,
      forecast: { ...base.forecast, status: 'stale' },
    }} />)
    expect(screen.queryByText('Прогноз ещё не готов')).not.toBeInTheDocument()
    expect(screen.queryByText('через 2 ч')).not.toBeInTheDocument()
  })
})
