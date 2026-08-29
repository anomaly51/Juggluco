import { normalizeSnapshot } from './normalize'
import type { CachedSnapshot, ViewerAccessMode, ViewerSnapshot } from './types'

const DB_NAME = 'juggluco-viewer'
const DB_VERSION = 1
const STORE_NAME = 'medical-snapshots'
const SNAPSHOT_KEY = 'latest-v1'
const CACHE_PREFIX = 'juggluco-viewer'

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(STORE_NAME)) database.createObjectStore(STORE_NAME)
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('Не удалось открыть локальное хранилище'))
  })
}

async function withStore<T>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const database = await openDatabase()
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, mode)
    const request = operation(transaction.objectStore(STORE_NAME))
    let result: T
    request.onsuccess = () => {
      result = request.result
    }
    request.onerror = () => reject(request.error ?? new Error('Ошибка локального хранилища'))
    transaction.oncomplete = () => {
      database.close()
      resolve(result)
    }
    transaction.onerror = () => {
      database.close()
      reject(transaction.error ?? new Error('Ошибка локального хранилища'))
    }
  })
}

export async function saveSnapshot(
  snapshot: ViewerSnapshot,
  savedAt = Date.now(),
  accessMode: ViewerAccessMode = 'session',
): Promise<void> {
  const value: CachedSnapshot = { snapshot: normalizeSnapshot(snapshot), savedAt, accessMode }
  await withStore('readwrite', (store) => store.put(value, SNAPSHOT_KEY))
}

export async function loadSnapshot(): Promise<CachedSnapshot | null> {
  const value = await withStore<unknown>('readonly', (store) => store.get(SNAPSHOT_KEY))
  if (typeof value !== 'object' || value === null) return null
  const cached = value as Partial<CachedSnapshot>
  if (typeof cached.savedAt !== 'number' || !Number.isFinite(cached.savedAt)) return null
  try {
    return {
      snapshot: normalizeSnapshot(cached.snapshot),
      savedAt: cached.savedAt,
      accessMode: cached.accessMode === 'public' ? 'public' : 'session',
    }
  } catch {
    await clearSnapshot()
    return null
  }
}

export async function clearSnapshot(): Promise<void> {
  await withStore('readwrite', (store) => store.delete(SNAPSHOT_KEY))
}

export async function clearDeviceData(): Promise<void> {
  await clearSnapshot()
  if ('caches' in globalThis) {
    const names = await caches.keys()
    await Promise.all(names.filter((name) => name.includes(CACHE_PREFIX)).map((name) => caches.delete(name)))
  }
}

export async function requestPersistentStorage(): Promise<boolean | null> {
  if (!navigator.storage?.persist) return null
  return navigator.storage.persist()
}
