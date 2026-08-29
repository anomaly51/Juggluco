import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearDeviceData, clearSnapshot, loadSnapshot, saveSnapshot } from './db'
import { normalizeSnapshot } from './normalize'
import { rawSnapshot } from './test/fixtures'

describe('snapshot IndexedDB cache', () => {
  beforeEach(async () => {
    await clearSnapshot()
    vi.restoreAllMocks()
  })

  it('round-trips a normalized snapshot with its access scope', async () => {
    const snapshot = normalizeSnapshot(rawSnapshot)
    await saveSnapshot(snapshot, 123_456, 'public')

    await expect(loadSnapshot()).resolves.toEqual({ snapshot, savedAt: 123_456, accessMode: 'public' })
    await expect(loadSnapshot()).resolves.toMatchObject({
      snapshot: {
        insulinEvents: [
          { insulinName: 'Tresiba', insulinType: 'long', insulinUnits: 12 },
          { insulinName: 'NovoRapid', insulinType: 'rapid', insulinUnits: 5 },
        ],
      },
    })
  })

  it('deletes only this PWA cache namespace', async () => {
    const deleteCache = vi.fn(async () => true)
    vi.spyOn(caches, 'keys').mockResolvedValue([
      'juggluco-viewer-precache-v1',
      'another-app-precache',
    ])
    vi.spyOn(caches, 'delete').mockImplementation(deleteCache)

    await clearDeviceData()

    expect(deleteCache).toHaveBeenCalledTimes(1)
    expect(deleteCache).toHaveBeenCalledWith('juggluco-viewer-precache-v1')
  })
})
