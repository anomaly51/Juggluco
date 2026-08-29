import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'
import { mmol, time } from '../format'
import { effectiveServerNow, forecastIsCurrent } from '../freshness'
import type { ForecastPoint, GlucoseReading, RangeHours, ViewerSnapshot } from '../types'

const DEFAULT_SIZE = { width: 820, height: 330 }
const GAP_MS = 10 * 60_000
const RANGE_OPTIONS = [3, 6, 8, 12, 24] as RangeHours[]

interface ChartProps {
  snapshot: ViewerSnapshot
  savedAt: number | null
  range: RangeHours
  onRangeChange: (range: RangeHours) => void
}

interface InspectablePoint {
  key: string
  atMs: number
  valueMgDl?: number
  kind: 'actual' | 'forecast' | 'insulin-rapid' | 'insulin-long'
  lowMgDl?: number
  highMgDl?: number
  insulinName?: string | null
  insulinUnits?: number
  chartY?: number
}

interface InsulinAnnotation {
  key: string
  atMs: number
  units: number
  type: 'rapid' | 'long'
  name: string
  markerX: number
  markerY: number
  labelX: number
  labelAnchor: 'start' | 'end'
}

function splitAtGaps(points: GlucoseReading[]): GlucoseReading[][] {
  const segments: GlucoseReading[][] = []
  for (const point of points) {
    const current = segments.at(-1)
    if (!current || point.measuredAtMs - current.at(-1)!.measuredAtMs > GAP_MS) segments.push([point])
    else current.push(point)
  }
  return segments
}

function direction(points: ForecastPoint[]): string {
  if (points.length < 2) return 'Направление прогноза пока не определено.'
  const delta = points.at(-1)!.medianMgDl - points[0].medianMgDl
  if (delta > 9) return 'Прогноз указывает на рост сахара.'
  if (delta < -9) return 'Прогноз указывает на снижение сахара.'
  return 'По прогнозу сахар останется примерно на текущем уровне.'
}

function insulinUnits(units: number): string {
  return units.toLocaleString('ru-RU', { maximumFractionDigits: 1 })
}

function insulinDescription(point: Pick<InspectablePoint, 'atMs' | 'kind' | 'insulinName' | 'insulinUnits'>): string {
  const rapid = point.kind === 'insulin-rapid'
  const name = point.insulinName?.trim() || (rapid ? 'Быстрый инсулин' : 'Длительный инсулин')
  return `Инсулин ${name}, ${insulinUnits(point.insulinUnits!)} ЕД, в ${time(point.atMs)}. ${rapid ? 'Быстрый' : 'Длительный'} инсулин.`
}

function inspectionText(point: InspectablePoint): string {
  if (point.kind === 'insulin-rapid' || point.kind === 'insulin-long') return insulinDescription(point)
  if (point.kind === 'forecast') {
    return `Прогноз на ${time(point.atMs)}: ${mmol(point.valueMgDl!)} ммоль/л, диапазон ${mmol(point.lowMgDl!)}–${mmol(point.highMgDl!)}.`
  }
  return `Измерение в ${time(point.atMs)}: ${mmol(point.valueMgDl!)} ммоль/л.`
}

export function GlucoseChart({ snapshot, savedAt, range, onRangeChange }: ChartProps) {
  const titleId = useId()
  const descriptionId = useId()
  const helpId = useId()
  const liveId = useId()
  const viewportRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState(DEFAULT_SIZE)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  useEffect(() => {
    const viewport = viewportRef.current
    if (!viewport) return

    const updateSize = (width: number, height: number) => {
      if (width <= 0 || height <= 0) return
      const next = { width: Math.round(width), height: Math.round(height) }
      setSize((current) => current.width === next.width && current.height === next.height ? current : next)
    }
    const measure = () => {
      const bounds = viewport.getBoundingClientRect()
      updateSize(bounds.width, bounds.height)
    }

    measure()
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver((entries) => {
        const bounds = entries[0]?.contentRect
        if (bounds) updateSize(bounds.width, bounds.height)
      })
      observer.observe(viewport)
      return () => observer.disconnect()
    }

    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [])

  const clientNow = useMemo(() => Date.now(), [snapshot, savedAt])
  const now = effectiveServerNow(snapshot, savedAt, clientNow)
  const start = now - range * 60 * 60_000
  const actual = useMemo(
    () => snapshot.glucoseHistory.filter((point) => point.measuredAtMs >= start && point.measuredAtMs <= now),
    [snapshot.glucoseHistory, start, now],
  )
  const forecast = useMemo(
    () => forecastIsCurrent(snapshot, savedAt, clientNow)
      ? snapshot.forecast.points.filter((point) => point.atMs >= now)
      : [],
    [snapshot, savedAt, clientNow, now],
  )
  const visibleInsulin = useMemo(
    () => (snapshot.insulinEvents ?? [])
      .filter((event) => event.occurredAtMs >= start && event.occurredAtMs <= now)
      .sort((left, right) => left.occurredAtMs - right.occurredAtMs),
    [snapshot.insulinEvents, start, now],
  )
  const end = Math.max(now, forecast.at(-1)?.atMs ?? now)
  const allValues = [
    ...actual.map((point) => point.glucoseMgDl),
    ...forecast.flatMap((point) => [point.lowMgDl, point.highMgDl]),
    snapshot.targetRange.lowMgDl,
    snapshot.targetRange.highMgDl,
  ]
  const rawLow = Math.min(...allValues)
  const rawHigh = Math.max(...allValues)
  // Keep one mmol/L of breathing room without clipping the backend-valid 20..600 mg/dL range.
  const yLow = Math.max(0, Math.floor((rawLow - 18) / 18) * 18)
  const yHigh = Math.max(yLow + 72, Math.ceil((rawHigh + 18) / 18) * 18)
  const chartWidth = Math.max(1, size.width)
  const chartHeight = Math.max(1, size.height)
  const margin = {
    top: chartHeight < 240 ? 12 : 18,
    right: chartWidth < 420 ? 38 : 46,
    bottom: chartHeight < 240 ? 25 : 32,
    left: chartWidth < 420 ? 10 : 18,
  }
  const plotWidth = Math.max(1, chartWidth - margin.left - margin.right)
  const plotHeight = Math.max(1, chartHeight - margin.top - margin.bottom)
  const x = (atMs: number) => margin.left + ((atMs - start) / Math.max(1, end - start)) * plotWidth
  const y = (mgDl: number) => margin.top + ((yHigh - mgDl) / (yHigh - yLow)) * plotHeight
  const linePath = <T,>(points: T[], at: (point: T) => number, value: (point: T) => number) =>
    points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${x(at(point)).toFixed(1)} ${y(value(point)).toFixed(1)}`).join(' ')
  const uncertainty = forecast.length
    ? [
        ...forecast.map((point) => `${x(point.atMs).toFixed(1)},${y(point.highMgDl).toFixed(1)}`),
        ...[...forecast].reverse().map((point) => `${x(point.atMs).toFixed(1)},${y(point.lowMgDl).toFixed(1)}`),
      ].join(' ')
    : ''
  const actualSegments = splitAtGaps(actual)
  const insulinAnnotations = (() => {
    const maxLanes = plotHeight < 175 ? 2 : plotHeight < 235 ? 3 : 4
    const lastXByLane = Array.from({ length: maxLanes }, () => Number.NEGATIVE_INFINITY)
    const minimumLabelGap = chartWidth < 440 ? 68 : 86

    return visibleInsulin.map<InsulinAnnotation>((event, index) => {
      const markerX = x(event.occurredAtMs)
      let lane = lastXByLane.findIndex((lastX) => markerX - lastX >= minimumLabelGap)
      if (lane < 0) {
        lane = lastXByLane.reduce((oldestLane, lastX, candidateLane) =>
          lastX < lastXByLane[oldestLane] ? candidateLane : oldestLane, 0)
      }
      lastXByLane[lane] = markerX

      const markerY = margin.top + 22 + lane * 24
      const preferEnd = markerX > chartWidth - margin.right - 96
        || (markerX > margin.left + 96 && lane % 2 === 1)
      const name = event.insulinName?.trim()
        || (event.insulinType === 'rapid' ? 'Быстрый' : 'Длительный')

      return {
        key: `insulin:${event.insulinType}:${event.occurredAtMs}:${index}`,
        atMs: event.occurredAtMs,
        units: event.insulinUnits,
        type: event.insulinType,
        name,
        markerX,
        markerY,
        labelX: markerX + (preferEnd ? -8 : 8),
        labelAnchor: preferEnd ? 'end' : 'start',
      }
    })
  })()
  const yTickCount = chartHeight < 240 ? 4 : 5
  const xTickCount = chartWidth < 440 ? 3 : 5
  const gridTicks = Array.from({ length: yTickCount }, (_, index) => yLow + ((yHigh - yLow) * index) / (yTickCount - 1))
  const timeTicks = Array.from({ length: xTickCount }, (_, index) => start + ((end - start) * index) / (xTickCount - 1))
  const latestActual = actual.at(-1)
  const latestForecast = forecast.at(-1)
  const inspectablePoints: InspectablePoint[] = [
    ...actual.map((point) => ({
      key: `actual:${point.readingId}`,
      atMs: point.measuredAtMs,
      valueMgDl: point.glucoseMgDl,
      chartY: y(point.glucoseMgDl),
      kind: 'actual' as const,
    })),
    ...forecast.map((point) => ({
      key: `forecast:${point.atMs}`,
      atMs: point.atMs,
      valueMgDl: point.medianMgDl,
      lowMgDl: point.lowMgDl,
      highMgDl: point.highMgDl,
      chartY: y(point.medianMgDl),
      kind: 'forecast' as const,
    })),
    ...insulinAnnotations.map((annotation) => ({
      key: annotation.key,
      atMs: annotation.atMs,
      kind: `insulin-${annotation.type}` as const,
      insulinName: annotation.name,
      insulinUnits: annotation.units,
      chartY: annotation.markerY,
    })),
  ].sort((left, right) => left.atMs - right.atMs)
  const selectedIndex = inspectablePoints.findIndex((point) => point.key === selectedKey)
  const selectedPoint = selectedIndex >= 0 ? inspectablePoints[selectedIndex] : null
  const insulinNarrative = visibleInsulin.length
    ? ` Отметки инсулина: ${visibleInsulin.map((event) => {
        const name = event.insulinName?.trim() || (event.insulinType === 'rapid' ? 'быстрый' : 'длительный')
        return `${name} ${insulinUnits(event.insulinUnits)} ЕД в ${time(event.occurredAtMs)}`
      }).join('; ')}.`
    : ''
  const forecastConfidence = Math.round(Math.max(0, Math.min(1, snapshot.forecast.confidence)) * 100)
  const narrative = latestActual
    ? `Последнее значение ${mmol(latestActual.glucoseMgDl)} ммоль/л в ${time(latestActual.measuredAtMs)}. ${
        latestForecast
          ? `${direction(forecast)} К ${time(latestForecast.atMs)} медиана составляет ${mmol(latestForecast.medianMgDl)} ммоль/л, возможный диапазон ${mmol(latestForecast.lowMgDl)}–${mmol(latestForecast.highMgDl)}. Уверенность прогноза ${forecastConfidence}%.`
          : 'Доступного прогноза сейчас нет.'
      }${insulinNarrative}${actualSegments.length > 1 ? ' В истории есть паузы между измерениями более 10 минут.' : ''}`
    : 'За выбранный период измерений нет. Попробуйте выбрать более длинный интервал.'

  const selectNearest = (atMs: number, localY?: number) => {
    if (!inspectablePoints.length) return
    let nearest = inspectablePoints[0]
    const cursorX = x(atMs)
    const score = (point: InspectablePoint) => {
      const horizontalDistance = Math.abs(x(point.atMs) - cursorX)
      if (localY == null) return horizontalDistance
      return Math.hypot(horizontalDistance, (point.chartY! - localY) * 0.72)
    }
    for (const point of inspectablePoints.slice(1)) {
      if (score(point) < score(nearest)) nearest = point
    }
    setSelectedKey((current) => current === nearest.key ? current : nearest.key)
  }

  const inspectPointer = (event: PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect()
    if (bounds.width <= 0) return
    const localX = ((event.clientX - bounds.left) / bounds.width) * chartWidth
    const localY = bounds.height > 0
      ? ((event.clientY - bounds.top) / bounds.height) * chartHeight
      : undefined
    const ratio = Math.max(0, Math.min(1, (localX - margin.left) / plotWidth))
    selectNearest(start + ratio * (end - start), localY)
  }

  const handlePointerDown = (event: PointerEvent<SVGSVGElement>) => {
    event.currentTarget.focus()
    // Focus first so its keyboard default cannot overwrite the point selected by this touch/click.
    inspectPointer(event)
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  const handleKeyDown = (event: KeyboardEvent<SVGSVGElement>) => {
    if (event.key === '+' || event.key === '=') {
      const index = RANGE_OPTIONS.indexOf(range)
      const next = RANGE_OPTIONS[Math.max(0, index - 1)]
      if (next != null && next !== range) onRangeChange(next)
      event.preventDefault()
      return
    }
    if (event.key === '-' || event.key === '_') {
      const index = RANGE_OPTIONS.indexOf(range)
      const next = RANGE_OPTIONS[Math.min(RANGE_OPTIONS.length - 1, Math.max(0, index) + 1)]
      if (next != null && next !== range) onRangeChange(next)
      event.preventDefault()
      return
    }
    if (!inspectablePoints.length) return

    let nextIndex: number | null = null
    const currentIndex = selectedIndex >= 0
      ? selectedIndex
      : inspectablePoints.reduce((nearestIndex, point, index) =>
          Math.abs(point.atMs - now) < Math.abs(inspectablePoints[nearestIndex].atMs - now) ? index : nearestIndex, 0)
    if (event.key === 'ArrowLeft') nextIndex = Math.max(0, currentIndex - 1)
    if (event.key === 'ArrowRight') nextIndex = Math.min(inspectablePoints.length - 1, currentIndex + 1)
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = inspectablePoints.length - 1
    if (event.key === 'Escape') {
      setSelectedKey(null)
      event.preventDefault()
      return
    }
    if (nextIndex != null) {
      setSelectedKey(inspectablePoints[nextIndex].key)
      event.preventDefault()
    }
  }

  const selectedX = selectedPoint ? x(selectedPoint.atMs) : 0
  const selectedY = selectedPoint ? selectedPoint.chartY! : 0
  const selectedIsInsulin = selectedPoint?.kind === 'insulin-rapid' || selectedPoint?.kind === 'insulin-long'
  const tooltipWidth = selectedIsInsulin ? (chartWidth < 380 ? 148 : 174) : chartWidth < 380 ? 132 : 154
  const tooltipHeight = selectedPoint?.kind === 'forecast' ? 60 : 48
  const tooltipX = selectedPoint
    ? selectedX + 12 + tooltipWidth > chartWidth - margin.right
      ? selectedX - tooltipWidth - 12
      : selectedX + 12
    : 0
  const tooltipY = selectedPoint
    ? selectedY - tooltipHeight - 12 < margin.top
      ? Math.min(chartHeight - margin.bottom - tooltipHeight, selectedY + 12)
      : selectedY - tooltipHeight - 12
    : 0

  return (
    <section className="card chart-card chart-panel" aria-labelledby="chart-heading">
      <div className="chart-toolbar">
        <h2 id="chart-heading" className="sr-only">График сахара</h2>
        <div className="segmented compact chart-range-controls" aria-label="Период графика">
          {RANGE_OPTIONS.map((hours) => (
            <button
              type="button"
              key={hours}
              className={range === hours ? 'selected' : ''}
              aria-label={`Показать данные за ${hours} ${hours === 3 || hours === 24 ? 'часа' : 'часов'}`}
              aria-pressed={range === hours}
              onClick={() => onRangeChange(hours)}
            >
              {hours}ч
            </button>
          ))}
        </div>
      </div>

      <div className="chart-scroll chart-viewport" ref={viewportRef} data-chart-width={chartWidth} data-chart-height={chartHeight}>
        <svg
          className="glucose-chart chart-interactive"
          viewBox={`0 0 ${chartWidth} ${chartHeight}`}
          width={chartWidth}
          height={chartHeight}
          role="img"
          tabIndex={0}
          aria-labelledby={titleId}
          aria-describedby={`${descriptionId} ${helpId} ${liveId}`}
          aria-keyshortcuts="ArrowLeft ArrowRight Home End Escape + -"
          data-y-min-mg-dl={yLow}
          data-y-max-mg-dl={yHigh}
          onFocus={() => {
            if (selectedKey == null && inspectablePoints.length) {
              const current = inspectablePoints.reduce((nearest, point) =>
                Math.abs(point.atMs - now) < Math.abs(nearest.atMs - now) ? point : nearest)
              setSelectedKey(current.key)
            }
          }}
          onBlur={() => setSelectedKey(null)}
          onKeyDown={handleKeyDown}
          onPointerDown={handlePointerDown}
          onPointerMove={inspectPointer}
          onPointerLeave={(event) => {
            if (event.pointerType === 'mouse') setSelectedKey(null)
          }}
        >
          <title id={titleId}>Сахар за {range} часов и прогноз</title>
          <desc id={descriptionId}>{narrative}</desc>
          <rect
            className="target-band"
            x={margin.left}
            y={y(snapshot.targetRange.highMgDl)}
            width={plotWidth}
            height={y(snapshot.targetRange.lowMgDl) - y(snapshot.targetRange.highMgDl)}
          />
          {gridTicks.map((tick) => (
            <g key={tick}>
              <line className="chart-grid" x1={margin.left} x2={chartWidth - margin.right} y1={y(tick)} y2={y(tick)} />
              <text className="axis-label" x={chartWidth - margin.right + 8} y={y(tick) + 4} textAnchor="start">
                {mmol(tick, tick % 18 === 0 ? 0 : 1)}
              </text>
            </g>
          ))}
          {timeTicks.map((tick, index) => (
            <text
              key={tick}
              className="axis-label"
              x={x(tick)}
              y={chartHeight - 9}
              textAnchor={index === 0 ? 'start' : index === timeTicks.length - 1 ? 'end' : 'middle'}
            >
              {time(tick)}
            </text>
          ))}
          {end > now && <line className="now-line" x1={x(now)} x2={x(now)} y1={margin.top} y2={chartHeight - margin.bottom} />}
          {uncertainty && <polygon className="forecast-band" points={uncertainty} />}
          {actualSegments.map((segment, index) =>
            segment.length > 1 ? (
              <path
                className="actual-line"
                data-series="actual"
                data-line-style="solid"
                d={linePath(segment, (point) => point.measuredAtMs, (point) => point.glucoseMgDl)}
                key={`${segment[0].readingId}-${index}`}
              />
            ) : (
              <circle
                className="actual-dot"
                key={`${segment[0].readingId}-${index}`}
                cx={x(segment[0].measuredAtMs)}
                cy={y(segment[0].glucoseMgDl)}
                data-glucose-mg-dl={segment[0].glucoseMgDl}
                r="3.5"
              />
            ),
          )}
          {forecast.length > 0 && (
            <path
              className="forecast-line"
              data-series="forecast"
              data-line-style="dashed"
              strokeDasharray="8 7"
              d={linePath(forecast, (point) => point.atMs, (point) => point.medianMgDl)}
            />
          )}
          {insulinAnnotations.map((annotation) => {
            const point: InspectablePoint = {
              key: annotation.key,
              atMs: annotation.atMs,
              kind: `insulin-${annotation.type}`,
              insulinName: annotation.name,
              insulinUnits: annotation.units,
            }
            const markerLabel = `${annotation.name} ${insulinUnits(annotation.units)} ЕД`
            return (
              <g
                key={annotation.key}
                className={`insulin-annotation ${annotation.type}-insulin-annotation`}
                data-testid={`insulin-marker-${annotation.type}`}
                data-insulin-type={annotation.type}
                data-insulin-name={annotation.name}
                data-insulin-units={annotation.units}
                data-at-ms={annotation.atMs}
                data-chart-x={annotation.markerX}
                data-chart-y={annotation.markerY}
              >
                <title>{insulinDescription(point)}</title>
                <line
                  className="insulin-marker-stem"
                  x1={annotation.markerX}
                  x2={annotation.markerX}
                  y1={annotation.markerY + 8}
                  y2={chartHeight - margin.bottom}
                />
                {annotation.type === 'rapid' ? (
                  <path
                    className="insulin-marker-shape rapid-insulin-marker-shape"
                    d={`M ${annotation.markerX} ${annotation.markerY - 7} L ${annotation.markerX + 7} ${annotation.markerY} L ${annotation.markerX} ${annotation.markerY + 7} L ${annotation.markerX - 7} ${annotation.markerY} Z`}
                  />
                ) : (
                  <rect
                    className="insulin-marker-shape long-insulin-marker-shape"
                    x={annotation.markerX - 6}
                    y={annotation.markerY - 6}
                    width="12"
                    height="12"
                    rx="3"
                  />
                )}
                <text
                  className="insulin-marker-label"
                  x={annotation.labelX}
                  y={annotation.markerY + 4}
                  textAnchor={annotation.labelAnchor}
                >
                  {markerLabel}
                </text>
              </g>
            )
          })}
          {latestActual && <circle className="latest-dot" cx={x(latestActual.measuredAtMs)} cy={y(latestActual.glucoseMgDl)} r="5" />}
          {end > now && (
            <text className="now-label" x={x(now) + 5} y={margin.top + 11}>
              сейчас
            </text>
          )}
          <text
            className="series-label target-series-label"
            x={margin.left + 7}
            y={y(snapshot.targetRange.highMgDl) + 14}
            aria-hidden="true"
          >
            цель 4,2–9
          </text>
          {latestForecast && (
            <text
              className="series-label forecast-series-label"
              x={x(latestForecast.atMs) - 5}
              y={Math.max(margin.top + 11, y(latestForecast.medianMgDl) - 9)}
              textAnchor="end"
              aria-hidden="true"
            >
              прогноз {mmol(latestForecast.medianMgDl)} ммоль/л
            </text>
          )}
          {!latestActual && (
            <text className="empty-chart-label" x={margin.left + plotWidth / 2} y={margin.top + plotHeight / 2} textAnchor="middle">
              Нет данных
            </text>
          )}
          {selectedPoint && (
            <g
              className="chart-inspector"
              data-testid="chart-inspector"
              data-point-kind={selectedPoint.kind}
              data-point-at-ms={selectedPoint.atMs}
              aria-hidden="true"
            >
              <line className="inspection-line" x1={selectedX} x2={selectedX} y1={margin.top} y2={chartHeight - margin.bottom} />
              {!selectedIsInsulin && (
                <line className="inspection-line horizontal" x1={margin.left} x2={chartWidth - margin.right} y1={selectedY} y2={selectedY} />
              )}
              <circle className="inspection-dot" cx={selectedX} cy={selectedY} r="5" />
              <g className="inspection-tooltip" transform={`translate(${tooltipX} ${tooltipY})`}>
                <rect width={tooltipWidth} height={tooltipHeight} rx="10" />
                <text className="inspection-value" x="10" y="19">
                  {selectedIsInsulin
                    ? `${selectedPoint.insulinName} · ${insulinUnits(selectedPoint.insulinUnits!)} ЕД`
                    : `${mmol(selectedPoint.valueMgDl!)} ммоль/л`}
                </text>
                <text className="inspection-meta" x="10" y="37">
                  {time(selectedPoint.atMs)} · {
                    selectedPoint.kind === 'forecast'
                      ? 'прогноз'
                      : selectedPoint.kind === 'insulin-rapid'
                        ? 'быстрый инсулин'
                        : selectedPoint.kind === 'insulin-long'
                          ? 'длительный инсулин'
                          : 'измерение'
                  }
                </text>
                {selectedPoint.kind === 'forecast' && (
                  <text className="inspection-range" x="10" y="53">
                    {mmol(selectedPoint.lowMgDl!)}–{mmol(selectedPoint.highMgDl!)}
                  </text>
                )}
              </g>
            </g>
          )}
        </svg>
      </div>

      <p id={helpId} className="sr-only">
        Коснитесь графика или проведите по нему, чтобы посмотреть значение. Стрелки влево и вправо переключают точки, плюс и минус меняют период.
      </p>
      <p id={liveId} className="sr-only chart-inspection-live" aria-live="polite" aria-atomic="true">
        {selectedPoint ? inspectionText(selectedPoint) : ''}
      </p>
    </section>
  )
}
