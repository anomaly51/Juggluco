import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataStatus } from './DataStatus'

describe('DataStatus', () => {
  it('shows a compact live connection without a manual data reload', () => {
    render(<DataStatus state="online" stale={false} />)

    expect(screen.getByText('Онлайн')).toBeInTheDocument()
    expect(screen.getByLabelText('Состояние данных: Онлайн')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText('Актуально')).not.toBeInTheDocument()
  })

  it.each([
    ['connecting', false, 'Подключение'],
    ['reconnecting', false, 'Подключение'],
    ['offline', false, 'Офлайн'],
    ['online', true, 'Нет новых данных'],
  ] as const)('maps %s to %s', (state, stale, label) => {
    render(<DataStatus state={state} stale={stale} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})
