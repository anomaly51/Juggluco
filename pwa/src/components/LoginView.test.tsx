import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { LoginView } from './LoginView'

describe('LoginView', () => {
  it('uses an accessible labelled input and clears the secret immediately on submit', async () => {
    const user = userEvent.setup()
    let release: (() => void) | undefined
    const onLogin = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    render(<LoginView error={null} onLogin={onLogin} />)

    const input = screen.getByLabelText('Ключ просмотра')
    await user.type(input, 'private-token')
    await user.click(screen.getByRole('button', { name: 'Открыть приложение' }))

    expect(onLogin).toHaveBeenCalledWith('private-token')
    expect(input).toHaveValue('')
    await act(async () => release?.())
  })

  it('announces an empty-token validation error', async () => {
    const user = userEvent.setup()
    render(<LoginView error={null} onLogin={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Открыть приложение' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Введите ключ просмотра')
  })
})
