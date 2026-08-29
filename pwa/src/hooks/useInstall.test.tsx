import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useInstall } from './useInstall'

describe('useInstall', () => {
  it('exposes the browser install prompt and reports acceptance', async () => {
    const prompt = vi.fn(async () => undefined)
    const event = Object.assign(new Event('beforeinstallprompt'), {
      prompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    })
    const { result } = renderHook(() => useInstall())

    act(() => window.dispatchEvent(event))
    await waitFor(() => expect(result.current.canPrompt).toBe(true))
    await act(async () => {
      await expect(result.current.install()).resolves.toBe(true)
    })

    expect(prompt).toHaveBeenCalledOnce()
    expect(result.current.canPrompt).toBe(false)
  })
})
