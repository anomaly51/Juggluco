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
    render(
      <GlucoseChart
        snapshot={normalizeSnapshot(rawSnapshot)}
        savedAt={Date.now()}
        serverNowMs={rawSnapshot.server_time_ms}
        range={6}
        onRangeChange={onRangeChange}
      />,
    )

    expect(screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })).toBeInTheDocument()
    expect(screen.getByText('прогноз 5,6 ммоль/л · 76%')).toBeInTheDocument()
    expect(screen.getByText('цель 4,2–9')).toBeInTheDocument()
    expect(screen.getByText(/Последнее значение 6,0 ммоль\/л/, { selector: 'desc' })).toBeInTheDocument()
    expect(screen.getByText(/Уверенность прогноза 76%/, { selector: 'desc' })).toBeInTheDocument()
    expect(document.querySelector('.forecast-line')).toHaveAttribute('data-line-style', 'dashed')
    expect(document.querySelector('.forecast-line')).toHaveAttribute('stroke-dasharray', '8 7')
    expect(document.querySelector('.forecast-line')).not.toHaveAttribute('data-glucose-zone')
    expect(document.querySelector('.forecast-band')).toHaveAttribute('data-series', 'forecast-uncertainty')
    expect(document.querySelector('.forecast-endpoint')).toHaveAttribute('data-forecast-confidence', '76')
    expect(screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ }))
      .toHaveAttribute('data-chart-end-ms', '12600000')
    expect(screen.getByRole('button', { name: 'Показать данные за 8 часов' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Показать данные за 12 часов' }))
    expect(onRangeChange).toHaveBeenCalledWith(12)
  })

  it('matches Android history zones while keeping the ready forecast one distinctly dashed series', () => {
    const crossingSnapshot = normalizeSnapshot({
      ...rawSnapshot,
      current_glucose: { ...rawSnapshot.current_glucose, measured_at_ms: 1_400_000, glucose_mg_dl: 180 },
      glucose_history: [
        { ...rawSnapshot.glucose_history[0], reading_id: 'low', measured_at_ms: 1_300_000, glucose_mg_dl: 60 },
        { ...rawSnapshot.glucose_history[0], reading_id: 'target', measured_at_ms: 1_350_000, glucose_mg_dl: 100 },
        { ...rawSnapshot.glucose_history[0], reading_id: 'high', measured_at_ms: 1_400_000, glucose_mg_dl: 180 },
      ],
      forecast: {
        ...rawSnapshot.forecast,
        points: [
          { at_ms: 2_100_000, median_mg_dl: 60, low_mg_dl: 50, high_mg_dl: 70 },
          { at_ms: 2_200_000, median_mg_dl: 100, low_mg_dl: 85, high_mg_dl: 120 },
          { at_ms: 2_300_000, median_mg_dl: 180, low_mg_dl: 150, high_mg_dl: 210 },
        ],
      },
    })
    render(<GlucoseChart snapshot={crossingSnapshot} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    expect(Array.from(chart.querySelectorAll('.glucose-zone-band')).map((band) => band.getAttribute('data-glucose-zone')))
      .toEqual(['high', 'target', 'low'])
    expect(Array.from(chart.querySelectorAll('.actual-line')).map((line) => line.getAttribute('data-glucose-zone')))
      .toEqual(['low', 'target', 'high'])
    expect(chart.querySelectorAll('.forecast-line')).toHaveLength(1)
    expect(chart.querySelector('.forecast-line')).toHaveAttribute('data-line-style', 'dashed')
    expect(chart.querySelector('.forecast-line')).toHaveAttribute('stroke-dasharray', '8 7')
    expect(chart.querySelector('.forecast-line')).not.toHaveAttribute('data-glucose-zone')
    expect(chart.querySelectorAll('.forecast-band')).toHaveLength(1)
    expect(chart.querySelector('.forecast-band')).not.toHaveAttribute('data-glucose-zone')

    const targetBand = chart.querySelector('.target-zone-band')!
    const targetTop = Number(targetBand.getAttribute('y'))
    const targetBottom = targetTop + Number(targetBand.getAttribute('height'))
    const lowPathNumbers = chart.querySelector('.actual-line.glucose-low')!.getAttribute('d')!.match(/-?\d+(?:\.\d+)?/g)!.map(Number)
    const highPathNumbers = chart.querySelector('.actual-line.glucose-high')!.getAttribute('d')!.match(/-?\d+(?:\.\d+)?/g)!.map(Number)
    expect(lowPathNumbers.at(-1)).toBeCloseTo(targetBottom, 1)
    expect(highPathNumbers[1]).toBeCloseTo(targetTop, 1)
    const forecastStroke = chart.querySelector('.forecast-line')!.getAttribute('stroke')!
    const forecastGradient = document.getElementById(forecastStroke.slice(5, -1))!
    const stops = forecastGradient.querySelectorAll('stop')
    const gradientTop = Number(forecastGradient.getAttribute('y1'))
    const gradientHeight = Number(forecastGradient.getAttribute('y2')) - gradientTop
    expect(Number(stops[1].getAttribute('offset')) * gradientHeight + gradientTop).toBeCloseTo(targetTop, 5)
    expect(stops[1].getAttribute('offset')).toEqual(stops[2].getAttribute('offset'))
    expect(Number(stops[3].getAttribute('offset')) * gradientHeight + gradientTop).toBeCloseTo(targetBottom, 5)
    expect(stops[3].getAttribute('offset')).toEqual(stops[4].getAttribute('offset'))
  })

  it('keeps a cold-start forecast out of the prediction while reserving the real future window', () => {
    const snapshot = normalizeSnapshot({
      ...rawSnapshot,
      forecast: {
        ...rawSnapshot.forecast,
        status: 'cold_start',
        confidence: 0.34,
        points: rawSnapshot.forecast.points.map((point) => ({
          ...point,
          median_mg_dl: 112,
          low_mg_dl: 57,
          high_mg_dl: 167,
        })),
      },
    })
    render(
      <GlucoseChart
        snapshot={snapshot}
        savedAt={Date.now()}
        serverNowMs={rawSnapshot.server_time_ms}
        range={6}
        onRangeChange={vi.fn()}
      />,
    )

    const chart = screen.getByRole('img', { name: 'Сахар за 6 часов' })
    expect(chart).toHaveAttribute('data-forecast-state', 'pending')
    expect(chart).toHaveAttribute('data-chart-end-ms', String(rawSnapshot.server_time_ms + 180 * 60_000))
    expect(chart).toHaveAttribute('data-future-window-minutes', '180')
    expect(chart).toHaveAttribute('data-forecast-horizon-minutes', '120')
    expect(chart.querySelector('.forecast-line')).toBeNull()
    expect(chart.querySelector('.forecast-band')).toBeNull()
    expect(chart.querySelector('.forecast-endpoint')).toBeNull()
    expect(chart.querySelector('.forecast-series-label')).toBeNull()
    expect(chart.querySelector('.now-line')).not.toBeNull()
    expect(chart.querySelector('.future-window')).toHaveAttribute('data-series', 'future-window')
    expect(screen.getByText(/Прогноз ещё не готов/, { selector: 'desc' })).toBeInTheDocument()
  })

  it('keeps exact 4.2 and 9.0 mmol/L boundaries inside the target zone', () => {
    const boundarySnapshot = normalizeSnapshot({
      ...rawSnapshot,
      current_glucose: { ...rawSnapshot.current_glucose, glucose_mg_dl: 162 },
      glucose_history: [
        { ...rawSnapshot.glucose_history[1], glucose_mg_dl: 75.6 },
        { ...rawSnapshot.glucose_history[0], glucose_mg_dl: 162 },
      ],
    })
    render(<GlucoseChart snapshot={boundarySnapshot} savedAt={Date.now()} range={6} onRangeChange={vi.fn()} />)

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    const boundaryDots = chart.querySelectorAll('.actual-dot')
    expect(boundaryDots).toHaveLength(2)
    for (const dot of boundaryDots) expect(dot).toHaveAttribute('data-glucose-zone', 'target')
    expect(chart.querySelector('.latest-dot')).toHaveAttribute('data-glucose-zone', 'target')
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

    const forecastEndpoint = chart.querySelector('.forecast-endpoint')!
    fireEvent(chart, new MouseEvent('pointerdown', {
      bubbles: true,
      clientX: Number(forecastEndpoint.getAttribute('cx')),
      clientY: Number(forecastEndpoint.getAttribute('cy')),
    }))
    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'forecast')
    expect(chart.querySelector('.inspection-line.horizontal')).not.toBeInTheDocument()
    expect(chart).not.toHaveFocus()
    fireEvent(chart, new MouseEvent('pointercancel', { bubbles: true }))
    expect(screen.queryByTestId('chart-inspector')).not.toBeInTheDocument()

    act(() => chart.focus())
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

  it('filters touch candidates by horizontal reach before comparing their vertical distance', () => {
    const snapshot = {
      ...normalizeSnapshot(rawSnapshot),
      insulinEvents: [
        { occurredAtMs: -1_000_000, insulinUnits: 5, insulinType: 'rapid' as const, insulinName: 'NovoRapid' },
      ],
    }
    render(
      <GlucoseChart
        snapshot={snapshot}
        savedAt={Date.now()}
        serverNowMs={rawSnapshot.server_time_ms}
        range={6}
        onRangeChange={vi.fn()}
      />,
    )

    const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })
    Object.defineProperty(chart, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({ left: 0, top: 0, right: 820, bottom: 330, width: 820, height: 330, x: 0, y: 0, toJSON: () => ({}) }),
    })
    const latest = chart.querySelector('.latest-dot')!
    const distantInsulin = screen.getByTestId('insulin-marker-rapid')
    fireEvent(chart, new MouseEvent('pointerdown', {
      bubbles: true,
      clientX: Number(latest.getAttribute('cx')),
      clientY: Number(distantInsulin.getAttribute('data-chart-y')),
    }))

    expect(screen.getByTestId('chart-inspector')).toHaveAttribute('data-point-kind', 'actual')
  })

  it('matches its SVG coordinate system to the live chart container', async () => {
    class TestResizeObserver {
      constructor(private readonly callback: ResizeObserverCallback) {}

      observe() {
        this.callback([
          { contentRect: { width: 375, height: 510 } } as ResizeObserverEntry,
        ], this as unknown as ResizeObserver)
      }

      disconnect() {}
      unobserve() {}
    }

    vi.stubGlobal('ResizeObserver', TestResizeObserver)
    try {
      const { rerender } = render(
        <GlucoseChart
          snapshot={normalizeSnapshot(rawSnapshot)}
          savedAt={Date.now()}
          serverNowMs={rawSnapshot.server_time_ms}
          range={6}
          onRangeChange={vi.fn()}
        />,
      )
      const chart = screen.getByRole('img', { name: /^Сахар за 6 часов и прогноз/ })

      await waitFor(() => expect(chart).toHaveAttribute('viewBox', '0 0 375 510'))
      expect(chart.closest('.chart-viewport')).toHaveAttribute('data-chart-height', '510')
      expect(chart).toHaveAttribute('data-future-window-minutes', '180')
      expect(chart).toHaveAttribute('data-forecast-horizon-minutes', '120')
      const plotLeft = 10
      const plotWidth = 375 - plotLeft - 42
      const nowRatio = (Number(chart.getAttribute('data-chart-now-x')) - plotLeft) / plotWidth
      expect(nowRatio).toBeCloseTo(2 / 3, 4)
      expect(screen.getByText('5 ЕД')).toBeInTheDocument()
      expect(screen.getByText('12 ЕД')).toBeInTheDocument()

      rerender(
        <GlucoseChart
          snapshot={normalizeSnapshot(rawSnapshot)}
          savedAt={Date.now()}
          serverNowMs={rawSnapshot.server_time_ms}
          range={24}
          onRangeChange={vi.fn()}
        />,
      )
      const timeTickXs = Array.from(chart.querySelectorAll('[data-axis="time"]'))
        .map((tick) => Number(tick.getAttribute('x')))
      expect(timeTickXs).toHaveLength(3)
      for (let index = 1; index < timeTickXs.length; index += 1) {
        expect(timeTickXs[index] - timeTickXs[index - 1]).toBeGreaterThanOrEqual(54)
      }
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
    expect(chart.querySelector('[data-glucose-mg-dl="20"]')).toHaveAttribute('data-glucose-zone', 'low')
    expect(chart.querySelector('[data-glucose-mg-dl="600"]')).toHaveAttribute('data-glucose-zone', 'high')
    expect(chart.querySelector('.latest-dot')).toHaveAttribute('data-glucose-zone', 'high')
  })
})
