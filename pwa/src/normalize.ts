import {
  JUGGLUCO_TARGET,
  type ForecastActivity,
  type ForecastPoint,
  type GlucoseReading,
  type IntakeEvent,
  type InsulinType,
  type ViewerInsulinEvent,
  type ViewerSnapshot,
} from './types'

type UnknownRecord = Record<string, unknown>

function record(value: unknown, label: string): UnknownRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Некорректные данные: ${label}`)
  }
  return value as UnknownRecord
}

function pick(source: UnknownRecord, snake: string, camel: string): unknown {
  return source[snake] ?? source[camel]
}

function finite(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Некорректные данные: ${label}`)
  }
  return value
}

function optionalFinite(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function glucose(value: unknown, label: string): GlucoseReading {
  const source = record(value, label)
  const readingId = text(pick(source, 'reading_id', 'readingId'))
  if (!readingId) throw new Error(`Некорректные данные: ${label}.reading_id`)
  return {
    readingId,
    measuredAtMs: finite(pick(source, 'measured_at_ms', 'measuredAtMs'), `${label}.measured_at_ms`),
    glucoseMgDl: finite(pick(source, 'glucose_mg_dl', 'glucoseMgDl'), `${label}.glucose_mg_dl`),
    trendMgDlMin: optionalFinite(pick(source, 'trend_mg_dl_min', 'trendMgDlMin')),
    sensorId: optionalText(pick(source, 'sensor_id', 'sensorId')),
    sensorGeneration: optionalText(pick(source, 'sensor_generation', 'sensorGeneration')),
    quality: optionalFinite(source.quality),
    utcOffsetMinutes: optionalFinite(pick(source, 'utc_offset_minutes', 'utcOffsetMinutes')),
    receivedAtMs: finite(pick(source, 'received_at_ms', 'receivedAtMs'), `${label}.received_at_ms`),
    ageMs: optionalFinite(pick(source, 'age_ms', 'ageMs')),
    isStale:
      typeof pick(source, 'is_stale', 'isStale') === 'boolean'
        ? (pick(source, 'is_stale', 'isStale') as boolean)
        : null,
  }
}

function forecastPoint(value: unknown, index: number): ForecastPoint {
  const source = record(value, `forecast.points[${index}]`)
  return {
    atMs: finite(pick(source, 'at_ms', 'atMs'), 'forecast point time'),
    medianMgDl: finite(pick(source, 'median_mg_dl', 'medianMgDl'), 'forecast median'),
    lowMgDl: finite(pick(source, 'low_mg_dl', 'lowMgDl'), 'forecast low'),
    highMgDl: finite(pick(source, 'high_mg_dl', 'highMgDl'), 'forecast high'),
  }
}

function activity(value: unknown, index: number): ForecastActivity {
  const source = record(value, `forecast.activities[${index}]`)
  return {
    eventId: text(pick(source, 'event_id', 'eventId'), `activity-${index}`),
    kind: text(source.kind, 'other'),
    label: text(source.label, 'Событие'),
    startMs: finite(pick(source, 'start_ms', 'startMs'), 'activity start'),
    peakMs: finite(pick(source, 'peak_ms', 'peakMs'), 'activity peak'),
    endMs: finite(pick(source, 'end_ms', 'endMs'), 'activity end'),
    strength: optionalFinite(source.strength) ?? 0,
    confidence: optionalFinite(source.confidence) ?? 0,
    amount: optionalFinite(source.amount) ?? 0,
    unit: text(source.unit),
  }
}

function intake(value: unknown, index: number): IntakeEvent {
  const source = record(value, `intake_events[${index}]`)
  const rawKind = text(source.kind, 'other')
  const kind = rawKind === 'meal' || rawKind === 'rapid' || rawKind === 'long' ? rawKind : 'other'
  return {
    id: text(source.id, `event-${index}`),
    kind,
    occurredAtMs: finite(pick(source, 'occurred_at_ms', 'occurredAtMs'), 'event time'),
    mealText: optionalText(pick(source, 'meal_text', 'mealText')),
    carbsG: optionalFinite(pick(source, 'carbs_g', 'carbsG')),
    portionG: optionalFinite(pick(source, 'portion_g', 'portionG')),
    insulinUnits: optionalFinite(pick(source, 'insulin_units', 'insulinUnits')),
    insulinType: optionalText(pick(source, 'insulin_type', 'insulinType')),
    insulinName: optionalText(pick(source, 'insulin_name', 'insulinName')),
    updatedAtMs: finite(pick(source, 'updated_at_ms', 'updatedAtMs'), 'event update time'),
    deleted: source.deleted === true,
  }
}

function insulinType(value: unknown): InsulinType | null {
  return value === 'rapid' || value === 'long' ? value : null
}

function insulinEvent(value: unknown, index: number): ViewerInsulinEvent | null {
  const source = record(value, `insulin_events[${index}]`)
  const type = insulinType(pick(source, 'insulin_type', 'insulinType'))

  // Unknown insulin categories are ignored instead of being rendered with an
  // incorrect action profile or colour on a medical chart.
  if (type === null) return null

  const occurredAtMs = finite(pick(source, 'occurred_at_ms', 'occurredAtMs'), 'insulin event time')
  const insulinUnits = finite(pick(source, 'insulin_units', 'insulinUnits'), 'insulin event units')
  if (occurredAtMs <= 0) throw new Error('Некорректные данные: insulin event time')
  if (insulinUnits <= 0 || insulinUnits > 500) {
    throw new Error('Некорректные данные: insulin event units')
  }

  return {
    occurredAtMs,
    insulinUnits,
    insulinType: type,
    insulinName: optionalText(pick(source, 'insulin_name', 'insulinName')),
  }
}

function legacyInsulinEvents(events: IntakeEvent[]): ViewerInsulinEvent[] {
  const result: ViewerInsulinEvent[] = []

  for (const event of events) {
    const type = insulinType(event.insulinType) ?? insulinType(event.kind)
    if (type === null || event.insulinUnits === null) continue
    result.push({
      occurredAtMs: event.occurredAtMs,
      insulinUnits: event.insulinUnits,
      insulinType: type,
      insulinName: event.insulinName,
    })
  }

  return result
}

function sortedUniqueReadings(readings: GlucoseReading[]): GlucoseReading[] {
  const byId = new Map<string, GlucoseReading>()
  for (const item of readings) byId.set(item.readingId, item)
  return [...byId.values()].sort((a, b) => a.measuredAtMs - b.measuredAtMs)
}

export function normalizeSnapshot(value: unknown): ViewerSnapshot {
  const source = record(value, 'snapshot')
  const historyRaw = pick(source, 'glucose_history', 'glucoseHistory')
  const eventsRaw = pick(source, 'intake_events', 'intakeEvents')
  const hasInsulinEvents = Object.hasOwn(source, 'insulin_events') || Object.hasOwn(source, 'insulinEvents')
  const insulinEventsRaw = pick(source, 'insulin_events', 'insulinEvents')
  const forecastSource = record(source.forecast, 'forecast')
  const pointsRaw = forecastSource.points
  const activitiesRaw = forecastSource.activities
  const currentRaw = pick(source, 'current_glucose', 'currentGlucose')

  if (
    !Array.isArray(historyRaw)
    || !Array.isArray(eventsRaw)
    || !Array.isArray(pointsRaw)
    || (hasInsulinEvents && !Array.isArray(insulinEventsRaw))
  ) {
    throw new Error('Некорректный формат снимка')
  }

  const intakeEvents = eventsRaw
    .map(intake)
    .filter((event) => !event.deleted)
    .sort((a, b) => a.occurredAtMs - b.occurredAtMs)
  const insulinEvents = (hasInsulinEvents
    ? (insulinEventsRaw as unknown[]).map(insulinEvent).filter((event): event is ViewerInsulinEvent => event !== null)
    : legacyInsulinEvents(intakeEvents)
  ).sort((a, b) => a.occurredAtMs - b.occurredAtMs)

  return {
    apiVersion: 'v1',
    serverTimeMs: finite(pick(source, 'server_time_ms', 'serverTimeMs'), 'server_time_ms'),
    fromMs: finite(pick(source, 'from_ms', 'fromMs'), 'from_ms'),
    toMs: finite(pick(source, 'to_ms', 'toMs'), 'to_ms'),
    targetRange: JUGGLUCO_TARGET,
    currentGlucose: currentRaw == null ? null : glucose(currentRaw, 'current_glucose'),
    glucoseHistory: sortedUniqueReadings(historyRaw.map((item, index) => glucose(item, `glucose_history[${index}]`))),
    glucoseHistoryTruncated: pick(source, 'glucose_history_truncated', 'glucoseHistoryTruncated') === true,
    intakeEvents,
    intakeEventsTruncated: pick(source, 'intake_events_truncated', 'intakeEventsTruncated') === true,
    insulinEvents,
    forecast: {
      status: text(forecastSource.status, 'no_data'),
      generatedAtMs: optionalFinite(pick(forecastSource, 'generated_at_ms', 'generatedAtMs')) ?? 0,
      basedOnReadingAtMs: optionalFinite(pick(forecastSource, 'based_on_reading_at_ms', 'basedOnReadingAtMs')),
      basedOnGlucoseMgDl: optionalFinite(pick(forecastSource, 'based_on_glucose_mg_dl', 'basedOnGlucoseMgDl')),
      horizonMinutes: optionalFinite(pick(forecastSource, 'horizon_minutes', 'horizonMinutes')) ?? 120,
      modelVersion: text(pick(forecastSource, 'model_version', 'modelVersion')),
      confidence: Math.max(0, Math.min(1, optionalFinite(forecastSource.confidence) ?? 0)),
      points: pointsRaw.map(forecastPoint).sort((a, b) => a.atMs - b.atMs),
      activities: Array.isArray(activitiesRaw) ? activitiesRaw.map(activity) : [],
      conditionalNotice: text(pick(forecastSource, 'conditional_notice', 'conditionalNotice')),
    },
  }
}
