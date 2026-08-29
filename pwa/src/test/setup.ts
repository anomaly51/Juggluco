import '@testing-library/jest-dom/vitest'
import 'fake-indexeddb/auto'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(cleanup)

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

Object.defineProperty(navigator, 'storage', {
  configurable: true,
  value: {
    persist: async () => true,
    estimate: async () => ({ usage: 1024, quota: 1024 * 1024 }),
  },
})

if (!globalThis.caches) {
  Object.defineProperty(globalThis, 'caches', {
    configurable: true,
    value: {
      keys: async () => [],
      delete: async () => true,
    },
  })
}
