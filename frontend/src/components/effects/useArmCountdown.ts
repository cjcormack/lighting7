import { useEffect, useState } from 'react'
import type { EffectsArmState } from '@/api/effectsApi'

/**
 * Whole seconds the desk's arm has left, ticking once a second while armed; null when disarmed.
 * Counted on this browser's clock from the frame's `remainingMs` (`api/effectsApi.ts`), so a window
 * with a skewed wall clock still shows the desk's number.
 */
export function useArmCountdown(arm: EffectsArmState): number | null {
  const until = arm.armed ? arm.armedUntilMs : null
  const [now, setNow] = useState(() => performance.now())
  useEffect(() => {
    if (until == null) return
    setNow(performance.now())
    const id = window.setInterval(() => setNow(performance.now()), 250)
    return () => window.clearInterval(id)
  }, [until])
  if (until == null) return null
  return Math.max(0, Math.ceil((until - now) / 1000))
}
