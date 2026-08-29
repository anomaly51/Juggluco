import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { normalizeSnapshot } from '../normalize'
import { rawSnapshot } from '../test/fixtures'
import { GlucoseChart } from './GlucoseChart'

describe('GlucoseChart', () => {
  it('keeps the detailed narrative accessible while exposing compact range controls', async () => {
    const user = userEvent.setup()
    const onRangeChange = vi.fn()
    render(<GlucoseChart snapshot={normalizeSnapshot(rawSnapshot)} savedAt={Date.now()} range={6} onRangeChange={onRangeChange} />)

    expect(screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })).toBeInTheDocument()
    expect(screen.getByText('прогноз 5,6 ммоль/л')).toBeInTheDocument()
    expect(screen.getByText('цель 4,2–9')).toBeInTheDocument()
    expect(screen.getByText(/Последнее значение 6,0 ммоль\/л/, { selector: 'desc' })).toBeInTheDocument()
    expect(screen.getByText(/Уверенность прогноза 76%/, { selector: 'desc' })).toBeInTheDocument()
    expect(document.querySelector('.forecast-line')).toHaveAttribute('data-line-style', 'dashed')
    expect(document.querySelector('.forecast-line')).toHaveAttribute('stroke-dasharray', '8 7')
    expect(screen.getByRole('button', { name: 'Показать данные за 8 часов' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Показать данные за 12 часов' }))
    expect(onRangeChange).toHaveBeenCalledWith(12)
  })

  it('renders rapid and long insulin as distinct, collision-aware annotations without changing the glucose scale', () => {
    const baseline = normalizeSnapshot(rawSnapshot)
    const snapshot = {
      ...baseline,
      insulinEvents: [
        { occurredAtMs: 1_200_000, insulinUnits: 5, insulinType: 'rapid' as const, insulinName: 'NovoRapid' },
        { occurredAtMs: 1_205_000, insulinUnits: 12, insulinType: 'long' as const, insulinName: 'Tresiba' },
      ],
    }
    const { rerender } = render(
      <GlucoseChart snapshot={baseline} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />,
    )
    const baselineChart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    const baselineScale = [
      baselineChart.getAttribute('data-y-min-mg-dl'),
      baselineChart.getAttribute('data-y-max-mg-dl'),
    ]

    rerender(<GlucoseChart snapshot={snapshot} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)

    const rapid = screen.getByTestId('insulin-marker-rapid')
    const long = screen.getByTestId('insulin-marker-long')
    expect(rapid).toHaveAttribute('data-insulin-name', 'NovoRapid')
    expect(rapid.querySelector('.rapid-insulin-marker-shape')).toHaveProperty('tagName', 'path')
    expect(long).toHaveAttribute('data-insulin-name', 'Tresiba')
    expect(long.querySelector('.long-insulin-marker-shape')).toHaveProperty('tagName', 'rect')
    expect(screen.getByText('NovoRapid 5 ЕД')).toBeInTheDocument()
    expect(screen.getByText('Tresiba 12 ЕД')).toBeInTheDocument()
    expect(rapid).not.toHaveAttribute('data-chart-y', long.getAttribute('data-chart-y'))

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    expect([
      chart.getAttribute('data-y-min-mg-dl'),
      chart.getAttribute('data-y-max-mg-dl'),
    ]).toEqual(baselineScale)
    expect(screen.getByText(/Отметки инсулина: NovoRapid 5 ЕД.*Tresiba 12 ЕД/, { selector: 'desc' })).toBeInTheDocument()
  })

  it('lets pointer and keyboard users inspect insulin annotations with name, dose, and time', () => {
    const snapshot = {
      ...normalizeSnapshot(rawSnapshot),
      insulinEvents: [
        { occurredAtMs: 1_200_000, insulinUnits: 5, insulinType: 'rapid' as const, insulinName: 'NovoRapid' },
        { occurredAtMs: 1_300_000, insulinUnits: 12, insulinType: 'long' as const, insulinName: 'Tresiba' },
      ],
    }
    render(<GlucoseChart snapshot={snapshot} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    Object.defineProperty(chart, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({ left: 0, top: 0, right: 820, bottom: 330, width: 820, height: 330, x: 0, y: 0, toJSON: () => ({}) }),
    })
    const rapid = screen.getByTestId('insulin-marker-rapid')
    fireEvent(chart, new MouseEvent('pointerdown', {
      bubbles: true,
      clientX: Number(rapid.getAttribute('data-chart-x')),
      clientY: Number(rapid.getAttribute('data-chart-y')),
    }))
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'insulin-rapid')
    expect(screen.getByText(/Инсулин NovoRapid, 5 ЕД, в .*Быстрый инсулин/, { selector: '.chart-inspection-live' })).toBeInTheDocument()

    fireEvent.keyDown(chart, { key: 'Home' })
    fireEvent.keyDown(chart, { key: 'ArrowRight' })
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'insulin-rapid')
    fireEvent.keyDown(chart, { key: 'ArrowRight' })
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'insulin-long')
    expect(screen.getByText(/Инсулин Tresiba, 12 ЕД, в .*Длительный инсулин/, { selector: '.chart-inspection-live' })).toBeInTheDocument()
  })

  it('lets touch, pointer, and keyboard users inspect points and zoom the time range', () => {
    const onRangeChange = vi.fn()
    render(<GlucoseChart snapshot={normalizeSnapshot(rawSnapshot)} savedAt={Date.now()} range={6} onRangeChange={onRangeChange} />)

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    Object.defineProperty(chart, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({ left: 0, top: 0, right: 820, bottom: 330, width: 820, height: 330, x: 0, y: 0, toJSON: () => ({}) }),
    })

    fireEvent(chart, new MouseEvent('pointerdown', { bubbles: true, clientX: 812, clientY: 160 }))
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'forecast')

    fireEvent.focus(chart)
    fireEvent.keyDown(chart, { key: 'Home' })
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'actual')
    expect(screen.getByText(/Измерение в .*7,0 ммоль\/л/, { selector: '.chart-inspection-live' })).toBeInTheDocument()

    fireEvent.keyDown(chart, { key: 'End' })
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'forecast')
    expect(screen.getByText(/Прогноз на .*5,6 ммоль\/л/, { selector: '.chart-inspection-live' })).toBeInTheDocument()

    fireEvent.keyDown(chart, { key: '+' })
    fireEvent.keyDown(chart, { key: '-' })
    expect(onRangeChange).toHaveBeenCalledWith(3)
    expect(onRangeChange).toHaveBeenCalledWith(8)
  })

  it('matches its SVG coordinate system to the live chart container', async () => {
    class TestResizeObserver {
      constructor(private readonly callback: ResizeObserverCallback) {}

      observe() {
        this.callback([
          { contentRect: { width: 390, height: 510 } } as ResizeObserverEntry,
        ], this as unknown as ResizeObserver)
      }

      disconnect() {}
      unobserve() {}
    }

    vi.stubGlobal('ResizeObserver', TestResizeObserver)
    try {
      render(<GlucoseChart snapshot={normalizeSnapshot(rawSnapshot)} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)
      const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })

      await waitFor(() => expect(chart).toHaveAttribute('viewBox', '0 0 390 510'))
      expect(chart.closest('.chart-viewport')).toHaveAttribute('data-chart-height', '510')
    } finally {
      act(() => vi.unstubAllGlobals())
    }
  })

  it('expands its scale so backend-valid 20 and 600 mg/dL readings remain inside the plot', () => {
    const extremeSnapshot = normalizeSnapshot({
      ...rawSnapshot,
      current_glucose: { ...rawSnapshot.current_glucose, glucose_mg_dl: 600 },
      glucose_history: rawSnapshot.glucose_history.map((reading) => ({
        ...reading,
        glucose_mg_dl: reading.reading_id === 'old' ? 20 : 600,
      })),
      forecast: {
        ...rawSnapshot.forecast,
        points: rawSnapshot.forecast.points.map((point, index) => ({
          ...point,
          median_mg_dl: index === 0 ? 600 : 20,
          low_mg_dl: 20,
          high_mg_dl: 600,
        })),
      },
    })
    render(<GlucoseChart snapshot={extremeSnapshot} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    expect(Number(chart.getAttribute('data-y-min-mg-dl'))).toBeLessThan(20)
    expect(Number(chart.getAttribute('data-y-max-mg-dl'))).toBeGreaterThan(600)

    for (const glucose of [20, 600]) {
      const point = chart.querySelector(`[data-glucose-mg-dl="${glucose}"]`)
      expect(point).not.toBeNull()
      const y = Number(point?.getAttribute('cy'))
      expect(y).toBeGreaterThanOrEqual(18)
      expect(y).toBeLessThanOrEqual(298)
    }
  })
})
