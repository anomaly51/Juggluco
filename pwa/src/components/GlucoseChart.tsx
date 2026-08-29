import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'
import { mmol, time } from '../format'
import { forecastPresentationAt } from '../forecastPresentation'
import { effectiveServerNow } from '../freshness'
import type { ForecastPoint, GlucoseReading, RangeHours, ViewerSnapshot } from '../types'

const DEFAULT_SIZE = { width: 820, height: 330 }
const GAP_MS = 10 * 60_000
const DEFAULT_FORECAST_HORIZON_MINUTES = 120
const MIN_FORECAST_HORIZON_MINUTES = 30
const MAX_FORECAST_HORIZON_MINUTES = 180
const RANGE_OPTIONS = [3, 6, 8, 12, 24] as RangeHours[]

type GlucoseZone = 'low' | 'target' | 'high'

interface ChartProps {
  snapshot: ViewerSnapshot
  savedAt: number | null
  serverNowMs?: number
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
  glucoseZone?: GlucoseZone
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

interface GlucoseLinePoint {
  atMs: number
  valueMgDl: number
}

interface ZonedGlucoseLine {
  zone: GlucoseZone
  points: GlucoseLinePoint[]
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

function glucoseZone(valueMgDl: number, lowMgDl: number, highMgDl: number): GlucoseZone {
  if (valueMgDl < lowMgDl) return 'low'
  if (valueMgDl > highMgDl) return 'high'
  return 'target'
}

function glucoseZoneLabel(zone: GlucoseZone): string {
  if (zone === 'low') return 'ниже цели'
  if (zone === 'high') return 'выше цели'
  return 'в цели'
}

function glucoseZoneSentence(zone: GlucoseZone): string {
  if (zone === 'low') return 'Ниже целевого диапазона.'
  if (zone === 'high') return 'Выше целевого диапазона.'
  return 'В целевом диапазоне.'
}

function splitGlucoseLine(
  points: GlucoseLinePoint[],
  lowMgDl: number,
  highMgDl: number,
): ZonedGlucoseLine[] {
  const result: ZonedGlucoseLine[] = []
  const thresholds = [lowMgDl, highMgDl]

  const interpolate = (left: GlucoseLinePoint, right: GlucoseLinePoint, ratio: number): GlucoseLinePoint => ({
    atMs: left.atMs + (right.atMs - left.atMs) * ratio,
    valueMgDl: left.valueMgDl + (right.valueMgDl - left.valueMgDl) * ratio,
  })

  for (let index = 1; index < points.length; index += 1) {
    const left = points[index - 1]
    const right = points[index]
    const ratios = [0, 1]
    const delta = right.valueMgDl - left.valueMgDl
    if (delta !== 0) {
      for (const threshold of thresholds) {
        const ratio = (threshold - left.valueMgDl) / delta
        if (ratio > 0 && ratio < 1) ratios.push(ratio)
      }
    }
    ratios.sort((a, b) => a - b)

    for (let ratioIndex = 1; ratioIndex < ratios.length; ratioIndex += 1) {
      const segmentStart = interpolate(left, right, ratios[ratioIndex - 1])
      const segmentEnd = interpolate(left, right, ratios[ratioIndex])
      const midpoint = (segmentStart.valueMgDl + segmentEnd.valueMgDl) / 2
      const zone = glucoseZone(midpoint, lowMgDl, highMgDl)
      const previous = result.at(-1)
      const previousEnd = previous?.points.at(-1)

      if (
        previous?.zone === zone
        && previousEnd?.atMs === segmentStart.atMs
        && previousEnd.valueMgDl === segmentStart.valueMgDl
      ) {
        previous.points.push(segmentEnd)
      } else {
        result.push({ zone, points: [segmentStart, segmentEnd] })
      }
    }
  }

  return result
}

function insulinDescription(point: Pick<InspectablePoint, 'atMs' | 'kind' | 'insulinName' | 'insulinUnits'>): string {
  const rapid = point.kind === 'insulin-rapid'
  const name = point.insulinName?.trim() || (rapid ? 'Быстрый инсулин' : 'Длительный инсулин')
  return `Инсулин ${name}, ${insulinUnits(point.insulinUnits!)} ЕД, в ${time(point.atMs)}. ${rapid ? 'Быстрый' : 'Длительный'} инсулин.`
}

function inspectionText(point: InspectablePoint): string {
  if (point.kind === 'insulin-rapid' || point.kind === 'insulin-long') return insulinDescription(point)
  const zone = point.glucoseZone ? ` ${glucoseZoneSentence(point.glucoseZone)}` : ''
  if (point.kind === 'forecast') {
    return `Прогноз на ${time(point.atMs)}: ${mmol(point.valueMgDl!)} ммоль/л, диапазон ${mmol(point.lowMgDl!)}–${mmol(point.highMgDl!)}.${zone}`
  }
  return `Измерение в ${time(point.atMs)}: ${mmol(point.valueMgDl!)} ммоль/л.${zone}`
}

export function GlucoseChart({ snapshot, savedAt, serverNowMs, range, onRangeChange }: ChartProps) {
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

  const fallbackClientNow = useMemo(() => Date.now(), [snapshot, savedAt])
  const now = serverNowMs ?? effectiveServerNow(snapshot, savedAt, fallbackClientNow)
  const start = now - range * 60 * 60_000
  const actual = useMemo(
    () => snapshot.glucoseHistory.filter((point) => point.measuredAtMs >= start && point.measuredAtMs <= now),
    [snapshot.glucoseHistory, start, now],
  )
  const forecastPresentation = useMemo(
    () => forecastPresentationAt(snapshot, now),
    [snapshot, now],
  )
  const forecast = forecastPresentation.kind === 'ready' ? forecastPresentation.points : []
  const visibleInsulin = useMemo(
    () => (snapshot.insulinEvents ?? [])
      .filter((event) => event.occurredAtMs >= start && event.occurredAtMs <= now)
      .sort((left, right) => left.occurredAtMs - right.occurredAtMs),
    [snapshot.insulinEvents, start, now],
  )
  const configuredHorizonMinutes = Number.isFinite(snapshot.forecast.horizonMinutes)
    ? snapshot.forecast.horizonMinutes
    : DEFAULT_FORECAST_HORIZON_MINUTES
  const visibleFutureMinutes = Math.max(
    MIN_FORECAST_HORIZON_MINUTES,
    Math.min(MAX_FORECAST_HORIZON_MINUTES, configuredHorizonMinutes),
  )
  // Keep the time scale stable while a forecast is learning: the right-hand
  // side is a real future window, but it remains empty until a ready forecast exists.
  const end = Math.max(
    now + visibleFutureMinutes * 60_000,
    forecast.at(-1)?.atMs ?? now,
  )
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
    right: chartWidth < 420 ? 42 : 46,
    bottom: chartHeight < 240 ? 25 : 32,
    left: chartWidth < 420 ? 10 : 18,
  }
  const plotWidth = Math.max(1, chartWidth - margin.left - margin.right)
  const plotHeight = Math.max(1, chartHeight - margin.top - margin.bottom)
  const x = (atMs: number) => margin.left + ((atMs - start) / Math.max(1, end - start)) * plotWidth
  const y = (mgDl: number) => margin.top + ((yHigh - mgDl) / (yHigh - yLow)) * plotHeight
  const plotBottom = margin.top + plotHeight
  const targetTop = y(snapshot.targetRange.highMgDl)
  const targetBottom = y(snapshot.targetRange.lowMgDl)
  const linePath = <T,>(points: T[], at: (point: T) => number, value: (point: T) => number) =>
    points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${x(at(point)).toFixed(1)} ${y(value(point)).toFixed(1)}`).join(' ')
  const uncertainty = forecast.length
    ? [
        ...forecast.map((point) => `${x(point.atMs).toFixed(1)},${y(point.highMgDl).toFixed(1)}`),
        ...[...forecast].reverse().map((point) => `${x(point.atMs).toFixed(1)},${y(point.lowMgDl).toFixed(1)}`),
      ].join(' ')
    : ''
  const actualSegments = splitAtGaps(actual)
  const actualZoneSegments = actualSegments.flatMap((segment, gapIndex) =>
    splitGlucoseLine(
      segment.map((point) => ({ atMs: point.measuredAtMs, valueMgDl: point.glucoseMgDl })),
      snapshot.targetRange.lowMgDl,
      snapshot.targetRange.highMgDl,
    ).map((zoned, zoneIndex) => ({
      ...zoned,
      key: `${segment[0].readingId}-${gapIndex}-${zoneIndex}`,
    })),
  )
  const insulinAnnotations = (() => {
    const maxLanes = chartWidth < 440
      ? plotHeight < 220 ? 2 : 3
      : plotHeight < 175 ? 2 : plotHeight < 235 ? 3 : 4
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
  const yTickCount = chartWidth < 440 || chartHeight < 240 ? 4 : 5
  const gridTicks = Array.from({ length: yTickCount }, (_, index) => yLow + ((yHigh - yLow) * index) / (yTickCount - 1))
  const timeTicks = (() => {
    const minimumGap = chartWidth < 440 ? 54 : 48
    const ticks = [start, end]
    const optionalTicks = chartWidth < 440
      ? [now, start + (now - start) / 2]
      : [now, start + (now - start) / 2, now + (end - now) / 2]
    for (const candidate of optionalTicks) {
      if (ticks.every((tick) => Math.abs(x(tick) - x(candidate)) >= minimumGap)) ticks.push(candidate)
    }
    return ticks.sort((left, right) => left - right)
  })()
  const latestActual = actual.at(-1)
  const latestForecast = forecast.at(-1)
  const inspectablePoints: InspectablePoint[] = [
    ...actual.map((point) => ({
      key: `actual:${point.readingId}`,
      atMs: point.measuredAtMs,
      valueMgDl: point.glucoseMgDl,
      chartY: y(point.glucoseMgDl),
      glucoseZone: glucoseZone(point.glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl),
      kind: 'actual' as const,
    })),
    ...forecast.map((point) => ({
      key: `forecast:${point.atMs}`,
      atMs: point.atMs,
      valueMgDl: point.medianMgDl,
      lowMgDl: point.lowMgDl,
      highMgDl: point.highMgDl,
      chartY: y(point.medianMgDl),
      glucoseZone: glucoseZone(point.medianMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl),
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
  const forecastConfidence = forecastPresentation.kind === 'ready'
    ? Math.round(Math.max(0, Math.min(1, forecastPresentation.confidence)) * 100)
    : 0
  const latestActualZone = latestActual
    ? glucoseZone(latestActual.glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl)
    : null
  const narrative = latestActual
    ? `Последнее значение ${mmol(latestActual.glucoseMgDl)} ммоль/л в ${time(latestActual.measuredAtMs)}. ${glucoseZoneSentence(latestActualZone!)} ${
        latestForecast
          ? `${direction(forecast)} К ${time(latestForecast.atMs)} медиана составляет ${mmol(latestForecast.medianMgDl)} ммоль/л, возможный диапазон ${mmol(latestForecast.lowMgDl)}–${mmol(latestForecast.highMgDl)}. Уверенность прогноза ${forecastConfidence}%. Прогноз экспериментальный и не предназначен для расчёта дозы.`
          : forecastPresentation.kind === 'pending'
            ? `${forecastPresentation.label}.`
            : 'Доступного прогноза сейчас нет.'
      }${insulinNarrative}${actualSegments.length > 1 ? ' В истории есть паузы между измерениями более 10 минут.' : ''}`
    : 'За выбранный период измерений нет. Попробуйте выбрать более длинный интервал.'

  const selectNearest = (atMs: number, localY?: number, maxHorizontalDistance?: number) => {
    if (!inspectablePoints.length) return
    const cursorX = x(atMs)
    const candidates = maxHorizontalDistance == null
      ? inspectablePoints
      : inspectablePoints.filter((point) => Math.abs(x(point.atMs) - cursorX) <= maxHorizontalDistance)
    if (!candidates.length) {
      setSelectedKey(null)
      return
    }
    let nearest = candidates[0]
    const score = (point: InspectablePoint) => {
      const horizontalDistance = Math.abs(x(point.atMs) - cursorX)
      if (localY == null) return horizontalDistance
      return Math.hypot(horizontalDistance, (point.chartY! - localY) * 0.72)
    }
    for (const point of candidates.slice(1)) {
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
    selectNearest(start + ratio * (end - start), localY, chartWidth < 440 ? 36 : 44)
  }

  const handlePointerDown = (event: PointerEvent<SVGSVGElement>) => {
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
          data-chart-start-ms={start}
          data-chart-end-ms={end}
          data-chart-now-ms={now}
          data-chart-now-x={x(now)}
          data-future-window-minutes={visibleFutureMinutes}
          data-forecast-state={forecastPresentation.kind}
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
          onPointerCancel={() => setSelectedKey(null)}
          onPointerLeave={(event) => {
            if (event.pointerType === 'mouse') setSelectedKey(null)
          }}
        >
          <title id={titleId}>Сахар за {range} часов{forecastPresentation.kind === 'ready' ? ' и прогноз' : ''}</title>
          <desc id={descriptionId}>{narrative}</desc>
          <rect
            className="glucose-zone-band high-zone-band"
            data-glucose-zone="high"
            x={margin.left}
            y={margin.top}
            width={plotWidth}
            height={targetTop - margin.top}
          />
          <rect
            className="glucose-zone-band target-band target-zone-band"
            data-glucose-zone="target"
            x={margin.left}
            y={targetTop}
            width={plotWidth}
            height={targetBottom - targetTop}
          />
          <rect
            className="glucose-zone-band low-zone-band"
            data-glucose-zone="low"
            x={margin.left}
            y={targetBottom}
            width={plotWidth}
            height={plotBottom - targetBottom}
          />
          <rect
            className={`future-window ${forecastPresentation.kind}`}
            data-series="future-window"
            x={x(now)}
            y={margin.top}
            width={chartWidth - margin.right - x(now)}
            height={plotHeight}
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
              data-axis="time"
              data-time-ms={tick}
              x={x(tick)}
              y={chartHeight - 9}
              textAnchor={index === 0 ? 'start' : index === timeTicks.length - 1 ? 'end' : 'middle'}
            >
              {time(tick)}
            </text>
          ))}
          {end > now && <line className="now-line" x1={x(now)} x2={x(now)} y1={margin.top} y2={chartHeight - margin.bottom} />}
          {uncertainty && (
            <polygon
              className="forecast-band"
              data-series="forecast-uncertainty"
              points={uncertainty}
            />
          )}
          {actualZoneSegments.map((segment) => (
              <path
                className={`actual-line glucose-${segment.zone}`}
                data-series="actual"
                data-line-style="solid"
                data-glucose-zone={segment.zone}
                d={linePath(segment.points, (point) => point.atMs, (point) => point.valueMgDl)}
                key={segment.key}
              />
          ))}
          {actualSegments.map((segment, index) =>
            segment.length === 1 ? (
              <circle
                className={`actual-dot glucose-${glucoseZone(segment[0].glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl)}`}
                key={`${segment[0].readingId}-${index}`}
                cx={x(segment[0].measuredAtMs)}
                cy={y(segment[0].glucoseMgDl)}
                data-glucose-mg-dl={segment[0].glucoseMgDl}
                data-glucose-zone={glucoseZone(segment[0].glucoseMgDl, snapshot.targetRange.lowMgDl, snapshot.targetRange.highMgDl)}
                r="3.5"
              />
            ) : null,
          )}
          {forecast.length >= 2 && (
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
            const markerLabel = chartWidth < 440
              ? `${insulinUnits(annotation.units)} ЕД`
              : `${annotation.name} ${insulinUnits(annotation.units)} ЕД`
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
          {latestActual && (
            <>
              <circle
                className={`latest-halo glucose-${latestActualZone}`}
                cx={x(latestActual.measuredAtMs)}
                cy={y(latestActual.glucoseMgDl)}
                r="9"
                aria-hidden="true"
              />
              <circle
                className={`latest-dot glucose-${latestActualZone}`}
                data-glucose-zone={latestActualZone!}
                cx={x(latestActual.measuredAtMs)}
                cy={y(latestActual.glucoseMgDl)}
                r="5"
              />
            </>
          )}
          {latestForecast && (
            <circle
              className="forecast-endpoint"
              data-forecast-confidence={forecastConfidence}
              cx={x(latestForecast.atMs)}
              cy={y(latestForecast.medianMgDl)}
              r="4.5"
            />
          )}
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
              прогноз {mmol(latestForecast.medianMgDl)} ммоль/л · {forecastConfidence}%
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
              <circle
                className={`inspection-dot${selectedPoint.glucoseZone ? ` glucose-${selectedPoint.glucoseZone}` : ''}`}
                cx={selectedX}
                cy={selectedY}
                r="5"
              />
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
                          : 'замер'
                  }{selectedPoint.glucoseZone ? ` · ${glucoseZoneLabel(selectedPoint.glucoseZone)}` : ''}
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
