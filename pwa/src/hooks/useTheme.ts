import { useEffect, useState } from 'react'
import type { ThemeMode } from '../types'

const THEME_KEY = 'juggluco-theme'

function storedTheme(): ThemeMode {
  const value = localStorage.getItem(THEME_KEY)
  return value === 'dark' || value === 'light' || value === 'system' ? value : 'dark'
}

export function useTheme() {
  const [theme, setThemeState] = useState<ThemeMode>(storedTheme)

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const apply = () => {
      const resolved = theme === 'system' ? (media.matches ? 'dark' : 'light') : theme
      document.documentElement.dataset.theme = resolved
      document.documentElement.style.colorScheme = resolved
      const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
      if (meta) meta.content = resolved === 'dark' ? '#060301' : '#f9eedc'
    }
    apply()
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [theme])

  const setTheme = (value: ThemeMode) => {
    localStorage.setItem(THEME_KEY, value)
    setThemeState(value)
  }

  return { theme, setTheme }
}
