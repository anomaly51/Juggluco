import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { normalizeSnapshot } from '../normalize'
import { rawSnapshot } from '../test/fixtures'
import { EventsView } from './EventsView'

describe('EventsView', () => {
  it('filters events through pressed, labelled touch controls', async () => {
    const user = userEvent.setup()
    render(<EventsView snapshot={normalizeSnapshot(rawSnapshot)} />)

    expect(screen.getByText('Рис')).toBeInTheDocument()
    expect(screen.getByText('NovoRapid')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Еда' }))
    expect(screen.getByRole('button', { name: 'Еда' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText('NovoRapid')).not.toBeInTheDocument()
  })
})
