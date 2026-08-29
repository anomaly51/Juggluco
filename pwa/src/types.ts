export type Tab = 'glucose' | 'events' | 'settings'
export type RangeHours = 3 | 6 | 8 | 12 | 24
export type ThemeMode = 'system' | 'dark' | 'light'
export type SyncState = 'loading' | 'fresh' | 'offline' | 'error'
export type AuthState = 'checking' | 'authenticated' | 'unauthenticated' | 'offline-cache' | 'logout-pending'
export type LogoutState = 'idle' | 'deleting' | 'failed'
export type ViewerAccessMode = 'public' | 'session'

export interface TargetRange {
  lowMgDl: number
  highMgDl: number
  lowMmolL: number
  highMmolL: number
}

export interface GlucoseReading {
  readingId: string
  measuredAtMs: number
  glucoseMgDl: number
  trendMgDlMin: number | null
  sensorId: string | null
  sensorGeneration: string | null
  quality: number | null
  utcOffsetMinutes: number | null
  receivedAtMs: number
  ageMs: number | null
  isStale: boolean | null
}

export interface ForecastPoint {
  atMs: number
  medianMgDl: number
  lowMgDl: number
  highMgDl: number
}

export interface ForecastActivity {
  eventId: string
  kind: string
  label: string
  startMs: number
  peakMs: number
  endMs: number
  strength: number
  confidence: number
  amount: number
  unit: string
}

export interface GlucoseForecast {
  status: string
  generatedAtMs: number
  basedOnReadingAtMs: number | null
  basedOnGlucoseMgDl: number | null
  horizonMinutes: number
  modelVersion: string
  confidence: number
  points: ForecastPoint[]
  activities: ForecastActivity[]
  conditionalNotice: string
}

export type EventKind = 'meal' | 'rapid' | 'long' | 'other'

export interface IntakeEvent {
  id: string
  kind: EventKind
  occurredAtMs: number
  mealText: string | null
  carbsG: number | null
  portionG: number | null
  insulinUnits: number | null
  insulinType: string | null
  insulinName: string | null
  updatedAtMs: number
  deleted: boolean
}

export type InsulinType = 'rapid' | 'long'

export interface ViewerInsulinEvent {
  occurredAtMs: number
  insulinUnits: number
  insulinType: InsulinType
  insulinName: string | null
}

export interface ViewerSnapshot {
  apiVersion: 'v1'
  serverTimeMs: number
  fromMs: number
  toMs: number
  targetRange: TargetRange
  currentGlucose: GlucoseReading | null
  glucoseHistory: GlucoseReading[]
  glucoseHistoryTruncated: boolean
  intakeEvents: IntakeEvent[]
  intakeEventsTruncated: boolean
  insulinEvents: ViewerInsulinEvent[]
  forecast: GlucoseForecast
}

export interface CachedSnapshot {
  snapshot: ViewerSnapshot
  savedAt: number
  accessMode: ViewerAccessMode
}

export interface ViewerSession {
  authenticated: boolean
  accessMode: ViewerAccessMode
  expiresAtMs: number | null
}

export const JUGGLUCO_TARGET: TargetRange = {
  lowMgDl: 75.6,
  highMgDl: 162,
  lowMmolL: 4.2,
  highMmolL: 9,
}
