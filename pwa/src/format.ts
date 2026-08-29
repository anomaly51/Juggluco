import type { GlucoseReading, IntakeEvent } from './types'

const timeFormatter = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' })
const dateFormatter = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' })

export function mmol(mgDl: number, digits = 1): string {
  return (mgDl / 18).toLocaleString('ru-RU', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export function compactNumber(value: number, digits = 1): string {
  return value.toLocaleString('ru-RU', { maximumFractionDigits: digits })
}

export function time(value: number): string {
  return timeFormatter.format(new Date(value))
}

export function dateTime(value: number): string {
  const date = new Date(value)
  return `${dateFormatter.format(date)}, ${timeFormatter.format(date)}`
}

export function relativeAge(value: number, now = Date.now()): string {
  const minutes = Math.max(0, Math.round((now - value) / 60_000))
  if (minutes < 1) return 'только что'
  if (minutes < 60) return `${minutes} мин назад`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} ч назад`
  return dateFormatter.format(new Date(value))
}

export function trend(reading: GlucoseReading): { arrow: string; label: string } {
  const value = reading.trendMgDlMin
  if (value == null) return { arrow: '—', label: 'тренд неизвестен' }
  if (value >= 3) return { arrow: '↑', label: 'быстро растёт' }
  if (value >= 1) return { arrow: '↗', label: 'растёт' }
  if (value > -1) return { arrow: '→', label: 'стабильно' }
  if (value > -3) return { arrow: '↘', label: 'снижается' }
  return { arrow: '↓', label: 'быстро снижается' }
}

export function eventTitle(event: IntakeEvent): string {
  if (event.kind === 'meal') return event.mealText || 'Приём пищи'
  if (event.kind === 'rapid') return event.insulinName || 'Быстрый инсулин'
  if (event.kind === 'long') return event.insulinName || 'Длительный инсулин'
  return 'Событие'
}

export function eventAmount(event: IntakeEvent): string {
  if (event.kind === 'meal') {
    const pieces = []
    if (event.carbsG != null) pieces.push(`${compactNumber(event.carbsG)} г углеводов`)
    if (event.portionG != null) pieces.push(`порция ${compactNumber(event.portionG, 0)} г`)
    return pieces.join(' · ') || 'Количество не указано'
  }
  return event.insulinUnits == null ? 'Доза не указана' : `${compactNumber(event.insulinUnits, 2)} ЕД`
}
