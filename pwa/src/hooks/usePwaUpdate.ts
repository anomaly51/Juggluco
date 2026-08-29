import { useEffect, useRef, useState } from 'react'
import { registerSW } from 'virtual:pwa-register'

export function usePwaUpdate() {
  const [updateReady, setUpdateReady] = useState(false)
  const updater = useRef<((reloadPage?: boolean) => Promise<void>) | null>(null)

  useEffect(() => {
    updater.current = registerSW({
      immediate: true,
      onNeedRefresh: () => setUpdateReady(true),
    })
  }, [])

  const applyUpdate = async () => {
    await updater.current?.(true)
  }

  return { updateReady, applyUpdate, dismissUpdate: () => setUpdateReady(false) }
}
