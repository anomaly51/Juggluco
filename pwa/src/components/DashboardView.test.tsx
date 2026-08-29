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
})
