import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { registerSW } from 'virtual:pwa-register'
import { usePwaUpdate } from './usePwaUpdate'

vi.mock('virtual:pwa-register', () => ({ registerSW: vi.fn() }))

describe('usePwaUpdate', () => {
  it('shows an update-ready state and applies the waiting service worker', async () => {
    const update = vi.fn(async () => undefined)
    let onNeedRefresh: (() => void) | undefined
    vi.mocked(registerSW).mockImplementation((options) => {
      onNeedRefresh = options?.onNeedRefresh
      return update
    })
    const { result } = renderHook(() => usePwaUpdate())

    act(() => onNeedRefresh?.())
    expect(result.current.updateReady).toBe(true)
    await act(async () => result.current.applyUpdate())
    expect(update).toHaveBeenCalledWith(true)
  })
})
