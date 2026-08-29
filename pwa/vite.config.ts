import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  base: '/viewer/',
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt',
      manifest: {
        id: '/viewer/',
        name: 'Juggluco — сахар',
        short_name: 'Juggluco',
        description: 'Личный просмотр сахара, прогноза и событий.',
        lang: 'ru',
        dir: 'ltr',
        start_url: '/viewer/',
        scope: '/viewer/',
        display: 'standalone',
        display_override: ['window-controls-overlay', 'standalone', 'minimal-ui'],
        orientation: 'any',
        background_color: '#090c0b',
        theme_color: '#090c0b',
        categories: ['health', 'medical', 'utilities'],
        icons: [
          {
            src: '/viewer/icons/icon-192.png',
            sizes: '192x192',
            type: 'image/png',
            purpose: 'any',
          },
          {
            src: '/viewer/icons/icon-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'any',
          },
          {
            src: '/viewer/icons/icon-maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        cacheId: 'juggluco-viewer',
        cleanupOutdatedCaches: true,
        clientsClaim: true,
        skipWaiting: false,
        navigateFallback: '/viewer/index.html',
        navigateFallbackDenylist: [/^\/v1\//],
        // The plugin adds its generated webmanifest automatically; the glob
        // covers every other production app-shell asset exactly once.
        globPatterns: ['**/*.{js,css,html,ico,png,svg}'],
        runtimeCaching: [
          {
            urlPattern: /\/v1(?:\/|$)/,
            handler: 'NetworkOnly',
          },
        ],
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  server: {
    proxy: {
      '/v1': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: false,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    restoreMocks: true,
    clearMocks: true,
  },
})
